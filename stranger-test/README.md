# Stranger test

The K-3 quickstart acceptance bar calls for a recorded "stranger test": one person who
has never seen this repo, given only the README quickstart section, attempting the
five-minute quickstart end to end (clone → `docker compose up` → open the UI → create a
collection) in a single, unaided attempt — transcript (terminal output + notes on where
they got stuck, if anywhere) checked into this directory.

**Status.**

- **Human run:** pending. A human run needs a person who is unfamiliar with the codebase, and an
  automated task cannot produce one. Do not fabricate a transcript.
- **Agent run** (D-036 option B, [PROTOCOL.md § Agent participant](PROTOCOL.md#agent-participant)):
  2026-10-20, [runs/2026-10-20-agent.md](runs/2026-10-20-agent.md). The agent reached a created
  collection in 3 min 44 s, in one attempt with no intervention. It did this through the API, not
  the UI, because the host's ports 5173 and 8080 were taken, so the run is **not a pass** against
  the pass criteria. The six friction points in the notes are follow-ups for the README.

How to run it — who qualifies, the handoff, observer rules, recording, pass criteria and
redaction — is in [PROTOCOL.md](PROTOCOL.md).

Once run, add the transcript (or a link to a recording) alongside this file and update
this status.
