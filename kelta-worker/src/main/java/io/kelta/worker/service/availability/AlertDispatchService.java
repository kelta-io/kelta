package io.kelta.worker.service.availability;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.AlertDeliveryRepository;
import io.kelta.worker.service.billing.EntitlementService;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.worker.service.push.DefaultPushService;
import io.kelta.worker.service.sms.SmsMessage;
import io.kelta.worker.service.sms.SmsProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delivers a claimed alert to a member over their chosen channels.
 *
 * <p><b>Sends happen outside any database transaction, and after the alert row is
 * already committed.</b> That ordering is the point: the alert row is the dedupe
 * record, so if a push provider times out and the send is retried, the member
 * must not receive a *second* alert. A failed send is recorded as a FAILED
 * delivery row, never by rolling back the alert.
 *
 * <p>Channels are the intersection of what the watch asked for and what the
 * member's plan entitles them to, so a downgraded member stops getting SMS
 * without anyone editing their watches. An empty intersection is not an error —
 * it just means nothing is owed.
 *
 * <p>SMS is delivered through the {@link SmsProvider} SPI (consumer-alerting slice 10) — the
 * default {@code LogOnlySmsProvider} logs, and {@code TwilioSmsProvider} sends for real once
 * {@code kelta.sms.provider=twilio} and the Twilio secret are configured. A member with no phone
 * number on file records a FAILED SMS delivery, exactly like a stale push token — never a
 * rollback.
 *
 * <p>Every SENT delivery records {@value #LATENCY_METRIC} (tags {@code tenant}, {@code channel}),
 * measured from the alert's {@code created_at} as defined by {@link AlertLatencySummary} — the
 * same definition {@code GET /api/alerts/latency} reports, so the metric and the endpoint agree.
 * FAILED deliveries record nothing.
 */
@Service
public class AlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);

    static final String CHANNEL_PUSH = "push";
    static final String CHANNEL_EMAIL = "email";
    static final String CHANNEL_SMS = "sms";

    /** Timer of detection → delivery latency per SENT delivery. */
    static final String LATENCY_METRIC = "kelta_alert_delivery_latency_seconds";

    /**
     * Channels that cost real money to deliver, and are therefore only ever sent on an
     * <b>affirmative</b> entitlement.
     *
     * <p>Everywhere else an absent entitlement means "this tenant is not gating channels", which is
     * the right default for push and email: a tenant with no billing configured, or one whose
     * entitlement authority is briefly unavailable, should still get its alerts. Applying that same
     * permissiveness to SMS means an absent or broken authority silently starts sending paid
     * messages for members who are not entitled to them — the platform spending money because it
     * could not determine that it shouldn't.
     *
     * <p>"Ungated" must never mean "free-for-all for the things that cost money."
     */
    public static final Set<String> BILLABLE_CHANNELS = Set.of(CHANNEL_SMS);

    /** Entitlement key listing the channels a member's plan permits. */
    static final String ENTITLEMENT_CHANNELS = "channels";
    /** Tenant-overridable email template for an availability alert. */
    static final String EMAIL_TEMPLATE = "availability.alert";

    private static final DateTimeFormatter WINDOW_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final AlertDeliveryRepository deliveryRepository;
    private final DefaultPushService pushService;
    private final EmailService emailService;
    private final SmsProvider smsProvider;
    private final EntitlementService entitlementService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AlertDispatchService(AlertDeliveryRepository deliveryRepository,
                                DefaultPushService pushService,
                                EmailService emailService,
                                SmsProvider smsProvider,
                                EntitlementService entitlementService,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.pushService = pushService;
        this.emailService = emailService;
        this.smsProvider = smsProvider;
        this.entitlementService = entitlementService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /** Delivers one claimed alert. Never throws — a failure is recorded, not propagated. */
    public void dispatch(String tenantId, AvailabilityMatchService.ClaimedAlert alert) {
        List<String> channels = resolveChannels(tenantId, alert);
        if (channels.isEmpty()) {
            log.debug("Alert {} has no deliverable channels for member {}",
                    alert.alertId(), alert.watch().memberId());
            return;
        }

        // Record what is owed BEFORE attempting any send, so a crash mid-dispatch
        // leaves evidence rather than losing the obligation.
        List<String> deliveryIds = deliveryRepository.createPending(alert.alertId(), channels);

        String title = "Now available: " + alert.target().name();
        String body = describeWindow(alert) + " just opened.";

        for (int i = 0; i < channels.size(); i++) {
            String channel = channels.get(i);
            String deliveryId = deliveryIds.get(i);
            try {
                send(tenantId, alert, channel, title, body);
                Instant sentAt = Instant.now();
                deliveryRepository.markSent(deliveryId, sentAt);
                recordLatency(tenantId, channel, alert.createdAt(), sentAt);
            } catch (Exception e) {
                // One channel failing must not stop the others: a member whose
                // push token is stale should still get the email.
                log.warn("Alert {} delivery over {} failed: {}",
                        alert.alertId(), channel, e.getMessage());
                deliveryRepository.markFailed(deliveryId, e.getMessage());
            }
        }
    }

    private void recordLatency(String tenantId, String channel, Instant alertCreatedAt,
                               Instant sentAt) {
        if (alertCreatedAt == null) {
            return;
        }
        Timer.builder(LATENCY_METRIC)
                .description("Alert delivery latency: alert_delivery.sent_at - alert.created_at")
                .tag("tenant", tenantId)
                .tag("channel", channel)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(AlertLatencySummary.latency(alertCreatedAt, sentAt));
    }

    private void send(String tenantId, AvailabilityMatchService.ClaimedAlert alert,
                      String channel, String title, String body) {
        switch (channel) {
            case CHANNEL_PUSH -> {
                int devices = pushService.sendToUser(alert.watch().memberId(), tenantId,
                        title, body, pushData(alert));
                if (devices == 0) {
                    // Not an error worth failing the delivery over, but worth
                    // knowing: the member has no registered devices.
                    log.debug("Alert {} push reached no devices for member {}",
                            alert.alertId(), alert.watch().memberId());
                }
            }
            case CHANNEL_EMAIL -> {
                String email = memberEmail(tenantId, alert.watch().memberId());
                if (email == null) {
                    throw new IllegalStateException("member has no email address");
                }
                emailService.sendByName(tenantId, email, EMAIL_TEMPLATE, emailVars(alert),
                                "availability-alert", alert.alertId())
                        .orElseThrow(() -> new IllegalStateException(
                                "email template '" + EMAIL_TEMPLATE + "' not found"));
            }
            case CHANNEL_SMS -> {
                String phone = memberPhone(tenantId, alert.watch().memberId());
                if (phone == null || phone.isBlank()) {
                    throw new IllegalStateException("member has no phone number");
                }
                // One-line, book-now text. describeWindow already reads naturally.
                String sms = title + " — " + describeWindow(alert) + " just opened. Book now.";
                smsProvider.send(new SmsMessage(phone, sms));
            }
            default -> throw new IllegalArgumentException("unknown channel: " + channel);
        }
    }

    /**
     * The channels actually owed: what the watch asked for, intersected with what
     * the member's plan permits. A watch with no explicit channels falls back to
     * the member's full entitlement.
     */
    List<String> resolveChannels(String tenantId, AvailabilityMatchService.ClaimedAlert alert) {
        Set<String> entitled = new LinkedHashSet<>(
                entitlementService.listLimit(tenantId, alert.watch().memberId(), ENTITLEMENT_CHANNELS));
        List<String> requested = parseChannels(alert.watch().channels());

        if (requested.isEmpty()) {
            return new ArrayList<>(entitled);
        }
        if (entitled.isEmpty()) {
            // No channels entitlement resolved: treat the tenant as not gating rather than
            // silently muting every alert -- EXCEPT for channels that cost money, which are only
            // ever sent on an affirmative entitlement. See BILLABLE_CHANNELS.
            return requested.stream().filter(c -> !BILLABLE_CHANNELS.contains(c)).toList();
        }
        List<String> intersection = new ArrayList<>();
        for (String channel : requested) {
            if (entitled.contains(channel)) {
                intersection.add(channel);
            }
        }
        return intersection;
    }

    private List<String> parseChannels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            // Rows written before the storage fix hold a JSON string wrapping the
            // array; unwrap one level so an existing watch keeps its channels
            // instead of silently falling back to the full entitlement.
            if (node.isTextual()) {
                node = objectMapper.readTree(node.stringValue());
            }
            if (!node.isArray()) {
                return List.of();
            }
            List<String> channels = new ArrayList<>();
            node.forEach(item -> {
                if (item != null && item.isTextual()) {
                    channels.add(item.stringValue());
                }
            });
            return channels;
        } catch (RuntimeException e) {
            log.warn("Unparseable watch channels {} — falling back to entitlement", json);
            return List.of();
        }
    }

    private String memberEmail(String tenantId, String memberId) {
        return TenantContext.callWithTenant(tenantId, () -> {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT email FROM platform_user WHERE id = ? AND tenant_id = ?",
                    String.class, memberId, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** The member's phone number (E.164) for SMS, or null when none is on file. */
    private String memberPhone(String tenantId, String memberId) {
        return TenantContext.callWithTenant(tenantId, () -> {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT phone_number FROM platform_user WHERE id = ? AND tenant_id = ?",
                    String.class, memberId, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    private Map<String, String> pushData(AvailabilityMatchService.ClaimedAlert alert) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("watchId", alert.watch().id());
        data.put("targetId", alert.target().id());
        data.put("slotKey", alert.slotKey());
        return data;
    }

    private Map<String, Object> emailVars(AvailabilityMatchService.ClaimedAlert alert) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("targetName", alert.target().name());
        vars.put("slotKey", alert.slotKey());
        vars.put("window", describeWindow(alert));
        return vars;
    }

    /** Human-readable window for the notification body. */
    private String describeWindow(AvailabilityMatchService.ClaimedAlert alert) {
        if (alert.windowStart() == null && alert.windowEnd() == null) {
            return alert.slotKey();
        }
        if (alert.windowEnd() == null) {
            return WINDOW_FORMAT.format(alert.windowStart());
        }
        if (alert.windowStart() == null) {
            return WINDOW_FORMAT.format(alert.windowEnd());
        }
        String start = WINDOW_FORMAT.format(alert.windowStart());
        String end = WINDOW_FORMAT.format(alert.windowEnd());
        return start.equals(end) ? start : start + " – " + end;
    }
}
