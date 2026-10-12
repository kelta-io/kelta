package io.kelta.testharness.scenarios;

import io.kelta.testharness.KeltaStack;
import io.kelta.testharness.ScenarioBase;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An OIDC provider's client secret, end to end through the real stack: the admin API accepts it
 * as a write-only {@code clientSecret} attribute, {@code OidcProviderSecretHook} stores it
 * encrypted in {@code oidc_provider.client_secret_enc}, no read shape ever returns the plaintext,
 * the ciphertext or the field name, an edit with a blank secret keeps it, a new one replaces it,
 * and the value kelta-auth reads from the worker's internal bootstrap endpoint decrypts with the
 * shared key to the submitted secret.
 *
 * <p>Also covers {@code OidcDiscoveryHook} on the real worker: an issuer that does not resolve
 * records {@code error}, and a loopback issuer is refused by the outbound URL guard
 * ({@code blocked}) — neither blocks the save.
 */
@DisplayName("OIDC provider secret scenario")
class OidcProviderSecretScenarioTest extends ScenarioBase {

    private static final String PROVIDERS = "/api/oidc-providers";
    /** Never resolves (RFC 6761), so discovery fails without a network dependency. */
    private static final String ISSUER = "https://idp.harness.invalid/realms/harness";
    private static final String AUTHORIZATION_URI = "https://idp.harness.invalid/authorize";

    @Test
    @DisplayName("the client secret is stored encrypted, never returned, kept on blank edit, and decryptable for kelta-auth")
    void clientSecretRoundTrip() throws Exception {
        String token = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient client = gatewayClientWithToken(token);
        waitForStatus(client, "/" + slug + PROVIDERS, HttpStatus.OK, 60);

        String name = "harness-oidc-" + Long.toHexString(System.nanoTime());
        String secret = "plaintext-" + UUID.randomUUID();
        List<String> createdIds = new ArrayList<>();
        SoftAssertions softly = new SoftAssertions();

        try (Connection db = openDbConnection()) {
            try {
                // ---- Create, shaped like the admin form's payload (empty overrides, scopes list).
                Map<String, Object> attributes = providerAttributes(name, ISSUER);
                attributes.put("clientSecret", secret);
                attributes.put("authorizationUri", AUTHORIZATION_URI);
                attributes.put("tokenUri", "https://idp.harness.invalid/token");
                ResponseEntity<String> created = write(client, "POST", "/" + slug + PROVIDERS, attributes, null);
                assertThat(created.getStatusCode()).as("create: %s", created.getBody()).isEqualTo(HttpStatus.CREATED);

                String id = readId(db, tenantId, name);
                createdIds.add(id);
                String cipher = readColumn(db, id, "client_secret_enc");
                assertThat(cipher).as("secret hook stored ciphertext").startsWith("enc:v1:").doesNotContain(secret);
                softly.assertThat(readColumn(db, id, "discovery_status"))
                        .as("an issuer that does not resolve is a non-blocking discovery error")
                        .isEqualTo("error");
                assertNoLeak(softly, "create response", created.getBody(), secret, cipher);

                // ---- Every read shape.
                assertNoLeak(softly, "list", getOk(client, "/" + slug + PROVIDERS), secret, cipher);
                assertNoLeak(softly, "single", getOk(client, "/" + slug + PROVIDERS + "/" + id), secret, cipher);
                assertNoLeak(softly, "sparse fieldset", getOk(client,
                        "/" + slug + PROVIDERS + "/" + id + "?fields[oidc-providers]=name,clientSecretEnc"),
                        secret, cipher);

                // ---- An edit with a blank secret (the form's "keep current") keeps the ciphertext.
                Map<String, Object> rename = providerAttributes(name, ISSUER);
                rename.put("clientSecret", "");
                rename.put("authorizationUri", AUTHORIZATION_URI);
                rename.put("tokenUri", "https://idp.harness.invalid/token");
                ResponseEntity<String> renamed = write(client, "PATCH", "/" + slug + PROVIDERS + "/" + id, rename, id);
                assertThat(renamed.getStatusCode()).as("blank-secret edit: %s", renamed.getBody()).isEqualTo(HttpStatus.OK);
                softly.assertThat(readColumn(db, id, "client_secret_enc")).as("kept on blank edit").isEqualTo(cipher);
                assertNoLeak(softly, "update response", renamed.getBody(), secret, cipher);

                // ---- What kelta-auth reads (the worker's internal bootstrap endpoint) decrypts, with
                // the shared key, to the submitted secret — the same EncryptionService format.
                softly.assertThat(decrypt(internalSecretEnc(tenantId, id)))
                        .as("kelta-auth's view of the secret decrypts to the submitted value")
                        .isEqualTo(secret);

                // ---- A new secret replaces the ciphertext.
                String rotatedSecret = "rotated-" + UUID.randomUUID();
                Map<String, Object> rotate = new HashMap<>(Map.of("clientSecret", rotatedSecret));
                ResponseEntity<String> rotated = write(client, "PATCH", "/" + slug + PROVIDERS + "/" + id, rotate, id);
                assertThat(rotated.getStatusCode()).as("rotate: %s", rotated.getBody()).isEqualTo(HttpStatus.OK);
                softly.assertThat(readColumn(db, id, "client_secret_enc")).as("rotated")
                        .startsWith("enc:v1:").isNotEqualTo(cipher);
                softly.assertThat(decrypt(internalSecretEnc(tenantId, id))).as("rotated value").isEqualTo(rotatedSecret);
                assertNoLeak(softly, "rotate response", rotated.getBody(), rotatedSecret, cipher);

                // ---- A loopback issuer is refused by the outbound URL guard; the save still succeeds.
                String blockedName = name + "-blocked";
                Map<String, Object> blocked = providerAttributes(blockedName, "http://127.0.0.1:1/realms/x");
                blocked.put("clientSecret", secret);
                ResponseEntity<String> blockedCreate = write(client, "POST", "/" + slug + PROVIDERS, blocked, null);
                assertThat(blockedCreate.getStatusCode()).as("blocked create: %s", blockedCreate.getBody())
                        .isEqualTo(HttpStatus.CREATED);
                String blockedId = readId(db, tenantId, blockedName);
                createdIds.add(blockedId);
                softly.assertThat(readColumn(db, blockedId, "discovery_status")).isEqualTo("blocked");

                softly.assertAll();
            } finally {
                for (String id : createdIds) {
                    try (PreparedStatement ps = db.prepareStatement("DELETE FROM oidc_provider WHERE id = ?")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    private static Map<String, Object> providerAttributes(String name, String issuer) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("name", name);
        attributes.put("issuer", issuer);
        attributes.put("clientId", "harness-client");
        attributes.put("scopes", List.of("openid", "profile", "email"));
        attributes.put("jwksUri", "");
        attributes.put("authorizationUri", "");
        attributes.put("tokenUri", "");
        attributes.put("userinfoUri", "");
        attributes.put("endSessionUri", "");
        attributes.put("active", true);
        return attributes;
    }

    private static ResponseEntity<String> write(RestClient client, String method, String uri,
                                                Map<String, Object> attributes, String id) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "oidc-providers");
        data.put("attributes", attributes);
        if (id != null) {
            data.put("id", id);
        }
        RestClient.RequestBodySpec spec = "POST".equals(method)
                ? client.post().uri(uri)
                : client.patch().uri(uri);
        return spec.contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("data", data))
                .retrieve()
                .onStatus(s -> true, (req, resp) -> { })
                .toEntity(String.class);
    }

    /** {@code clientSecretEnc} as kelta-auth receives it from the worker's internal endpoint. */
    @SuppressWarnings("unchecked")
    private String internalSecretEnc(String tenantId, String id) {
        List<Map<String, Object>> providers = workerClient().get()
                .uri("/internal/oidc/providers?tenantId=" + tenantId)
                .retrieve()
                .body(List.class);
        assertThat(providers).as("internal provider list").isNotNull();
        return providers.stream()
                .filter(p -> id.equals(p.get("id")))
                .map(p -> (String) p.get("clientSecretEnc"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("provider " + id + " not served to kelta-auth"));
    }

    /** Decrypts {@code enc:v1:<iv>:<ciphertext>} (AES-256-GCM) exactly as {@code EncryptionService} does. */
    private static String decrypt(String value) throws Exception {
        assertThat(value).startsWith("enc:v1:");
        String[] parts = value.substring("enc:v1:".length()).split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(KeltaStack.encryptionKey()), "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }

    private static void assertNoLeak(SoftAssertions softly, String shape, String body, String plaintext, String cipher) {
        assertThat(body).as("%s body", shape).isNotNull();
        softly.assertThat(body).as("%s must not carry the plaintext", shape).doesNotContain(plaintext);
        softly.assertThat(body).as("%s must not carry the ciphertext", shape).doesNotContain(cipher);
        softly.assertThat(body).as("%s must not name the secret fields", shape).doesNotContain("clientSecret");
    }

    private static String getOk(RestClient client, String uri) {
        ResponseEntity<String> response = client.get().uri(uri)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> { })
                .toEntity(String.class);
        assertThat(response.getStatusCode()).as("GET %s: %s", uri, response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String readId(Connection db, String tenantId, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM oidc_provider WHERE tenant_id = ? AND name = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("oidc_provider row %s exists", name).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static String readColumn(Connection db, String id, String column) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT " + column + " FROM oidc_provider WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
