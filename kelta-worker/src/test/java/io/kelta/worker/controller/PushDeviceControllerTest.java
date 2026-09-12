package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.service.push.DefaultPushService;
import io.kelta.worker.service.push.WebPushProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushDeviceController Tests")
class PushDeviceControllerTest {

    private static final String VAPID_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";

    @Mock private DefaultPushService pushService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private WebPushProvider webPushProvider;
    @Mock private io.kelta.runtime.router.UserIdResolver userIdResolver;

    private PushDeviceController controller(WebPushProvider provider) {
        @SuppressWarnings("unchecked")
        ObjectProvider<WebPushProvider> objectProvider = mock(ObjectProvider.class);
        // Only the key endpoint consults it; the registration tests never do.
        lenient().when(objectProvider.getIfAvailable()).thenReturn(provider);
        // The header carries an email; the store needs platform_user.id. Existing tests pass
        // "u1" as the header and expect "u1" stored, so the resolver is an identity here and
        // the email→id case is covered by its own test below.
        lenient().when(userIdResolver.resolve(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        return new PushDeviceController(pushService, jdbcTemplate, objectProvider, userIdResolver);
    }

    @Test
    @DisplayName("Resolves the X-User-Id email to platform_user.id before storing")
    void resolvesEmailHeaderToUserId() {
        // push_device.user_id is a foreign key to platform_user(id). Storing the header's email
        // was a constraint violation on every call: POST /api/devices had never once succeeded.
        PushDeviceController c = controller(null);
        when(userIdResolver.resolve("craig@example.com", "t1")).thenReturn("uuid-1");
        when(pushService.registerDevice("uuid-1", "t1", "ios", "tok", "iPhone")).thenReturn("d9");

        var response = withTenant(() -> c.registerDevice(
                Map.of("platform", "ios", "deviceToken", "tok", "deviceName", "iPhone"),
                "craig@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(pushService).registerDevice("uuid-1", "t1", "ios", "tok", "iPhone");
        verify(pushService, never()).registerDevice(eq("craig@example.com"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("An unresolvable identity is a 400, not a foreign-key 500")
    void unknownUserIsBadRequest() {
        PushDeviceController c = controller(null);
        when(userIdResolver.resolve("ghost@example.com", "t1")).thenReturn(null);

        var response = withTenant(() -> c.registerDevice(
                Map.of("platform", "ios", "deviceToken", "tok"), "ghost@example.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(pushService, never()).registerDevice(any(), any(), any(), any(), any());
    }

    private ResponseEntity<?> withTenant(java.util.function.Supplier<ResponseEntity<?>> call) {
        return TenantContext.callWithTenant("t1", call::get);
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("a subscription registers as a browser device, not a token device")
        void subscriptionTakesWebPath() {
            var c = controller(null);
            String subscription = "{\"endpoint\":\"https://push.test/a\"}";
            when(pushService.registerWebDevice("u1", "t1", subscription, "Chrome"))
                    .thenReturn("d1");

            var response = withTenant(() -> c.registerDevice(
                    Map.of("subscription", subscription, "deviceName", "Chrome"), "u1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            // A browser has no device token — routing it down the native path
            // would store the literal string "null" as a token.
            verify(pushService, never()).registerDevice(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a native registration is unchanged")
        void tokenTakesNativePath() {
            var c = controller(null);
            when(pushService.registerDevice("u1", "t1", "ios", "tok", "iPhone")).thenReturn("d2");

            var response = withTenant(() -> c.registerDevice(
                    Map.of("platform", "ios", "deviceToken", "tok", "deviceName", "iPhone"), "u1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(pushService, never()).registerWebDevice(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a blank subscription falls through rather than registering an empty browser device")
        void blankSubscriptionFallsThrough() {
            var c = controller(null);
            when(pushService.registerDevice("u1", "t1", "ios", "tok", null)).thenReturn("d3");

            withTenant(() -> c.registerDevice(
                    Map.of("subscription", "   ", "platform", "ios", "deviceToken", "tok"), "u1"));

            verify(pushService, never()).registerWebDevice(any(), any(), any(), any());
        }

        @Test
        @DisplayName("an unreadable subscription is a 400, not a 500")
        void badSubscriptionIsBadRequest() {
            var c = controller(null);
            when(pushService.registerWebDevice(any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("subscription has no endpoint"));

            var response = withTenant(() -> c.registerDevice(
                    Map.of("subscription", "{}"), "u1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("VAPID public key")
    class VapidPublicKey {

        @Test
        @DisplayName("serves the key when web push is configured")
        void servesKey() {
            when(webPushProvider.publicKey()).thenReturn(VAPID_PUBLIC);

            var response = controller(webPushProvider).vapidPublicKey();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(
                    Map.of("data", Map.of("publicKey", VAPID_PUBLIC)));
        }

        @Test
        @DisplayName("404s when the provider is not registered")
        void notFoundWhenUnconfigured() {
            // Lets a frontend distinguish "web push is off here" from a
            // misconfiguration, instead of subscribing with an empty key.
            assertThat(controller(null).vapidPublicKey().getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("404s rather than serving a blank key")
        void notFoundWhenKeyBlank() {
            when(webPushProvider.publicKey()).thenReturn("");

            assertThat(controller(webPushProvider).vapidPublicKey().getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
