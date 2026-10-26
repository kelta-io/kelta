package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.CoreSystemCollections;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.controller.PersonalAccessTokenController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UserAnonymizationService")
class UserAnonymizationServiceTest {

    private static final String TENANT = "t-1";
    private static final String USER = "u-1";
    private static final String ACTOR = "admin-1";
    private static final CollectionDefinition USERS = CoreSystemCollections.users();
    /** One {@code column = value} assignment in the scrub's SET clause. */
    private static final Pattern ASSIGNMENT = Pattern.compile("(\\w+) = (\\?|NULL|false|'\\{}'::jsonb)");

    private final QueryEngine queryEngine = mock(QueryEngine.class);
    private final CollectionRegistry registry = mock(CollectionRegistry.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PersonalAccessTokenController tokens = mock(PersonalAccessTokenController.class);
    private final SetupAuditService audit = mock(SetupAuditService.class);
    private final UserAnonymizationService service =
            new UserAnonymizationService(queryEngine, registry, jdbc, tokens, audit);

    /** The platform_user row, by column, as the service's writes leave it. */
    private final Map<String, Object> row = new HashMap<>();
    /** A record in a tenant collection that the user created and owns. */
    private final Map<String, Object> order = new HashMap<>(Map.of(
            "id", "order-1", "createdBy", USER, "updatedBy", USER, "ownerId", USER, "total", 42));
    private final AtomicReference<Optional<List<String>>> namesDuringWrite = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(registry.get("users")).thenReturn(USERS);
        row.putAll(Map.of("id", USER, "tenant_id", TENANT, "email", "ann@example.test", "username", "ann",
                "first_name", "Ann", "last_name", "Lee", "phone_number", "+15550100",
                "email_verification_token", "verify-token", "status", "ACTIVE", "settings", "{\"nick\":\"annie\"}"));
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(String.class), eq(USER), eq(TENANT)))
                .thenAnswer(inv -> List.of(row.get("status")));
        when(queryEngine.getById(USERS, USER)).thenAnswer(inv -> Optional.of(userRecord()));
        when(queryEngine.update(eq(USERS), eq(USER), anyMap())).thenAnswer(inv -> {
            namesDuringWrite.set(UserAnonymizationService.priorLoginNames());
            Map<String, Object> patch = inv.getArgument(2);
            row.put("status", patch.get("status"));
            return Optional.of(userRecord());
        });
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.startsWith("UPDATE platform_user")) {
                applyScrub(sql, Arrays.copyOfRange(inv.getRawArguments(), 1, inv.getRawArguments().length));
                return 1;
            }
            return 0;
        }).when(jdbc).update(anyString(), any(Object[].class));
    }

    /** The row as the users collection serves it. */
    private Map<String, Object> userRecord() {
        Map<String, Object> record = new HashMap<>();
        record.put("id", row.get("id"));
        record.put("tenantId", row.get("tenant_id"));
        record.put("email", row.get("email"));
        record.put("username", row.get("username"));
        record.put("firstName", row.get("first_name"));
        record.put("lastName", row.get("last_name"));
        record.put("status", row.get("status"));
        return record;
    }

    /** Applies the scrub's SET clause to {@link #row}, binding {@code ?} in order; checks the WHERE. */
    private void applyScrub(String sql, Object[] args) {
        Object[] flat = args.length == 1 && args[0] instanceof Object[] nested ? nested : args;
        String set = sql.substring(sql.indexOf(" SET ") + 5, sql.indexOf(" WHERE "));
        int bound = 0;
        Matcher m = ASSIGNMENT.matcher(set);
        while (m.find()) {
            Object value = switch (m.group(2)) {
                case "?" -> flat[bound++];
                case "NULL" -> null;
                case "false" -> false;
                default -> "{}";
            };
            row.put(m.group(1), value);
        }
        assertThat(sql).endsWith("WHERE id = ? AND tenant_id = ?");
        assertThat(Arrays.copyOfRange(flat, bound, flat.length)).containsExactly(USER, TENANT);
    }

    private List<String> statements() {
        List<String> sql = new ArrayList<>();
        for (Invocation invocation : mockingDetails(jdbc).getInvocations()) {
            sql.add(invocation.getArgument(0));
        }
        return sql;
    }

    @Test
    @DisplayName("keeps the id and leaves the row with the placeholder email, status ANONYMIZED and every personal column cleared")
    void scrubsEveryPersonalField() {
        UserAnonymizationService.Result result = service.anonymize(TENANT, USER, ACTOR);

        assertThat(result).isEqualTo(new UserAnonymizationService.Result(USER, false));
        assertThat(row.get("id")).isEqualTo(USER);
        assertThat(row.get("email")).isEqualTo("deleted+u-1@invalid");
        assertThat(row.get("status")).isEqualTo("ANONYMIZED");
        assertThat(row.get("first_name")).isNull();
        assertThat(row.get("last_name")).isNull();
        assertThat(row.get("username")).isNull();
        assertThat(row.get("phone_number")).isNull();
        assertThat(row.get("email_verification_token")).isNull();
        assertThat(row.get("email_verification_expires_at")).isNull();
        assertThat(row.get("settings")).isEqualTo("{}");
        assertThat(row.get("mfa_enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("scrubs the row before the users write, so the write (and its event and audit row) never sees the old values")
    void scrubPrecedesTheUsersWrite() {
        AtomicReference<Map<String, Object>> rowAtWrite = new AtomicReference<>();
        when(queryEngine.update(eq(USERS), eq(USER), anyMap())).thenAnswer(inv -> {
            rowAtWrite.set(new HashMap<>(row));
            namesDuringWrite.set(UserAnonymizationService.priorLoginNames());
            return Optional.of(userRecord());
        });

        service.anonymize(TENANT, USER, ACTOR);

        assertThat(rowAtWrite.get()).containsEntry("email", "deleted+u-1@invalid")
                .containsEntry("first_name", null).containsEntry("username", null);
    }

    @Test
    @DisplayName("writes status ANONYMIZED through the users collection with the old login names bound for the revocation hook")
    void statusGoesThroughTheUsersCollection() {
        service.anonymize(TENANT, USER, ACTOR);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).update(eq(USERS), eq(USER), patch.capture());
        assertThat(patch.getValue()).containsEntry("status", "ANONYMIZED").containsEntry("updatedBy", ACTOR)
                .doesNotContainKey("id");
        assertThat(namesDuringWrite.get()).contains(List.of("ann@example.test", "ann"));
        assertThat(UserAnonymizationService.priorLoginNames()).isEmpty();
    }

    @Test
    @DisplayName("deletes the password, TOTP and recovery-code rows and revokes every PAT")
    void deletesCredentialsAndRevokesTokens() {
        service.anonymize(TENANT, USER, ACTOR);

        verify(jdbc).update("DELETE FROM user_credential WHERE user_id = ?", USER);
        verify(jdbc).update("DELETE FROM user_totp_secret WHERE user_id = ?", USER);
        verify(jdbc).update("DELETE FROM user_recovery_code WHERE user_id = ?", USER);
        verify(jdbc).update("DELETE FROM user_ui_preference WHERE user_id = ? AND tenant_id = ?", USER, TENANT);
        verify(jdbc).update("DELETE FROM portal_login_token WHERE user_id = ? AND tenant_id = ?", USER, TENANT);
        verify(jdbc).update("DELETE FROM push_device WHERE user_id = ? AND tenant_id = ?", USER, TENANT);
        verify(tokens).revokeAllUserTokens(TENANT, USER, ACTOR,
                SecurityAuditLogger.EventType.PAT_ADMIN_REVOKED, "user anonymized");
    }

    @Test
    @DisplayName("a record the user created and owns is untouched and still references the same user id")
    void recordsCreatedByTheUserAreUnchanged() {
        Map<String, Object> before = new HashMap<>(order);

        service.anonymize(TENANT, USER, ACTOR);

        assertThat(order).isEqualTo(before);
        assertThat(order.get("createdBy")).isEqualTo(row.get("id"));
        assertThat(order.get("ownerId")).isEqualTo(row.get("id"));
        verify(queryEngine, never()).update(argThat(definition -> !"users".equals(definition.name())),
                anyString(), anyMap());
        verify(queryEngine, never()).delete(any(), anyString());
        assertThat(statements()).allSatisfy(sql -> assertThat(sql).matches(
                "(SELECT status FROM platform_user |UPDATE platform_user |DELETE FROM (user_credential"
                        + "|user_totp_secret|user_recovery_code|user_ui_preference|portal_login_token"
                        + "|push_device) ).*"));
    }

    @Test
    @DisplayName("writes one ANONYMIZED setup-audit entry naming actor, tenant and target, with no personal data")
    void auditsWithoutPii() {
        service.anonymize(TENANT, USER, ACTOR);

        ArgumentCaptor<String> newValue = ArgumentCaptor.forClass(String.class);
        verify(audit).log(eq(TENANT), eq(ACTOR), eq("ANONYMIZED"), eq("Users"), eq("user"), eq(USER),
                isNull(), isNull(), newValue.capture());
        assertThat(newValue.getValue()).isEqualTo("{\"status\":\"ANONYMIZED\"}")
                .doesNotContain("ann@example.test").doesNotContain("Ann").doesNotContain("Lee");
    }

    @Test
    @DisplayName("an anonymized user is left alone: no write, no deletes, no second audit entry")
    void idempotent() {
        service.anonymize(TENANT, USER, ACTOR);
        UserAnonymizationService.Result again = service.anonymize(TENANT, USER, ACTOR);

        assertThat(again).isEqualTo(new UserAnonymizationService.Result(USER, true));
        verify(queryEngine).update(eq(USERS), eq(USER), anyMap());
        verify(jdbc).update("DELETE FROM user_credential WHERE user_id = ?", USER);
        verify(audit).log(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString());
    }

    @Test
    @DisplayName("the system user is refused before anything is read or written")
    void refusesTheSystemUser() {
        assertThatThrownBy(() -> service.anonymize("system", "system", ACTOR))
                .isInstanceOf(UserAnonymizationService.AnonymizationRefusedException.class);

        verifyNoInteractions(queryEngine, jdbc, tokens, audit);
    }

    @Test
    @DisplayName("an unknown id, or a user of another tenant, is not found and nothing is written")
    void notFound() {
        assertThatThrownBy(() -> service.anonymize(TENANT, "missing", ACTOR))
                .isInstanceOf(UserAnonymizationService.UserNotFoundException.class);
        assertThatThrownBy(() -> service.anonymize("t-other", USER, ACTOR))
                .isInstanceOf(UserAnonymizationService.UserNotFoundException.class);

        verify(queryEngine, never()).update(any(), anyString(), anyMap());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(tokens, audit);
    }

    @Test
    @DisplayName("a user the QueryEngine read does not place in the tenant is not found either")
    void notFoundWhenTheRecordNamesAnotherTenant() {
        row.put("tenant_id", "t-other");
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(String.class), eq(USER), eq(TENANT)))
                .thenReturn(List.of("ACTIVE"));

        assertThatThrownBy(() -> service.anonymize(TENANT, USER, ACTOR))
                .isInstanceOf(UserAnonymizationService.UserNotFoundException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
