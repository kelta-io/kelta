package io.kelta.ai.service;

import io.kelta.ai.TestFixtures;
import io.kelta.ai.model.Conversation;
import io.kelta.ai.repository.AiConfigRepository;
import io.kelta.ai.repository.ChatMessageRepository;
import io.kelta.ai.repository.ConversationRepository;
import io.kelta.ai.service.tools.DispatchResult;
import io.kelta.ai.service.tools.ToolDispatcher;
import io.kelta.ai.service.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link ChatService#chatStream} through the real anthropic-java client and its streaming
 * event types ({@code RawMessageStreamEvent}) against a local stub of {@code /v1/messages}.
 */
@DisplayName("ChatService streaming over the SDK")
class ChatServiceStreamingTest {

    private static final String TENANT = TestFixtures.TENANT_ID;
    private static final String USER = TestFixtures.USER_ID;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private AnthropicStubServer stub;
    private ToolDispatcher toolDispatcher;
    private ToolRegistry toolRegistry;
    private TokenTrackingService tokenTrackingService;
    private ChatService chatService;
    private Conversation conversation;

    @BeforeEach
    void setUp() throws IOException {
        stub = new AnthropicStubServer();
        toolDispatcher = mock(ToolDispatcher.class);
        toolRegistry = mock(ToolRegistry.class);
        tokenTrackingService = mock(TokenTrackingService.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);

        AnthropicService anthropicService = new AnthropicService(stub.client(), TestFixtures.aiConfig(),
                mock(AiConfigRepository.class), toolRegistry);
        chatService = new ChatService(anthropicService, systemPromptService, toolDispatcher, toolRegistry,
                tokenTrackingService, conversationRepository, messageRepository, objectMapper);

        conversation = TestFixtures.conversation();
        when(conversationRepository.findById(conversation.id(), TENANT)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversation(conversation.id(), TENANT))
                .thenReturn(List.of(TestFixtures.userMessage(conversation.id(), "list my collections")));
        when(systemPromptService.buildSystemPrompt(eq(TENANT), any(), any())).thenReturn("system prompt");
        when(toolRegistry.toolDefinitions()).thenReturn(List.of());
        when(toolRegistry.isReadTool("list_collections")).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName("streams text deltas, runs a read tool, replays tool_use/tool_result and emits the same SSE events")
    void streamsThroughToolLoop() throws IOException {
        stub.enqueueStream(List.of(
                messageStart(100),
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\",\"citations\":null}}",
                textDelta(0, "Let me"),
                textDelta(0, " check."),
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"toolu_1\",\"name\":\"list_collections\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"lim\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"it\\\": 5}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                messageDelta("tool_use", 40),
                "{\"type\":\"message_stop\"}"));
        stub.enqueueStream(List.of(
                messageStart(150),
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\",\"citations\":null}}",
                textDelta(0, "No collections yet."),
                "{\"type\":\"content_block_stop\",\"index\":0}",
                messageDelta("end_turn", 10),
                "{\"type\":\"message_stop\"}"));
        when(toolDispatcher.dispatch(TENANT, USER, "toolu_1", "list_collections", Map.of("limit", 5)))
                .thenReturn(DispatchResult.readResult("toolu_1", "list_collections", "{\"collections\":[]}", false));

        RecordingEmitter emitter = new RecordingEmitter();
        chatService.chatStream(TENANT, USER, conversation.id(), "list my collections", null, null, emitter);

        assertThat(emitter.events).containsExactly(
                event("delta", "{\"text\":\"Let me\"}"),
                event("delta", "{\"text\":\" check.\"}"),
                event("tool_call", "{\"toolUseId\":\"toolu_1\",\"name\":\"list_collections\",\"status\":\"pending\"}"),
                event("tool_result", "{\"toolUseId\":\"toolu_1\",\"name\":\"list_collections\","
                        + "\"isError\":false,\"status\":\"done\"}"),
                event("delta", "{\"text\":\"No collections yet.\"}"),
                event("done", "{\"conversationId\":\"" + conversation.id() + "\","
                        + "\"tokensUsed\":{\"input\":250,\"output\":50}}"));

        verify(tokenTrackingService).recordUsage(TENANT, 100, 40);
        verify(tokenTrackingService).recordUsage(TENANT, 150, 10);

        List<JsonNode> requests = stub.requests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).get("stream").asBoolean()).isTrue();
        assertThat(requests.get(0).get("model").asString()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(requests.get(0).get("messages")).isEqualTo(objectMapper.readTree(
                "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"list my collections\"}]}]"));

        JsonNode replay = requests.get(1).get("messages");
        assertThat(replay).hasSize(3);
        assertThat(replay.get(1)).isEqualTo(objectMapper.readTree("{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"Let me check.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"list_collections\",\"input\":{\"limit\":5}}]}"));
        assertThat(replay.get(2)).isEqualTo(objectMapper.readTree("{\"role\":\"user\",\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"{\\\"collections\\\":[]}\",\"is_error\":false}]}"));
    }

    private SseEvent event(String name, String json) {
        return new SseEvent(name, objectMapper.readTree(json));
    }

    private static String messageStart(int inputTokens) {
        return "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-20250514\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":1}}}";
    }

    private static String textDelta(int index, String text) {
        return "{\"type\":\"content_block_delta\",\"index\":" + index
                + ",\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}";
    }

    private static String messageDelta(String stopReason, int outputTokens) {
        return "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":" + outputTokens + "}}";
    }

    private record SseEvent(String name, JsonNode data) {}

    /** Captures each sent SSE event with its data parsed as JSON. */
    private final class RecordingEmitter extends SseEmitter {
        private final List<SseEvent> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder raw = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                raw.append(part.getData());
            }
            String name = null;
            String data = null;
            for (String line : raw.toString().split("\n")) {
                if (line.startsWith("event:")) name = line.substring("event:".length());
                if (line.startsWith("data:")) data = line.substring("data:".length());
            }
            events.add(new SseEvent(name, objectMapper.readTree(data)));
        }
    }
}
