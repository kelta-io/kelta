import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { usePreferenceValue } from './usePreferenceStore'

const mockGetList = vi.fn()
const mockPost = vi.fn()
const mockPatch = vi.fn()
let mockIdentity: { userId: string } | undefined

vi.mock('../context/ApiContext', () => ({
  useApi: vi.fn(() => ({
    apiClient: {
      getList: (...a: unknown[]) => mockGetList(...a),
      postResource: (...a: unknown[]) => mockPost(...a),
      patchResource: (...a: unknown[]) => mockPatch(...a),
    },
  })),
}))
vi.mock('./useMyIdentity', () => ({
  useMyIdentity: vi.fn(() => ({ identity: mockIdentity, isLoading: false })),
}))

const localBacking = new Map<string, string>()
function wireLocalStorageBacking() {
  localBacking.clear()
  vi.mocked(localStorage.getItem).mockImplementation((k: string) => localBacking.get(k) ?? null)
  vi.mocked(localStorage.setItem).mockImplementation(
    (k: string, v: string) => void localBacking.set(k, String(v))
  )
  vi.mocked(localStorage.removeItem).mockImplementation((k: string) => void localBacking.delete(k))
}

const ME = '11111111-1111-1111-1111-111111111111'
const LOCAL_KEY = 'kelta_test_pref'

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return React.createElement(QueryClientProvider, { client: qc }, children)
}

beforeEach(() => {
  vi.clearAllMocks()
  wireLocalStorageBacking()
  mockIdentity = { userId: ME }
  mockPost.mockResolvedValue({ id: 'row-new' })
  mockPatch.mockResolvedValue({})
})

describe('usePreferenceValue', () => {
  it('loads the server row filtered to the caller and surfaces its value', async () => {
    mockGetList.mockResolvedValue([
      { id: 'row-1', userId: ME, prefType: 'list-view', prefKey: 'orders', value: ['a'] },
    ])
    const { result } = renderHook(
      () => usePreferenceValue<string[]>('list-view', 'orders', { localKey: LOCAL_KEY }),
      { wrapper }
    )
    await waitFor(() => expect(result.current.isLoaded).toBe(true))
    expect(result.current.value).toEqual(['a'])
    const url = mockGetList.mock.calls[0][0] as string
    expect(url).toContain(`filter[userId][eq]=${ME}`)
    expect(url).toContain('filter[prefType][eq]=list-view')
    expect(url).toContain('filter[prefKey][eq]=orders')
  })

  it('save PATCHes an existing row and mirrors to localStorage', async () => {
    mockGetList.mockResolvedValue([
      { id: 'row-1', userId: ME, prefType: 'list-view', prefKey: 'orders', value: [] },
    ])
    const { result } = renderHook(
      () => usePreferenceValue<string[]>('list-view', 'orders', { localKey: LOCAL_KEY }),
      { wrapper }
    )
    await waitFor(() => expect(result.current.isLoaded).toBe(true))
    act(() => result.current.save(['x']))
    await waitFor(() => expect(mockPatch).toHaveBeenCalled())
    expect(mockPatch.mock.calls[0][0]).toBe('/api/user-ui-preferences/row-1')
    expect(JSON.parse(localStorage.getItem(LOCAL_KEY)!)).toEqual(['x'])
  })

  it('save POSTs a new row carrying the caller userId when none exists', async () => {
    mockGetList.mockResolvedValue([])
    const { result } = renderHook(
      () => usePreferenceValue<string[]>('list-view', 'orders', { localKey: LOCAL_KEY }),
      { wrapper }
    )
    await waitFor(() => expect(result.current.isLoaded).toBe(true))
    act(() => result.current.save(['y']))
    await waitFor(() => expect(mockPost).toHaveBeenCalled())
    const body = mockPost.mock.calls[0][1] as {
      data: { attributes: Record<string, unknown> }
    }
    expect(body.data.attributes.userId).toBe(ME)
    expect(body.data.attributes.value).toEqual(['y'])
  })

  it('falls back to localStorage with no resolvable identity', async () => {
    mockIdentity = undefined
    localStorage.setItem(LOCAL_KEY, JSON.stringify(['local']))
    const { result } = renderHook(
      () => usePreferenceValue<string[]>('list-view', 'orders', { localKey: LOCAL_KEY }),
      { wrapper }
    )
    await waitFor(() => expect(result.current.isLoaded).toBe(true))
    expect(result.current.value).toEqual(['local'])
    expect(mockGetList).not.toHaveBeenCalled()
    act(() => result.current.save(['still-local']))
    expect(mockPost).not.toHaveBeenCalled()
    expect(JSON.parse(localStorage.getItem(LOCAL_KEY)!)).toEqual(['still-local'])
  })
  it('fetches and reads nothing when disabled', async () => {
    localBacking.set(LOCAL_KEY, JSON.stringify(['local']))
    const { result } = renderHook(
      () =>
        usePreferenceValue<string[]>('list-view', 'orders', {
          localKey: LOCAL_KEY,
          enabled: false,
        }),
      { wrapper }
    )
    expect(result.current.isLoaded).toBe(true)
    expect(result.current.value).toBeNull()
    await act(async () => {})
    expect(mockGetList).not.toHaveBeenCalled()
  })
})
