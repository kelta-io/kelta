package io.kelta.worker.service.mailbox;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.MailboxEscalationRepository;
import io.kelta.worker.service.email.DefaultEmailService;
import io.kelta.worker.service.push.DefaultPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells someone that a support thread has gone past its SLA.
 *
 * <p>Structurally a copy of {@code AlertDispatchService}: resolve channels, write every delivery
 * row as {@code PENDING} <b>before</b> attempting anything, then send per channel with the failure
 * of one never stopping the others. That discipline is the difference between "we don't know what
 * happened" and "we know exactly what we owed and which part failed".
 *
 * <p>Never throws. A dispatch failure is recorded on the delivery row; propagating it would abort
 * the sweep and starve every later escalation in the same batch.
 *
 * @since 1.0.0
 */
@Service
public class MailboxEscalationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MailboxEscalationDispatchService.class);

    /**
     * The {@code template_key} of the escalation body seeded by V192 — resolved with
     * {@code sendByKey}, never {@code sendByName}.
     *
     * <p>{@code email_template} has both a {@code name} and a {@code template_key}, and they are
     * different strings for the same row: this template is named "Support SLA escalation" and keyed
     * "support.sla_escalation". Passing the key to {@code sendByName} matches nothing, so every
     * escalation email failed with "template not found" while the row sat in the table.
     *
     * <p>Both lookups exist and both are legitimate — {@code AlertDispatchService} resolves a
     * tenant-authored template whose {@code name} is the lookup value — which is exactly why the
     * mistake reads as correct code.
     */
    private static final String EMAIL_TEMPLATE_KEY = "support.sla_escalation";
    private static final String CHANNEL_EMAIL = "email";
    private static final String CHANNEL_PUSH = "push";

    private final MailboxEscalationRepository escalationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DefaultEmailService emailService;
    private final DefaultPushService pushService;
    private final ObjectMapper objectMapper;
    private final String appBaseUrl;

    public MailboxEscalationDispatchService(MailboxEscalationRepository escalationRepository,
                                            JdbcTemplate jdbcTemplate,
                                            org.springframework.beans.factory.ObjectProvider<DefaultEmailService> emailServiceProvider,
                                            org.springframework.beans.factory.ObjectProvider<DefaultPushService> pushServiceProvider,
                                            ObjectMapper objectMapper,
                                            // The APP host, not the API host. kelta.external-base-url is
                                            // the gateway (https://api.kelta.io); a thread link built on it
                                            // is a 404 with no tenant slug — which is what every
                                            // escalation email sent before this carried.
                                            @Value("${kelta.mailbox.app-base-url:${kelta.external-base-url:}}") String appBaseUrl) {
        this.escalationRepository = escalationRepository;
        this.jdbcTemplate = jdbcTemplate;
        // Both are optional beans: email is absent when kelta.email.enabled=false (the test
        // harness runs that way), and push is absent in stacks without VAPID keys. A missing
        // channel must degrade to "that channel failed", not to a context that will not start.
        this.emailService = emailServiceProvider.getIfAvailable();
        this.pushService = pushServiceProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.appBaseUrl = appBaseUrl;
    }

    /**
     * Notifies everyone configured for this escalation's level.
     *
     * <p>Must be called with the escalation's tenant bound.
     */
    public void dispatch(MailboxEscalationRepository.Claimed escalation) {
        String tenantId = escalation.tenantId();
        List<Map<String, Object>> contacts =
                escalationRepository.contactsFor(tenantId, escalation.mailboxId(), escalation.level());

        if (contacts.isEmpty()) {
            // Worth a warning rather than silence: an escalation level with no contacts is a
            // configuration gap that looks exactly like a working system until something breaches.
            log.warn("Mailbox {} has no {} escalation contacts — thread {} breached with nobody to tell",
                    escalation.mailboxId(), escalation.level(), escalation.threadId());
            return;
        }

        Map<String, Object> context = loadContext(tenantId, escalation);
        String subject = String.valueOf(context.getOrDefault("subject", "(no subject)"));

        for (Map<String, Object> contact : contacts) {
            String userId = (String) contact.get("user_id");
            List<String> channels = parseChannels(contact.get("channels"));
            if (channels.isEmpty()) {
                continue;
            }

            List<String> deliveryIds =
                    escalationRepository.createPending(tenantId, escalation.id(), userId, channels);

            for (int i = 0; i < channels.size(); i++) {
                String channel = channels.get(i);
                String deliveryId = deliveryIds.get(i);
                try {
                    send(tenantId, escalation, userId, channel, context, subject);
                    escalationRepository.markSent(tenantId, deliveryId);
                } catch (Exception e) {
                    // One channel failing must not stop the others: a stale push token should not
                    // cost this person the email.
                    log.warn("Escalation {} delivery over {} to {} failed: {}",
                            escalation.id(), channel, userId, e.getMessage());
                    escalationRepository.markFailed(tenantId, deliveryId, e.getMessage());
                }
            }
        }
    }

    private void send(String tenantId, MailboxEscalationRepository.Claimed escalation,
                      String userId, String channel, Map<String, Object> context, String subject) {
        switch (channel) {
            case CHANNEL_EMAIL -> {
                if (emailService == null) {
                    throw new IllegalStateException("email delivery is disabled");
                }
                String address = userEmail(tenantId, userId);
                if (address == null || address.isBlank()) {
                    throw new IllegalStateException("user has no email address");
                }
                emailService.sendByKey(tenantId, address, EMAIL_TEMPLATE_KEY, context,
                                "support-escalation", escalation.id())
                        .orElseThrow(() -> new IllegalStateException(
                                "email template key '" + EMAIL_TEMPLATE_KEY + "' not found"));
            }
            case CHANNEL_PUSH -> {
                if (pushService == null) {
                    throw new IllegalStateException("push delivery is not configured");
                }
                // The service worker opens data.url on tap. Without it the notification can only
                // focus whatever tab happens to be open, which for a page at 3am is nothing.
                pushService.sendToUser(userId, tenantId,
                        "[" + escalation.level() + "] " + context.get("mailboxName"),
                        subject,
                        Map.of("threadId", escalation.threadId(), "kind", "support-escalation",
                                "url", String.valueOf(context.getOrDefault("threadUrl", ""))));
            }
            // SMS is deliberately absent for now: it is billable, and nothing in this slice
            // resolves a per-tenant SMS entitlement. The channel CHECK permits it so adding the
            // arm later needs no migration.
            default -> throw new IllegalArgumentException("unsupported channel: " + channel);
        }
    }

    /** Template variables. Flat keys only — the substituter matches names literally. */
    private Map<String, Object> loadContext(String tenantId,
                                            MailboxEscalationRepository.Claimed escalation) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("level", escalation.level());
        vars.put("clockLabel", "FIRST_RESPONSE".equals(escalation.clock())
                ? "first response" : "resolution");
        vars.put("dueAt", escalation.dueAt() == null ? "" : escalation.dueAt().toString());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT t.subject, t.requester_email, m.name AS mailbox_name, tn.slug
                  FROM mailbox_thread t
                  JOIN mailbox m ON m.id = t.mailbox_id
                  JOIN tenant tn ON tn.id = t.tenant_id
                 WHERE t.id = ? AND t.tenant_id = ?
                """, escalation.threadId(), tenantId);

        // Console routes are /{slug}/app/..., so a link without the slug lands nowhere.
        String slug = rows.isEmpty() ? null : (String) rows.getFirst().get("slug");
        vars.put("threadUrl", appBaseUrl.isBlank() || slug == null || slug.isBlank()
                ? ""
                : appBaseUrl.replaceAll("/+$", "") + "/" + slug
                        + "/app/mailbox?thread=" + escalation.threadId());

        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.getFirst();
            vars.put("subject", orEmpty(row.get("subject")));
            vars.put("requesterEmail", orEmpty(row.get("requester_email")));
            vars.put("mailboxName", orEmpty(row.get("mailbox_name")));
        } else {
            vars.put("subject", "");
            vars.put("requesterEmail", "");
            vars.put("mailboxName", "");
        }
        return vars;
    }

    private String userEmail(String tenantId, String userId) {
        return TenantContext.callWithTenant(tenantId, () -> {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT email FROM platform_user WHERE id = ? AND tenant_id = ?",
                    String.class, userId, tenantId);
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> parseChannels(Object raw) {
        if (raw == null) {
            return List.of(CHANNEL_EMAIL);
        }
        try {
            Object parsed = objectMapper.readValue(raw.toString(), List.class);
            List<String> out = new java.util.ArrayList<>();
            for (Object o : (List<Object>) parsed) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out.isEmpty() ? List.of(CHANNEL_EMAIL) : out;
        } catch (Exception e) {
            // A malformed channels value should still reach someone rather than silently
            // notifying nobody.
            log.warn("Unreadable escalation channels value '{}' — defaulting to email", raw);
            return List.of(CHANNEL_EMAIL);
        }
    }

    private static String orEmpty(Object o) {
        return o == null ? "" : o.toString();
    }
}
