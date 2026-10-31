package io.kelta.worker.controller;

import io.kelta.runtime.workflow.ActionResult;
import io.kelta.worker.module.RuntimeModuleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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

    private RuntimeModuleManager manager;
    private ModuleWebhookController controller;

    @BeforeEach
    void setUp() {
        manager = mock(RuntimeModuleManager.class);
        controller = new ModuleWebhookController(manager);
    }

    @Test
    @DisplayName("Answers 200 when the module handled the webhook")
    void okWhenHandled() {
        when(manager.dispatchWebhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(ActionResult.success()));

        ResponseEntity<Void> response =
                controller.receive("t1", "m1", Map.of(), bytes("{}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Answers 404 when there is nothing to dispatch to")
    void notFoundWhenNothingDispatched() {
        when(manager.dispatchWebhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(controller.receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Answers 401 when the module rejected the request, not 500")
    void unauthorizedWhenModuleRejects() {
        when(manager.dispatchWebhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(ActionResult.failure("bad signature")));

        // A failed signature is not a fault to retry.
        assertThat(controller.receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Answers 500 when module code throws, so the sender retries")
    void serverErrorWhenModuleThrows() {
        when(manager.dispatchWebhook(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThat(controller.receive("t1", "m1", Map.of(), bytes("{}")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Passes the raw body through untouched")
    void passesRawBodyThrough() {
        when(manager.dispatchWebhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(ActionResult.success()));

        String body = "{\"id\":\"evt_1\",\"nested\":{\"a\": 1}}";
        controller.receive("t1", "m1", Map.of(), bytes(body));

        // Re-serializing would change the bytes an HMAC covers.
        verify(manager).dispatchWebhook(eq("t1"), eq("m1"), eq(body), any());
    }

    @Test
    @DisplayName("Forwards signature headers lower-cased and drops unrelated ones")
    void forwardsSignatureHeadersOnly() {
        when(manager.dispatchWebhook(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(ActionResult.success()));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Stripe-Signature", "t=1,v1=abc");
        headers.put("X-Custom-Sig", "xyz");
        headers.put("Content-Type", "application/json");
        headers.put("Host", "internal.worker.svc");
        headers.put("Authorization", "Bearer platform-internal");

        controller.receive("t1", "m1", headers, bytes("{}"));

        ArgumentCaptor<Map<String, String>> forwarded = ArgumentCaptor.captor();
        verify(manager).dispatchWebhook(anyString(), anyString(), any(), forwarded.capture());

        // Lower-cased so a module can look one up by a known key.
        assertThat(forwarded.getValue())
                .containsEntry("stripe-signature", "t=1,v1=abc")
                .containsEntry("x-custom-sig", "xyz")
                .containsEntry("content-type", "application/json");
        // Infrastructure detail a module has no business reading.
        assertThat(forwarded.getValue()).doesNotContainKeys("host", "authorization");
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
