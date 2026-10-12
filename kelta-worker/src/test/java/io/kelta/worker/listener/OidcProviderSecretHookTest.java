package io.kelta.worker.listener;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OidcProviderSecretHook")
class OidcProviderSecretHookTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SECRET = "plaintext-client-secret-value";

    private EncryptionService encryptionService;
    private OidcProviderSecretHook hook;

    @BeforeEach
    void setUp() {
        encryptionService = new EncryptionService(TEST_KEY);
        hook = new OidcProviderSecretHook(provider(encryptionService));
    }

    private static ObjectProvider<EncryptionService> provider(EncryptionService service) {
        StaticListableBeanFactory factory = service == null
                ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(Map.of("encryptionService", service));
        return factory.getBeanProvider(EncryptionService.class);
    }

    private static Map<String, Object> record(Object secret) {
        Map<String, Object> record = new HashMap<>();
        record.put("name", "Provider");
        record.put("issuer", "https://idp.example.com");
        record.put("clientId", "client");
        record.put("clientSecret", secret);
        return record;
    }

    @Test
    @DisplayName("targets oidc-providers and runs first, before every other hook sees the record")
    void collectionAndOrder() {
        assertEquals("oidc-providers", hook.getCollectionName());
        assertEquals(-100, hook.getOrder());
    }

    @Test
    @DisplayName("create encrypts the secret into clientSecretEnc and removes the plaintext")
    void createEncrypts() {
        Map<String, Object> record = record(SECRET);

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertTrue(result.isSuccess());
        assertFalse(record.containsKey("clientSecret"), "plaintext must not travel further");
        String stored = (String) result.getFieldUpdates().get("clientSecretEnc");
        assertTrue(EncryptionService.isEncrypted(stored));
        assertFalse(stored.contains(SECRET));
        assertEquals(SECRET, encryptionService.decrypt(stored), "kelta-auth can decrypt it");
    }

    @Test
    @DisplayName("update with a new secret replaces the stored ciphertext")
    void updateEncrypts() {
        Map<String, Object> record = record("rotated-secret");

        BeforeSaveResult result = hook.beforeUpdate("id-1", record,
                Map.of("clientSecretEnc", encryptionService.encrypt(SECRET)), "tenant-1");

        assertFalse(record.containsKey("clientSecret"));
        assertEquals("rotated-secret",
                encryptionService.decrypt((String) result.getFieldUpdates().get("clientSecretEnc")));
    }

    @Test
    @DisplayName("update with a blank or absent secret keeps the stored one")
    void blankOrAbsentKeepsSecret() {
        for (Object blank : new Object[] {"", "   ", null}) {
            Map<String, Object> record = record(blank);

            BeforeSaveResult result = hook.beforeUpdate("id-1", record, Map.of(), "tenant-1");

            assertTrue(result.isSuccess());
            assertFalse(result.hasFieldUpdates(), "nothing written for " + blank);
            assertFalse(record.containsKey("clientSecret"));
        }
        Map<String, Object> withoutKey = new HashMap<>(Map.of("name", "renamed"));
        BeforeSaveResult result = hook.beforeUpdate("id-1", withoutKey, Map.of(), "tenant-1");
        assertTrue(result.isSuccess());
        assertFalse(result.hasFieldUpdates());
    }

    @Test
    @DisplayName("surrounding whitespace is stripped before encryption")
    void stripsWhitespace() {
        BeforeSaveResult result = hook.beforeCreate(record("  " + SECRET + "\n"), "tenant-1");

        assertEquals(SECRET, encryptionService.decrypt((String) result.getFieldUpdates().get("clientSecretEnc")));
    }

    @Test
    @DisplayName("a value that looks like ciphertext is still encrypted, never stored as given")
    void ciphertextLookingInputIsEncrypted() {
        String planted = encryptionService.encrypt("someone-elses-secret");

        BeforeSaveResult result = hook.beforeCreate(record(planted), "tenant-1");

        String stored = (String) result.getFieldUpdates().get("clientSecretEnc");
        assertNotEquals(planted, stored);
        assertEquals(planted, encryptionService.decrypt(stored));
    }

    @Test
    @DisplayName("a non-string secret is rejected and its value is not echoed")
    void nonStringRejected() {
        Map<String, Object> record = record(Map.of("nested", SECRET));

        BeforeSaveResult result = hook.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertEquals("clientSecret", result.getErrors().get(0).field());
        assertFalse(result.getErrors().get(0).message().contains(SECRET));
        assertFalse(record.containsKey("clientSecret"));
    }

    @Test
    @DisplayName("without an encryption key a supplied secret is refused, not silently dropped")
    void refusesWithoutEncryption() {
        OidcProviderSecretHook unconfigured = new OidcProviderSecretHook(provider(null));
        Map<String, Object> record = record(SECRET);

        BeforeSaveResult result = unconfigured.beforeCreate(record, "tenant-1");

        assertFalse(result.isSuccess());
        assertEquals("clientSecret", result.getErrors().get(0).field());
        assertFalse(result.getErrors().get(0).message().contains(SECRET));
        assertFalse(record.containsKey("clientSecret"));
    }

    @Test
    @DisplayName("without an encryption key a save that carries no secret still succeeds")
    void noSecretNoEncryptionNeeded() {
        OidcProviderSecretHook unconfigured = new OidcProviderSecretHook(provider(null));

        assertTrue(unconfigured.beforeUpdate("id-1", record(""), Map.of(), "tenant-1").isSuccess());
    }
}
