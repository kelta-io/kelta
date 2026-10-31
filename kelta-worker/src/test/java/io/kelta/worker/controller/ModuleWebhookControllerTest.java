package io.kelta.worker.controller;

import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.module.ExternalModuleProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModuleWebhookController")
class ModuleWebhookControllerTest {

    private ExternalModuleProxy proxy;
    private ModuleWebhookController controller;

    @BeforeEach
    void setUp() {
        proxy = mock(ExternalModuleProxy.class);
        controller = new ModuleWebhookController();
        controller.setExternalModules(proxy);
    }

    @Test
    @DisplayName("Answers with the module's status, so the sender retries what it did not accept")
    void answersWithModuleStatus() {
        when(proxy.webhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(new ExternalModuleProxy.Forwarded(200, null)));
        assertThat(controller.receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        when(proxy.webhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(new ExternalModuleProxy.Forwarded(401, null)));
        assertThat(controller.receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Answers 404 when there is nothing to forward to")
    void notFoundWhenNothingToForwardTo() {
        when(proxy.webhook(anyString(), anyString(), any(), any())).thenReturn(Optional.empty());

        assertThat(controller.receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Answers 503 when the module cannot be called, so the sender retries")
    void unavailableWhenModuleCannotBeCalled() {
        when(proxy.webhook(anyString(), anyString(), any(), any()))
                .thenThrow(ModuleUnavailableException.of("m1", "circuit open"));

        assertThat(controller.receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Passes the raw body bytes through untouched")
    void passesRawBodyThrough() {
        when(proxy.webhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(new ExternalModuleProxy.Forwarded(200, null)));
        byte[] body = bytes("{\"id\":\"evt_1\",\"nested\":{\"a\": 1}}");
        Map<String, String> headers = Map.of("Stripe-Signature", "t=1,v1=abc");

        controller.receive("t1", "m1", headers, body);

        // Re-serializing would change the bytes an HMAC covers.
        verify(proxy).webhook(eq("t1"), eq("m1"), eq(body), eq(headers));
    }

    @Test
    @DisplayName("Answers 404 with module support switched off")
    void notFoundWithoutModuleSupport() {
        assertThat(new ModuleWebhookController().receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
