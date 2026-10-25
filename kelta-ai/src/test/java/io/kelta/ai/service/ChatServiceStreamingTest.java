package io.kelta.ai.service;

import io.kelta.ai.TestFixtures;
import io.kelta.ai.model.ChatMessage;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.type.TypeReference;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link ChatService#chatStream} through the real anthropic-java client against
 * {@link MockAnthropicApi}, so the SDK's own SSE decoding produces the {@code RawMessageStreamEvent}s
 * that {@link StreamAssembler} consumes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService streaming over the anthropic-java client")
class ChatServiceStreamingTest {

    private static final String TENANT = TestFixtures.TENANT_ID;
    private static final String USER = TestFixtures.USER_ID;

    @Mock private SystemPromptService systemPromptService;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private ToolRegistry toolRegistry;
    @Mock private TokenTrackingService tokenTrackingService;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private AiConfigRepository aiConfigRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private MockAnthropicApi api;
    private ChatService chatService;
    private Conversation conversation;

    @BeforeEach
    void setUp() throws IOException {
        api = new MockAnthropicApi();
        AnthropicService anthropicService = new AnthropicService(api.client(), TestFixtures.aiConfig(),
                aiConfigRepository, toolRegistry);
        chatService = new ChatService(anthropicService, systemPromptService, toolDispatcher, toolRegistry,
                tokenTrackingService, conversationRepository, messageRepository, objectMapper);
        conversation = TestFixtures.conversation();
        when(conversationRepository.findById(conversation.id(), TENANT)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversation(conversation.id(), TENANT))
                .thenReturn(List.of(ChatMessage.user(TENANT, conversation.id(), "hello")));
        when(systemPromptService.buildSystemPrompt(eq(TENANT), any(), any())).thenReturn("system prompt");
        when(aiConfigRepository.getConfig(anyString(), anyString())).thenReturn(Optional.empty());
        when(toolRegistry.toolDefinitions()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    @DisplayName("text + tool_use stream: emits delta/tool_call/tool_result/done and replays the tool turn")
    void streamsTextAndToolUseThenFinalAnswer() {
        api.replyStream(
                messageStart(10),
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me \"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"check.\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"list_collections\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"limit\\\":\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\" 5}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                messageDelta("tool_use", 7),
                "{\"type\":\"message_stop\"}");
        api.replyStream(
                messageStart(20),
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"No collections yet.\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                messageDelta("end_turn", 4),
                "{\"type\":\"message_stop\"}");

        when(toolRegistry.isReadTool("list_collections")).thenReturn(true);
        when(toolDispatcher.dispatch(TENANT, USER, "toolu_1", "list_collections", Map.of("limit", 5)))
                .thenReturn(DispatchResult.readResult("toolu_1", "list_collections", "{\"collections\":[]}", false));

        RecordingEmitter emitter = new RecordingEmitter();
        chatService.chatStream(TENANT, USER, conversation.id(), "hello", null, null, emitter);

        assertThat(emitter.events).extracting(SseEvent::name)
                .containsExactly("delta", "delta", "tool_call", "tool_result", "delta", "done");
        assertThat(emitter.data("delta", 0)).isEqualTo(Map.of("text", "Let me "));
        assertThat(emitter.data("delta", 1)).isEqualTo(Map.of("text", "check."));
        assertThat(emitter.data("tool_call", 0)).isEqualTo(
                Map.of("toolUseId", "toolu_1", "name", "list_collections", "status", "pending"));
        assertThat(emitter.data("tool_result", 0)).isEqualTo(
                Map.of("toolUseId", "toolu_1", "name", "list_collections", "isError", false, "status", "done"));
        assertThat(emitter.data("delta", 2)).isEqualTo(Map.of("text", "No collections yet."));
        assertThat(emitter.data("done", 0)).isEqualTo(Map.of(
                "conversationId", conversation.id().toString(),
                "tokensUsed", Map.of("input", 30, "output", 11)));

        verify(tokenTrackingService).recordUsage(TENANT, 10, 7);
        verify(tokenTrackingService).recordUsage(TENANT, 20, 4);

        List<JsonNode> requests = api.requests();
        assertThat(requests).hasSize(2);
        JsonNode first = requests.get(0);
        assertThat(first.get("stream").asBoolean()).isTrue();
        assertThat(first.get("model").asString()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(first.get("system").asString()).isEqualTo("system prompt");
        assertThat(first.get("messages")).hasSize(1);
        assertThat(first.at("/messages/0/role").asString()).isEqualTo("user");
        assertThat(first.at("/messages/0/content/0/type").asString()).isEqualTo("text");
        assertThat(first.at("/messages/0/content/0/text").asString()).isEqualTo("hello");

        JsonNode replay = requests.get(1).get("messages");
        assertThat(replay).hasSize(3);
        assertThat(replay.at("/1/role").asString()).isEqualTo("assistant");
        assertThat(replay.at("/1/content/0/text").asString()).isEqualTo("Let me check.");
        assertThat(replay.at("/1/content/1/type").asString()).isEqualTo("tool_use");
        assertThat(replay.at("/1/content/1/id").asString()).isEqualTo("toolu_1");
        assertThat(replay.at("/1/content/1/input/limit").asInt()).isEqualTo(5);
        assertThat(replay.at("/2/role").asString()).isEqualTo("user");
        assertThat(replay.at("/2/content/0/type").asString()).isEqualTo("tool_result");
        assertThat(replay.at("/2/content/0/tool_use_id").asString()).isEqualTo("toolu_1");
        assertThat(replay.at("/2/content/0/content").asString()).isEqualTo("{\"collections\":[]}");
        assertThat(replay.at("/2/content/0/is_error").asBoolean()).isFalse();
    }

    private static String messageStart(long inputTokens) {
        return "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-20250514\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":1}}}";
    }

    private static String messageDelta(String stopReason, long outputTokens) {
        return "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":" + outputTokens + "}}";
    }

    private record SseEvent(String name, String data) {}

    /** Captures what {@link ChatService} sends, decoded back into event name + JSON data. */
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
            events.add(new SseEvent(name, data));
        }

        private Map<String, Object> data(String name, int occurrence) {
            List<SseEvent> named = events.stream().filter(e -> name.equals(e.name())).toList();
            return objectMapper.readValue(named.get(occurrence).data(), new TypeReference<>() {});
        }
    }
}
