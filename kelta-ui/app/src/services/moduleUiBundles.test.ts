import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { loadModuleUiBundles, resetLoadedModuleUiBundles } from './moduleUiBundles'
import type { ApiClient } from './apiClient'

/**
 * The loader evaluates fetched source via a blob URL. jsdom implements neither
 * `URL.createObjectURL` nor dynamic `import()` of one, so both are stubbed: what these tests
 * assert is which modules the loader *selects* and how it *fails*, not the browser's module
 * evaluation itself.
 *
 * `getList` is what unwraps the JSON:API envelope — `get` returns it raw. Faking the module list
 * on `get` (as an earlier version of this file did) hides a real defect: iterating the raw
 * envelope throws `not iterable`, which wedged PluginProvider until E2E caught it. The two are
 * kept on separate methods here so that mistake cannot be re-made silently.
 */
function fakeApiClient(overrides: Partial<Record<string, unknown>> = {}): ApiClient {
  return {
    getList: vi.fn(async () => overrides.modules ?? []),
    get: vi.fn(async (url: string) => {
      if (url === '/api/modules') {
        throw new Error('the loader must use getList for the module list, not get')
      }
      if (typeof overrides.bundle === 'function') {
        return (overrides.bundle as (u: string) => unknown)(url)
      }
      return 'export const x = 1\n'
    }),
  } as unknown as ApiClient
}

// jsdom implements neither of these. Patch the two methods on the real URL rather than
// replacing the global object: URL itself is used for parsing across the suite, and swapping it
// out leaks into other test files running in the same worker.
type BlobUrlMethods = { createObjectURL?: unknown; revokeObjectURL?: unknown }
let originalCreate: unknown
let originalRevoke: unknown

beforeEach(() => {
  resetLoadedModuleUiBundles()
  const url = URL as unknown as BlobUrlMethods
  originalCreate = url.createObjectURL
  originalRevoke = url.revokeObjectURL
  url.createObjectURL = vi.fn(() => 'blob:fake-url')
  url.revokeObjectURL = vi.fn()
})

afterEach(() => {
  const url = URL as unknown as BlobUrlMethods
  url.createObjectURL = originalCreate
  url.revokeObjectURL = originalRevoke
})

describe('loadModuleUiBundles', () => {
  it('skips modules that are not active', async () => {
    const apiClient = fakeApiClient({
      modules: [{ moduleId: 'a', status: 'DISABLED', uiBundlePath: 'static/ui.js' }],
    })

    expect(await loadModuleUiBundles(apiClient)).toEqual([])
    expect(apiClient.get).not.toHaveBeenCalled() // listed only, never fetched
  })

  it('fetches the pinned bundle of a DEGRADED external module', async () => {
    // The bundle was pinned at install; a module service that is not answering does not change it.
    const apiClient = fakeApiClient({
      modules: [{ moduleId: 'ext', status: 'DEGRADED', uiBundlePath: '/ui/bundle.js' }],
    })

    await loadModuleUiBundles(apiClient)

    expect(apiClient.get).toHaveBeenCalledWith('/api/modules/ext/ui-bundle.js')
  })

  it('skips active modules that declare no bundle', async () => {
    const apiClient = fakeApiClient({
      modules: [{ moduleId: 'a', status: 'ACTIVE', uiBundlePath: null }],
    })

    expect(await loadModuleUiBundles(apiClient)).toEqual([])
    expect(apiClient.get).not.toHaveBeenCalled()
  })

  it('returns empty rather than throwing when the module list is unavailable', async () => {
    // A tenant without the modules feature, or without permission, is the normal case.
    const apiClient = {
      getList: vi.fn(async () => {
        throw new Error('403')
      }),
    } as unknown as ApiClient

    await expect(loadModuleUiBundles(apiClient)).resolves.toEqual([])
  })

  it('returns empty rather than throwing when the list is not an array', async () => {
    // The defect E2E caught: `get` hands back the raw JSON:API envelope, and iterating an
    // object throws `not iterable`. Nothing this loader does may escape to its caller.
    const apiClient = {
      getList: vi.fn(async () => ({ data: [] })),
    } as unknown as ApiClient

    await expect(loadModuleUiBundles(apiClient)).resolves.toEqual([])
  })

  it('keeps going when one module bundle fails to load', async () => {
    const apiClient = fakeApiClient({
      modules: [
        { moduleId: 'broken', status: 'ACTIVE', uiBundlePath: 'static/ui.js' },
        { moduleId: 'ok', status: 'ACTIVE', uiBundlePath: 'static/ui.js' },
      ],
      bundle: (url: string) => {
        if (url.includes('broken')) {
          throw new Error('404')
        }
        return 'export const x = 1\n'
      },
    })

    // One broken module must not take the admin UI down with it. Neither module can actually
    // evaluate under jsdom, so the assertion is that the call resolves and both were attempted.
    await expect(loadModuleUiBundles(apiClient)).resolves.toBeInstanceOf(Array)
    expect(apiClient.get).toHaveBeenCalledWith('/api/modules/broken/ui-bundle.js')
    expect(apiClient.get).toHaveBeenCalledWith('/api/modules/ok/ui-bundle.js')
  })

  it('tolerates a null module list', async () => {
    const apiClient = fakeApiClient({ modules: null })
    await expect(loadModuleUiBundles(apiClient)).resolves.toEqual([])
  })
})
