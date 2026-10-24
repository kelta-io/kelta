package io.kelta.auth.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkerClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    /**
     * @param restClient worker client from {@code InternalWorkerClientConfig}: based at
     *                   {@code kelta.auth.worker-url} and, with the internal-auth rollout
     *                   flag on, attaching a client_credentials bearer to {@code /internal/**}
     */
    public WorkerClient(ObjectMapper objectMapper,
                        @Value("${kelta.internal.token:}") String internalToken,
                        @Qualifier("internalWorkerRestClient") RestClient restClient) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    /**
     * Sends an email via the worker's internal email endpoint.
     *
     * @return true if the email was successfully queued, false on failure
     */
    public boolean sendEmail(String tenantId, String to, String subject, String body, String source) {
        try {
            Map<String, String> requestBody = Map.of(
                    "tenantId", tenantId,
                    "to", to,
                    "subject", subject,
                    "body", body,
                    "source", source
            );

            restClient.post()
                    .uri("/api/internal/email/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", internalToken)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            return true;
        } catch (Exception e) {
            log.error("Failed to send email via worker: to={}, source={}, error={}", to, source, e.getMessage());
            return false;
        }
    }

    /**
     * Sends a user-invitation email via the worker's
     * {@code POST /api/internal/email/invite} endpoint. The worker resolves the
     * {@code user_invite} template (tenant override → system default) and fills
     * {@code ${inviteLink}} / {@code ${tenantName}} placeholders.
     *
     * @return true when the worker accepted the request and queued delivery.
     */
    public boolean sendInviteEmail(String tenantId, String email, String inviteToken) {
        try {
            Map<String, String> requestBody = Map.of(
                    "email", email,
                    "tenantId", tenantId,
                    "inviteToken", inviteToken
            );

            restClient.post()
                    .uri("/api/internal/email/invite")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", internalToken)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to send invite email via worker: tenant={}, to={}, error={}",
                    tenantId, email, e.getMessage());
            return false;
        }
    }

    /**
     * Sends an email using a stable {@code templateKey} resolved by the worker
     * (tenant override or system default) with {@code ${var}} substitution.
     *
     * @return true when the worker accepted the request (template existed and was queued).
     */
    public boolean sendTemplateEmail(String tenantId, String to, String templateKey,
                                     Map<String, Object> vars, String source, String sourceId) {
        try {
            Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("tenantId", tenantId);
            requestBody.put("to", to);
            requestBody.put("templateKey", templateKey);
            requestBody.put("vars", vars == null ? Map.of() : vars);
            if (source != null)   requestBody.put("source", source);
            if (sourceId != null) requestBody.put("sourceId", sourceId);

            restClient.post()
                    .uri("/api/internal/email/send-template")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", internalToken)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to send template email via worker: to={}, key={}, error={}",
                    to, templateKey, e.getMessage());
            return false;
        }
    }

    /**
     * Creates (or re-invites) a portal user for self-signup, delegating to the
     * worker so the {@code maxPortalUsers} seat governor is enforced — kelta-auth
     * has no quota resolver, and public signup without a seat cap is unbounded
     * account creation.
     *
     * @return the worker's outcome for auditing, or {@code null} when the call
     *         failed — the caller answers 202 either way, so a worker outage must
     *         not turn into an account-existence signal
     */
    public String portalSignup(String tenantId, String email, String firstName, String lastName) {
        try {
            Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("tenantId", tenantId);
            requestBody.put("email", email);
            if (firstName != null) requestBody.put("firstName", firstName);
            if (lastName != null)  requestBody.put("lastName", lastName);

            JsonNode response = restClient.post()
                    .uri("/api/internal/portal/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", internalToken)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            return response == null ? null : response.path("outcome").asText(null);
        } catch (Exception e) {
            log.error("Portal signup via worker failed for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    public record OidcProviderInfo(
            String id, String name, String issuer, String jwksUri, String audience,
            String clientId, String clientSecretEnc,
            String rolesClaim, String groupsClaim,
            String groupsProfileMapping,
            String authorizationUri, String tokenUri,
            String userinfoUri, String endSessionUri,
            String emailClaim, String usernameClaim, String nameClaim
    ) {}

    public record SamlProviderInfo(
            String id, String name, String registrationId,
            String idpEntityId, String ssoUrl, String sloUrl, String idpCertificate,
            String nameIdFormat, String emailAttribute, String profileAttribute,
            boolean active
    ) {}

    public record UserIdentity(
            String userId, String profileId, String profileName
    ) {}

    public record ProfileInfo(String id, String name) {}

    /**
     * Resolves a profile by display name, scoped to the tenant. Returns
     * {@link Optional#empty()} if the tenant has no profile with that name or
     * if the worker lookup fails — callers treat both as "unknown profile" and
     * should fall back to PENDING_ACTIVATION or the Minimum Access profile.
     */
    public Optional<ProfileInfo> findProfileByName(String name, String tenantId) {
        if (name == null || name.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode response = restClient.get()
                    .uri("/internal/profile/by-name?name={name}&tenantId={tenantId}", name, tenantId)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }
            return Optional.of(new ProfileInfo(
                    response.path("id").asText(null),
                    response.path("name").asText(null)));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to look up profile '{}' for tenant {}: {}", name, tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<OidcProviderInfo> findOidcProviderByIssuer(String issuer, String tenantId) {
        try {
            String url = tenantId != null
                    ? "/internal/oidc/by-issuer?issuer={issuer}&tenantId={tenantId}"
                    : "/internal/oidc/by-issuer?issuer={issuer}";

            JsonNode response = tenantId != null
                    ? restClient.get()
                        .uri(url, issuer, tenantId)
                        .retrieve()
                        .body(JsonNode.class)
                    : restClient.get()
                        .uri(url, issuer)
                        .retrieve()
                        .body(JsonNode.class);

            if (response == null) {
                return Optional.empty();
            }

            return Optional.of(parseOidcProviderInfo(response));
        } catch (Exception e) {
            log.warn("Failed to look up OIDC provider for issuer {}: {}", issuer, e.getMessage());
            return Optional.empty();
        }
    }

    public List<OidcProviderInfo> findActiveOidcProviders(String tenantId) {
        return lookupActiveOidcProviders(tenantId).orElse(List.of());
    }

    /**
     * Like {@link #findActiveOidcProviders(String)}, but tells a failed lookup apart from
     * an answered empty list: {@code Optional.empty()} means the worker could not answer
     * (error or unexpected body). Callers that remember a miss use this so an outage is
     * never cached as "no such provider".
     */
    public Optional<List<OidcProviderInfo>> lookupActiveOidcProviders(String tenantId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/internal/oidc/providers?tenantId={tenantId}", tenantId)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.isArray()) {
                return Optional.empty();
            }

            List<OidcProviderInfo> providers = new ArrayList<>();
            for (JsonNode node : response) {
                providers.add(parseOidcProviderInfo(node));
            }
            return Optional.of(providers);
        } catch (Exception e) {
            log.warn("Failed to list OIDC providers for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lists the active SAML providers configured for a tenant. Used by
     * {@code DynamicRelyingPartyRegistrationRepository} to build relying-party
     * registrations. Returns an empty list on any failure — callers treat that
     * as "tenant has no SAML federation".
     */
    public List<SamlProviderInfo> findActiveSamlProviders(String tenantId) {
        return lookupActiveSamlProviders(tenantId).orElse(List.of());
    }

    /**
     * Like {@link #findActiveSamlProviders(String)}, but {@code Optional.empty()} when the
     * worker could not answer, so a failure is never mistaken for "no such provider".
     */
    public Optional<List<SamlProviderInfo>> lookupActiveSamlProviders(String tenantId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/internal/saml/providers?tenantId={tenantId}", tenantId)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.isArray()) {
                return Optional.empty();
            }

            List<SamlProviderInfo> providers = new ArrayList<>();
            for (JsonNode node : response) {
                providers.add(parseSamlProviderInfo(node));
            }
            return Optional.of(providers);
        } catch (Exception e) {
            log.warn("Failed to list SAML providers for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves a single active SAML provider by IdP entity ID within a tenant.
     * Used by the SAML success handler to recover the provider's attribute
     * mappings from the validated assertion's issuer.
     */
    public Optional<SamlProviderInfo> findSamlProviderByEntityId(String entityId, String tenantId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/internal/saml/by-entity-id?entityId={entityId}&tenantId={tenantId}", entityId, tenantId)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return Optional.empty();
            }
            return Optional.of(parseSamlProviderInfo(response));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to look up SAML provider for entityId {}: {}", entityId, e.getMessage());
            return Optional.empty();
        }
    }

    private SamlProviderInfo parseSamlProviderInfo(JsonNode node) {
        return new SamlProviderInfo(
                node.path("id").asText(null),
                node.path("name").asText(null),
                node.path("registrationId").asText(null),
                node.path("idpEntityId").asText(null),
                node.path("ssoUrl").asText(null),
                node.path("sloUrl").asText(null),
                node.path("idpCertificate").asText(null),
                node.path("nameIdFormat").asText(null),
                node.path("emailAttribute").asText(null),
                node.path("profileAttribute").asText(null),
                node.path("active").asBoolean(true)
        );
    }

    public record JitProvisionResult(
            String userId, String profileId, String profileName,
            String status, boolean created
    ) {}

    public Optional<JitProvisionResult> jitProvisionUser(
            String email, String tenantId, String firstName, String lastName, String profileId) {
        try {
            Map<String, Object> body = Map.of(
                    "email", email,
                    "tenantId", tenantId,
                    "firstName", firstName != null ? firstName : "",
                    "lastName", lastName != null ? lastName : "",
                    "profileId", profileId != null ? profileId : ""
            );

            // Remove empty profileId
            java.util.Map<String, Object> requestBody = new java.util.LinkedHashMap<>(body);
            if (profileId == null) {
                requestBody.remove("profileId");
            }

            JsonNode response = restClient.post()
                    .uri("/internal/user-identity/jit")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return Optional.empty();
            }

            return Optional.of(new JitProvisionResult(
                    response.path("userId").asText(null),
                    response.path("profileId").asText(null),
                    response.path("profileName").asText(null),
                    response.path("status").asText(null),
                    response.path("created").asBoolean(false)
            ));
        } catch (Exception e) {
            log.warn("Failed to JIT provision user {}: {}", email, e.getMessage());
            return Optional.empty();
        }
    }

    private OidcProviderInfo parseOidcProviderInfo(JsonNode response) {
        return new OidcProviderInfo(
                response.path("id").asText(null),
                response.path("name").asText(null),
                response.path("issuer").asText(null),
                response.path("jwksUri").asText(null),
                response.path("audience").asText(null),
                response.path("clientId").asText(null),
                response.path("clientSecretEnc").asText(null),
                response.path("rolesClaim").asText(null),
                response.path("groupsClaim").asText(null),
                response.path("groupsProfileMapping").asText(null),
                response.path("authorizationUri").asText(null),
                response.path("tokenUri").asText(null),
                response.path("userinfoUri").asText(null),
                response.path("endSessionUri").asText(null),
                response.path("emailClaim").asText(null),
                response.path("usernameClaim").asText(null),
                response.path("nameClaim").asText(null)
        );
    }

    public Optional<UserIdentity> findUserIdentity(String email, String tenantId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/internal/user-identity?email={email}&tenantId={tenantId}", email, tenantId)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return Optional.empty();
            }

            return Optional.of(new UserIdentity(
                    response.path("userId").asText(null),
                    response.path("profileId").asText(null),
                    response.path("profileName").asText(null)
            ));
        } catch (Exception e) {
            log.warn("Failed to look up user identity for {}: {}", email, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves a custom domain (Host header value) to a tenant slug via the
     * worker's internal endpoint. Returns {@code Optional.empty()} for unknown
     * domains or transient failures — callers fall back to other tenant
     * resolution strategies.
     */
    public Optional<String> resolveCustomDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return Optional.empty();
        }
        try {
            String slug = restClient.get()
                    .uri("/internal/domains/resolve?domain={domain}", domain)
                    .retrieve()
                    .body(String.class);
            return (slug == null || slug.isBlank()) ? Optional.empty() : Optional.of(slug.trim());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Custom domain resolve failed for '{}': {}", domain, e.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // SMS Verification
    // -----------------------------------------------------------------------

    public boolean sendSmsCode(String phoneNumber, String tenantId) {
        try {
            restClient.post()
                    .uri("/internal/sms/send")
                    .body(Map.of("phoneNumber", phoneNumber, "tenantId", tenantId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("Failed to send SMS code via worker: phone={}, error={}", phoneNumber, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public boolean verifySmsCode(String phoneNumber, String code, String tenantId) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/internal/sms/verify")
                    .body(Map.of("phoneNumber", phoneNumber, "code", code, "tenantId", tenantId))
                    .retrieve()
                    .body(Map.class);
            return response != null && Boolean.TRUE.equals(response.get("verified"));
        } catch (Exception e) {
            log.error("Failed to verify SMS code via worker: error={}", e.getMessage());
            return false;
        }
    }
}
