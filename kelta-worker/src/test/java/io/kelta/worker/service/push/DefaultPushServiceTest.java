package io.kelta.worker.service.push;

import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.worker.repository.PushRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPushService Tests")
class DefaultPushServiceTest {

    @Mock private PushProvider pushProvider;
    @Mock private PushRepository pushRepository;

    private DefaultPushService service;

    @BeforeEach
    void setUp() {
        service = new DefaultPushService(pushProvider, pushRepository, List.of(pushProvider),
                new OutboundUrlGuard(""));
    }

    @Nested
    @DisplayName("Device Registration")
    class DeviceRegistration {
        @Test
        void shouldRejectBlankToken() {
            assertThatThrownBy(() -> service.registerDevice("u1", "t1", "ios", "", "iPhone"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectNullToken() {
            assertThatThrownBy(() -> service.registerDevice("u1", "t1", "ios", null, "iPhone"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectTokenTooLong() {
            String longToken = "x".repeat(501);
            assertThatThrownBy(() -> service.registerDevice("u1", "t1", "ios", longToken, "iPhone"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectInvalidPlatform() {
            assertThatThrownBy(() -> service.registerDevice("u1", "t1", "blackberry", "token", "BB"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldAcceptValidRegistration() {
            when(pushRepository.findDeviceIdByToken("t1", "valid-token")).thenReturn(Optional.empty());
            when(pushRepository.insertDevice("u1", "t1", "ios", "valid-token", "iPhone"))
                    .thenReturn("new-device-id");

            String id = service.registerDevice("u1", "t1", "ios", "valid-token", "iPhone");
            assertThat(id).isEqualTo("new-device-id");
            verify(pushRepository).insertDevice("u1", "t1", "ios", "valid-token", "iPhone");
        }

        @Test
        void shouldUpdateExistingDevice() {
            when(pushRepository.findDeviceIdByToken("t1", "existing-token"))
                    .thenReturn(Optional.of("device-1"));

            String id = service.registerDevice("u1", "t1", "android", "existing-token", "Pixel");
            assertThat(id).isEqualTo("device-1");
            verify(pushRepository).updateDevice("device-1", "u1", "android", "Pixel");
        }
    }

    @Nested
    @DisplayName("Send Notifications")
    class SendNotifications {
        @Test
        void shouldSendToAllUserDevices() {
            when(pushRepository.findDevicesForUser("u1", "t1")).thenReturn(List.of(
                    Map.of("id", "d1", "device_token", "tok1", "platform", "ios"),
                    Map.of("id", "d2", "device_token", "tok2", "platform", "android")
            ));
            when(pushRepository.getTenantSettings("t1")).thenReturn(null);

            int delivered = service.sendToUser("u1", "t1", "Title", "Body", null);

            assertThat(delivered).isEqualTo(2);
            verify(pushProvider, times(2)).send(any(), isNull());
        }

        @Test
        void shouldRemoveStaleTokenOnInvalidTokenError() {
            when(pushRepository.findDevicesForUser("u1", "t1")).thenReturn(
                    List.of(Map.of("id", "d1", "device_token", "stale", "platform", "ios")));
            when(pushRepository.getTenantSettings("t1")).thenReturn(null);

            doThrow(new PushDeliveryException("Invalid token", true))
                    .when(pushProvider).send(any(), any());

            int delivered = service.sendToUser("u1", "t1", "Title", "Body", null);

            assertThat(delivered).isEqualTo(0);
            verify(pushRepository).deleteDeviceById("d1");
        }

        @Test
        void shouldLoadTenantPushSettings() throws Exception {
            when(pushRepository.findDevicesForUser("u1", "t1")).thenReturn(
                    List.of(Map.of("id", "d1", "device_token", "tok1", "platform", "ios")));
            var mapper = new ObjectMapper();
            var settingsNode = mapper.readTree(
                    "{\"push\":{\"fcm\":{\"projectId\":\"tenant-proj\",\"clientEmail\":\"sa@tenant.iam\",\"privateKey\":\"pk\"}}}");
            when(pushRepository.getTenantSettings("t1")).thenReturn(settingsNode);

            service.sendToUser("u1", "t1", "Title", "Body", null);

            verify(pushProvider).send(any(), argThat(settings ->
                    settings != null && "tenant-proj".equals(settings.fcmProjectId())));
        }

        @Test
        void shouldHandleMissingTenantSettings() {
            when(pushRepository.findDevicesForTenant("t1")).thenReturn(
                    List.of(Map.of("id", "d1", "device_token", "tok1", "platform", "web")));
            when(pushRepository.getTenantSettings("t1")).thenReturn(null);

            int delivered = service.sendToTenant("t1", "Title", "Body", null);

            assertThat(delivered).isEqualTo(1);
            verify(pushProvider).send(any(), isNull());
        }
    }

    @Nested
    @DisplayName("Provider Routing")
    class ProviderRouting {

        @Mock private PushProvider webProvider;

        /** Browser push runs beside the mobile provider, not instead of it. */
        private DefaultPushService routingService() {
            when(webProvider.supports(anyString()))
                    .thenAnswer(call -> "web".equals(call.getArgument(0)));
            return new DefaultPushService(pushProvider, pushRepository,
                    List.of(pushProvider, webProvider), new OutboundUrlGuard(""));
        }

        @Test
        @DisplayName("routes each device to the provider claiming its platform")
        void routesPerDevice() {
            var routing = routingService();
            when(pushRepository.findDevicesForUser("u1", "t1")).thenReturn(List.of(
                    Map.of("id", "d1", "device_token", "tok1", "platform", "ios"),
                    Map.of("id", "d2", "device_token", "tok2", "platform", "web",
                            "web_push_subscription", "{\"endpoint\":\"https://push.test/a\"}")));
            when(pushRepository.getTenantSettings("t1")).thenReturn(null);

            int delivered = routing.sendToUser("u1", "t1", "Title", "Body", null);

            assertThat(delivered).isEqualTo(2);
            verify(pushProvider).send(argThat(m -> "ios".equals(m.platform())), isNull());
            verify(webProvider).send(argThat(m -> "web".equals(m.platform())), isNull());
        }

        @Test
        @DisplayName("passes the stored subscription through to the web provider")
        void passesSubscription() {
            var routing = routingService();
            when(pushRepository.findDevicesForUser("u1", "t1")).thenReturn(List.of(
                    Map.of("id", "d2", "device_token", "tok2", "platform", "web",
                            "web_push_subscription", "{\"endpoint\":\"https://push.test/a\"}")));
            when(pushRepository.getTenantSettings("t1")).thenReturn(null);

            routing.sendToUser("u1", "t1", "Title", "Body", null);

            // Without this the provider has only a hash of the endpoint and cannot
            // address the browser at all.
            verify(webProvider).send(argThat(m ->
                    m.subscription() != null && m.subscription().contains("push.test/a")), isNull());
        }

        @Test
        @DisplayName("falls back to the configured provider when nothing claims the platform")
        void fallsBackToDefault() {
            var routing = routingService();
            when(pushRepository.findDevicesForUser("u1", "t1")).thenReturn(List.of(
                    Map.of("id", "d1", "device_token", "tok1", "platform", "android")));
            when(pushRepository.getTenantSettings("t1")).thenReturn(null);

            routing.sendToUser("u1", "t1", "Title", "Body", null);

            verify(pushProvider).send(any(), isNull());
            verify(webProvider, never()).send(any(), any());
        }
    }

    @Nested
    @DisplayName("Web Device Registration")
    class WebDeviceRegistration {

        @Test
        @DisplayName("stores the subscription and keys the device by the endpoint hash")
        void storesSubscription() {
            String subscription = "{\"endpoint\":\"https://push.test/" + "x".repeat(600)
                    + "\",\"keys\":{\"p256dh\":\"a\",\"auth\":\"b\"}}";
            String expectedToken = DefaultPushService.webDeviceToken(subscription);
            when(pushRepository.findDeviceIdByToken("t1", expectedToken)).thenReturn(Optional.empty());
            when(pushRepository.insertDevice("u1", "t1", "web", expectedToken, "Chrome"))
                    .thenReturn("d9");

            String id = service.registerWebDevice("u1", "t1", subscription, "Chrome");

            assertThat(id).isEqualTo("d9");
            verify(pushRepository).updateWebPushSubscription("d9", subscription);
        }

        @Test
        @DisplayName("re-subscribing the same browser updates rather than duplicates")
        void reSubscribeUpdates() {
            String subscription = "{\"endpoint\":\"https://push.test/a\"}";
            when(pushRepository.findDeviceIdByToken("t1", DefaultPushService.webDeviceToken(subscription)))
                    .thenReturn(Optional.of("d1"));

            String id = service.registerWebDevice("u1", "t1", subscription, "Chrome");

            assertThat(id).isEqualTo("d1");
            verify(pushRepository, never()).insertDevice(any(), any(), any(), any(), any());
            verify(pushRepository).updateWebPushSubscription("d1", subscription);
        }

        @Test
        @DisplayName("refuses an endpoint that is not https, and stores nothing")
        void refusesNonHttpsEndpoint() {
            assertThatThrownBy(() -> service.registerWebDevice("u1", "t1",
                    "{\"endpoint\":\"http://push.example.com/a\"}", "Chrome"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("https");
            verifyNoInteractions(pushRepository);
        }

        @Test
        @DisplayName("refuses an endpoint on a non-public address, and stores nothing")
        void refusesNonPublicEndpoint() {
            for (String endpoint : new String[]{"https://127.0.0.1/x", "https://10.0.0.5/x",
                    "https://169.254.169.254/latest"}) {
                assertThatThrownBy(() -> service.registerWebDevice("u1", "t1",
                        "{\"endpoint\":\"" + endpoint + "\"}", "Chrome"))
                        .as(endpoint)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("non-public");
            }
            verifyNoInteractions(pushRepository);
        }

        @Test
        @DisplayName("rejects a missing subscription")
        void rejectsMissingSubscription() {
            assertThatThrownBy(() -> service.registerWebDevice("u1", "t1", "  ", "Chrome"))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(pushRepository);
        }
    }
}
