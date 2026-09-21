---
title: Observability setup
description: What the services emit — OpenTelemetry traces, OTLP metrics, structured logs — how to point them at your backend, the metrics worth alerting on, and the console's monitoring integration.
section: deploy
order: 60
---

## Instrumentation

Every Java service is instrumented with OpenTelemetry (the Java agent is bundled in the images and the Spring
Boot starter is on the classpath) and logs JSON lines (Logstash encoder). Spans carry `kelta.tenant.id`,
`kelta.user.id` and `kelta.correlation.id`; the same correlation id is the `requestId` in API responses.

## Export

| Signal | Variable | Notes |
|---|---|---|
| Traces | `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | OTLP/HTTP, e.g. `http://otel-collector:4318/v1/traces`; W3C propagation |
| Metrics | `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | OTLP/HTTP `…/v1/metrics`; also scrapeable at `/actuator/prometheus` |
| Sampling | `OTEL_TRACES_SAMPLER_ARG` | ratio, default `1.0` |
| Logs | stdout | ship with your log agent; JSON fields include tenant, user, request id |

Every replica must report a distinct `service.instance.id` — the services derive it from `HOSTNAME`, the pod
name in Kubernetes, so leave it unset.

Any OTLP-compatible backend works: a Grafana stack (Tempo, Loki, Mimir behind an Alloy or OpenTelemetry
Collector), Jaeger, or a hosted APM. The compose `observability` profile runs Jaeger + OpenSearch locally.

## Metrics worth watching

| Metric | Meaning |
|---|---|
| `kelta.gateway.requests`, `kelta.gateway.requests.active`, `kelta.gateway.errors` | traffic and errors at the edge |
| `kelta.gateway.auth.failures`, `kelta.gateway.authz.denied` | credential and permission failures |
| `kelta.gateway.ratelimit.exceeded`, `kelta.gateway.ratelimit.remaining.ratio` | tenants near their budget |
| `kelta.gateway.tenant.resolution` | tenant lookup latency |
| `kelta_worker_request_total`, `kelta_worker_request_duration_seconds`, `kelta_worker_error_total` | worker traffic |
| `kelta_flow_execution_total`, `kelta_flow_execution_active`, `kelta_flow_execution_duration_seconds`, `kelta_flow_error_total`, `kelta_flow_step_*` | automation health |
| `kelta.worker.collections.active`, `kelta.worker.collection.count`, `kelta.worker.collections.initializing` | registry state (useful for autoscaling) |
| `kelta.worker.tenant.concurrency.rejected` | a tenant hitting its concurrency guard |
| `kelta_worker_flowlog_purged`, `kelta_worker_analytics_purged` | retention sweeps |
| MCP: session and tool-call metrics | agent usage |

## Console integration

Setup → Platform → Monitoring reads logs, traces and metrics from your backends when the worker is given
`LOKI_URL`, `TEMPO_URL` and `MIMIR_URL`; a request-log entry then deep-links to its trace. A tenant can
additionally receive its own spans through the per-tenant OTLP target
([Audit trails and tenant monitoring](/docs/platform/audit-and-monitoring/)).

## Frontend telemetry

The UI emits browser traces to `VITE_OTEL_ENDPOINT` when set at build time.
