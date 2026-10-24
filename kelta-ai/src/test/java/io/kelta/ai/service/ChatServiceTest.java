package io.kelta.ai.service;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import io.kelta.ai.TestFixtures;
import io.kelta.ai.model.Conversation;
import io.kelta.ai.repository.ChatMessageRepository;
import io.kelta.ai.repository.ConversationRepository;
import io.kelta.ai.service.tools.ToolDispatcher;
import io.kelta.ai.service.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService length guard")
class ChatServiceTest {

    private static final String TENANT = TestFixtures.TENANT_ID;
    private static final String USER = TestFixtures.USER_ID;

    @Mock private AnthropicService anthropicService;
    @Mock private SystemPromptService systemPromptService;
    @Mock private ToolDispatcher toolDispatcher;
    @Mock private ToolRegistry toolRegistry;
    @Mock private TokenTrackingService tokenTrackingService;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ChatMessageRepository messageRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private ChatService chatService;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(anthropicService, systemPromptService, toolDispatcher, toolRegistry,
                tokenTrackingService, conversationRepository, messageRepository, objectMapper);
        conversation = TestFixtures.conversation();
        when(conversationRepository.findById(conversation.id(), TENANT)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversation(conversation.id(), TENANT)).thenReturn(List.of());
    }

    private static MessageCreateParams.Builder minimalRequestBuilder() {
        return MessageCreateParams.builder()
                .model("claude-sonnet-5")
                .maxTokens(1024)
                .system("system prompt")
                .addMessage(MessageParam.ofUser("hi"));
    }

    private static Message assistantMessage(String text, long inputTokens, long outputTokens) {
        Usage usage = Usage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreation(Optional.empty())
                .cacheCreationInputTokens(Optional.empty())
                .cacheReadInputTokens(Optional.empty())
                .inferenceGeo(Optional.empty())
                .outputTokensDetails(Optional.empty())
                .serverToolUse(Optional.empty())
                .serviceTier(Optional.empty())
                .speed(Optional.empty())
                .build();
        return Message.builder()
                .id("msg_test")
                .container(Optional.empty())
                .diagnostics(Optional.empty())
                .model("claude-sonnet-5")
                .stopDetails(Optional.empty())
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .content(List.of(ContentBlock.ofText(
                        TextBlock.builder().text(text).citations(List.of()).build())))
                .usage(usage)
                .build();
    }

    @Test
    @DisplayName("below threshold: guard does not trip, normal flow proceeds")
    void guardDoesNotTripBelowThreshold() {
        when(messageRepository.findMostRecentAssistantTokensInput(conversation.id(), TENANT)).thenReturn(1_000);
        when(systemPromptService.buildSystemPrompt(eq(TENANT), any(), any())).thenReturn("system prompt");
        when(anthropicService.buildRequest(eq(TENANT), anyString(), anyList())).thenReturn(minimalRequestBuilder());
        when(anthropicService.sendMessage(any())).thenReturn(assistantMessage("Sure, happy to help.", 500, 50));

        Map<String, Object> result = chatService.chat(TENANT, USER, conversation.id(), "hello", null, null);

        assertThat(result.get("content")).isEqualTo("Sure, happy to help.");
        assertThat(result).doesNotContainKey("conversationTooLarge");
        verify(systemPromptService).buildSystemPrompt(eq(TENANT), any(), any());
    }

    @Test
    @DisplayName("above threshold: guard trips, skips system prompt + tool loop entirely, returns summary + redirect")
    void guardTripsAboveThreshold() {
        when(messageRepository.findMostRecentAssistantTokensInput(conversation.id(), TENANT)).thenReturn(170_000);
        when(anthropicService.buildRequest(eq(TENANT), anyString(), anyList())).thenReturn(minimalRequestBuilder());
        when(anthropicService.sendMessage(any()))
                .thenReturn(assistantMessage("Key points: created 'customers' collection with email field.", 165_000, 120));

        Map<String, Object> result = chatService.chat(TENANT, USER, conversation.id(), "keep going", null, null);

        String content = (String) result.get("content");
        assertThat(content).contains("Key points: created 'customers' collection");
        assertThat(content).contains("Start a new conversation");
        assertThat(result.get("conversationTooLarge")).isEqualTo(true);
        assertThat(result.get("tokensUsed")).isEqualTo(Map.of("input", 165_000, "output", 120));

        verifyNoInteractions(systemPromptService);
        verify(tokenTrackingService).recordUsage(TENANT, 165_000, 120);
        verify(messageRepository).save(argThatAssistantRole());
    }

    @Test
    @DisplayName("above threshold + summarization call fails: falls back to plain redirect, records no tokens")
    void guardFallsBackWhenSummarizationFails() {
        when(messageRepository.findMostRecentAssistantTokensInput(conversation.id(), TENANT)).thenReturn(170_000);
        when(anthropicService.buildRequest(eq(TENANT), anyString(), anyList())).thenReturn(minimalRequestBuilder());
        when(anthropicService.sendMessage(any())).thenThrow(new RuntimeException("upstream 500"));

        Map<String, Object> result = chatService.chat(TENANT, USER, conversation.id(), "keep going", null, null);

        String content = (String) result.get("content");
        assertThat(content).doesNotContain("---");
        assertThat(content).contains("Start a new conversation");
        assertThat(result.get("tokensUsed")).isEqualTo(Map.of("input", 0, "output", 0));

        verifyNoInteractions(systemPromptService);
        verify(tokenTrackingService, never()).recordUsage(anyString(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("non-streaming chat() returns a clean error instead of throwing when Anthropic errors")
    void chatReturnsCleanErrorOnProviderException() {
        when(messageRepository.findMostRecentAssistantTokensInput(conversation.id(), TENANT)).thenReturn(0);
        when(systemPromptService.buildSystemPrompt(eq(TENANT), any(), any())).thenReturn("system prompt");
        when(anthropicService.buildRequest(eq(TENANT), anyString(), anyList())).thenReturn(minimalRequestBuilder());
        when(anthropicService.sendMessage(any())).thenThrow(new RuntimeException("prompt is too long: 210000 tokens > 200000 maximum"));

        Map<String, Object> result = chatService.chat(TENANT, USER, conversation.id(), "one more thing", null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) result.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(error.get("message")).isEqualTo("prompt is too long: 210000 tokens > 200000 maximum");
    }

    private static io.kelta.ai.model.ChatMessage argThatAssistantRole() {
        return org.mockito.ArgumentMatchers.argThat(m -> "assistant".equals(m.role()));
    }
}
