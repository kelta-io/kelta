package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.interceptor.PermissionGatedControllers;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.MailboxAccessRepository;
import io.kelta.worker.repository.MailboxAutoReplyDecisionRepository;
import io.kelta.worker.repository.MailboxEscalationRepository;
import io.kelta.worker.repository.MailboxRepository;
import io.kelta.worker.service.mailbox.MailboxAccessGuard;
import io.kelta.worker.service.mailbox.SupportAutoReplySweep;
import tools.jackson.databind.json.JsonMapper;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.mailbox.MailboxSecretService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@DisplayName("MailboxAdminController")
class MailboxAdminControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String MAILBOX_ID = "mb-1";

    private MailboxRepository mailboxRepository;
    private MailboxAccessRepository accessRepository;
    private MailboxSecretService secretService;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private MailboxEscalationRepository escalationRepository;
    private MailboxAutoReplyDecisionRepository decisionRepository;
    private SupportAutoReplySweep autoReplySweep;
    private MailboxAccessGuard accessGuard;
    private HttpServletRequest request;
    private MailboxAdminController controller;

    @BeforeEach
    void setUp() {
        mailboxRepository = mock(MailboxRepository.class);
        accessRepository = mock(MailboxAccessRepository.class);
        secretService = mock(MailboxSecretService.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        escalationRepository = mock(MailboxEscalationRepository.class);
        decisionRepository = mock(MailboxAutoReplyDecisionRepository.class);
        autoReplySweep = mock(SupportAutoReplySweep.class);
        request = mock(HttpServletRequest.class);

        accessGuard = mock(MailboxAccessGuard.class);
        // The gateway stamps X-User-Id with an email; the guard maps it to a user id. Tests pass
        // ids through unchanged so they exercise the controller rather than the lookup.
        when(accessGuard.resolveUserId(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        controller = new MailboxAdminController(mailboxRepository, accessRepository,
                secretService, accessGuard, permissionResolver, bootstrapRepository,
                escalationRepository, decisionRepository, autoReplySweep,
                JsonMapper.builder().build());

        grantPermission();
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(secretService.generateWebhookKey()).thenReturn("wk-random");
        when(secretService.overlapMinutes()).thenReturn(1440);
    }

    private void grantPermission() {
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_SUPPORT_MAILBOX", "granted", true)));
    }

    private <T> T withTenant(ScopedValue.CallableOp<T, RuntimeException> body) {
        return TenantContext.callWithTenant(TENANT, body);
    }

    @Test
    @DisplayName("gates every handler on MANAGE_SUPPORT_MAILBOX (PermissionGatedController promise)")
    void everyHandlerIsPermissionGated() {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_SUPPORT_MAILBOX", "granted", false)));

        withTenant(() -> {
            PermissionGatedControllers.assertEveryHandlerForbidden(controller, request);
            return null;
        });
        verifyNoInteractions(mailboxRepository, accessRepository, escalationRepository);
    }

    private Map<String, Object> mailboxRow() {
        return new java.util.HashMap<>(Map.of(
                "id", MAILBOX_ID,
                "tenant_id", TENANT,
                "name", "Support",
                "address", "support@example.com",
                "webhook_key", "wk-random",
                "inbound_secret_hint", "…abcd",
                "inbound_provider", "SES_SNS",
                "active", true));
    }

    // ------------------------------------------------------------------ Authorization

    @Nested
    @DisplayName("permission gate")
    class PermissionGate {

        @Test
        @DisplayName("Rejects a caller without MANAGE_SUPPORT_MAILBOX")
        void rejectsWithoutPermission() {
            when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                    Map.of("permission_name", "MANAGE_SUPPORT_MAILBOX", "granted", false)));

            assertThatThrownBy(() -> withTenant(() -> controller.list(request, 50, 0)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("MANAGE_SUPPORT_MAILBOX");
            verifyNoInteractions(mailboxRepository);
        }

        @Test
        @DisplayName("Rejects a caller with no identity at all")
        void rejectsWithoutIdentity() {
            when(permissionResolver.getProfileId(request)).thenReturn(null);

            assertThatThrownBy(() -> withTenant(() -> controller.list(request, 50, 0)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No identity");
        }

        @Test
        @DisplayName("Holding a different permission is not enough")
        void otherPermissionDoesNotGrant() {
            when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                    Map.of("permission_name", "MANAGE_CHAT", "granted", true)));

            assertThatThrownBy(() -> withTenant(() -> controller.list(request, 50, 0)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("MANAGE_SUPPORT_MAILBOX");
        }
    }

    // ------------------------------------------------------------------ Secret handling

    @Nested
    @DisplayName("inbound secret")
    class Secrets {

        @Test
        @DisplayName("Create returns the plaintext secret exactly once, with the credential id withheld")
        void createReturnsSecretOnce() {
            when(mailboxRepository.addressExists(eq(TENANT), anyString(), isNull())).thenReturn(false);
            when(mailboxRepository.create(eq(TENANT), eq("wk-random"), any(), anyString()))
                    .thenReturn(MAILBOX_ID);
            when(secretService.mint(eq(TENANT), eq(MAILBOX_ID), eq("Support"), anyString()))
                    .thenReturn(new MailboxSecretService.MintedSecret("cred-1", "PLAINTEXT-SECRET", "…CRET"));
            when(mailboxRepository.findById(MAILBOX_ID, TENANT)).thenReturn(Optional.of(mailboxRow()));

            ResponseEntity<Map<String, Object>> resp = withTenant(() -> controller.create(request,
                    Map.of("name", "Support", "address", "support@example.com")));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<String, Object> attrs = attributesOf(resp);
            assertThat(attrs.get("inboundSecret")).isEqualTo("PLAINTEXT-SECRET");
            // The vault pointer must never be published — it tells a reader which row to attack.
            assertThat(attrs).doesNotContainKeys(
                    "inboundSecretCredentialId", "inbound_secret_credential_id",
                    "inboundPrevSecretCredentialId", "inbound_prev_secret_credential_id");
        }

        @Test
        @DisplayName("Reading a mailbox back never exposes the secret")
        void getDoesNotExposeSecret() {
            when(mailboxRepository.findById(MAILBOX_ID, TENANT)).thenReturn(Optional.of(mailboxRow()));

            ResponseEntity<Map<String, Object>> resp =
                    withTenant(() -> controller.get(request, MAILBOX_ID));

            Map<String, Object> attrs = attributesOf(resp);
            assertThat(attrs).doesNotContainKey("inboundSecret");
            // The hint is safe and is what an admin actually reads.
            assertThat(attrs.get("inboundSecretHint")).isEqualTo("…abcd");
        }

        @Test
        @DisplayName("Rotation mints a new secret and retires the one being displaced")
        void rotationRetiresTheDisplacedSecret() {
            Map<String, Object> row = mailboxRow();
            row.put("inbound_prev_secret_credential_id", "cred-old");
            when(mailboxRepository.findById(MAILBOX_ID, TENANT)).thenReturn(Optional.of(row));
            when(secretService.mint(eq(TENANT), eq(MAILBOX_ID), anyString(), anyString()))
                    .thenReturn(new MailboxSecretService.MintedSecret("cred-2", "NEW-SECRET", "…CRET"));

            ResponseEntity<Map<String, Object>> resp =
                    withTenant(() -> controller.rotateSecret(request, MAILBOX_ID));

            assertThat(attributesOf(resp).get("inboundSecret")).isEqualTo("NEW-SECRET");
            // Two generations back is displaced by this rotation and must not stay active.
            verify(secretService).deactivate(TENANT, "cred-old");
            // The new secret takes the live slot with a non-zero overlap so in-flight
            // deliveries signed with the old one still verify.
            verify(mailboxRepository).rotateSecret(eq(MAILBOX_ID), eq(TENANT), eq("cred-2"),
                    anyString(), eq(1440), anyString());
        }

        @Test
        @DisplayName("Create pins the first secret with no overlap — there is nothing to overlap with")
        void createUsesZeroOverlap() {
            when(mailboxRepository.addressExists(eq(TENANT), anyString(), isNull())).thenReturn(false);
            when(mailboxRepository.create(eq(TENANT), anyString(), any(), anyString())).thenReturn(MAILBOX_ID);
            when(secretService.mint(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new MailboxSecretService.MintedSecret("cred-1", "S", "…S"));
            when(mailboxRepository.findById(MAILBOX_ID, TENANT)).thenReturn(Optional.of(mailboxRow()));

            withTenant(() -> controller.create(request,
                    Map.of("name", "Support", "address", "support@example.com")));

            verify(mailboxRepository).rotateSecret(eq(MAILBOX_ID), eq(TENANT), eq("cred-1"),
                    anyString(), eq(0), anyString());
        }
    }

    // ------------------------------------------------------------------ Input handling

    @Nested
    @DisplayName("request body handling")
    class Input {

        @Test
        @DisplayName("A caller cannot set the webhook key or point at someone else's credential")
        void ignoresServerMintedFields() {
            when(mailboxRepository.addressExists(eq(TENANT), anyString(), isNull())).thenReturn(false);
            when(mailboxRepository.create(eq(TENANT), eq("wk-random"), any(), anyString()))
                    .thenReturn(MAILBOX_ID);
            when(secretService.mint(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new MailboxSecretService.MintedSecret("cred-1", "S", "…S"));
            when(mailboxRepository.findById(MAILBOX_ID, TENANT)).thenReturn(Optional.of(mailboxRow()));

            withTenant(() -> controller.create(request, Map.of(
                    "name", "Support",
                    "address", "support@example.com",
                    "webhookKey", "attacker-chosen",
                    "inboundSecretCredentialId", "cred-belonging-to-someone-else")));

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                    org.mockito.ArgumentCaptor.forClass(Map.class);
            // The key passed to the repository is the generated one, not the body's.
            verify(mailboxRepository).create(eq(TENANT), eq("wk-random"), captor.capture(), anyString());
            assertThat(captor.getValue()).doesNotContainKeys(
                    "webhook_key", "inbound_secret_credential_id");
        }

        @Test
        @DisplayName("Accepts a JSON:API envelope as well as a plain object")
        void acceptsJsonApiEnvelope() {
            when(mailboxRepository.addressExists(eq(TENANT), anyString(), isNull())).thenReturn(false);
            when(mailboxRepository.create(eq(TENANT), anyString(), any(), anyString())).thenReturn(MAILBOX_ID);
            when(secretService.mint(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new MailboxSecretService.MintedSecret("cred-1", "S", "…S"));
            when(mailboxRepository.findById(MAILBOX_ID, TENANT)).thenReturn(Optional.of(mailboxRow()));

            ResponseEntity<Map<String, Object>> resp = withTenant(() -> controller.create(request,
                    Map.of("data", Map.of("attributes",
                            Map.of("name", "Support", "address", "support@example.com")))));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("Rejects a malformed address, an unknown provider and out-of-range policy values")
        void rejectsInvalidInput() {
            assertThatThrownBy(() -> withTenant(() -> controller.create(request,
                    Map.of("name", "S", "address", "not-an-address"))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("valid email address");

            assertThatThrownBy(() -> withTenant(() -> controller.create(request,
                    Map.of("name", "S", "address", "a@b.com", "inboundProvider", "CARRIER_PIGEON"))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("inboundProvider");

            assertThatThrownBy(() -> withTenant(() -> controller.create(request,
                    Map.of("name", "S", "address", "a@b.com", "slaRiskThresholdPct", 0))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("slaRiskThresholdPct");

            assertThatThrownBy(() -> withTenant(() -> controller.create(request,
                    Map.of("name", "S", "address", "a@b.com", "autoReplyMinConfidence", 1.5))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("autoReplyMinConfidence");
        }

        @Test
        @DisplayName("Rejects a duplicate address in the same tenant")
        void rejectsDuplicateAddress() {
            when(mailboxRepository.addressExists(eq(TENANT), eq("support@example.com"), isNull()))
                    .thenReturn(true);

            assertThatThrownBy(() -> withTenant(() -> controller.create(request,
                    Map.of("name", "S", "address", "support@example.com"))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("already exists");
        }
    }

    // ------------------------------------------------------------------ Membership

    @Nested
    @DisplayName("access grants")
    class Access {

        @BeforeEach
        void mailboxExists() {
            when(mailboxRepository.findById(MAILBOX_ID, TENANT)).thenReturn(Optional.of(mailboxRow()));
        }

        @Test
        @DisplayName("Grants a role to a user")
        void grantsRole() {
            when(accessRepository.grant(TENANT, MAILBOX_ID, "USER", "user-9", "MANAGER", "user-1"))
                    .thenReturn("acc-1");
            when(accessRepository.findById("acc-1", TENANT)).thenReturn(Optional.of(Map.of(
                    "id", "acc-1", "mailbox_id", MAILBOX_ID, "principal_type", "USER",
                    "principal_id", "user-9", "role", "MANAGER")));

            ResponseEntity<Map<String, Object>> resp = withTenant(() -> controller.grantAccess(
                    request, MAILBOX_ID,
                    Map.of("principalType", "user", "principalId", "user-9", "role", "manager")));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            // Case-insensitive in, canonical out.
            verify(accessRepository).grant(TENANT, MAILBOX_ID, "USER", "user-9", "MANAGER", "user-1");
        }

        @Test
        @DisplayName("Rejects an unknown role or principal type")
        void rejectsBadGrant() {
            assertThatThrownBy(() -> withTenant(() -> controller.grantAccess(request, MAILBOX_ID,
                    Map.of("principalType", "USER", "principalId", "u", "role", "SUPERUSER"))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("role must be one of");

            assertThatThrownBy(() -> withTenant(() -> controller.grantAccess(request, MAILBOX_ID,
                    Map.of("principalType", "ROBOT", "principalId", "u", "role", "AGENT"))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("principalType must be one of");
        }

        @Test
        @DisplayName("A grant belonging to another mailbox cannot be revoked through this one")
        void cannotRevokeForeignGrant() {
            when(accessRepository.findById("acc-other", TENANT)).thenReturn(Optional.of(Map.of(
                    "id", "acc-other", "mailbox_id", "mb-999",
                    "principal_type", "USER", "principal_id", "u", "role", "AGENT")));

            assertThatThrownBy(() -> withTenant(() ->
                    controller.revokeAccess(request, MAILBOX_ID, "acc-other")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not found");

            verify(accessRepository, never()).revoke(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("A mailbox in another tenant is not found, rather than forbidden")
    void foreignMailboxIsNotFound() {
        when(mailboxRepository.findById("mb-other", TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withTenant(() -> controller.get(request, "mb-other")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Mailbox not found");
    }

    @Test
    @DisplayName("Page size is clamped so a caller cannot ask for the whole table")
    void clampsPageSize() {
        when(mailboxRepository.list(eq(TENANT), anyInt(), anyInt())).thenReturn(List.of());

        withTenant(() -> controller.list(request, 100_000, -5));

        verify(mailboxRepository).list(TENANT, 200, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributesOf(ResponseEntity<Map<String, Object>> resp) {
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (Map<String, Object>) data.get("attributes");
    }
}
