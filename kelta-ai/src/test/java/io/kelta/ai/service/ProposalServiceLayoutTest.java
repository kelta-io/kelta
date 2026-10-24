package io.kelta.ai.service;

import io.kelta.ai.TestFixtures;
import io.kelta.ai.model.ChatMessage;
import io.kelta.ai.repository.AiConfigRepository;
import io.kelta.ai.repository.ChatMessageRepository;
import io.kelta.ai.service.tools.ToolRegistry;
import io.kelta.ai.service.tools.handlers.ProposeLayoutHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Applying a collection proposal asks Claude for a layout: this sends {@link ProposalService}'s
 * request through the real anthropic-java client to a local stub of {@code /v1/messages} and
 * applies the stubbed {@code propose_layout} tool-use reply.
 */
@DisplayName("ProposalService AI layout over the SDK")
class ProposalServiceLayoutTest {

    private static final String TENANT = TestFixtures.TENANT_ID;
    private static final String USER = TestFixtures.USER_ID;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private AnthropicStubServer stub;
    private ChatMessageRepository messageRepository;
    private WorkerApiClient workerApiClient;
    private ProposalService service;

    @BeforeEach
    void setUp() throws IOException {
        stub = new AnthropicStubServer();
        messageRepository = mock(ChatMessageRepository.class);
        workerApiClient = mock(WorkerApiClient.class);
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new ProposeLayoutHandler()));
        AnthropicService anthropicService = new AnthropicService(stub.client(), TestFixtures.aiConfig(),
                mock(AiConfigRepository.class), toolRegistry);
        service = new ProposalService(messageRepository, workerApiClient, anthropicService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName("sends the layout prompt with the propose_layout tool and applies the tool-use reply")
    void buildsRequestAndAppliesToolUse() {
        UUID proposalId = stubCollectionProposal();
        when(workerApiClient.createCollection(eq(TENANT), eq(USER), any())).thenReturn(data("col-1"));
        when(workerApiClient.createFields(eq(TENANT), eq(USER), eq("col-1"), anyList())).thenReturn(List.of());
        when(workerApiClient.listFields(TENANT, "col-1")).thenReturn(List.of(
                Map.of("id", "fld-title", "attributes", Map.of("name", "title", "type", "STRING")),
                Map.of("id", "fld-notes", "attributes", Map.of("name", "notes", "type", "RICH_TEXT"))));
        when(workerApiClient.createPageLayout(eq(TENANT), eq(USER), any())).thenReturn(data("lay-1"));
        when(workerApiClient.createLayoutSection(eq(TENANT), eq(USER), any())).thenReturn(data("sec-1"));
        stub.enqueueJson("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-20250514\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                + "\"name\":\"propose_layout\",\"input\":{\"name\":\"Tickets Layout\",\"layoutType\":\"DETAIL\","
                + "\"sections\":[{\"heading\":\"Key Info\",\"columns\":1,\"style\":\"DEFAULT\","
                + "\"fieldPlacements\":[{\"fieldName\":\"notes\"},{\"fieldName\":\"title\"}]}]}}],"
                + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}");

        service.applyProposal(proposalId, TENANT, USER);

        JsonNode request = stub.requests().getFirst();
        assertThat(request.get("model").asString()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(request.get("system").asString()).startsWith("You are a UI layout designer.");
        JsonNode messages = request.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asString()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asString())
                .startsWith("Generate a DETAIL page layout for the 'Tickets' collection.")
                .contains("- title (STRING)\n- notes (RICH_TEXT)\n");
        JsonNode tools = request.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name").asString()).isEqualTo("propose_layout");
        assertThat(tools.get(0).get("input_schema"))
                .isEqualTo(objectMapper.valueToTree(new ProposeLayoutHandler().inputSchema()));

        verify(workerApiClient).createPageLayout(eq(TENANT), eq(USER),
                argThat(l -> "Tickets Layout".equals(l.get("name")) && "col-1".equals(l.get("collectionId"))));
        verify(workerApiClient).createLayoutSection(eq(TENANT), eq(USER),
                argThat(sec -> "Key Info".equals(sec.get("heading")) && Integer.valueOf(1).equals(sec.get("columns"))));
        InOrder placements = inOrder(workerApiClient);
        placements.verify(workerApiClient).createLayoutField(eq(TENANT), eq(USER),
                argThat(f -> "fld-notes".equals(f.get("fieldId")) && Integer.valueOf(0).equals(f.get("sortOrder"))));
        placements.verify(workerApiClient).createLayoutField(eq(TENANT), eq(USER),
                argThat(f -> "fld-title".equals(f.get("fieldId")) && Integer.valueOf(1).equals(f.get("sortOrder"))));
    }

    private UUID stubCollectionProposal() {
        UUID proposalId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "tickets");
        input.put("displayName", "Tickets");
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
        when(workerApiClient.listMenus(anyString())).thenReturn(List.of());
        return proposalId;
    }

    private static Map<String, Object> data(String id) {
        return Map.of("data", Map.of("id", id));
    }
}
