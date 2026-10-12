package io.kelta.worker.listener;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Triggers OIDC Discovery when an OIDC provider is created or updated.
 *
 * <p>Fetches {@code .well-known/openid-configuration} from the provider's issuer URI
 * and populates endpoint fields (jwksUri, authorizationUri, tokenUri, etc.)
 * when they are not explicitly set as overrides.
 *
 * <p>Sets {@code discoveryStatus} to "discovered", "manual", or "error" based on the result.
 *
 * <p><b>Not registered.</b> Before-save hooks are registered by being Spring beans
 * ({@code BeforeSaveHookRegistrar}), and this class is deliberately not one. It used to be a
 * {@code @Component} at a time when hooks were registered by hand, and nothing ever registered
 * it, so it has never run. Making it a bean again is a behaviour change to make on purpose, with
 * tests, and must update {@code BeforeSaveHookWiringTest}.
 */
public class OidcDiscoveryHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryHook.class);
    private static final String COLLECTION = "oidc-providers";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OidcDiscoveryHook(ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .defaultHeader("Accept", "application/json")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    @Override
    public int getOrder() {
        return -50; // After secret encryption (-100), before audit (1000)
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return runDiscovery(record);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        // Only run Discovery if issuer changed
        Object newIssuer = record.get("issuer");
        if (newIssuer == null) {
            return BeforeSaveResult.ok();
        }
        Object oldIssuer = previous != null ? previous.get("issuer") : null;
        if (newIssuer.equals(oldIssuer)) {
            return BeforeSaveResult.ok();
        }
        return runDiscovery(record);
    }

    private BeforeSaveResult runDiscovery(Map<String, Object> record) {
        Object issuerObj = record.get("issuer");
        if (issuerObj == null || issuerObj.toString().isBlank()) {
            return BeforeSaveResult.ok();
        }

        String issuer = issuerObj.toString();
        String discoveryUrl = issuer.endsWith("/")
                ? issuer.substring(0, issuer.length() - 1) + "/.well-known/openid-configuration"
                : issuer + "/.well-known/openid-configuration";

        Map<String, Object> updates = new HashMap<>();

        try {
            log.debug("Running OIDC Discovery for issuer: {}", issuer);
            JsonNode config = restClient.get()
                    .uri(discoveryUrl)
                    .retrieve()
                    .body(JsonNode.class);

            if (config == null) {
                updates.put("discoveryStatus", "error");
                return BeforeSaveResult.withFieldUpdates(updates);
            }

            // Only set discovered values when no override is present
            setIfBlank(updates, record, "jwksUri", textOrNull(config, "jwks_uri"));
            setIfBlank(updates, record, "authorizationUri", textOrNull(config, "authorization_endpoint"));
            setIfBlank(updates, record, "tokenUri", textOrNull(config, "token_endpoint"));
            setIfBlank(updates, record, "userinfoUri", textOrNull(config, "userinfo_endpoint"));
            setIfBlank(updates, record, "endSessionUri", textOrNull(config, "end_session_endpoint"));
            updates.put("discoveryStatus", "discovered");

            log.info("OIDC Discovery succeeded for issuer={}", issuer);
        } catch (Exception e) {
            log.warn("OIDC Discovery failed for issuer={}: {}", issuer, e.getMessage());
            updates.put("discoveryStatus", "error");
        }

        return updates.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.withFieldUpdates(updates);
    }

    private void setIfBlank(Map<String, Object> updates, Map<String, Object> record,
                             String field, String discoveredValue) {
        if (discoveredValue == null) {
            return;
        }
        Object existing = record.get(field);
        if (existing == null || existing.toString().isBlank()) {
            updates.put(field, discoveredValue);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String text = child.asText();
        return text.isBlank() ? null : text;
    }
}
