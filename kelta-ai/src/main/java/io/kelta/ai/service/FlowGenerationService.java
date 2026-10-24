package io.kelta.ai.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import io.kelta.ai.config.AiConfigProperties;
import io.kelta.ai.repository.AiConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates flow definitions from plain-English descriptions using Claude.
 *
 * <p>Unlike the interactive chat flow, this is a one-shot call: no tool loop,
 * no conversation history. The system prompt embeds the tenant's collection
 * context so Claude can reference real collection and field names.
 *
 * @since 1.0.0
 */
@Service
public class FlowGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FlowGenerationService.class);

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```",
            Pattern.DOTALL);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a flow definition generator for the Kelta platform.

            Given a plain-English description, output a valid Kelta flow definition as JSON.
            Respond with ONLY the JSON object — no explanation, no markdown, no prose.

            ## Flow Definition Format

            ```json
            {
              "Comment": "optional description",
              "StartAt": "StateName",
              "States": {
                "StateName": {
                  "Type": "Task|Choice|Wait|Pass|Parallel|Map|Succeed|Fail",
                  "Next": "NextStateName",
                  "End": true
                }
              }
            }
            ```

            Every state must have either `"Next": "StateName"` or `"End": true`.
            The last state in a happy path must have `"End": true`.
            `StartAt` must match an existing state key in `States`.

            ## Task Step
            ```json
            {
              "Type": "Task",
              "Resource": "RESOURCE_KEY",
              "Parameters": { },
              "Next": "NextState"
            }
            ```

            Available resources:
            - Data: CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, QUERY_RECORDS, FIELD_UPDATE, SQL_QUERY
            - Communication: EMAIL_ALERT, SEND_NOTIFICATION, OUTBOUND_MESSAGE
            - Integration: CALL_API, HTTP_CALLOUT, PUBLISH_EVENT, INVOKE_SCRIPT
            - Utility: LOG_MESSAGE, TRIGGER_FLOW (start another flow), DECISION (prefer a Choice state)

            Never use DELAY (use a Wait state) or CREATE_TASK (use CREATE_RECORD into the task \
            collection): the platform rejects both at save.

            ## Choice Step (branching)
            ```json
            {
              "Type": "Choice",
              "Choices": [
                {
                  "Variable": "$.fieldName",
                  "StringEquals": "value",
                  "Next": "BranchStateName"
                }
              ],
              "Default": "DefaultStateName"
            }
            ```
            Choice steps do NOT have a `Next` field.

            ## Wait Step
            ```json
            { "Type": "Wait", "Seconds": 60, "Next": "NextState" }
            ```

            ## Succeed / Fail
            ```json
            { "Type": "Succeed" }
            { "Type": "Fail", "Error": "ErrorName", "Cause": "reason" }
            ```

            ## Guidelines
            - Use descriptive PascalCase names for states (e.g. "SendWelcomeEmail", "CheckStatus")
            - For record operations, set `Parameters.collectionName` to the relevant collection
            - For email steps, set `Parameters.to`, `Parameters.subject`, `Parameters.body`
            - Keep flows simple — one happy path with an optional error branch

            ## Tenant Collections
            %s
            """;

    private final AnthropicClient client;
    private final AiConfigProperties config;
    private final AiConfigRepository aiConfigRepository;
    private final WorkerApiClient workerApiClient;

    public FlowGenerationService(AnthropicClient client, AiConfigProperties config,
                                  AiConfigRepository aiConfigRepository,
                                  WorkerApiClient workerApiClient) {
        this.client = client;
        this.config = config;
        this.aiConfigRepository = aiConfigRepository;
        this.workerApiClient = workerApiClient;
    }

    /**
     * Generates a flow definition from a plain-English description.
     *
     * @param tenantId    the tenant ID (for collection context and model resolution)
     * @param description what the flow should do
     * @return the parsed flow definition as a Map
     * @throws IllegalArgumentException if Claude's response cannot be parsed as JSON
     */
    public Map<String, Object> generateFlow(String tenantId, String description) {
        String collectionContext = buildCollectionContext(tenantId);
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(collectionContext);

        String model = resolveModel(tenantId);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4096L)
                .system(systemPrompt)
                .addMessage(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(List.of(ContentBlockParam.ofText(
                                TextBlockParam.builder().text(description).build())))
                        .build())
                .build();

        log.info("Generating flow for tenant {} — description: {}", tenantId,
                description.length() > 100 ? description.substring(0, 100) + "..." : description);

        var message = client.messages().create(params);

        String raw = message.content().stream()
                .filter(b -> b.isText())
                .map(b -> b.asText().text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Empty response from Claude"));

        return parseDefinition(raw);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDefinition(String raw) {
        String json = raw.trim();

        // Strip markdown code fences if Claude wrapped the output
        Matcher matcher = JSON_BLOCK.matcher(json);
        if (matcher.find()) {
            json = matcher.group(1).trim();
        }

        try {
            Object parsed = new ObjectMapper().readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map && map.containsKey("States")) {
                return (Map<String, Object>) parsed;
            }
            throw new IllegalArgumentException("Response is not a valid flow definition (missing 'States')");
        } catch (Exception e) {
            log.error("Failed to parse Claude flow generation response: {}", raw);
            throw new IllegalArgumentException("Could not parse flow definition from AI response: " + e.getMessage());
        }
    }

    private String buildCollectionContext(String tenantId) {
        try {
            List<Map<String, Object>> collections = workerApiClient.listCollections(tenantId);
            if (collections.isEmpty()) {
                return "No collections defined yet in this tenant.";
            }
            StringBuilder sb = new StringBuilder("Collections available in this tenant:\n");
            for (Map<String, Object> col : collections) {
                String name = (String) col.get("name");
                String displayName = (String) col.getOrDefault("displayName", name);
                sb.append("- **").append(displayName).append("** (`").append(name).append("`)\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Could not fetch collections for tenant {} during flow generation: {}", tenantId, e.getMessage());
            return "Collection list unavailable.";
        }
    }

    private String resolveModel(String tenantId) {
        return aiConfigRepository.getConfig(tenantId, "anthropic.model")
                .or(() -> aiConfigRepository.getConfig("0", "anthropic.model"))
                .orElse(config.anthropic().defaultModel());
    }
}
