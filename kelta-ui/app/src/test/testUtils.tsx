/**
 * Test Utilities
 *
 * Shared utilities for testing React components with all required providers.
 *
 * All API calls in the app flow through the SDK's KeltaClient Axios instance.
 * Tests mock `axios` at the module level so that `axios.create()` returns a
 * controllable mock instance. Page-level tests use `getMockAxiosInstance()`
 * to set up response mocks on the shared Axios instance.
 */

import React from 'react'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '../context/I18nContext'
import { ToastProvider } from '../components/Toast'
import { ApiProvider } from '../context/ApiContext'
import { AuthProvider } from '../context/AuthContext'
import { PluginProvider } from '../context/PluginContext'
import { vi } from 'vitest'

/**
 * Mock bootstrap config response
 */
// eslint-disable-next-line react-refresh/only-export-components
export const mockBootstrapConfig = {
  oidcProviders: [
    {
      id: 'test-provider',
      name: 'Test Provider',
      issuer: 'https://test.example.com',
      clientId: 'test-client-id',
    },
  ],
}

/**
 * Mock tokens for authenticated state
 */
// eslint-disable-next-line react-refresh/only-export-components
export const mockTokens = {
  accessToken: 'mock-access-token',
  idToken:
    'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0LXVzZXIiLCJlbWFpbCI6InRlc3RAdGVzdC5jb20iLCJuYW1lIjoiVGVzdCBVc2VyIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c',
  refreshToken: 'mock-refresh-token',
  expiresAt: Date.now() + 3600000, // 1 hour from now
}

// Store the original fetch globally so tests can access it
let originalFetch: typeof fetch
let bootstrapFetchWrapper: typeof fetch

/**
 * Build a JSON:API list response for mock data.
 */
function jsonApiListResponse(type: string, items: Record<string, unknown>[]) {
  return {
    data: items.map((item, i) => ({
      type,
      id: (item.id as string) ?? `${type}-${i + 1}`,
      attributes: Object.fromEntries(Object.entries(item).filter(([k]) => k !== 'id')),
    })),
    metadata: {
      totalCount: items.length,
      currentPage: 0,
      pageSize: 500,
      totalPages: 1,
    },
  }
}

/**
 * Create a fetch wrapper that always handles bootstrap JSON:API config requests.
 *
 * Bootstrap is now composed from 4 parallel JSON:API calls:
 *   /api/ui-pages, /api/ui-menus, /api/ui-translations, /api/oidc-providers, /api/tenants
 */
function createBootstrapFetchWrapper(baseFetch: typeof fetch): typeof fetch {
  return ((url: string | URL | Request, ...args: unknown[]) => {
    const urlString = typeof url === 'string' ? url : url instanceof URL ? url.toString() : url.url

    const makeJsonResponse = (body: unknown) =>
      ({
        ok: true,
        status: 200,
        json: () => Promise.resolve(body),
        text: () => Promise.resolve(JSON.stringify(body)),
        clone: function () {
          return this
        },
        headers: new Headers(),
        redirected: false,
        statusText: 'OK',
        type: 'basic' as ResponseType,
        url: urlString,
        body: null,
        bodyUsed: false,
        arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
        blob: () => Promise.resolve(new Blob()),
        formData: () => Promise.resolve(new FormData()),
        bytes: () => Promise.resolve(new Uint8Array()),
      }) as Response

    // Intercept JSON:API bootstrap endpoints
    if (urlString.includes('/api/ui-pages')) {
      return Promise.resolve(makeJsonResponse(jsonApiListResponse('ui-pages', [])))
    }
    if (urlString.includes('/api/ui-menus')) {
      return Promise.resolve(makeJsonResponse(jsonApiListResponse('ui-menus', [])))
    }
    if (urlString.includes('/api/ui-translations')) {
      return Promise.resolve(makeJsonResponse(jsonApiListResponse('ui-translations', [])))
    }
    if (urlString.includes('/api/oidc-providers')) {
      return Promise.resolve(
        makeJsonResponse(
          jsonApiListResponse(
            'oidc-providers',
            mockBootstrapConfig.oidcProviders as unknown as Record<string, unknown>[]
          )
        )
      )
    }
    if (urlString.includes('/api/tenants')) {
      return Promise.resolve(
        makeJsonResponse(
          jsonApiListResponse('tenants', [
            { id: 'tenant-1', slug: 'default', name: 'Default Tenant' },
          ])
        )
      )
    }

    // For all other requests, use the base fetch (which might be a test mock)
    return baseFetch(url as RequestInfo | URL, ...(args as [RequestInit?]))
  }) as typeof fetch
}

/**
 * Setup mock fetch for bootstrap config and authentication.
 * This should be called once at the start of each test file.
 *
 * Note: Bootstrap config is still fetched via native `fetch` (in AuthContext),
 * so we keep the fetch mock for that. All API calls go through Axios.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function setupAuthMocks() {
  // Store original fetch if not already stored
  if (!originalFetch) {
    originalFetch = global.fetch
  }

  // Mock sessionStorage
  const mockSessionStorage: Record<string, string> = {
    kelta_auth_tokens: JSON.stringify(mockTokens),
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

  // Create the bootstrap wrapper
  bootstrapFetchWrapper = createBootstrapFetchWrapper(originalFetch)
  global.fetch = bootstrapFetchWrapper

  // Return a function that wraps any test's fetch mock with bootstrap handling
  return () => {
    global.fetch = originalFetch
  }
}

/**
 * Wrap a test's fetch mock to also handle bootstrap config.
 * Call this after setting up your test's fetch mock.
 *
 * Note: This is still needed because bootstrap config (AuthContext) uses
 * native fetch. Page-level API mocking now uses mockAxios instead.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function wrapFetchMock(testFetchMock: typeof fetch) {
  global.fetch = createBootstrapFetchWrapper(testFetchMock)
}

/**
 * Shared mock Axios instance used by all tests.
 *
 * When axios.create() is called (by KeltaClient), it returns this mock instance.
 * Tests can set up response mocks via:
 *   mockAxios.get.mockResolvedValueOnce({ data: ... })
 *   mockAxios.post.mockRejectedValueOnce(createAxiosError(400, ...))
 */
// eslint-disable-next-line react-refresh/only-export-components
export const mockAxios = {
  request: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
  defaults: {
    baseURL: '',
    headers: { 'Content-Type': 'application/json' },
  },
  interceptors: {
    request: { use: vi.fn(), eject: vi.fn() },
    response: { use: vi.fn(), eject: vi.fn() },
  },
}

/**
 * Get the mock Axios instance for setting up response mocks in tests.
 *
 * Usage in test files:
 *   const mockAxios = getMockAxiosInstance()
 *   mockAxios.get.mockResolvedValueOnce({ data: { content: [...] } })
 */
// eslint-disable-next-line react-refresh/only-export-components
export function getMockAxiosInstance() {
  return mockAxios
}

/**
 * Reset all mock Axios method calls.
 * Call this in beforeEach() to ensure clean state.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function resetMockAxios() {
  mockAxios.request.mockReset()
  mockAxios.get.mockReset()
  mockAxios.post.mockReset()
  mockAxios.put.mockReset()
  mockAxios.patch.mockReset()
  mockAxios.delete.mockReset()
}

/**
 * Helper to create an Axios-style error for mocking rejected API calls.
 *
 * Usage:
 *   mockAxios.get.mockRejectedValueOnce(createAxiosError(400, { message: 'Bad request', errors: [...] }))
 */
// eslint-disable-next-line react-refresh/only-export-components
export function createAxiosError(
  status: number,
  data?: unknown,
  statusText?: string
): {
  isAxiosError: true
  response: { status: number; data: unknown; statusText: string }
  message: string
} {
  return {
    isAxiosError: true,
    response: {
      status,
      data: data ?? null,
      statusText: statusText || (status >= 400 && status < 500 ? 'Bad Request' : 'Server Error'),
    },
    message: `Request failed with status code ${status}`,
  }
}

/**
 * Create a test wrapper with all required providers
 */
// eslint-disable-next-line react-refresh/only-export-components
export function createTestWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })

  return function TestWrapper({ children }: { children: React.ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <I18nProvider>
            <AuthProvider>
              <ApiProvider>
                <PluginProvider>
                  <ToastProvider>{children}</ToastProvider>
                </PluginProvider>
              </ApiProvider>
            </AuthProvider>
          </I18nProvider>
        </BrowserRouter>
      </QueryClientProvider>
    )
  }
}

/**
 * Auth wrapper component for tests that need custom routing or query client setup
 * Wraps children with AuthProvider, ApiProvider, and PluginProvider only
 */
export function AuthWrapper({ children }: { children: React.ReactNode }) {
  return (
    <AuthProvider>
      <ApiProvider>
        <PluginProvider>
          <I18nProvider>
            <ToastProvider>{children}</ToastProvider>
          </I18nProvider>
        </PluginProvider>
      </ApiProvider>
    </AuthProvider>
  )
}
