package io.kelta.auth.controller;

import io.kelta.auth.session.UserSessionRevoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("InternalSessionRevocationController")
class InternalSessionRevocationControllerTest {

    private static final String TOKEN = "internal-token-for-tests";

    private final UserSessionRevoker revoker = mock(UserSessionRevoker.class);

    @Test
    @DisplayName("with no internal token configured every call is refused")
    void refusesWhenUnconfigured() {
        var controller = new InternalSessionRevocationController(revoker, "");

        assertThat(controller.revokeSessions("t1", "u1", "", null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.revokeSessions("t1", "u1", null, null).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(revoker);
    }

    @Test
    @DisplayName("a missing or wrong token is refused")
    void refusesWrongToken() {
        var controller = new InternalSessionRevocationController(revoker, TOKEN);

        assertThat(controller.revokeSessions("t1", "u1", null, null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.revokeSessions("t1", "u1", "nope", null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.revokeSessions("t1", "u1", TOKEN + "x", null).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(revoker);
    }

    @Test
    @DisplayName("malformed ids are refused before any lookup")
    void refusesMalformedIds() {
        var controller = new InternalSessionRevocationController(revoker, TOKEN);

        assertThat(controller.revokeSessions("t1' OR 1=1", "u1", TOKEN, null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.revokeSessions("t1", "u1/../x", TOKEN, null).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(revoker);
    }

    @Test
    @DisplayName("the right token signs the tenant's user out everywhere")
    void revokesWithTheRightToken() {
        var controller = new InternalSessionRevocationController(revoker, TOKEN);
        when(revoker.signOutEverywhere("t1", "u1", List.of("a@example.test")))
                .thenReturn(new UserSessionRevoker.Result(2, 3));

        var response = controller.revokeSessions("t1", "u1", TOKEN,
                Map.of("loginNames", List.of("a@example.test", 42)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("sessionsEnded", 2).containsEntry("authorizationsRevoked", 3);
        verify(revoker).signOutEverywhere("t1", "u1", List.of("a@example.test"));
    }

    @Test
    @DisplayName("no body: no login names")
    void noBody() {
        var controller = new InternalSessionRevocationController(revoker, TOKEN);
        when(revoker.signOutEverywhere(any(), any(), any())).thenReturn(new UserSessionRevoker.Result(0, 0));

        controller.revokeSessions("t1", "u1", TOKEN, null);

        verify(revoker).signOutEverywhere("t1", "u1", List.of());
    }
}
