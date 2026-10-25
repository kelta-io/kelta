import { WebTracerProvider } from '@opentelemetry/sdk-trace-web'
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { ZoneContextManager } from '@opentelemetry/context-zone'
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch'
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions'
import { W3CTraceContextPropagator } from '@opentelemetry/core'
import { registerInstrumentations } from '@opentelemetry/instrumentation'
import { SpanStatusCode, trace, type Tracer } from '@opentelemetry/api'

// Resource attribute keys that are not in the stable semantic-conventions export
// but are well-known OpenTelemetry resource conventions.
const ATTR_SERVICE_NAMESPACE = 'service.namespace'
const ATTR_DEPLOYMENT_ENVIRONMENT = 'deployment.environment'
const ATTR_BROWSER_USER_AGENT = 'browser.user_agent'
const ATTR_BROWSER_LANGUAGE = 'browser.language'
const ATTR_SESSION_ID = 'session.id'

const TRACER_NAME = 'kelta-ui'

let tracer: Tracer | null = null

/**
 * Generate a random session id (RFC 4122 v4 if crypto.randomUUID is available,
 * otherwise a best-effort random string). Used as a resource attribute so every
 * span emitted from a given page load can be correlated in Tempo/Loki.
 */
function generateSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `sess-${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`
}

/**
 * Initialize OpenTelemetry for the browser.
 *
 * Sends trace spans via OTLP HTTP to the gateway, which proxies them to the
 * alloy-collector → Tempo pipeline in the homelab LGTM stack.
 *
 * Auto-instruments fetch and XMLHttpRequest to propagate W3C trace-context
 * headers across the browser → gateway → backend hop so a single trace spans
 * the whole request lifecycle.
 *
 * Resource attributes are aligned with the backend services
 * (`service.namespace=emf`, `deployment.environment=production`) so UI spans
 * show up in the same Grafana dashboards that filter on those labels.
 */
export function initTelemetry(): void {
  const env = (import.meta as unknown as Record<string, Record<string, string>>).env

  // Use the gateway origin (VITE_API_BASE_URL) for OTEL traces, not window.location.origin.
  // The UI is served from kelta.io but the OTEL route is on the gateway.
  const gatewayOrigin = env?.VITE_API_BASE_URL || window.location.origin
  const otlpEndpoint = env?.VITE_OTEL_ENDPOINT || `${gatewayOrigin}/otel/v1/traces`

  const serviceName = env?.VITE_OTEL_SERVICE_NAME || 'kelta-ui'
  const serviceNamespace = env?.VITE_OTEL_SERVICE_NAMESPACE || 'emf'
  const deploymentEnvironment = env?.VITE_OTEL_DEPLOYMENT_ENVIRONMENT || 'production'
  const serviceVersion = env?.VITE_APP_VERSION || 'unknown'

  const resource = resourceFromAttributes({
    [ATTR_SERVICE_NAME]: serviceName,
    [ATTR_SERVICE_NAMESPACE]: serviceNamespace,
    [ATTR_SERVICE_VERSION]: serviceVersion,
    [ATTR_DEPLOYMENT_ENVIRONMENT]: deploymentEnvironment,
    [ATTR_SESSION_ID]: generateSessionId(),
    [ATTR_BROWSER_USER_AGENT]: typeof navigator !== 'undefined' ? navigator.userAgent : '',
    [ATTR_BROWSER_LANGUAGE]: typeof navigator !== 'undefined' ? navigator.language : '',
  })

  const exporter = new OTLPTraceExporter({
    url: otlpEndpoint,
  })

  const provider = new WebTracerProvider({
    resource,
    spanProcessors: [new BatchSpanProcessor(exporter)],
  })

  // Use W3C TraceContext only (traceparent/tracestate headers).
  // B3 propagation was removed because the b3 header isn't needed for
  // browser→gateway communication and causes CORS preflight failures.
  // Backend services use b3multi between themselves via the OTEL Java agent.
  provider.register({
    contextManager: new ZoneContextManager(),
    propagator: new W3CTraceContextPropagator(),
  })

  // Propagate trace context headers to both the UI origin and the gateway origin
  const corsUrls: RegExp[] = [
    new RegExp(window.location.origin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  ]
  if (gatewayOrigin && gatewayOrigin !== window.location.origin) {
    corsUrls.push(new RegExp(gatewayOrigin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }

  registerInstrumentations({
    instrumentations: [
      new FetchInstrumentation({
        propagateTraceHeaderCorsUrls: corsUrls,
      }),
      new XMLHttpRequestInstrumentation({
        propagateTraceHeaderCorsUrls: corsUrls,
      }),
    ],
  })

  tracer = trace.getTracer(TRACER_NAME, serviceVersion)

  registerGlobalErrorHandlers()
}

/**
 * Returns the shared browser tracer. Returns `null` until {@link initTelemetry}
 * has been called. Callers can use this to create custom spans for user
 * interactions, navigations, or workflow steps.
 */
export function getTracer(): Tracer | null {
  return tracer
}

/**
 * Emit a one-off span describing an unhandled browser error or promise
 * rejection so it is visible in Tempo alongside regular request spans.
 */
function recordBrowserError(
  name: string,
  error: unknown,
  extraAttrs: Record<string, string> = {}
): void {
  if (!tracer) return

  const span = tracer.startSpan(name, {
    attributes: {
      'browser.url': window.location.href,
      'browser.route': window.location.pathname,
      ...extraAttrs,
    },
  })
  try {
    if (error instanceof Error) {
      span.recordException(error)
      span.setStatus({ code: SpanStatusCode.ERROR, message: error.message })
    } else {
      span.recordException({
        name: 'BrowserError',
        message: typeof error === 'string' ? error : JSON.stringify(error),
      })
      span.setStatus({ code: SpanStatusCode.ERROR })
    }
  } finally {
    span.end()
  }
}

function registerGlobalErrorHandlers(): void {
  if (typeof window === 'undefined') return

  window.addEventListener('error', (event: ErrorEvent) => {
    recordBrowserError('browser.error', event.error ?? event.message, {
      'error.filename': event.filename ?? '',
      'error.lineno': String(event.lineno ?? ''),
      'error.colno': String(event.colno ?? ''),
    })
  })

  window.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
    recordBrowserError('browser.unhandled_rejection', event.reason)
  })
}
