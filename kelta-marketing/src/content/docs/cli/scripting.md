---
title: Output contract, exit codes and kelta api
description: The rules that make the CLI safe to script — output formats and flattening, stdout vs stderr, the error envelope and exit codes, destructive-command confirmation, the list query grammar, and the raw API escape hatch.
section: cli
order: 50
---

## Output

- `--output table|json|yaml|csv|ndjson`. On a TTY the default is a table; when piped it is JSON. **Never parse
  the table** — it has no stability guarantee.
- JSON:API resources are **flattened** to `{ "id", ...attributes, "<toOneRelationship>": "<id>" }`. `--raw`
  gives the unflattened envelope with `links`, `meta` and `relationships`.
- `--quiet` prints ids only, one per line.
- **stdout carries data only**; progress and diagnostics always go to stderr. No colour is ever emitted.

## Errors and exit codes

On failure the CLI prints one JSON line on stderr:

```json
{"error":{"code":"VALIDATION_FAILED","status":400,"detail":"…","source":{"pointer":"/data/attributes/name"},"meta":{"requestId":"…"}},"errors":[…]}
```

`error` is the first entry flattened for convenience; `errors` is the full JSON:API array. Branch on `code`.

| Exit | Meaning |
|---|---|
| `0` | success |
| `1` | API error (4xx/5xx other than the below) |
| `2` | usage error, or a destructive command without `--yes` off-TTY (`CONFIRMATION_REQUIRED`) |
| `3` | authentication required or failed |
| `4` | not found |
| `5` | conflict or rate limit |

## Destructive commands

Anything that deletes, removes, resets, bulk-writes — and every non-GET `kelta api` call — prompts on a TTY and
requires `--yes` otherwise. `kelta manifest` marks these `"dangerous": true`.

## Query grammar for list commands

```bash
kelta records list invoices \
  --filter status=open --filter amount.gte=100 \   # field[.op]=value, repeatable, ANDed
  --sort -createdAt,number \
  --fields number,amount --include customer \
  --page 1 --size 200                              # or --all (client cap 10 000 rows)
```

Operators: `eq neq gt gte lt lte isnull contains starts ends icontains istarts iends ieq in` (`any` = `in`).

## The escape hatch: `kelta api`

Any endpoint, with the profile's auth and tenant prefix applied and the response returned verbatim:

```bash
kelta api GET '/api/collections?page[size]=5'
kelta api POST /api/admin/domains --data '{"domain":"portal.acme.example"}' --yes
kelta api PATCH /api/invoices/<id> --data @patch.json --header 'If-Match: "…"' --yes
```

`--data` takes inline JSON, `@file` or `-` for stdin; `--header Name:value` is repeatable. Non-2xx responses
surface the full error envelope and exit per the table above.

## Gotchas

- Flow input is wrapped for you: `--input '{"k":"v"}'` → the flow reads `$.input.k`.
- Validation-rule formulas are **error conditions**: `TRUE` rejects.
- A just-created collection can `404` on another pod for a moment; the CLI retries name lookups, but scripts
  that chain a create with an immediate write should tolerate one retry.
- `--size` is clamped at 200 server-side; the CLI rejects larger values up front.
