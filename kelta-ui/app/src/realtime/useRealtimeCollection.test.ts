import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useRealtimeCollection } from './useRealtimeCollection'
import type { RealtimeClient } from './RealtimeClient'

const fakeClient = { subscribe: vi.fn(), unsubscribe: vi.fn() }
let currentClient: RealtimeClient | null = null

vi.mock('./RealtimeProvider', () => ({ useRealtimeClient: () => currentClient }))

beforeEach(() => {
  fakeClient.subscribe.mockClear()
  fakeClient.unsubscribe.mockClear()
  currentClient = fakeClient as unknown as RealtimeClient
})

describe('useRealtimeCollection', () => {
  it('subscribes on mount and releases its ref on unmount', () => {
    const { unmount } = renderHook(() => useRealtimeCollection('orders'))
    expect(fakeClient.subscribe).toHaveBeenCalledWith('orders')
    expect(fakeClient.unsubscribe).not.toHaveBeenCalled()

    unmount()
    expect(fakeClient.unsubscribe).toHaveBeenCalledWith('orders')
  })

  it('moves the subscription when the collection changes', () => {
    const { rerender } = renderHook(({ name }) => useRealtimeCollection(name), {
      initialProps: { name: 'orders' },
    })
    rerender({ name: 'customers' })
    expect(fakeClient.unsubscribe).toHaveBeenCalledWith('orders')
    expect(fakeClient.subscribe).toHaveBeenLastCalledWith('customers')
  })

  it('does nothing without a realtime client (admin shell) or a collection', () => {
    renderHook(() => useRealtimeCollection(undefined))
    currentClient = null
    renderHook(() => useRealtimeCollection('orders'))
    expect(fakeClient.subscribe).not.toHaveBeenCalled()
  })
})
