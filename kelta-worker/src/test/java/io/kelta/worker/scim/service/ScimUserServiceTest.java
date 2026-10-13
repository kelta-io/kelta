package io.kelta.worker.scim.service;

import io.kelta.worker.scim.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ScimUserService")
class ScimUserServiceTest {

    private JdbcTemplate jdbcTemplate;
    private io.kelta.worker.service.AuthSessionRevocationClient authSessions;
    private ScimUserService service;

    private static final String TENANT_ID = "tenant-1";
    private static final String BASE_URL = "https://example.com";

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        authSessions = mock(io.kelta.worker.service.AuthSessionRevocationClient.class);
        service = new ScimUserService(jdbcTemplate, null, authSessions);
    }

    @Test
    @DisplayName("getUser throws 404 when user not found")
    void getUserNotFound() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(TENANT_ID), eq("no-such-id")))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.getUser(TENANT_ID, "no-such-id", BASE_URL))
                .isInstanceOf(ScimException.class)
                .satisfies(ex -> assertThat(((ScimException) ex).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("createUser rejects missing email")
    void createUserMissingEmail() {
        ScimUser request = new ScimUser();
        // no userName or emails set

        assertThatThrownBy(() -> service.createUser(TENANT_ID, request, BASE_URL))
                .isInstanceOf(ScimException.class)
                .satisfies(ex -> assertThat(((ScimException) ex).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("createUser rejects duplicate email")
    void createUserDuplicateEmail() {
        ScimUser request = new ScimUser();
        request.setUserName("john@test.com");

        when(jdbcTemplate.queryForObject(contains("COUNT"), eq(Integer.class), eq(TENANT_ID), eq("john@test.com")))
                .thenReturn(1);

        assertThatThrownBy(() -> service.createUser(TENANT_ID, request, BASE_URL))
                .isInstanceOf(ScimException.class)
                .satisfies(ex -> {
                    ScimException scimEx = (ScimException) ex;
                    assertThat(scimEx.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(scimEx.getScimType()).isEqualTo("uniqueness");
                });
    }

    @Test
    @DisplayName("deleteUser sets status to INACTIVE")
    void deleteUserDeactivates() {
        when(jdbcTemplate.update(contains("INACTIVE"), eq(TENANT_ID), eq("user-1")))
                .thenReturn(1);

        service.deleteUser(TENANT_ID, "user-1");

        verify(jdbcTemplate).update(contains("INACTIVE"), eq(TENANT_ID), eq("user-1"));
        verify(authSessions).revokeAfterCommit(TENANT_ID, "user-1");
    }

    @Test
    @DisplayName("a PATCH that deactivates an active user signs them out of kelta-auth; other patches do not")
    @SuppressWarnings("unchecked")
    void patchDeactivationSignsOut() {
        ScimUser active = new ScimUser();
        active.setActive(true);
        ScimUser inactive = new ScimUser();
        inactive.setActive(false);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(TENANT_ID), eq("user-1")))
                .thenReturn(List.of(active), List.of(inactive));

        service.patchUser(TENANT_ID, "user-1", patch("active", false), BASE_URL);

        verify(authSessions).revokeAfterCommit(TENANT_ID, "user-1");

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(TENANT_ID), eq("user-2")))
                .thenReturn(List.of(active), List.of(active));
        service.patchUser(TENANT_ID, "user-2", patch("name.givenName", "Ann"), BASE_URL);
        verify(authSessions, never()).revokeAfterCommit(TENANT_ID, "user-2");
    }

    private static ScimPatchOp patch(String path, Object value) {
        ScimPatchOp.Operation op = new ScimPatchOp.Operation();
        op.setOp("replace");
        op.setPath(path);
        op.setValue(value);
        ScimPatchOp patch = new ScimPatchOp();
        patch.setOperations(List.of(op));
        return patch;
    }

    @Test
    @DisplayName("deleteUser throws 404 for unknown user")
    void deleteUserNotFound() {
        when(jdbcTemplate.update(anyString(), eq(TENANT_ID), eq("no-such-id")))
                .thenReturn(0);

        assertThatThrownBy(() -> service.deleteUser(TENANT_ID, "no-such-id"))
                .isInstanceOf(ScimException.class)
                .satisfies(ex -> assertThat(((ScimException) ex).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("patchUser rejects null operations")
    void patchUserNullOperations() {
        // Mock getUser to return a user so we pass the existence check
        ScimUser mockUser = new ScimUser();
        mockUser.setId("user-1");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(TENANT_ID), eq("user-1")))
                .thenReturn(List.of(mockUser));

        ScimPatchOp patchOp = new ScimPatchOp();
        // operations is null

        assertThatThrownBy(() -> service.patchUser(TENANT_ID, "user-1", patchOp, BASE_URL))
                .isInstanceOf(ScimException.class)
                .satisfies(ex -> assertThat(((ScimException) ex).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
