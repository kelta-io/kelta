package io.kelta.ai.service;

import io.kelta.ai.TestFixtures;
import io.kelta.ai.repository.AiConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sends {@link FlowGenerationService}'s {@code MessageCreateParams} through the real anthropic-java
 * client to a local stub of {@code /v1/messages} and parses the stubbed {@code Message} back.
 */
@DisplayName("FlowGenerationService over the SDK")
class FlowGenerationServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private AnthropicStubServer stub;
    private FlowGenerationService service;

    @BeforeEach
    void setUp() throws IOException {
        stub = new AnthropicStubServer();
        WorkerApiClient workerApiClient = mock(WorkerApiClient.class);
        when(workerApiClient.listCollections(TestFixtures.TENANT_ID))
                .thenReturn(List.of(Map.of("name", "orders", "displayName", "Orders")));
        service = new FlowGenerationService(stub.client(), TestFixtures.aiConfig(),
                mock(AiConfigRepository.class), workerApiClient);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName("sends one user text block with the default model and parses the fenced JSON reply")
    void buildsRequestAndParsesResponse() {
        stub.enqueueJson(textMessage("```json\\n{\\\"StartAt\\\":\\\"Notify\\\",\\\"States\\\":"
                + "{\\\"Notify\\\":{\\\"Type\\\":\\\"Succeed\\\"}}}\\n```"));

        Map<String, Object> definition = service.generateFlow(TestFixtures.TENANT_ID, "Notify when an order ships");

        assertThat(definition).containsEntry("StartAt", "Notify");
        assertThat(definition.get("States")).isEqualTo(Map.of("Notify", Map.of("Type", "Succeed")));

        JsonNode request = stub.requests().getFirst();
        assertThat(request.get("model").asString()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(request.get("max_tokens").asLong()).isEqualTo(4096L);
        assertThat(request.get("system").asString()).contains("- **Orders** (`orders`)");
        assertThat(request.has("stream")).isFalse();
        assertThat(request.get("messages")).isEqualTo(objectMapper.readTree(
                "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Notify when an order ships\"}]}]"));
    }

    @Test
    @DisplayName("rejects a reply that is not a flow definition")
    void rejectsNonFlowReply() {
        stub.enqueueJson(textMessage("{\\\"hello\\\":\\\"world\\\"}"));

        assertThatThrownBy(() -> service.generateFlow(TestFixtures.TENANT_ID, "anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing 'States'");
    }

    private static String textMessage(String escapedText) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-20250514\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + escapedText + "\",\"citations\":null}],"
                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
    }
}
