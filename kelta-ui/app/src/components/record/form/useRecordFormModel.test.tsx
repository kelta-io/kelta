/**
 * `useRecordFormModel` — the data side of the record form core. Covers what P6 added for the
 * admin convergence: clone (`cloneFromId` — the source is read through `useRecord`, its values
 * seed a NEW record, its ETag is not carried, its record type is preselected) and `recordError`
 * (the record being edited / cloned failed to load or does not exist). The rest of the model is
 * exercised through `ObjectFormPage.test.tsx` and `ResourceFormPage.test.tsx`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import React from 'react'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useRecordFormModel } from './useRecordFormModel'
import type { UseRecordFormModelOptions } from './useRecordFormModel'

const FIELDS = [
  { id: 'f-name', name: 'name', displayName: 'Name', type: 'string', required: true },
  { id: 'f-amount', name: 'amount', displayName: 'Amount', type: 'number', required: false },
]

interface Served {
  record?: Record<string, unknown>
  isLoading?: boolean
  error?: Error | null
  etag?: string
}
let served: Served
const useRecordCalls: Array<{ recordId?: string; enabled?: boolean }> = []

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: { getList: vi.fn(async () => []) } }),
}))
vi.mock('@/context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' } }),
}))
vi.mock('@/hooks/useCollectionSchema', () => ({
  useCollectionSchema: () => ({
    schema: { id: 'col-1', name: 'accounts', displayName: 'Accounts' },
    fields: FIELDS,
    isLoading: false,
    error: null,
  }),
}))
vi.mock('@/hooks/useRecord', () => ({
  useRecord: (opts: { recordId?: string; enabled?: boolean }) => {
    useRecordCalls.push({ recordId: opts.recordId, enabled: opts.enabled })
    return {
      record: served.isLoading ? undefined : served.record,
      isLoading: !!served.isLoading,
      error: served.error ?? null,
      etag: served.isLoading ? undefined : served.etag,
      refetch: vi.fn(),
    }
  },
}))
vi.mock('@/hooks/useCollectionPermissions', () => ({
  useCollectionPermissions: () => ({
    permissions: { canCreate: true, canRead: true, canEdit: true, canDelete: true },
    isFieldVisible: () => true,
    isFieldEditable: () => true,
    isLoading: false,
  }),
}))
vi.mock('@/hooks/usePageLayout', () => ({
  usePageLayout: () => ({ layout: null, isLoading: false }),
}))
vi.mock('@/hooks/useLookupDisplayMap', () => ({
  useLookupDisplayMap: () => ({ lookupDisplayMap: undefined }),
}))

function renderModel(options: UseRecordFormModelOptions) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return renderHook(() => useRecordFormModel(options), { wrapper })
}

const SOURCE = {
  id: 'src-1',
  name: 'Original',
  amount: 7,
  recordTypeId: 'rt-2',
  createdAt: '2026-01-01T00:00:00Z',
}

beforeEach(() => {
  served = {}
  useRecordCalls.length = 0
})

describe('useRecordFormModel — clone (cloneFromId)', () => {
  it('reads the source record and seeds a new record from it', () => {
    served = { record: SOURCE, etag: '"v9"' }
    const { result } = renderModel({ collectionName: 'accounts', cloneFromId: 'src-1' })

    expect(useRecordCalls.at(-1)).toEqual({ recordId: 'src-1', enabled: true })
    expect(result.current.isNew).toBe(true)
    expect(result.current.isClone).toBe(true)
    expect(result.current.initialData).toEqual({ name: 'Original', amount: 7 })
    expect(result.current.formKey).toMatch(/^clone:src-1:src-1:/)
  })

  it("does not carry the source's version (a clone is a new record)", () => {
    served = { record: SOURCE, etag: '"v9"' }
    const { result } = renderModel({ collectionName: 'accounts', cloneFromId: 'src-1' })
    expect(result.current.etag).toBeUndefined()
  })

  it("preselects the source's record type", () => {
    served = { record: SOURCE }
    const { result } = renderModel({ collectionName: 'accounts', cloneFromId: 'src-1' })
    expect(result.current.selectedRecordTypeId).toBe('rt-2')
  })

  it('applies the prefill over the copied values', () => {
    served = { record: SOURCE }
    const { result } = renderModel({
      collectionName: 'accounts',
      cloneFromId: 'src-1',
      prefill: { name: 'From the link' },
    })
    expect(result.current.initialData).toEqual({ name: 'From the link', amount: 7 })
  })

  it('stays loading until the source arrives', () => {
    served = { isLoading: true }
    const { result } = renderModel({ collectionName: 'accounts', cloneFromId: 'src-1' })
    expect(result.current.isLoading).toBe(true)
    expect(result.current.recordError).toBeNull()
  })

  it('reports a source that cannot be read', () => {
    served = { error: new Error('Request failed with status code 404') }
    const { result } = renderModel({ collectionName: 'accounts', cloneFromId: 'src-1' })
    expect(result.current.recordError?.message).toMatch(/404/)
  })

  it('is ignored on edit', () => {
    served = { record: { id: 'rec-1', name: 'Edited' }, etag: '"v1"' }
    const { result } = renderModel({
      collectionName: 'accounts',
      recordId: 'rec-1',
      cloneFromId: 'src-1',
    })
    expect(useRecordCalls.at(-1)?.recordId).toBe('rec-1')
    expect(result.current.isClone).toBe(false)
    expect(result.current.etag).toBe('"v1"')
    expect(result.current.initialData.name).toBe('Edited')
  })
})

describe('useRecordFormModel — record load errors', () => {
  it('reports a record that failed to load on edit', () => {
    served = { error: new Error('Request failed with status code 500') }
    const { result } = renderModel({ collectionName: 'accounts', recordId: 'rec-1' })
    expect(result.current.recordError?.message).toMatch(/500/)
  })

  it('reports an edit whose record came back empty (not found)', () => {
    served = {}
    const { result } = renderModel({ collectionName: 'accounts', recordId: 'rec-1' })
    expect(result.current.recordError).toBeInstanceOf(Error)
  })

  it('has no record error on a plain create, and reads no record', () => {
    const { result } = renderModel({ collectionName: 'accounts' })
    expect(result.current.recordError).toBeNull()
    expect(useRecordCalls.at(-1)).toEqual({ recordId: undefined, enabled: false })
  })
})
