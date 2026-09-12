package io.kelta.testharness.scenarios;

import io.kelta.testharness.ScenarioBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Device registration through the real gateway and Postgres.
 *
 * <p>{@code POST /api/devices} had never once succeeded. The gateway stamps {@code X-User-Id}
 * with the user's email; {@code push_device.user_id} is a foreign key to {@code platform_user(id)};
 * the controller stored the header verbatim. Every call was a constraint violation and the table
 * was empty — discovered only when a real phone tried to enable notifications. The controller's
 * unit test passed throughout, because a mocked service accepts any string as a user id.
 */
@DisplayName("Push Device Scenario")
class PushDeviceScenarioTest extends ScenarioBase {

    @Test
    @DisplayName("registers a web push subscription for the signed-in user and lists it back")
    @SuppressWarnings("unchecked")
    void registersAndListsWebDevice() {
        String token = auth.loginAsAdmin();
        String tenantId = auth.extractTenantId(token);
        String slug = tenants.slugForTenantId(tenantId);

        String subscription = "{\"endpoint\":\"https://push.example/harness-" + tenantId
                + "\",\"keys\":{\"p256dh\":\"k\",\"auth\":\"a\"}}";

        ResponseEntity<Map> created = gatewayClientWithToken(token)
                .post().uri("/" + slug + "/api/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("platform", "web", "subscription", subscription,
                        "deviceName", "Harness (web)"))
                .retrieve().toEntity(Map.class);
        assertThat(created.getStatusCode())
                .as("the email in X-User-Id must resolve to platform_user.id before the insert")
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> mine = gatewayClientWithToken(token)
                .get().uri("/" + slug + "/api/devices")
                .retrieve().toEntity(Map.class);
        List<Map<String, Object>> devices = (List<Map<String, Object>>) mine.getBody().get("data");
        assertThat(devices)
                .as("the same identity resolution must apply on read, or the device is orphaned")
                .anySatisfy(d -> assertThat(d.get("platform")).isEqualTo("web"));
    }
}
