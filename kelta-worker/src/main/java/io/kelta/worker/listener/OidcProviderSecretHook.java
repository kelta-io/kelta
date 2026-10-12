package io.kelta.worker.listener;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stores an OIDC provider's client secret encrypted, and never in plaintext.
 *
 * <p>Callers (the admin UI, the API) send the secret as a write-only {@code clientSecret}
 * attribute. It is not a field of {@code oidc-providers}; this hook consumes it:
 * <ol>
 *   <li>a non-blank value is encrypted (AES-256-GCM, {@link EncryptionService}) into
 *       {@code clientSecretEnc};</li>
 *   <li>the plaintext is removed from the record in every case, before any later hook, the
 *       storage adapter or the {@code record.changed} event can see it;</li>
 *   <li>an absent or blank value leaves the stored secret unchanged — the admin form sends an
 *       empty value on edit to mean "keep the current secret".</li>
 * </ol>
 *
 * <p>{@code clientSecretEnc} is a server-only field ({@code FieldDefinition.serverOnly}): the
 * query engine never returns it and drops a caller-supplied value before the hooks run, so this
 * hook is its only writer through the API. kelta-auth reads the column over the worker's internal
 * bootstrap endpoint and decrypts it to authenticate to the external identity provider.
 *
 * <p>If no encryption key is configured, a save that carries a secret is refused rather than
 * silently storing nothing.
 */
@Component
public class OidcProviderSecretHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(OidcProviderSecretHook.class);

    static final String COLLECTION = "oidc-providers";
    static final String CLIENT_SECRET_FIELD = "clientSecret";
    static final String CLIENT_SECRET_ENC_FIELD = "clientSecretEnc";

    private final ObjectProvider<EncryptionService> encryptionService;

    public OidcProviderSecretHook(ObjectProvider<EncryptionService> encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    /** First on the collection: the plaintext must be gone before any other hook sees the record. */
    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return encryptSecret(record);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return encryptSecret(record);
    }

    private BeforeSaveResult encryptSecret(Map<String, Object> record) {
        // Removed unconditionally: whatever happens next, the plaintext never travels further.
        Object submitted = record.remove(CLIENT_SECRET_FIELD);
        if (submitted == null) {
            return BeforeSaveResult.ok();
        }
        if (!(submitted instanceof String secret)) {
            return BeforeSaveResult.error(CLIENT_SECRET_FIELD, "Client secret must be a string.");
        }
        // Surrounding whitespace is never part of a client secret; it is a paste artefact.
        String value = secret.strip();
        if (value.isEmpty()) {
            return BeforeSaveResult.ok(); // keep the stored secret
        }

        EncryptionService encryption = encryptionService.getIfAvailable();
        if (encryption == null) {
            log.warn("Refusing to store an OIDC client secret: no encryption key is configured");
            return BeforeSaveResult.error(CLIENT_SECRET_FIELD,
                    "The client secret cannot be stored because encryption is not configured on this server.");
        }

        log.debug("Encrypting the client secret for an OIDC provider");
        return BeforeSaveResult.withFieldUpdates(Map.of(CLIENT_SECRET_ENC_FIELD, encryption.encrypt(value)));
    }
}
