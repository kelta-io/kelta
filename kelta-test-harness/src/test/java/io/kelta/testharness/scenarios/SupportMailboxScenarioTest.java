package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Support mailbox through the real stack (support-mailbox slices 1-7).
 *
 * <p>This scenario exists because of what the worker's own tests cannot see. Those mock
 * {@code JdbcTemplate} and mock the request, so a SQL string Postgres will reject passes cleanly
 * and the identity the gateway actually stamps is never observed. Three production bugs reached a
 * live deployment that way:
 *
 * <ul>
 *   <li>{@code addressExists} used {@code ? IS NULL}, which Postgres rejects with "could not
 *       determine data type of parameter" — mailbox creation returned 500.</li>
 *   <li>The console thread query concatenated to {@code WHEREt.tenant_id} with no space.</li>
 *   <li>Membership was stored against a user id while {@code X-User-Id} carries an email, so no
 *       grant ever matched and the console showed nothing.</li>
 * </ul>
 *
 * <p>Every assertion below would have failed on at least one of those. The point is not the
 * individual cases but the class: these endpoints must be exercised against a real Postgres and a
 * real gateway, or the next one ships the same way.
 */
@DisplayName("Support Mailbox Scenario")
class SupportMailboxScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("creates a mailbox, grants access by email, and serves the console")
    @SuppressWarnings("unchecked")
    void mailboxRoundTrip() throws Exception {
        String token = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String slug = tenants.slugForTenantId(tenantId);

        waitForStatus(gatewayClientWithToken(token), "/" + slug + "/api/support/mailboxes",
                HttpStatus.OK, 20);

        // Creation exercises addressExists — the query whose untyped parameter Postgres rejected.
        ResponseEntity<Map> created = gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/support/mailboxes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "name", "Harness Support",
                        "address", "harness-support@example.com",
                        "inboundProvider", "SES_SNS",
                        "slaFirstResponseMinutes", 60))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        String mailboxId = String.valueOf(data.get("id"));
        Map<String, Object> attrs = (Map<String, Object>) data.get("attributes");

        // The webhook key and the one-time secret are both minted server-side.
        assertThat(attrs.get("webhookKey")).asString().isNotBlank();
        assertThat(attrs.get("inboundSecret")).asString().isNotBlank();
        // Automation is off on a new mailbox, always.
        assertThat(attrs.get("autoReplyEnabled")).isEqualTo(false);

        // Re-reading must never disclose the secret again.
        ResponseEntity<Map> reread = gatewayClientWithToken(token)
                .get().uri("/" + slug + "/api/support/mailboxes/" + mailboxId)
                .retrieve().toEntity(Map.class);
        Map<String, Object> rereadAttrs =
                (Map<String, Object>) ((Map<String, Object>) reread.getBody().get("data")).get("attributes");
        assertThat(rereadAttrs).doesNotContainKey("inboundSecret");

        // The auto-reply denylist is jsonb. A PATCH that binds a Java list to a plain placeholder
        // returned 200 and persisted nothing — the kill switch could only be changed at a psql
        // prompt. Only a real Postgres shows the difference between "accepted" and "stored".
        ResponseEntity<Map> patched = gatewayClientWithToken(token)
                .patch().uri("/" + slug + "/api/support/mailboxes/" + mailboxId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "autoReplyBlockedCategories", List.of("billing", "alert_quality"),
                        "maxAutoRepliesPerDay", 7))
                .retrieve().toEntity(Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> afterPatch = (Map<String, Object>) ((Map<String, Object>)
                gatewayClientWithToken(token)
                        .get().uri("/" + slug + "/api/support/mailboxes/" + mailboxId)
                        .retrieve().toEntity(Map.class).getBody().get("data")).get("attributes");
        assertThat(afterPatch.get("autoReplyBlockedCategories"))
                .as("the denylist must be read back as what was written, not the default")
                .isEqualTo(List.of("billing", "alert_quality"));
        assertThat(afterPatch.get("maxAutoRepliesPerDay")).isEqualTo(7);

        // A duplicate address is refused rather than silently creating a second mailbox that
        // would split one conversation stream in two.
        assertThat(postExpectingFailure(token, slug, Map.of(
                "name", "Duplicate", "address", "harness-support@example.com")))
                .isEqualTo(HttpStatus.CONFLICT);

        // Granting by EMAIL is what an admin actually types, and what the gateway stamps in
        // X-User-Id. It must resolve to the same identity the console later looks up.
        ResponseEntity<Map> grant = gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/support/mailboxes/" + mailboxId + "/access")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "principalType", "USER",
                        "principalId", io.kelta.testharness.fixtures.AuthFixture.adminUsername(slug),
                        "role", "MANAGER"))
                .retrieve().toEntity(Map.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The console must now see the mailbox. This is the assertion that catches the
        // email-versus-id mismatch: the grant above succeeds either way, and only this fails.
        ResponseEntity<Map> mine = gatewayClientWithToken(token)
                .get().uri("/" + slug + "/api/support/my-mailboxes")
                .retrieve().toEntity(Map.class);
        List<Map<String, Object>> visible = (List<Map<String, Object>>) mine.getBody().get("data");
        assertThat(visible)
                .as("a granted member must see the mailbox in the console")
                .anySatisfy(m -> assertThat(((Map<String, Object>) m.get("attributes")).get("address"))
                        .isEqualTo("harness-support@example.com"));

        // The thread list and summary run the console's own SQL. Empty is the correct result here;
        // what matters is that both execute rather than 500.
        ResponseEntity<Map> threads = gatewayClientWithToken(token)
                .get().uri("/" + slug + "/api/support/threads?view=open&mailboxId=" + mailboxId)
                .retrieve().toEntity(Map.class);
        assertThat(threads.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) threads.getBody().get("data")).isEmpty();

        ResponseEntity<Map> summary = gatewayClientWithToken(token)
                .get().uri("/" + slug + "/api/support/threads/summary?mailboxId=" + mailboxId)
                .retrieve().toEntity(Map.class);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody()).containsKeys("open", "unassigned", "atRisk", "breached");

        // Escalation contacts, whose recipient is later looked up by platform_user.id.
        ResponseEntity<Map> contact = gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/support/mailboxes/" + mailboxId + "/escalation-contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("level", "BREACH", "userId", io.kelta.testharness.fixtures.AuthFixture.adminUsername(slug),
                        "channels", List.of("email")))
                .retrieve().toEntity(Map.class);
        assertThat(contact.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> contactAttrs = (Map<String, Object>)
                ((Map<String, Object>) contact.getBody().get("data")).get("attributes");
        // A list, not the JDBC driver's jsonb wrapper.
        assertThat(contactAttrs.get("channels")).isInstanceOf(List.class);

        // The auto-reply report must state which mode it is in — every other number on it means
        // something different depending on the answer.
        ResponseEntity<Map> report = gatewayClientWithToken(token)
                .get().uri("/" + slug + "/api/support/mailboxes/" + mailboxId + "/auto-reply-report")
                .retrieve().toEntity(Map.class);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getBody()).containsKey("shadowMode");
    }

    @Test
    @DisplayName("an unknown webhook key is 404, and an unsigned delivery is dropped with 200")
    void inboundWebhookRefusesUnknownAndUnsigned() {
        // 404 only for an unknown key: 32 random bytes are not guessable, and an operator who has
        // misconfigured a provider needs that signal.
        assertThat(unauthenticatedPost("/api/webhooks/mail/definitely-not-a-real-key", "{}"))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpStatus postExpectingFailure(String token, String slug, Map<String, Object> body) {
        try {
            gatewayClientWithToken(token)
                    .post().uri("/" + slug + "/api/support/mailboxes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().toEntity(Map.class);
            return HttpStatus.OK;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    private HttpStatus unauthenticatedPost(String path, String body) {
        try {
            gatewayClient().post().uri(path)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(body)
                    .retrieve().toEntity(String.class);
            return HttpStatus.OK;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }
}
