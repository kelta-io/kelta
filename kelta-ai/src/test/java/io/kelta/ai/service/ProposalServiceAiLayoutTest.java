package io.kelta.ai.service;

import io.kelta.ai.TestFixtures;
import io.kelta.ai.model.AiProposal;
import io.kelta.ai.model.ChatMessage;
import io.kelta.ai.repository.AiConfigRepository;
import io.kelta.ai.repository.ChatMessageRepository;
import io.kelta.ai.service.tools.ProposeToolHandler;
import io.kelta.ai.service.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * applyProposal for the collection type asks Claude for a DETAIL layout through
 * {@link AnthropicService}; this drives that call through the real anthropic-java client against
 * {@link MockAnthropicApi} and applies the {@code propose_layout} tool-use reply.
 */
@DisplayName("ProposalService AI layout over the anthropic-java client")
class ProposalServiceAiLayoutTest {

    private static final String TENANT = TestFixtures.TENANT_ID;
    private static final String USER = TestFixtures.USER_ID;

    private MockAnthropicApi api;
    private ChatMessageRepository messageRepository;
    private WorkerApiClient workerApiClient;
    private ProposalService service;
    private UUID proposalId;

    @BeforeEach
    void setUp() throws IOException {
        api = new MockAnthropicApi();
        AiConfigRepository aiConfigRepository = mock(AiConfigRepository.class);
        when(aiConfigRepository.getConfig(anyString(), anyString())).thenReturn(Optional.empty());
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new ProposeLayoutHandler()));
        AnthropicService anthropicService = new AnthropicService(api.client(), TestFixtures.aiConfig(),
                aiConfigRepository, toolRegistry);
        messageRepository = mock(ChatMessageRepository.class);
        workerApiClient = mock(WorkerApiClient.class);
        service = new ProposalService(messageRepository, workerApiClient, anthropicService, new ObjectMapper());
        proposalId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    @DisplayName("sends the layout prompt as a user message and applies the propose_layout tool input")
    void appliesProposeLayoutToolUse() {
        stubCollectionProposal();
        when(workerApiClient.createCollection(eq(TENANT), eq(USER), any()))
                .thenReturn(Map.of("data", Map.of("id", "col-1")));
        when(workerApiClient.listFields(TENANT, "col-1")).thenReturn(List.of(
                Map.of("id", "f-title", "attributes", Map.of("name", "title", "type", "STRING")),
                Map.of("id", "f-notes", "attributes", Map.of("name", "notes", "type", "RICH_TEXT"))));
        when(workerApiClient.createPageLayout(eq(TENANT), eq(USER), any()))
                .thenReturn(Map.of("data", Map.of("id", "layout-1")));
        when(workerApiClient.createLayoutSection(eq(TENANT), eq(USER), any()))
                .thenReturn(Map.of("data", Map.of("id", "sec-1")), Map.of("data", Map.of("id", "sec-2")));
        api.replyJson("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-20250514",
                 "content":[{"type":"tool_use","id":"toolu_1","name":"propose_layout","input":{
                   "name":"Orders Detail","layoutType":"DETAIL","sections":[
                     {"heading":"Key Info","columns":2,"style":"DEFAULT","fieldPlacements":[{"fieldName":"title"}]},
                     {"heading":"Notes","columns":1,"style":"COLLAPSIBLE","fieldPlacements":[{"fieldName":"notes"}]}]}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":50,"output_tokens":60}}
                """);

        service.applyProposal(proposalId, TENANT, USER);

        ArgumentCaptor<Map<String, Object>> layout = mapCaptor();
        verify(workerApiClient).createPageLayout(eq(TENANT), eq(USER), layout.capture());
        assertThat(layout.getValue()).containsEntry("collectionId", "col-1")
                .containsEntry("name", "Orders Detail")
                .containsEntry("layoutType", "DETAIL");

        ArgumentCaptor<Map<String, Object>> sections = mapCaptor();
        verify(workerApiClient, times(2)).createLayoutSection(eq(TENANT), eq(USER), sections.capture());
        assertThat(sections.getAllValues()).extracting(s -> s.get("heading"), s -> s.get("columns"), s -> s.get("style"))
                .containsExactly(
                        tuple("Key Info", 2, "DEFAULT"),
                        tuple("Notes", 1, "COLLAPSIBLE"));

        ArgumentCaptor<Map<String, Object>> placements = mapCaptor();
        verify(workerApiClient, times(2)).createLayoutField(eq(TENANT), eq(USER), placements.capture());
        assertThat(placements.getAllValues()).extracting(p -> p.get("sectionId"), p -> p.get("fieldId"))
                .containsExactly(
                        tuple("sec-1", "f-title"),
                        tuple("sec-2", "f-notes"));

        JsonNode request = api.requests().getFirst();
        assertThat(request.get("model").asString()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(request.get("system").asString()).startsWith("You are a UI layout designer.");
        assertThat(request.get("messages")).hasSize(1);
        assertThat(request.at("/messages/0/role").asString()).isEqualTo("user");
        assertThat(request.at("/messages/0/content").isString()).isTrue();
        assertThat(request.at("/messages/0/content").asString())
                .startsWith("Generate a DETAIL page layout for the 'Orders' collection.")
                .contains("- title (STRING)\n- notes (RICH_TEXT)\n");
        assertThat(request.get("tools")).hasSize(1);
        assertThat(request.at("/tools/0/name").asString()).isEqualTo("propose_layout");
        assertThat(request.at("/tools/0/input_schema/type").asString()).isEqualTo("object");
        assertThat(request.at("/tools/0/input_schema/properties/sections/type").asString()).isEqualTo("array");
    }

    private void stubCollectionProposal() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "orders");
        input.put("displayName", "Orders");
        input.put("fields", List.of(Map.of("name", "title", "type", "STRING"),
                Map.of("name", "notes", "type", "RICH_TEXT")));
        Map<String, Object> toolUse = new LinkedHashMap<>();
        toolUse.put("type", "tool_use");
        toolUse.put("proposalId", proposalId.toString());
        toolUse.put("name", "propose_collection");
        toolUse.put("input", input);
        ChatMessage message = new ChatMessage(UUID.randomUUID(), TENANT, UUID.randomUUID(),
                "assistant", List.of(toolUse), null, null, 0, 0, Instant.now());
        when(messageRepository.findByProposalId(proposalId, TENANT)).thenReturn(Optional.of(message));
    }

    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.captor();
    }

    private static final class ProposeLayoutHandler implements ProposeToolHandler {
        @Override public String name() { return "propose_layout"; }
        @Override public String description() { return "Propose a page layout"; }
        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of("sections", Map.of("type", "array")));
        }
        @Override public AiProposal buildProposal(Map<String, Object> input) {
            return AiProposal.pending("layout", input);
        }
    }
}
