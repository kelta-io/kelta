import '@testing-library/jest-dom'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, beforeAll, afterAll, vi } from 'vitest'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { mockAxios } from './src/test/testUtils'

// ─── Async utility timeout ──────────────────────────────────────────────────
// Testing Library's default asyncUtilTimeout is 1000ms, inherited by every waitFor
// and findBy* in this suite. kelta-web sets 5000 in its own setup and its suite is
// green in CI; this one is ~4x larger, renders whole routed pages, and had no
// configure() at all.
//
// Evidence this matters: the first CI run of this suite failed 11 tests that pass
// 223/223 locally, and the DOM dumped at failure was the Suspense fallback
// (data-testid="page-loader", aria-busy, "Loading...") — the lazy route chunk had
// simply not resolved yet, i.e. the wait expired rather than the element being
// absent. The job shares the k8s-runner with concurrent GraalVM native image builds
// (concerns.md -> "Frontend suite starves under concurrent image builds").
//
// Caveat, stated honestly: this was NOT reproduced locally. Running the three
// failing files under a 1-core limit passed both with and without this setting —
// three files generate none of the 223-file worker contention that the runner sees.
// CI is the only environment that reproduces it, so CI is the verification.
//
// 10s stays well inside the 30s testTimeout in vitest.config.ts, and a genuinely
// missing element still fails — it just gets a budget that survives contention.
configure({ asyncUtilTimeout: 10000 })

// ─── Global Axios mock ──────────────────────────────────────────────────────
// All API calls flow through KeltaClient's Axios instance.
// Mock `axios.create()` so it returns the shared `mockAxios` instance from testUtils.
// This ensures every test that renders components using `useApi()` gets mock responses.
vi.mock('axios', async () => {
  const actual = await vi.importActual('axios')
  return {
    ...actual,
    default: {
      ...((actual as Record<string, unknown>).default as Record<string, unknown>),
      create: vi.fn(() => mockAxios),
      isAxiosError: (error: unknown) =>
        error !== null &&
        error !== undefined &&
        typeof error === 'object' &&
        'isAxiosError' in error &&
        (error as { isAxiosError: boolean }).isAxiosError === true,
    },
  }
})

// Cleanup after each test
afterEach(() => {
  cleanup()
})

// Mock window.matchMedia for responsive tests
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(), // deprecated
    removeListener: vi.fn(), // deprecated
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})

// Mock ResizeObserver — a real class so `new ResizeObserver(...)` works (dnd-kit's canvas relies on it).
class ResizeObserverMock {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
global.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver
window.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver

// Mock IntersectionObserver (a real class so `new IntersectionObserver(...)` works)
class IntersectionObserverMock {
  root = null
  rootMargin = ''
  thresholds = []
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}
global.IntersectionObserver = IntersectionObserverMock as unknown as typeof IntersectionObserver
window.IntersectionObserver = IntersectionObserverMock as unknown as typeof IntersectionObserver

// Mock scrollTo
window.scrollTo = vi.fn()

// Mock localStorage
const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
  length: 0,
  key: vi.fn(),
}
Object.defineProperty(window, 'localStorage', {
  value: localStorageMock,
})

// MSW Server setup for API mocking
// Export the server so tests can add handlers
// Bootstrap config is now composed from 4 parallel JSON:API calls
export const server = setupServer(
  // Default handler for ui-pages
  http.get('*/api/ui-pages', () => {
    return HttpResponse.json({
      data: [],
      metadata: { totalCount: 0, currentPage: 0, pageSize: 500, totalPages: 0 },
    })
  }),
  // Default handler for ui-menus
  http.get('*/api/ui-menus', () => {
    return HttpResponse.json({
      data: [],
      metadata: { totalCount: 0, currentPage: 0, pageSize: 500, totalPages: 0 },
    })
  }),
  // Default handler for oidc-providers
  http.get('*/api/oidc-providers', () => {
    return HttpResponse.json({
      data: [
        {
          type: 'oidc-providers',
          id: 'test-provider',
          attributes: {
            name: 'Test Provider',
            issuer: 'https://test.example.com',
            clientId: 'test-client-id',
          },
        },
      ],
      metadata: { totalCount: 1, currentPage: 0, pageSize: 100, totalPages: 1 },
    })
  }),
  // Default handler for tenants
  http.get('*/api/tenants', () => {
    return HttpResponse.json({
      data: [
        {
          type: 'tenants',
          id: 'tenant-1',
          attributes: { slug: 'default', name: 'Default Tenant' },
        },
      ],
      metadata: { totalCount: 1, currentPage: 0, pageSize: 1, totalPages: 1 },
    })
  })
)

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'warn' })

  // Mock sessionStorage for auth
  const mockSessionStorage: Record<string, string> = {
    kelta_auth_tokens: JSON.stringify({
      accessToken: 'mock-access-token',
      idToken:
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0LXVzZXIiLCJlbWFpbCI6InRlc3RAdGVzdC5jb20iLCJuYW1lIjoiVGVzdCBVc2VyIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c',
      refreshToken: 'mock-refresh-token',
      expiresAt: Date.now() + 3600000,
    }),
  }

  Object.defineProperty(window, 'sessionStorage', {
    value: {
      getItem: (key: string) => mockSessionStorage[key] || null,
      setItem: (key: string, value: string) => {
        mockSessionStorage[key] = value
      },
      removeItem: (key: string) => {
        delete mockSessionStorage[key]
      },
      clear: () => {
        Object.keys(mockSessionStorage).forEach((key) => delete mockSessionStorage[key])
      },
      get length() {
        return Object.keys(mockSessionStorage).length
      },
      key: (index: number) => Object.keys(mockSessionStorage)[index] || null,
    },
    writable: true,
  })
})

afterEach(() => {
  server.resetHandlers()
})

afterAll(() => {
  server.close()
})
