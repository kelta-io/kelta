package io.kelta.worker.controller;

import io.kelta.worker.service.CerbosPolicySyncService;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Custom rules are compiled into the tenant's Cerbos policies, so writing one is a grant of
 * authority: the endpoints take {@code MANAGE_USERS}, the gate on the profile detail page that
 * edits them, work only in the tenant the request is bound to, and refuse a rule that would not
 * compile the way the admin asked for it.
 */
@ExtendWith(MockitoExtension.class)
@WithTenant("tenant-1")
@DisplayName("CustomRuleController")
class CustomRuleControllerTest {

    private static final String PERMISSION = "MANAGE_USERS";

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private CerbosPolicySyncService syncService;

    private CustomRuleController controller;

    @BeforeEach
    void setUp() {
        controller = new CustomRuleController(jdbcTemplate, syncService, new ObjectMapper(),
                AdminPermissionGuardFixture.granting(PERMISSION));
    }

    private static Map<String, Object> allowAllRule() {
        return new HashMap<>(Map.of("collectionId", "col-1", "action", "read", "effect", "ALLOW",
                "conditionType", "CEL", "condition", Map.of("expression", "true")));
    }

    private static Map<String, Object> visualRule(String operator, Object value) {
        Map<String, Object> condition = new HashMap<>();
        condition.put("field", "region");
        condition.put("operator", operator);
        condition.put("value", value);
        return new HashMap<>(Map.of("collectionId", "col-1", "action", "edit", "effect", "deny",
                "conditionType", "visual", "condition", condition));
    }

    private static Map<String, Object> celRule(String expression) {
        return new HashMap<>(Map.of("collectionId", "col-1", "action", "edit", "effect", "deny",
                "conditionType", "cel", "condition", Map.of("expression", expression)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        return (List<Map<String, Object>>) response.getBody().get("errors");
    }

    @SuppressWarnings("unchecked")
    private static String pointer(Map<String, Object> error) {
        return ((Map<String, Object>) error.get("source")).get("pointer").toString();
    }

    /** Asserts a single 400 VALIDATION_FAILED at {@code pointer}, and that nothing was written or synced. */
    private void assertRefused(ResponseEntity<Map<String, Object>> response, String pointer) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errors = errors(response);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("status", "400").containsEntry("code", "VALIDATION_FAILED");
        assertThat(errors.get(0).get("detail").toString()).isNotBlank();
        assertThat(pointer(errors.get(0))).isEqualTo(pointer);
        verifyNoInteractions(jdbcTemplate, syncService);
    }

    @Test
    @DisplayName("A profile without MANAGE_USERS (API_ACCESS only, like Portal User) gets 403 on every handler")
    void deniedWithoutManageUsers() {
        CustomRuleController denied = new CustomRuleController(jdbcTemplate, syncService, new ObjectMapper(),
                AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> denied.listRules(adminRequest, "p-1"), PERMISSION);
        AdminPermissionGuardFixture.assertForbidden(() -> denied.createRule("p-1", allowAllRule(), adminRequest), PERMISSION);
        AdminPermissionGuardFixture.assertForbidden(() -> denied.updateRule(adminRequest, "p-1", "r-1", allowAllRule()),
                PERMISSION);
        AdminPermissionGuardFixture.assertForbidden(() -> denied.deleteRule(adminRequest, "p-1", "r-1"), PERMISSION);
        verifyNoInteractions(jdbcTemplate, syncService);
    }

    @Test
    @DisplayName("A profile holding MANAGE_USERS can create a rule, stored lower-case and synced to Cerbos")
    void createAllowedWithManageUsers() {
        var response = controller.createRule("p-1", allowAllRule(), adminRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(jdbcTemplate).update(contains("INSERT INTO profile_custom_rules"),
                anyString(), eq("tenant-1"), eq("p-1"), eq("col-1"), eq("read"), eq("allow"), eq("cel"),
                eq("{\"expression\":\"true\"}"), eq(true));
        verify(syncService).syncTenant("tenant-1");
        assertThat(response.getBody()).containsEntry("effect", "allow").containsEntry("tenantId", "tenant-1");
    }

    @Test
    @DisplayName("A profile holding MANAGE_USERS can list rules, served camelCase with the condition as an object")
    void listAllowedWithManageUsers() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "r-1");
        row.put("tenant_id", "tenant-1");
        row.put("profile_id", "p-1");
        row.put("collection_id", "col-1");
        row.put("action", "edit");
        row.put("effect", "deny");
        row.put("condition_type", "visual");
        // The jsonb driver object's toString() is the JSON text
        row.put("condition_json", "{\"field\":\"region\",\"operator\":\"in\",\"value\":\"US,EU\"}");
        row.put("enabled", true);
        when(jdbcTemplate.queryForList(contains("FROM profile_custom_rules"), eq("p-1"), eq("tenant-1")))
                .thenReturn(List.of(row));

        var response = controller.listRules(adminRequest, "p-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> rule = response.getBody().get(0);
        assertThat(rule).containsEntry("id", "r-1").containsEntry("profileId", "p-1")
                .containsEntry("collectionId", "col-1").containsEntry("effect", "deny")
                .containsEntry("conditionType", "visual").containsEntry("enabled", true)
                .containsEntry("condition", Map.of("field", "region", "operator", "in", "value", "US,EU"));
    }

    @Nested
    @DisplayName("tenant")
    class TenantTests {

        @Test
        @DisplayName("create ignores a body tenantId (and the X-Cerbos-Scope header) and uses the bound tenant")
        void createIgnoresBodyTenant() {
            Map<String, Object> body = allowAllRule();
            body.put("tenantId", "tenant-2");
            adminRequest.addHeader("X-Cerbos-Scope", "tenant-2");

            var response = controller.createRule("p-1", body, adminRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ArgumentCaptor<Object> tenant = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).update(contains("INSERT INTO profile_custom_rules"),
                    anyString(), tenant.capture(), eq("p-1"), eq("col-1"), eq("read"), eq("allow"), eq("cel"),
                    anyString(), eq(true));
            assertThat(tenant.getValue()).isEqualTo("tenant-1");
            verify(syncService).syncTenant("tenant-1");
            verify(syncService, never()).syncTenant("tenant-2");
            assertThat(response.getBody()).containsEntry("tenantId", "tenant-1");
        }

        @Test
        @WithTenant(none = true)
        @DisplayName("every handler refuses a request with no bound tenant")
        void refusesWithoutTenant() {
            for (Runnable call : List.<Runnable>of(
                    () -> controller.listRules(adminRequest, "p-1"),
                    () -> controller.createRule("p-1", allowAllRule(), adminRequest),
                    () -> controller.updateRule(adminRequest, "p-1", "r-1", allowAllRule()),
                    () -> controller.deleteRule(adminRequest, "p-1", "r-1"))) {
                ResponseStatusException e = catchThrowableOfType(ResponseStatusException.class, call::run);
                assertThat(e).isNotNull();
                assertThat(e.getStatusCode().value()).isEqualTo(400);
            }
            verifyNoInteractions(jdbcTemplate, syncService);
        }

        @Test
        @DisplayName("update and delete are scoped to the bound tenant and sync it")
        void updateAndDeleteScopedToTenant() {
            when(jdbcTemplate.update(contains("UPDATE profile_custom_rules"),
                    eq("edit"), eq("deny"), eq("cel"), anyString(), eq(true), eq("r-1"), eq("p-1"), eq("tenant-1")))
                    .thenReturn(1);
            when(jdbcTemplate.update(contains("DELETE FROM profile_custom_rules"),
                    eq("r-1"), eq("p-1"), eq("tenant-1"))).thenReturn(1);

            assertThat(controller.updateRule(adminRequest, "p-1", "r-1", celRule("R.attr.status == \"locked\""))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(controller.deleteRule(adminRequest, "p-1", "r-1").getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            verify(syncService, org.mockito.Mockito.times(2)).syncTenant("tenant-1");
        }

        @Test
        @DisplayName("update of a rule that is not in the tenant answers 404 and syncs nothing")
        void updateMissingRule() {
            var response = controller.updateRule(adminRequest, "p-1", "r-9", celRule("true"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errors(response).get(0)).containsEntry("code", "NOT_FOUND");
            verifyNoInteractions(syncService);
        }
    }

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("stores deny lower-case whatever the case it was sent in")
        void storesDenyLowerCase() {
            Map<String, Object> body = celRule("R.attr.status == \"locked\"");
            body.put("effect", "DeNy");

            assertThat(controller.createRule("p-1", body, adminRequest).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(jdbcTemplate).update(contains("INSERT INTO profile_custom_rules"),
                    anyString(), eq("tenant-1"), eq("p-1"), eq("col-1"), eq("edit"), eq("deny"), eq("cel"),
                    anyString(), eq(true));
        }

        @Test
        @DisplayName("refuses an effect other than allow or deny")
        void refusesUnknownEffect() {
            for (Object effect : new Object[] {"EFFECT_DENY", "block", "", null, 1}) {
                Map<String, Object> body = celRule("true");
                body.put("effect", effect);
                assertRefused(controller.createRule("p-1", body, adminRequest), "/effect");
                assertRefused(controller.updateRule(adminRequest, "p-1", "r-1", body), "/effect");
            }
        }

        @Test
        @DisplayName("refuses an unknown action, condition type or enabled flag")
        void refusesOtherFields() {
            Map<String, Object> badAction = celRule("true");
            badAction.put("action", "approve");
            assertRefused(controller.createRule("p-1", badAction, adminRequest), "/action");

            Map<String, Object> badType = celRule("true");
            badType.put("conditionType", "sql");
            assertRefused(controller.createRule("p-1", badType, adminRequest), "/conditionType");

            Map<String, Object> badEnabled = celRule("true");
            badEnabled.put("enabled", "yes");
            assertRefused(controller.createRule("p-1", badEnabled, adminRequest), "/enabled");

            Map<String, Object> noCollection = celRule("true");
            noCollection.remove("collectionId");
            assertRefused(controller.createRule("p-1", noCollection, adminRequest), "/collectionId");
        }

        @Test
        @DisplayName("refuses visual conditions that would not compile as asked")
        void refusesBadVisualConditions() {
            assertRefused(controller.createRule("p-1", visualRule("matches", "x"), adminRequest), "/condition/operator");
            assertRefused(controller.createRule("p-1", visualRule("greater_than", "lots"), adminRequest),
                    "/condition/value");
            assertRefused(controller.createRule("p-1", visualRule("in", " , "), adminRequest), "/condition/value");

            Map<String, Object> badField = visualRule("equals", "x");
            @SuppressWarnings("unchecked")
            Map<String, Object> condition = (Map<String, Object>) badField.get("condition");
            condition.put("field", "region == \"x\" || true");
            assertRefused(controller.createRule("p-1", badField, adminRequest), "/condition/field");
        }

        @Test
        @DisplayName("refuses CEL that does not parse, at save rather than at policy push")
        void refusesUnparseableCel() {
            assertRefused(controller.createRule("p-1", celRule("R.attr.region in US,EU"), adminRequest),
                    "/condition/expression");
            assertRefused(controller.createRule("p-1", celRule("R.attr.a == \"x\") || (true"), adminRequest),
                    "/condition/expression");
            assertRefused(controller.createRule("p-1", celRule("true // comment"), adminRequest),
                    "/condition/expression");
            assertRefused(controller.updateRule(adminRequest, "p-1", "r-1", celRule("")), "/condition/expression");
        }

        @Test
        @DisplayName("accepts an in condition sent as comma-separated text, as the admin UI sends it")
        void acceptsInAsText() {
            var response = controller.createRule("p-1", visualRule("in", "US,EU"), adminRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(syncService).syncTenant("tenant-1");
        }

        @Test
        @DisplayName("reports every problem of a body at once")
        void reportsAllProblems() {
            Map<String, Object> body = celRule("R.attr.region in US,EU");
            body.put("action", "approve");
            body.put("effect", "maybe");

            var response = controller.createRule("p-1", body, adminRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errors(response)).extracting(CustomRuleControllerTest::pointer)
                    .containsExactly("/action", "/effect", "/condition/expression");
            verifyNoInteractions(jdbcTemplate, syncService);
        }
    }
}
