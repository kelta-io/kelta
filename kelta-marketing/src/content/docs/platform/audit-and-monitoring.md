---
title: Audit trails and tenant monitoring
description: Where to look when something happened — the three audit logs, the monitoring pages, request tracing, retention and per-tenant OTLP export.
section: platform
order: 40
---

## Audit logs

Three read-only collections — the **security audit log**, the **setup audit trail** and **login history** —
are described on [Audit logs](/docs/security/audit-log/). Data changes are on
[Field history and record versioning](/docs/data-model/record-history/).

## Monitoring pages

Setup → Platform → Monitoring (`VIEW_SETUP`):

| Page | Shows |
|---|---|
| Overview | request volume, error rate, P50/P95/P99 latency |
| Requests | the request log — method, path, status, duration, user, `requestId`; a request's detail page links to its distributed trace |
| Logs | structured application log lines for the tenant |
| Errors | grouped failures |
| Performance | latency per endpoint |
| Activity | per-user activity |
| Health | rule-based configuration checks (a scheduled flow with no job, a layout pointing at a missing field, …) |
| Settings | retention for request logs and traces |

Flow runs and their step logs are under Setup → Automation → Flows ([Runs](/docs/automation/flow-runs/)).

## Correlation

Every API response carries `meta.requestId`; audit rows carry `correlationId`; spans are tagged with the tenant,
user and correlation id. Paste a `requestId` into the request log to jump from a user's report to the trace.

## Retention

Request logs and traces are pruned per the tenant's observability settings
(`GET|PUT /api/admin/observability-settings`). Audit logs are kept indefinitely.

## Per-tenant OTLP export

A tenant can receive its own copy of the platform's spans in its own observability stack:

```http
PUT /api/admin/observability/otlp-target
{ "endpoint": "https://otlp.acme.example/v1/traces", "headers": { "Authorization": "Bearer …" } }
```

(`GET` reads, `DELETE` removes; `VIEW_SETUP`.) Spans tagged with the tenant are forwarded additively — the
platform's own pipeline is unaffected — and re-pointing the endpoint takes effect on the next span without a
restart.

## Platform-level observability

Prometheus metrics, OTLP tracing and log shipping for the deployment as a whole are configured by the operator —
see [Observability setup](/docs/deploy/observability/).
