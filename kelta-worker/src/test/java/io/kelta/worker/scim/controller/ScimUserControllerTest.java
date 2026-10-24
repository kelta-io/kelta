package io.kelta.worker.scim.controller;

import io.kelta.worker.scim.model.*;
import io.kelta.worker.scim.service.ScimUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ScimUserController")
class ScimUserControllerTest {

    private ScimUserService userService;
    private ScimUserController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        userService = mock(ScimUserService.class);
        controller = new ScimUserController(userService, null);
        request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("example.com");
        request.setServerPort(443);
    }

    @Test
    @DisplayName("listUsers returns 200 with list response")
    void listUsersReturns200() {
        ScimListResponse<ScimUser> listResponse = new ScimListResponse<>(List.of(), 0, 1, 0);
        when(userService.listUsers(eq("t1"), isNull(), eq(1), eq(100), anyString()))
                .thenReturn(listResponse);

        var response = controller.listUsers("t1", null, 1, 100, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalResults()).isEqualTo(0);
    }

    @Test
    @DisplayName("getUser returns 200 with user")
    void getUserReturns200() {
        ScimUser user = new ScimUser();
        user.setId("user-1");
        user.setUserName("john@test.com");
        when(userService.getUser(eq("t1"), eq("user-1"), anyString())).thenReturn(user);

        var response = controller.getUser("t1", "user-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getUserName()).isEqualTo("john@test.com");
    }

    @Test
    @DisplayName("createUser returns 201")
    void createUserReturns201() {
        ScimUser input = new ScimUser();
        input.setUserName("new@test.com");

        ScimUser created = new ScimUser();
        created.setId("new-id");
        created.setUserName("new@test.com");
        when(userService.createUser(eq("t1"), any(), anyString())).thenReturn(created);

        var response = controller.createUser("t1", input, request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().getId()).isEqualTo("new-id");
    }

    @Test
    @DisplayName("deleteUser returns 204")
    void deleteUserReturns204() {
        doNothing().when(userService).deleteUser("t1", "user-1");

        var response = controller.deleteUser("t1", "user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(userService).deleteUser("t1", "user-1");
    }

    @Test
    @DisplayName("count is capped at MAX_PAGE_SIZE")
    void countIsCapped() {
        ScimListResponse<ScimUser> listResponse = new ScimListResponse<>(List.of(), 0, 1, 0);
        when(userService.listUsers(anyString(), any(), anyInt(), eq(1000), anyString()))
                .thenReturn(listResponse);

        controller.listUsers("t1", null, 1, 5000, request);

        verify(userService).listUsers(anyString(), any(), anyInt(), eq(1000), anyString());
    }

    @Test
    @DisplayName("meta.location is built on kelta.scim.public-url, not the request's own address")
    void metaLocationUsesConfiguredPublicUrl() {
        controller = new ScimUserController(userService, "https://api.kelta.example/");
        stubGetUserEchoingBaseUrl();

        var response = controller.getUser("t1", "user-1", request);

        assertThat(response.getBody().getMeta().getLocation())
                .isEqualTo("https://api.kelta.example/scim/v2/Users/user-1");
    }

    @Test
    @DisplayName("X-Forwarded-Host / -Proto cannot change meta.location")
    void forwardedHeadersAreIgnored() {
        controller = new ScimUserController(userService, "https://api.kelta.example");
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Forwarded-Proto", "http");
        stubGetUserEchoingBaseUrl();

        var response = controller.getUser("t1", "user-1", request);

        assertThat(response.getBody().getMeta().getLocation())
                .startsWith("https://api.kelta.example/")
                .doesNotContain("evil.example");
    }

    @Test
    @DisplayName("blank public-url falls back to the request's scheme, host and port, still ignoring forwarded headers")
    void blankPublicUrlFallsBackToRequest() {
        controller = new ScimUserController(userService, "  ");
        request.addHeader("X-Forwarded-Host", "evil.example");
        stubGetUserEchoingBaseUrl();

        var response = controller.getUser("t1", "user-1", request);

        assertThat(response.getBody().getMeta().getLocation())
                .isEqualTo("https://example.com:443/scim/v2/Users/user-1");
    }

    /** Mirrors the service: meta.location is the base URL the controller hands it plus the path. */
    private void stubGetUserEchoingBaseUrl() {
        when(userService.getUser(eq("t1"), eq("user-1"), anyString())).thenAnswer(inv -> {
            ScimUser user = new ScimUser();
            user.setId("user-1");
            user.setMeta(new ScimMeta("User", null, null,
                    inv.getArgument(2) + "/scim/v2/Users/user-1"));
            return user;
        });
    }
}
