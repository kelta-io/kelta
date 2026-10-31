package io.kelta.ai.controller;

import io.kelta.ai.repository.AiConfigRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiConfigController")
class AiConfigControllerTest {

    @Mock
    private AiConfigRepository configRepository;

    private AiConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new AiConfigController(configRepository);
    }

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("returns tenant config merged with global defaults")
        void returnsTenantConfigWithGlobalDefaults() {
            Map<String, String> tenantConfig = new HashMap<>();
            tenantConfig.put("anthropic.model", "claude-sonnet-4-20250514");

            Map<String, String> globalConfig = new HashMap<>();
            globalConfig.put("anthropic.maxTokens", "8192");
            globalConfig.put("aiEnabled", "false");

            when(configRepository.getAllConfig("tenant-1")).thenReturn(tenantConfig);
            when(configRepository.getAllConfig("0")).thenReturn(globalConfig);

            ResponseEntity<Map<String, Object>> response = controller.getConfig("tenant-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            assertThat(data.get("model")).isEqualTo("claude-sonnet-4-20250514");
            assertThat(data.get("maxTokens")).isEqualTo("8192");
            assertThat(data.get("aiEnabled")).isEqualTo("false");
        }

        @Test
        @DisplayName("returns hardcoded defaults when no config exists")
        void returnsDefaultsWhenNoConfig() {
            when(configRepository.getAllConfig("tenant-1")).thenReturn(new HashMap<>());
            when(configRepository.getAllConfig("0")).thenReturn(new HashMap<>());

            ResponseEntity<Map<String, Object>> response = controller.getConfig("tenant-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            assertThat(data.get("model")).isEqualTo("claude-sonnet-4-20250514");
            assertThat(data.get("maxTokens")).isEqualTo("4096");
            assertThat(data.get("temperature")).isEqualTo("0.7");
            assertThat(data.get("aiTokensPerMonth")).isEqualTo("1000000");
            assertThat(data.get("aiEnabled")).isEqualTo("true");
        }

        @Test
        @DisplayName("tenant config overrides global config")
        void tenantOverridesGlobal() {
            Map<String, String> tenantConfig = new HashMap<>();
            tenantConfig.put("aiEnabled", "false");

            Map<String, String> globalConfig = new HashMap<>();
            globalConfig.put("aiEnabled", "true");

            when(configRepository.getAllConfig("tenant-1")).thenReturn(tenantConfig);
            when(configRepository.getAllConfig("0")).thenReturn(globalConfig);

            ResponseEntity<Map<String, Object>> response = controller.getConfig("tenant-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            assertThat(data.get("aiEnabled")).isEqualTo("false");
        }
    }

    @Nested
    @DisplayName("updateConfig")
    class UpdateConfig {

        @Test
        @DisplayName("maps UI field names to config keys and persists")
        void mapsFieldNamesToConfigKeys() {
            Map<String, Object> body = Map.of(
                    "model", "claude-opus-4-20250514",
                    "maxTokens", "16384"
            );

            when(configRepository.getAllConfig("tenant-1")).thenReturn(new HashMap<>());
            when(configRepository.getAllConfig("0")).thenReturn(new HashMap<>());

            controller.updateConfig("tenant-1", body);

            verify(configRepository).setConfig("tenant-1", "anthropic.model", "claude-opus-4-20250514");
            verify(configRepository).setConfig("tenant-1", "anthropic.maxTokens", "16384");
        }

        @Test
        @DisplayName("returns updated config after save")
        void returnsUpdatedConfig() {
            Map<String, Object> body = Map.of("aiEnabled", "false");

            Map<String, String> updatedConfig = new HashMap<>();
            updatedConfig.put("aiEnabled", "false");
            when(configRepository.getAllConfig("tenant-1")).thenReturn(updatedConfig);
            when(configRepository.getAllConfig("0")).thenReturn(new HashMap<>());

            ResponseEntity<Map<String, Object>> response = controller.updateConfig("tenant-1", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("stores every known key in its normalized form (the UI sends strings)")
        void storesAllKnownKeysNormalized() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-sonnet-4-20250514");
            body.put("maxTokens", 8192);
            body.put("temperature", "0.70");
            body.put("aiTokensPerMonth", "2000000");
            body.put("aiEnabled", "TRUE");
            when(configRepository.getAllConfig(anyString())).thenReturn(new HashMap<>());

            ResponseEntity<Map<String, Object>> response = controller.updateConfig("tenant-1", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(configRepository).setConfig("tenant-1", "anthropic.model", "claude-sonnet-4-20250514");
            verify(configRepository).setConfig("tenant-1", "anthropic.maxTokens", "8192");
            verify(configRepository).setConfig("tenant-1", "anthropic.temperature", "0.7");
            verify(configRepository).setConfig("tenant-1", "aiTokensPerMonth", "2000000");
            verify(configRepository).setConfig("tenant-1", "aiEnabled", "true");
        }

        @Test
        @DisplayName("refuses an unknown key with 400 VALIDATION_FAILED and writes nothing, not even the valid keys")
        void unknownKeyIsRefused() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-sonnet-4-20250514");
            body.put("anthropic.apiKey", "sk-anything");

            ResponseEntity<Map<String, Object>> response = controller.updateConfig("tenant-1", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            List<Map<String, Object>> errors = errors(response);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0))
                    .containsEntry("status", "400")
                    .containsEntry("code", "VALIDATION_FAILED")
                    .containsEntry("source", Map.of("pointer", "/anthropic.apiKey"));
            assertThat((String) errors.get(0).get("detail")).contains("accepted keys: model, maxTokens");
            assertThat(errors.get(0).get("meta")).asInstanceOf(InstanceOfAssertFactories.MAP).containsKey("requestId");
            verify(configRepository, never()).setConfig(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("refuses invalid values, one error per key, and writes nothing")
        void invalidValuesAreRefused() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "  ");
            body.put("maxTokens", 0);
            body.put("temperature", 1.5);
            body.put("aiTokensPerMonth", "-5");
            body.put("aiEnabled", "yes");

            ResponseEntity<Map<String, Object>> response = controller.updateConfig("tenant-1", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errors(response)).extracting(e -> e.get("source"))
                    .containsExactly(Map.of("pointer", "/model"), Map.of("pointer", "/maxTokens"),
                            Map.of("pointer", "/temperature"), Map.of("pointer", "/aiTokensPerMonth"),
                            Map.of("pointer", "/aiEnabled"));
            verify(configRepository, never()).setConfig(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a null value is refused")
        void nullValueIsRefused() {
            Map<String, Object> body = new HashMap<>();
            body.put("aiEnabled", null);

            ResponseEntity<Map<String, Object>> response = controller.updateConfig("tenant-1", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(configRepository, never()).setConfig(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("an unknown key's JSON Pointer escapes '~' and '/'")
        void pointerEscapesKey() {
            ResponseEntity<Map<String, Object>> response =
                    controller.updateConfig("tenant-1", Map.of("a/b~c", "x"));

            assertThat(errors(response).get(0)).containsEntry("source", Map.of("pointer", "/a~1b~0c"));
        }

        private List<Map<String, Object>> errors(ResponseEntity<Map<String, Object>> response) {
            List<Map<String, Object>> errors = new ArrayList<>();
            for (Object error : (List<?>) response.getBody().get("errors")) {
                Map<String, Object> copy = new LinkedHashMap<>();
                ((Map<?, ?>) error).forEach((key, value) -> copy.put(String.valueOf(key), value));
                errors.add(copy);
            }
            return errors;
        }
    }

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("model ids: provider ids accepted; blank, whitespace, over-long and non-strings refused")
        void modelIds() {
            assertThat(AiConfigController.normalize("model", "claude-opus-4-20250514")).contains("claude-opus-4-20250514");
            assertThat(AiConfigController.normalize("model", "us.anthropic.claude-v2:1")).contains("us.anthropic.claude-v2:1");
            assertThat(AiConfigController.normalize("model", "claude sonnet")).isEmpty();
            assertThat(AiConfigController.normalize("model", "claude\nsonnet")).isEmpty();
            assertThat(AiConfigController.normalize("model", "a".repeat(AiConfigController.MODEL_MAX_LENGTH + 1))).isEmpty();
            assertThat(AiConfigController.normalize("model", 42)).isEmpty();
        }

        @Test
        @DisplayName("whole numbers: positive and in range, as numbers or numeric strings; no fractions or booleans")
        void wholeNumbers() {
            assertThat(AiConfigController.normalize("maxTokens", "4096")).contains("4096");
            assertThat(AiConfigController.normalize("maxTokens", 4096.0)).contains("4096");
            assertThat(AiConfigController.normalize("maxTokens", 1.5)).isEmpty();
            assertThat(AiConfigController.normalize("maxTokens", (long) Integer.MAX_VALUE + 1)).isEmpty();
            assertThat(AiConfigController.normalize("maxTokens", true)).isEmpty();
            assertThat(AiConfigController.normalize("aiTokensPerMonth", 10_000_000_000L)).contains("10000000000");
            assertThat(AiConfigController.normalize("aiTokensPerMonth", 0)).isEmpty();
            assertThat(AiConfigController.normalize("aiTokensPerMonth", "1e3")).contains("1000");
            assertThat(AiConfigController.normalize("aiTokensPerMonth", "lots")).isEmpty();
        }

        @Test
        @DisplayName("temperature: 0 to 1 inclusive")
        void temperature() {
            assertThat(AiConfigController.normalize("temperature", 0)).contains("0");
            assertThat(AiConfigController.normalize("temperature", "1.0")).contains("1");
            assertThat(AiConfigController.normalize("temperature", -0.1)).isEmpty();
            assertThat(AiConfigController.normalize("temperature", "NaN")).isEmpty();
        }

        @Test
        @DisplayName("aiEnabled: booleans or the strings true/false")
        void aiEnabled() {
            assertThat(AiConfigController.normalize("aiEnabled", false)).contains("false");
            assertThat(AiConfigController.normalize("aiEnabled", " true ")).contains("true");
            assertThat(AiConfigController.normalize("aiEnabled", 1)).isEmpty();
        }
    }
}
