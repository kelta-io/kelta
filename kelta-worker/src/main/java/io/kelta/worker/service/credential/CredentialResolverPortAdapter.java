package io.kelta.worker.service.credential;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.worker.repository.CredentialOAuthTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adapts the worker's {@link CredentialResolver} to the platform-side
 * {@link CredentialResolverPort} SPI consumed by integration handlers
 * (CALL_API and friends in PR 4+). Also splices the current OAuth access
 * token onto the credential's secrets for OAuth types so handlers can write
 * a single uniform applyAuth path.
 */
@Service
@ConditionalOnProperty(name = "kelta.encryption.key")
public class CredentialResolverPortAdapter implements CredentialResolverPort {

    private static final Logger log = LoggerFactory.getLogger(CredentialResolverPortAdapter.class);

    private final CredentialResolver resolver;
    private final CredentialOAuthTokenRepository tokenRepository;
    private final EncryptionService encryptionService;

    public CredentialResolverPortAdapter(CredentialResolver resolver,
                                          CredentialOAuthTokenRepository tokenRepository,
                                          EncryptionService encryptionService) {
        this.resolver = resolver;
        this.tokenRepository = tokenRepository;
        this.encryptionService = encryptionService;
    }

    @Override
    public ResolvedCredential resolve(String tenantId, String reference, String purpose) {
        return resolve(tenantId, reference, purpose, null);
    }

    /** As the three-argument form, with {@code actorUserId} recorded as the audit row's user. */
    @Override
    public ResolvedCredential resolve(String tenantId, String reference, String purpose, String actorUserId) {
        ResolvedCredential base = resolver.resolve(
            tenantId, reference, ResolutionContext.forUser(actorUserId, purpose));

        if (!isOAuthType(base.type())) {
            return base;
        }

        // Layer the (decrypted) access token onto secretFields for the handler.
        Optional<Map<String, Object>> tokenRow =
            tokenRepository.findByCredentialId(base.id(), tenantId);
        if (tokenRow.isEmpty()) {
            return base;
        }
        Map<String, Object> row = tokenRow.get();
        String accessTokenEnc = (String) row.get("access_token_enc");
        if (accessTokenEnc == null || accessTokenEnc.isBlank()) {
            return base;
        }

        Map<String, Object> secrets = new LinkedHashMap<>(base.secretFields());
        try {
            secrets.put("accessToken", encryptionService.decrypt(accessTokenEnc));
        } catch (Exception e) {
            log.warn("Failed to decrypt OAuth access token for credential {}: {}",
                base.id(), e.getMessage());
            return base;
        }
        // Also expose token_type / expires_at so downstream code can decide
        // whether to refresh proactively.
        if (row.get("token_type") != null) {
            secrets.put("tokenType", row.get("token_type"));
        }
        if (row.get("expires_at") != null) {
            secrets.put("expiresAt", row.get("expires_at").toString());
        }

        return new ResolvedCredential(
            base.id(), base.name(), base.type(),
            secrets, base.metadataFields(), Instant.now());
    }

    private static boolean isOAuthType(String type) {
        return "oauth2_client_credentials".equals(type)
            || "oauth2_authorization_code".equals(type);
    }
}
