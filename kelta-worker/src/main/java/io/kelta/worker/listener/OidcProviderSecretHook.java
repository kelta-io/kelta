package io.kelta.worker.listener;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Encrypts the OIDC provider client secret before it is persisted.
 *
 * <p>The UI sends a plaintext {@code clientSecret} field. This hook:
 * <ol>
 *   <li>Encrypts it into {@code clientSecretEnc} using AES-256-GCM</li>
 *   <li>Removes the plaintext from the record so it is never stored</li>
 * </ol>
 *
 * <p>{@code clientSecretEnc} is a server-only field ({@code FieldDefinition.serverOnly}): the
 * query engine never returns it and ignores a caller-supplied value, so this hook is the only
 * writer. The encrypted value is only decrypted internally (read from the column directly) when
 * kelta-auth needs to perform token exchange with the external IdP.
 *
 * <p><b>Not registered.</b> Before-save hooks are registered by being Spring beans
 * ({@code BeforeSaveHookRegistrar}), and this class is deliberately not one. It used to be a
 * {@code @Component} (gated on {@code kelta.encryption.key}) at a time when hooks were registered
 * by hand, and nothing ever registered it, so it has never run. Making it a bean again is a
 * behaviour change to make on purpose, with tests, and must update
 * {@code BeforeSaveHookWiringTest}.
 */
public class OidcProviderSecretHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(OidcProviderSecretHook.class);
    private static final String COLLECTION = "oidc-providers";
    private static final String CLIENT_SECRET_FIELD = "clientSecret";
    private static final String CLIENT_SECRET_ENC_FIELD = "clientSecretEnc";

    private final EncryptionService encryptionService;

    public OidcProviderSecretHook(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    @Override
    public int getOrder() {
        return -100; // Run early, before audit hooks
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
        Object clientSecret = record.get(CLIENT_SECRET_FIELD);
        if (clientSecret == null) {
            return BeforeSaveResult.ok();
        }

        String secretValue = clientSecret.toString();
        if (secretValue.isBlank()) {
            return BeforeSaveResult.ok();
        }

        // Don't re-encrypt already encrypted values
        if (EncryptionService.isEncrypted(secretValue)) {
            return BeforeSaveResult.ok();
        }

        log.debug("Encrypting client secret for OIDC provider");
        String encrypted = encryptionService.encrypt(secretValue);

        Map<String, Object> updates = new HashMap<>();
        updates.put(CLIENT_SECRET_ENC_FIELD, encrypted);
        // Remove plaintext so it never reaches the storage adapter
        record.remove(CLIENT_SECRET_FIELD);

        return BeforeSaveResult.withFieldUpdates(updates);
    }
}
