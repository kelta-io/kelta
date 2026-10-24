package io.kelta.ai.service.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.kelta.ai.service.AnthropicService;
import io.kelta.ai.service.tools.ToolRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic-SDK implementation of {@link AgentModelClient}. Attaches only the agent's allowed tool
 * subset (via {@link ToolRegistry#toolDefinitions(java.util.Collection)}), applies model/max-token
 * overrides, converts the portable {@link AgentMessage} history into SDK {@code MessageParam}s, sends
 * the request, and extracts the response into a portable {@link AgentTurn}. All SDK coupling lives
 * here so {@link AgentRuntimeService} stays testable.
 */
@Component
public class AnthropicAgentModelClient implements AgentModelClient {

    private final AnthropicService anthropicService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public AnthropicAgentModelClient(AnthropicService anthropicService, ToolRegistry toolRegistry,
                                     ObjectMapper objectMapper) {
        this.anthropicService = anthropicService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentTurn nextTurn(AgentTurnRequest request) {
        List<ToolUnion> tools = toolRegistry.toolDefinitions(request.allowedTools());
        List<MessageParam> messages = toMessageParams(request.history());

        MessageCreateParams params = anthropicService.buildRequest(
                request.tenantId(), request.systemPrompt(), messages, tools,
                request.model(), request.maxTokens()).build();

        Message response = anthropicService.sendMessage(params);

        StringBuilder text = new StringBuilder();
        List<AgentToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.toolUse().ifPresent(t ->
                    toolCalls.add(new AgentToolCall(t.id(), t.name(), convertToolInput(t._input()))));
        }

        String stopReason = response.stopReason()
                .map(sr -> sr.toString().toLowerCase().replace("\"", ""))
                .orElse(null);

        return new AgentTurn(text.toString(), toolCalls, stopReason,
                (int) response.usage().inputTokens(), (int) response.usage().outputTokens());
    }

    private List<MessageParam> toMessageParams(List<AgentMessage> history) {
        List<MessageParam> messages = new ArrayList<>(history.size());
        for (AgentMessage msg : history) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (Map<String, Object> block : msg.blocks()) {
                ContentBlockParam param = convertBlock(block);
                if (param != null) {
                    blocks.add(param);
                }
            }
            if (blocks.isEmpty()) {
                continue;
            }
            messages.add("user".equals(msg.role())
                    ? MessageParam.ofUserOfContentBlockParams(blocks)
                    : MessageParam.ofAssistantOfContentBlockParams(blocks));
        }
        return messages;
    }

    private ContentBlockParam convertBlock(Map<String, Object> block) {
        String type = String.valueOf(block.getOrDefault("type", ""));
        return switch (type) {
            case "text" -> ContentBlockParam.ofText(TextBlockParam.builder()
                    .text(String.valueOf(block.getOrDefault("text", "")))
                    .build());
            case "tool_use" -> {
                ToolUseBlockParam.Input.Builder inputBuilder = ToolUseBlockParam.Input.builder();
                if (block.get("input") instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        inputBuilder.putAdditionalProperty(String.valueOf(e.getKey()), JsonValue.from(e.getValue()));
                    }
                }
                yield ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(String.valueOf(block.get("id")))
                        .name(String.valueOf(block.get("name")))
                        .input(inputBuilder.build())
                        .build());
            }
            case "tool_result" -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(String.valueOf(block.get("tool_use_id")))
                    .content(String.valueOf(block.getOrDefault("content", "")))
                    .isError(Boolean.TRUE.equals(block.get("is_error")))
                    .build());
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToolInput(Object rawInput) {
        if (rawInput instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        try {
            return objectMapper.convertValue(rawInput, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
