# kelta-ai

AI assistant service for the Kelta platform. Wraps the Anthropic Claude API for schema-aware, tenant-scoped AI interactions with SSE streaming support.

## Package Layout

```
io.kelta.ai/
  config/          ← AiConfigProperties (record), AnthropicConfig, WebConfig
  controller/      ← ChatController (SSE), ChatHistoryController, ProposalController, AiConfigController, AiUsageController
  filter/          ← TenantContextFilter, TokenLimitFilter
  model/           ← Records: Conversation, ChatMessage, AiProposal, AgentDefinition
  repository/      ← JdbcTemplate repositories (NOT JPA): ConversationRepository, ChatMessageRepository, AiConfigRepository, TokenUsageRepository, AgentDefinitionRepository
  controller/      ← ChatController (SSE), …, AgentController (CRUD + POST /{id}/run)
  service/         ← AnthropicService, ChatService, ProposalService, SystemPromptService, TokenTrackingService, WorkerApiClient, AgentService
  service/agent/   ← Governed agent runtime: AgentRuntimeService (bounded tool-use loop), AgentModelClient (SDK seam) + AnthropicAgentModelClient
```

> **Persistence note:** repositories here are `@Repository` classes using `JdbcTemplate` with hand-written SQL (records, not JPA), matching the platform-wide rule. Tenant isolation is by explicit `WHERE tenant_id = ?` on every query (kelta-ai connects as the table owner, so RLS is defense-in-depth, not the boundary).

## Key Patterns

### Models
All models are Java records with static factory methods:
```java
public record Conversation(UUID id, String tenantId, String userId, String title, Instant createdAt, Instant updatedAt) {
    public static Conversation create(String tenantId, String userId, String title) { ... }
}
```

### SSE Streaming
`ChatController` returns `SseEmitter` for streaming Claude responses:
```java
@PostMapping("/stream")
public SseEmitter chatStream(@RequestHeader("X-Tenant-ID") String tenantId, ...) {
    SseEmitter emitter = new SseEmitter(config.sseTimeoutMs());
    // Non-blocking: returns immediately, streams data to client
}
```
**Reference**: `ChatController.java`

### Anthropic API Integration
- `AnthropicService` wraps the `anthropic-java` SDK
- Model configurable via `AiConfigProperties` — see `kelta-platform/pom.xml` for the current default model ID
- Context built via `SystemPromptService` using schema from `WorkerApiClient`

### Conversation-length guard
`ChatService.buildMessageHistory` resends a conversation's *entire* message history every turn — unbounded, since a thread can be resumed indefinitely. Before that (and before the schema-fetching `SystemPromptService.buildSystemPrompt` call), both `chat()` and `chatStream()` check `isConversationTooLarge` — the most recent assistant message's recorded `tokensInput` against `MAX_CONVERSATION_INPUT_TOKENS` (160k; claude-sonnet-5's 200k window minus the reserved max-tokens budget, with margin). If tripped, one extra non-streaming call asks Claude for a structured handoff summary, which is returned/streamed to the user with a message to start a new conversation — no schema change, no automatic compaction of the thread itself. See `.claude/docs/specs/_archive/ai-chat-length-guard-design.md`.

### Config Properties
```java
@ConfigurationProperties(prefix = "kelta.ai")
public record AiConfigProperties(
    AnthropicProperties anthropic,  // apiKey, defaultModel, defaultMaxTokens, defaultTemperature
    String workerServiceUrl,
    long sseTimeoutMs
) {}
```

### AI settings (`AiConfigController`, `/api/ai/config`)
- `GET` needs `API_ACCESS`. `PUT` needs `MANAGE_TENANTS` — enforced by the **gateway**
  (`RouteAuthorizationFilter`, `static-ai` route, on the decoded path), since this service holds no
  permission data. Every key is a platform control: `TokenLimitFilter` enforces `aiTokensPerMonth`
  and `aiEnabled` from these `ai_config` rows (not the worker's governor-limit values), and the
  model and max tokens decide request cost.
- A `PUT` accepts only `CONFIG_KEYS` (`model`, `maxTokens`, `temperature`, `aiTokensPerMonth`,
  `aiEnabled`), each validated (`normalize`) before anything is written; an unknown key or bad value
  is `400 {"errors":[…]}` with `code: VALIDATION_FAILED` and `source.pointer` naming the key, and
  nothing is stored. A new setting needs an entry in `CONFIG_KEYS`, a `normalize` case and a test.

### Error Handling
- No retry/backoff around Anthropic calls — a 429/5xx/context-length error surfaces to the caller on that turn
- `ChatService.chat()` and `chatStream()` both catch any exception from the tool loop and return/emit a clean `{"code": "AI_PROVIDER_ERROR", "message": ...}` shape instead of letting it propagate as a raw 500 — `chatStream()` as an `error` SSE event, `chat()` as an `error` key in the response map
- No custom exception classes — uses standard Spring exceptions

## When Adding a New Endpoint

1. Add method to existing controller or create new `@RestController` in `controller/`
2. Accept `@RequestHeader("X-Tenant-ID")` and `@RequestHeader("X-User-Id")` for tenant isolation
3. Delegate to service in `service/`
4. Add test in `src/test/java/io/kelta/ai/controller/`

**Reference**: `ChatController.java` + `ChatControllerTest.java`

## Reference Implementations

| Pattern | File |
|---------|------|
| SSE streaming | `controller/ChatController.java` |
| REST controller | `controller/ChatHistoryController.java` |
| Anthropic wrapper | `service/AnthropicService.java` |
| Conversation mgmt | `service/ChatService.java` |
| Worker HTTP client | `service/WorkerApiClient.java` |
| Controller test | `controller/ChatControllerTest.java` |
| Service test | `service/AnthropicServiceTest.java` |

## Running Tests

```bash
mvn test -f kelta-ai/pom.xml                                           # All tests
mvn test -f kelta-ai/pom.xml -Dtest=ChatControllerTest                 # Single class
mvn test -f kelta-ai/pom.xml -Dtest=ChatControllerTest#sendsChatMessage  # Single method
mvn test -f kelta-ai/pom.xml -Dtest="*Service*"                        # Pattern match
```

## Test Fixtures

Use `TestFixtures.java` in `src/test/java/io/kelta/ai/` for pre-built `Conversation`, `ChatMessage`, `AiProposal`, and `AiConfigProperties` instances. Prefer these over hand-constructing records so tests stay terse.
