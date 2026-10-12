/**
 * useRecordMutation offline wiring (Rec 2B-2). While offline, writes queue to the
 * outbox via engine.queue instead of hitting the network; online is unchanged.
 * Also: a write invalidates both record stacks' caches (end-user and admin).
 */
import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useRecordMutation } from './useRecordMutation'
import { OfflineProvider } from '../offline'
import { InMemoryOfflineStore } from '../offline/store'

const apiClient = {
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
}
vi.mock('../context/ApiContext', () => ({
  useApi: () => ({ apiClient }),
}))

function offlineWrapper(store: InMemoryOfflineStore) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return React.createElement(
      QueryClientProvider,
      { client: qc },
      React.createElement(OfflineProvider, { store, children })
    )
  }
}

beforeEach(() => {
  apiClient.post
    .mockReset()
    .mockResolvedValue({ data: { id: 's1', type: 'orders', attributes: {} } })
  apiClient.put
    .mockReset()
    .mockResolvedValue({ data: { id: 's1', type: 'orders', attributes: {} } })
  apiClient.patch
    .mockReset()
    .mockResolvedValue({ data: { id: 's1', type: 'orders', attributes: {} } })
  apiClient.delete.mockReset().mockResolvedValue(undefined)
})
afterEach(() => vi.restoreAllMocks())

describe('useRecordMutation — offline queueing', () => {
  it('offline create: queues to the outbox instead of POSTing', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const store = new InMemoryOfflineStore()

    const { result } = renderHook(() => useRecordMutation({ collectionName: 'orders' }), {
      wrapper: offlineWrapper(store),
    })

    await result.current.create.mutateAsync({ name: 'X' })

    expect(apiClient.post).not.toHaveBeenCalled()
    const outbox = await store.listOutbox()
    expect(outbox).toHaveLength(1)
    expect(outbox[0].op).toBe('create')
    expect(outbox[0].payload).toEqual({ name: 'X' })
  })

  it('offline delete: queues a delete op instead of DELETEing', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const store = new InMemoryOfflineStore()
    await store.putRecords('orders', [{ id: 'r1', name: 'A' }])

    const { result } = renderHook(() => useRecordMutation({ collectionName: 'orders' }), {
      wrapper: offlineWrapper(store),
    })

    await result.current.remove.mutateAsync('r1')

    expect(apiClient.delete).not.toHaveBeenCalled()
    const outbox = await store.listOutbox()
    expect(outbox).toHaveLength(1)
    expect(outbox[0].op).toBe('delete')
    expect(outbox[0].recordId).toBe('r1')
    // optimistic local delete
    expect(await store.get('orders', 'r1')).toBeUndefined()
  })

  it('online create: POSTs as usual', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    const store = new InMemoryOfflineStore()

    const { result } = renderHook(() => useRecordMutation({ collectionName: 'orders' }), {
      wrapper: offlineWrapper(store),
    })

    await result.current.create.mutateAsync({ name: 'X' })

    expect(apiClient.post).toHaveBeenCalledTimes(1)
    await waitFor(async () => expect(await store.listOutbox()).toHaveLength(0))
  })
})

describe('useRecordMutation — cache invalidation', () => {
  it("invalidates both stacks' list and record caches for the collection after a write", async () => {
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } },
    })
    const keys = [
      ['collection-records', 'orders', { page: 1 }],
      ['record', 'orders', 's1'],
      ['resources', 'orders', 0, 25],
      ['resource', 'orders', 's1'],
      ['resource', 'customers', 'c1'], // another collection — untouched
    ]
    for (const key of keys) qc.setQueryData(key, { cached: true })
    const wrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children)

    const { result } = renderHook(() => useRecordMutation({ collectionName: 'orders' }), {
      wrapper,
    })
    await result.current.update.mutateAsync({ id: 's1', data: { name: 'Y' } })

    await waitFor(() =>
      expect(qc.getQueryState(['resource', 'orders', 's1'])?.isInvalidated).toBe(true)
    )
    for (const key of keys.slice(0, 4)) {
      expect(qc.getQueryState(key)?.isInvalidated).toBe(true)
    }
    expect(qc.getQueryState(['resource', 'customers', 'c1'])?.isInvalidated).toBe(false)
  })
})
