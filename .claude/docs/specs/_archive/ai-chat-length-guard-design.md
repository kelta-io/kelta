# AI chat conversation-length guard

2026-07-27

## Problem

`ChatService.buildMessageHistory` reloads a conversation's *entire* message
history from the DB and resends it to Claude on every turn, with no cap. A
long-lived conversation thread (same `conversationId`, resumed over many
sessions) grows unbounded until a turn's input exceeds the model's context
window. At that point Anthropic's API rejects the request outright (a clean
400 `invalid_request_error` — the model never runs, never sees the request).

Today that failure surfaces cleanly on the streaming path (`chatStream`
already catches all exceptions and emits an `error` SSE event) but not on the
non-streaming path (`chat()` has no error handling at all, so it would 500
raw). Either way, once a thread crosses the line, it's dead — no recovery, no
guidance to the user.

This is not a rare pathological case: tool results (schema dumps, sampled
records) accumulate across a session on top of normal chat text, and a single
productive builder session can plausibly approach the limit well before
"hundreds of messages."

## Non-goals

Full automatic compaction (summarize-and-continue *within* the same thread,
threading the summary back into every future turn via the system prompt) was
considered and explicitly deferred — real cost/complexity (new `Conversation`
columns, a compaction marker, rewritten history-loading), open-ended benefit.
This spec covers a smaller, contained stopgap instead: detect the danger,
stop before it becomes a hard failure, and hand the user a summary to carry
into a fresh conversation manually. No schema migration, no change to how any
other turn is built or threaded.

## Design

**Threshold.** Default model is `claude-sonnet-5` (200,000-token context
window); `default-max-tokens` is 32,768 and Anthropic reserves that full
budget against the context window on every call, so the real ceiling for
input is ~167,000 tokens. Guard threshold: **160,000 tokens** — close enough
to avoid tripping while there's still real room (per-turn growth is normally
small; tool results are already size-capped), far enough to leave margin
before the actual wall.

**Signal.** No new token-counting logic needed. `ChatMessage.tokensInput()`
already stores the exact input-token count Anthropic reported for that turn —
i.e., the true size of history + system prompt actually sent. New repository
method `ChatMessageRepository.findMostRecentAssistantTokensInput(conversationId,
tenantId)` returns the most recent assistant message's `tokensInput` (0 if
none). `ChatService.isConversationTooLarge(...)` compares it to the
threshold.

**Check placement.** In both `chat()` and `chatStream()`, immediately after
`buildMessageHistory` and *before* `SystemPromptService.buildSystemPrompt`
(which makes a live schema-fetch call to the worker — skip it entirely on the
guard-trip path, nothing to save it for). Cost when it doesn't trip: one
`int` comparison against data already loaded for other reasons — no new I/O,
no new network call. Cost is zero on the common path.

**On trip:** one extra non-streaming Anthropic call, prompted specifically
for a structured handoff summary (not generic narrative):

> Summarize this conversation for someone continuing it in a new session.
> Cover, briefly: (1) what collections/fields/objects were discussed,
> created, or proposed, (2) decisions made and why, (3) any stated
> preferences or constraints ("don't do X", "always Y"), (4) open questions
> or unfinished next steps. Be concise — this is a handoff note, not a
> transcript.

The reply sent to the user is `<summary>` + a fixed redirect line telling
them to start a new conversation and paste the summary in if they want
continuity. This is persisted as a normal assistant `ChatMessage` (no new
columns, no special role) and returned/streamed exactly like any other
assistant turn — `chat()` returns it in the normal response shape (plus a
`conversationTooLarge: true` flag); `chatStream()` emits it as a single
`delta` event (matching `StreamAssembler`'s existing event shape,
`{"text": ...}`) followed by `done` (also flagged).

**Failure inside the guard itself.** If the summarization call errors, catch
it and fall back to the plain redirect line with no summary — the guard's
core job (never let the turn hard-fail) holds regardless of whether the
extra call succeeds. No new tokens are recorded to `TokenTrackingService` in
that case.

**Separately: non-streaming error handling.** `chat()` gets the same
try/catch shape `chatStream()` already has, wrapping the tool-loop call and
translating any Anthropic API exception into a clean
`{"error": {"code": "AI_PROVIDER_ERROR", "message": ...}}` result instead of
an unhandled 500. Small, independent, closes an existing asymmetry between
the two entry points.

## Testing

- `ChatMessageRepositoryTest`: new test for
  `findMostRecentAssistantTokensInput` (found row / no rows).
- `ChatServiceTest` (new — none existed before): guard trips above threshold
  and skips the tool loop entirely; guard does not trip at/under threshold;
  summarization failure falls back to the plain redirect with zero recorded
  tokens; non-streaming `chat()` returns a clean error map instead of
  throwing on an Anthropic exception.

## Out of scope / later

- Full automatic compaction (see Non-goals).
- Surfacing a live "context used" indicator in the AiChat UI.
- Governed agent runs (`AgentRuntimeService`) are already bounded per-run
  (8 iterations / 100k tokens) and are not a growing conversation — untouched
  here.
