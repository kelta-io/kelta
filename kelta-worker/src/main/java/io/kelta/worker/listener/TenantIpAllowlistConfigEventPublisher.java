package io.kelta.worker.listener;

import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates and broadcasts per-tenant IP allowlist configuration.
 *
 * <p><b>Before save</b> — rejects malformed CIDR entries in {@code ipAllowlistCidrs}
 * so invalid ranges never reach the store (and never silently fail-open at the gateway).
 *
 * <p><b>After save</b> — publishes {@code kelta.config.tenant.ip-allowlist.changed.<tenantId>}
 * whenever the {@code ipAllowlistEnabled} / {@code ipAllowlistCidrs} columns on a
 * {@code tenants} row change (or the row is deleted). Every gateway pod consumes the event
 * and refreshes its in-memory allowlist cache so the new ranges take effect without a restart
 * (Critical Rule 1 — multi-pod NATS broadcast).
 *
 * <p>Mirrors {@link TenantEmailConfigEventPublisher}.
 */
@Component
public class TenantIpAllowlistConfigEventPublisher implements BeforeSaveHook {

    private static final Logger log =
            LoggerFactory.getLogger(TenantIpAllowlistConfigEventPublisher.class);

    static final String SUBJECT_PREFIX = "kelta.config.tenant.ip-allowlist.changed.";
    private static final String EVENT_TYPE = "kelta.config.tenant.ip-allowlist.changed";

    private static final String FIELD_ENABLED = "ipAllowlistEnabled";
    private static final String FIELD_CIDRS = "ipAllowlistCidrs";

    private final PlatformEventPublisher eventPublisher;

    public TenantIpAllowlistConfigEventPublisher(PlatformEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override public String getCollectionName() { return "tenants"; }
    // Run after provisioning/audit hooks, directly after TenantEmailConfigEventPublisher (200):
    // hooks are Spring-discovered, so no two hooks on one collection may share an order.
    @Override public int getOrder() { return 201; }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validateCidrs(record);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return validateCidrs(record);
    }

    @Override
    public void afterUpdate(String id, Map<String, Object> record,
                             Map<String, Object> previous, String tenantId) {
        if (!ipFieldChanged(record, previous)) {
            return;
        }
        publish(id);
    }

    @Override
    public void afterDelete(String id, String tenantId) {
        publish(id);
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private BeforeSaveResult validateCidrs(Map<String, Object> record) {
        if (record == null || !record.containsKey(FIELD_CIDRS)) {
            return BeforeSaveResult.ok();
        }
        for (String cidr : toStringList(record.get(FIELD_CIDRS))) {
            if (!isValidCidr(cidr)) {
                return BeforeSaveResult.error(FIELD_CIDRS,
                        "Invalid CIDR range: '" + cidr + "'. Expected e.g. 10.0.0.0/8 or 2001:db8::/32.");
            }
        }
        return BeforeSaveResult.ok();
    }

    private boolean ipFieldChanged(Map<String, Object> record, Map<String, Object> previous) {
        if (previous == null) return true;
        for (String field : new String[]{FIELD_ENABLED, FIELD_CIDRS}) {
            if (record.containsKey(field) && !Objects.equals(record.get(field), previous.get(field))) {
                return true;
            }
        }
        return false;
    }

    private void publish(String tenantId) {
        if (tenantId == null) {
            log.warn("Skipping tenant IP allowlist event: tenant id is null");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        PlatformEvent<Map<String, Object>> event = EventFactory.createEvent(EVENT_TYPE, payload);
        event.setTenantId(tenantId);
        String subject = SUBJECT_PREFIX + tenantId;
        log.info("Publishing tenant IP allowlist changed event for {} to '{}'", tenantId, subject);
        eventPublisher.publish(subject, event);
    }

    // ── CIDR parsing helpers (no external IP library on the worker classpath) ──

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            // A single comma-separated string fallback (defensive; UI sends a JSON array).
            return List.of(s.split(","));
        }
        return List.of();
    }

    /**
     * Validates an IPv4 or IPv6 CIDR literal (e.g. {@code 10.0.0.0/8}, {@code 2001:db8::/32}).
     * Parses IP literals only — never performs DNS resolution.
     */
    static boolean isValidCidr(String cidr) {
        if (cidr == null) return false;
        String s = cidr.trim();
        if (s.isEmpty()) return false;
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) return false;

        String addr = s.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(s.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }

        if (addr.indexOf(':') >= 0) {
            return prefix >= 0 && prefix <= 128 && isValidInet6(addr);
        }
        return prefix >= 0 && prefix <= 32 && isValidInet4(addr);
    }

    private static boolean isValidInet4(String addr) {
        String[] parts = addr.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
            int v = Integer.parseInt(p);
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    private static boolean isValidInet6(String addr) {
        // ofLiteral parses IP literals only — never performs a DNS lookup.
        try {
            return InetAddress.ofLiteral(addr) instanceof Inet6Address;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
