package io.kelta.ai.service;

import io.kelta.ai.TestFixtures;
import io.kelta.ai.repository.AiConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FlowGenerationService over the anthropic-java client")
class FlowGenerationServiceTest {

    private static final String TENANT = TestFixtures.TENANT_ID;

    private MockAnthropicApi api;
    private AiConfigRepository aiConfigRepository;
    private FlowGenerationService service;

    @BeforeEach
    void setUp() throws IOException {
        api = new MockAnthropicApi();
        aiConfigRepository = mock(AiConfigRepository.class);
        when(aiConfigRepository.getConfig(anyString(), anyString())).thenReturn(Optional.empty());
        WorkerApiClient workerApiClient = mock(WorkerApiClient.class);
        when(workerApiClient.listCollections(TENANT))
                .thenReturn(List.of(Map.of("name", "orders", "displayName", "Orders")));
        service = new FlowGenerationService(api.client(), TestFixtures.aiConfig(), aiConfigRepository,
                workerApiClient);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    @DisplayName("sends one user text block and parses the fenced flow definition from the reply")
    void buildsRequestAndParsesFlowDefinition() {
        api.replyJson(textMessage(
                "```json\\n{\\\"StartAt\\\":\\\"Log\\\",\\\"States\\\":{\\\"Log\\\":"
                        + "{\\\"Type\\\":\\\"Task\\\",\\\"Resource\\\":\\\"LOG_MESSAGE\\\",\\\"End\\\":true}}}\\n```"));

        Map<String, Object> flow = service.generateFlow(TENANT, "Log every new order");

        assertThat(flow).containsEntry("StartAt", "Log");
        assertThat(flow.get("States")).isEqualTo(Map.of("Log",
                Map.of("Type", "Task", "Resource", "LOG_MESSAGE", "End", true)));

        JsonNode request = api.requests().getFirst();
        assertThat(request.get("model").asString()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(request.get("max_tokens").asLong()).isEqualTo(4096L);
        assertThat(request.get("system").asString()).contains("**Orders** (`orders`)");
        assertThat(request.get("messages")).hasSize(1);
        assertThat(request.at("/messages/0/role").asString()).isEqualTo("user");
        assertThat(request.at("/messages/0/content")).hasSize(1);
        assertThat(request.at("/messages/0/content/0/type").asString()).isEqualTo("text");
        assertThat(request.at("/messages/0/content/0/text").asString()).isEqualTo("Log every new order");
    }

    @Test
    @DisplayName("uses the tenant's configured model instead of the default")
    void usesTenantModel() {
        when(aiConfigRepository.getConfig(TENANT, "anthropic.model")).thenReturn(Optional.of("claude-opus-5"));
        api.replyJson(textMessage("{\\\"StartAt\\\":\\\"Done\\\",\\\"States\\\":{\\\"Done\\\":{\\\"Type\\\":\\\"Succeed\\\"}}}"));

        service.generateFlow(TENANT, "do nothing");

        assertThat(api.requests().getFirst().get("model").asString()).isEqualTo("claude-opus-5");
    }

    @Test
    @DisplayName("a reply without States is rejected")
    void rejectsReplyWithoutStates() {
        api.replyJson(textMessage("I cannot help with that."));

        assertThatThrownBy(() -> service.generateFlow(TENANT, "anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not parse flow definition");
    }

    private static String textMessage(String escapedText) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-20250514\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + escapedText + "\"}],"
                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":34}}";
    }
}
