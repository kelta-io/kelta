package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-only fields ({@code FieldDefinition.serverOnly}) never leave the worker through the
 * generic record API. The {@code credentials} system collection stores its encrypted material in
 * {@code dataEnc}, and {@code credential-oauth-tokens} stores encrypted access/refresh tokens;
 * both ride the generic {@code DynamicCollectionRouter} route, so the property has to hold on
 * every read shape a client can ask for — the create response, a list, a single read, a sparse
 * fieldset that names the field, and an {@code ?include=} that pulls the credential in through a
 * lookup. A filter on a server-only field is rejected outright: answering it would make the
 * stored value guessable one prefix at a time.
 *
 * <p>Assertions are on the raw response text so a field that leaks anywhere in the document
 * (attributes, included, meta) fails the test, and the stored ciphertext is read back from the
 * database first so the absence is not just "nothing was stored".
 */
@DisplayName("Server-only field scenario")
class ServerOnlyFieldScenarioTest extends ScenarioBase {

    private static final String CREDENTIALS = "/api/credentials";
    private static final String OAUTH_TOKENS = "/api/credential-oauth-tokens";

    @Test
    @DisplayName("credential material is absent from create, list, get, sparse fieldsets and includes")
    @SuppressWarnings("unchecked")
    void serverOnlyFieldsAreNeverSerialized() throws Exception {
        String token = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String slug = tenants.slugForTenantId(tenantId);
        RestClient client = gatewayClientWithToken(token);
        waitForStatus(client, "/" + slug + CREDENTIALS, HttpStatus.OK, 60);

        String name = "harness-server-only-" + Long.toHexString(System.nanoTime());
        String plaintextSecret = "plaintext-" + UUID.randomUUID();
        String tokenRowId = UUID.randomUUID().toString();
        String accessCipher = "access-cipher-" + UUID.randomUUID();
        String refreshCipher = "refresh-cipher-" + UUID.randomUUID();
        String credentialId = null;
        SoftAssertions softly = new SoftAssertions();

        try (Connection db = openDbConnection()) {
            try {
                // ---- Create through the generic route: the encryption hook fills dataEnc.
                ResponseEntity<String> created = client.post()
                        .uri("/" + slug + CREDENTIALS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("data", Map.of(
                                "type", "credentials",
                                "attributes", Map.of(
                                        "name", name,
                                        "type", "bearer_token",
                                        "token", plaintextSecret))))
                        .retrieve()
                        .onStatus(s -> true, (req, resp) -> { })
                        .toEntity(String.class);
                assertThat(created.getStatusCode()).as("create: %s", created.getBody())
                        .isEqualTo(HttpStatus.CREATED);
                credentialId = readCredentialId(db, tenantId, name);
                String storedCipher = readDataEnc(db, credentialId);
                assertThat(storedCipher).as("encryption hook stored ciphertext").isNotBlank();

                assertNoLeak(softly, "create response", created.getBody(), plaintextSecret, storedCipher);

                // ---- List, single, and a sparse fieldset that names the field explicitly.
                String list = getOk(client, "/" + slug + CREDENTIALS + "?filter[name][eq]=" + name);
                assertThat(list).contains(credentialId);
                assertNoLeak(softly, "list", list, plaintextSecret, storedCipher);

                String single = getOk(client, "/" + slug + CREDENTIALS + "/" + credentialId);
                assertThat(single).contains(name);
                assertNoLeak(softly, "single", single, plaintextSecret, storedCipher);

                ResponseEntity<String> sparse = client.get()
                        .uri("/" + slug + CREDENTIALS + "/" + credentialId
                                + "?fields[credentials]=name,dataEnc")
                        .retrieve()
                        .onStatus(s -> true, (req, resp) -> { })
                        .toEntity(String.class);
                assertNoLeak(softly, "sparse fieldset", sparse.getBody(), plaintextSecret, storedCipher);

                // ---- A predicate on a server-only field is refused, not evaluated.
                ResponseEntity<String> oracle = client.get()
                        .uri("/" + slug + CREDENTIALS + "?filter[dataEnc][starts]="
                                + storedCipher.substring(0, 4))
                        .retrieve()
                        .onStatus(s -> true, (req, resp) -> { })
                        .toEntity(String.class);
                softly.assertThat(oracle.getStatusCode().is4xxClientError())
                        .as("filter on a server-only field must be rejected, got %s: %s",
                                oracle.getStatusCode(), oracle.getBody())
                        .isTrue();

                // ---- Include: an OAuth token row pulls the credential in through its lookup.
                insertOAuthToken(db, tokenRowId, credentialId, tenantId, accessCipher, refreshCipher);
                String withInclude = getOk(client, "/" + slug + OAUTH_TOKENS
                        + "?filter[credentialId][eq]=" + credentialId + "&include=credentialId");
                assertThat(withInclude).as("token row is listed").contains(tokenRowId);
                assertThat(withInclude).as("credential is included").contains(name);
                assertNoLeak(softly, "include", withInclude, plaintextSecret, storedCipher);
                softly.assertThat(withInclude).doesNotContain("accessTokenEnc", "refreshTokenEnc",
                        accessCipher, refreshCipher);
                softly.assertAll();
            } finally {
                deleteRow(db, "DELETE FROM credential_oauth_token WHERE id = ?", tokenRowId);
                if (credentialId != null) {
                    deleteRow(db, "DELETE FROM credential WHERE id = ?", credentialId);
                }
            }
        }
    }

    private static void assertNoLeak(SoftAssertions softly, String shape, String body, String plaintext, String cipher) {
        assertThat(body).as("%s body", shape).isNotNull();
        softly.assertThat(body).as("%s must not carry the field name", shape).doesNotContain("dataEnc");
        softly.assertThat(body).as("%s must not carry the stored ciphertext", shape).doesNotContain(cipher);
        softly.assertThat(body).as("%s must not carry the plaintext input", shape).doesNotContain(plaintext);
    }

    private static String getOk(RestClient client, String uri) {
        ResponseEntity<String> response = client.get().uri(uri)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> { })
                .toEntity(String.class);
        assertThat(response.getStatusCode()).as("GET %s: %s", uri, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String readCredentialId(Connection db, String tenantId, String name) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM credential WHERE tenant_id = ? AND name = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("credential row %s exists", name).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static String readDataEnc(Connection db, String id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("SELECT data_enc FROM credential WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void insertOAuthToken(Connection db, String id, String credentialId, String tenantId,
                                         String access, String refresh) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO credential_oauth_token
                    (id, credential_id, tenant_id, access_token_enc, refresh_token_enc, token_type,
                     expires_at, refreshed_at)
                VALUES (?, ?, ?, ?, ?, 'Bearer', now() + interval '1 hour', now())
                """)) {
            ps.setString(1, id);
            ps.setString(2, credentialId);
            ps.setString(3, tenantId);
            ps.setString(4, access);
            ps.setString(5, refresh);
            ps.executeUpdate();
        }
    }

    private static void deleteRow(Connection db, String sql, String id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
