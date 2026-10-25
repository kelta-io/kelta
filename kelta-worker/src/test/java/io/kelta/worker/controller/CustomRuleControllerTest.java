package io.kelta.worker.controller;

import io.kelta.worker.service.CerbosPolicySyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Custom rules are compiled into the tenant's Cerbos policies, so writing one is a grant of
 * authority: the endpoints take {@code MANAGE_USERS}, the gate on the profile detail page that
 * edits them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomRuleController")
class CustomRuleControllerTest {

    private static final String PERMISSION = "MANAGE_USERS";

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private CerbosPolicySyncService syncService;

    private CustomRuleController controller;

    @BeforeEach
    void setUp() {
        adminRequest.addHeader("X-Cerbos-Scope", "tenant-1");
        controller = new CustomRuleController(jdbcTemplate, syncService, new ObjectMapper(),
                AdminPermissionGuardFixture.granting(PERMISSION));
    }

    private static Map<String, Object> allowAllRule() {
        return new HashMap<>(Map.of("collectionId", "col-1", "action", "read", "effect", "ALLOW",
                "conditionType", "CEL", "condition", Map.of("expr", "true")));
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
    @DisplayName("A profile holding MANAGE_USERS can create a rule, which is synced to Cerbos")
    void createAllowedWithManageUsers() {
        var response = controller.createRule("p-1", allowAllRule(), adminRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(jdbcTemplate).update(contains("INSERT INTO profile_custom_rules"),
                anyString(), eq("tenant-1"), eq("p-1"), eq("col-1"), eq("read"), eq("ALLOW"), eq("CEL"),
                anyString(), eq(true));
        verify(syncService).syncTenant("tenant-1");
    }

    @Test
    @DisplayName("A profile holding MANAGE_USERS can list rules")
    void listAllowedWithManageUsers() {
        when(jdbcTemplate.queryForList(contains("FROM profile_custom_rules"), eq("p-1")))
                .thenReturn(List.of(Map.of("id", "r-1")));

        var response = controller.listRules(adminRequest, "p-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }
}
