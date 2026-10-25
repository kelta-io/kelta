/**
 * Telemetry Unit Tests
 *
 * Validates that initTelemetry wires up a tracer with the correct resource
 * attributes (so UI spans land in the same Grafana dashboards as the backend
 * services in the `service.namespace=emf` filter), and that the global error
 * handlers emit spans for unhandled errors and promise rejections.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Capture the resource passed to WebTracerProvider so we can assert on it.
const capturedResources: Array<Record<string, unknown>> = []

vi.mock('@opentelemetry/sdk-trace-web', () => {
  return {
    WebTracerProvider: class {
      constructor(opts: { resource?: { attributes?: Record<string, unknown> } }) {
        if (opts?.resource?.attributes) {
          capturedResources.push(opts.resource.attributes)
        }
      }
      register() {
        /* no-op */
      }
    },
  }
})

vi.mock('@opentelemetry/sdk-trace-base', () => ({
  BatchSpanProcessor: class {},
}))

vi.mock('@opentelemetry/exporter-trace-otlp-http', () => ({
  OTLPTraceExporter: class {
    url: string
    constructor(opts: { url: string }) {
      this.url = opts.url
    }
  },
}))

vi.mock('@opentelemetry/context-zone', () => ({
  ZoneContextManager: class {},
}))

vi.mock('@opentelemetry/instrumentation-fetch', () => ({
  FetchInstrumentation: class {},
}))

vi.mock('@opentelemetry/instrumentation-xml-http-request', () => ({
  XMLHttpRequestInstrumentation: class {},
}))

vi.mock('@opentelemetry/instrumentation', () => ({
  registerInstrumentations: vi.fn(),
}))

vi.mock('@opentelemetry/core', () => ({
  W3CTraceContextPropagator: class {},
}))

vi.mock('@opentelemetry/resources', () => ({
  resourceFromAttributes: (attributes: Record<string, unknown>) => ({ attributes }),
}))

const startSpanMock = vi.fn()
const recordExceptionMock = vi.fn()
const setStatusMock = vi.fn()
const endMock = vi.fn()

vi.mock('@opentelemetry/api', () => {
  const span = {
    recordException: recordExceptionMock,
    setStatus: setStatusMock,
    end: endMock,
  }
  startSpanMock.mockReturnValue(span)
  return {
    SpanStatusCode: { ERROR: 2, OK: 1, UNSET: 0 },
    trace: {
      getTracer: () => ({
        startSpan: startSpanMock,
      }),
    },
  }
})

describe('telemetry', () => {
  beforeEach(() => {
    capturedResources.length = 0
    startSpanMock.mockClear()
    recordExceptionMock.mockClear()
    setStatusMock.mockClear()
    endMock.mockClear()
  })

  afterEach(() => {
    vi.resetModules()
  })

  it('initializes resource attributes aligned with backend services', async () => {
    const { initTelemetry } = await import('./index')
    initTelemetry()

    expect(capturedResources.length).toBeGreaterThan(0)
    const attrs = capturedResources[0]
    expect(attrs['service.name']).toBe('kelta-ui')
    expect(attrs['service.namespace']).toBe('emf')
    expect(attrs['deployment.environment']).toBe('production')
    expect(attrs['session.id']).toBeTruthy()
  })

  it('exposes a tracer after initialization', async () => {
    const { initTelemetry, getTracer } = await import('./index')
    expect(getTracer()).toBeNull()
    initTelemetry()
    expect(getTracer()).not.toBeNull()
  })

  it('records a span when an unhandled error occurs', async () => {
    const { initTelemetry } = await import('./index')
    initTelemetry()

    const err = new Error('boom')
    window.dispatchEvent(
      new ErrorEvent('error', {
        error: err,
        message: 'boom',
        filename: 'app.js',
        lineno: 10,
        colno: 5,
      })
    )

    expect(startSpanMock).toHaveBeenCalledWith(
      'browser.error',
      expect.objectContaining({
        attributes: expect.objectContaining({
          'error.filename': 'app.js',
          'error.lineno': '10',
          'error.colno': '5',
        }),
      })
    )
    expect(recordExceptionMock).toHaveBeenCalledWith(err)
    expect(setStatusMock).toHaveBeenCalledWith(expect.objectContaining({ code: 2 }))
    expect(endMock).toHaveBeenCalled()
  })

  it('records a span when an unhandled promise rejection occurs', async () => {
    const { initTelemetry } = await import('./index')
    initTelemetry()

    const reason = new Error('async failure')
    // PromiseRejectionEvent is a real DOM type in jsdom
    const event = new Event('unhandledrejection') as PromiseRejectionEvent
    Object.defineProperty(event, 'reason', { value: reason })
    Object.defineProperty(event, 'promise', { value: Promise.resolve() })
    window.dispatchEvent(event)

    expect(startSpanMock).toHaveBeenCalledWith('browser.unhandled_rejection', expect.any(Object))
    expect(recordExceptionMock).toHaveBeenCalledWith(reason)
  })
})
