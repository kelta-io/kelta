package io.kelta.worker.runner;

import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repairs the {@code default} tenant's identity providers on a fresh install of the
 * flattened baseline (#1591).
 *
 * <p>{@code V1__baseline} was flattened from a real environment (#1189) and carried its
 * {@code oidc_provider} rows along: an internal provider whose issuer is the production
 * {@code https://auth.kelta.io}, and two external providers (a local Keycloak and a homelab
 * Authentik) that no fresh install can reach. The SPA follows the internal provider's issuer to
 * sign in, so a newcomer's login page sent them to production, and the two dead external rows
 * also switched off the login page's "single internal provider → redirect straight to the form"
 * path. {@link io.kelta.worker.listener.TenantProvisioningHook} builds a correct internal
 * provider for tenants created later; nothing did it for the baseline's {@code default} tenant.
 *
 * <p>Editing {@code V1__baseline} is not an option — it changes the checksum every existing
 * database has recorded. Instead, on startup, when the internal provider <em>still carries the
 * baseline's production issuer</em> and this deployment is configured with a
 * <em>different</em> issuer ({@code kelta.auth.issuer-uri}), the row is pointed at the
 * configured issuer and the two baseline external rows are deactivated. Every condition is
 * checked in the {@code UPDATE}'s {@code WHERE}, so:
 * <ul>
 *   <li><b>Production is untouched</b> — its configured issuer is the baseline issuer.</li>
 *   <li><b>It runs at most once per database</b> — after the repair the issuer no longer matches
 *       the baseline, so an external provider a developer re-enables later stays enabled.</li>
 *   <li><b>Concurrent replicas are safe</b> — only the replica whose {@code UPDATE} matched does
 *       anything further.</li>
 * </ul>
 *
 * <p>The repaired rows are broadcast as {@code record.updated} events on
 * {@code kelta.record.changed.<tenant>.oidc-providers} so every pod's system-collection caches
 * (worker Caffeine, gateway response cache) evict them (CLAUDE.md, critical rule 1).
 *
 * <p>Runs with no tenant bound — a platform bootstrap path, like Flyway (critical rule 3).
 */
@Component
@Order(20)
public class BaselineIdentityProviderReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineIdentityProviderReconciler.class);

    static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    static final String BASELINE_INTERNAL_PROVIDER_ID = "7c41fced-4c9e-4b31-ba95-fdd603e79687";
    static final String BASELINE_ISSUER = "https://auth.kelta.io";
    static final String COLLECTION = "oidc-providers";

    /** Baseline external providers, keyed by id, with the issuer they were seeded with. */
    static final Map<String, String> BASELINE_EXTERNAL_PROVIDERS = Map.of(
            "local-keycloak", "http://localhost:8180/realms/emf",
            "kelta-api-provider", "https://authentik.rzware.com/application/o/kelta-api/");

    static final String REPAIR_INTERNAL = """
            UPDATE oidc_provider SET issuer = ?, jwks_uri = ?, updated_at = NOW()
            WHERE id = ? AND tenant_id = ? AND is_internal = TRUE AND issuer = ?
            """;

    static final String DEACTIVATE_EXTERNAL = """
            UPDATE oidc_provider SET active = FALSE, updated_at = NOW()
            WHERE id = ? AND tenant_id = ? AND is_internal = FALSE AND active = TRUE AND issuer = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<RecordEventPublisher> recordEventPublisher;
    private final String configuredIssuer;

    /**
     * The publisher is looked up lazily: this runner also runs in the migrate Job, and the
     * repair (hence the publish) never happens there — its issuer is the baseline one.
     */
    public BaselineIdentityProviderReconciler(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectProvider<RecordEventPublisher> recordEventPublisher,
            @Value("${kelta.auth.issuer-uri:}") String configuredIssuer) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.recordEventPublisher = recordEventPublisher;
        this.configuredIssuer = stripTrailingSlash(configuredIssuer);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            reconcile();
        } catch (RuntimeException e) {
            // Never block startup over a convenience repair; the login page just keeps its old rows.
            log.error("Baseline identity-provider repair failed: {}", e.getMessage(), e);
        }
    }

    /** @return ids of the rows that were changed (empty when there was nothing to do). */
    List<String> reconcile() {
        if (configuredIssuer.isEmpty() || BASELINE_ISSUER.equals(configuredIssuer)) {
            return List.of();
        }
        List<String> changed = transactionTemplate.execute(status -> {
            List<String> ids = new ArrayList<>();
            int repaired = jdbcTemplate.update(REPAIR_INTERNAL,
                    configuredIssuer, configuredIssuer + "/oauth2/jwks",
                    BASELINE_INTERNAL_PROVIDER_ID, DEFAULT_TENANT_ID, BASELINE_ISSUER);
            if (repaired == 0) {
                return ids;
            }
            ids.add(BASELINE_INTERNAL_PROVIDER_ID);
            BASELINE_EXTERNAL_PROVIDERS.forEach((id, issuer) -> {
                if (jdbcTemplate.update(DEACTIVATE_EXTERNAL, id, DEFAULT_TENANT_ID, issuer) > 0) {
                    ids.add(id);
                }
            });
            return ids;
        });
        if (changed == null || changed.isEmpty()) {
            return List.of();
        }
        log.info("Repaired baseline identity providers for the default tenant: internal issuer {} -> {}; "
                        + "changed rows {}", BASELINE_ISSUER, configuredIssuer, changed);
        changed.forEach(this::publishUpdated);
        return changed;
    }

    private void publishUpdated(String providerId) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", providerId);
            List<String> changedFields;
            if (BASELINE_INTERNAL_PROVIDER_ID.equals(providerId)) {
                data.put("issuer", configuredIssuer);
                data.put("jwksUri", configuredIssuer + "/oauth2/jwks");
                changedFields = List.of("issuer", "jwksUri");
            } else {
                data.put("active", false);
                changedFields = List.of("active");
            }
            RecordChangedPayload payload = RecordChangedPayload.updated(
                    COLLECTION, providerId, data, null, changedFields);
            PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                    "record.updated", DEFAULT_TENANT_ID, null, payload);
            RecordEventPublisher publisher = recordEventPublisher.getIfAvailable();
            if (publisher == null) {
                log.warn("No record event publisher; other pods keep {} cached until restart", providerId);
                return;
            }
            publisher.publish(event);
        } catch (RuntimeException e) {
            log.warn("Failed to broadcast repaired identity provider {}: {}", providerId, e.getMessage());
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }
}
