/**
 * Inline cell edit on the end-user list — optimistic concurrency (record-stack convergence P5).
 *
 * List rows carry no ETag, so a cell edit checks the row's `updatedAt` against the stored record
 * (fresh GET) and PATCHes with that GET's ETag as If-Match. A conflict — detected up front or
 * returned by the worker as 409 STALE_WRITE — reverts the cell (the commit resolves, the editor
 * closes on the refetched list) and says why; any other error stays inline in the editor.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { LiveRegionProvider } from '@/components/LiveRegion'
import { MY_PERMISSIONS_QUERY_KEY } from '@/hooks/useSystemPermissions'
import { ApiError } from '@/services/apiClient'
import { ObjectListPage } from './ObjectListPage'

const FIELDS = [
  { id: 'f-title', name: 'title', displayName: 'Title', type: 'string', required: false },
]
const LOADED_AT = '2026-10-01T10:00:00Z'
const RECORDS = [{ id: 'r-1', title: 'First', updatedAt: LOADED_AT }]

/** What the record GET (`getWithMeta`) returns: the stored version + its ETag. */
let stored: { updatedAt: string; etag: string }
const getWithMeta = vi.fn(async () => ({
  data: {
    data: {
      type: 'projects',
      id: 'r-1',
      attributes: { title: 'First', updatedAt: stored.updatedAt },
    },
  },
  etag: stored.etag,
}))
const patchMutateAsync = vi.fn()
const refetch = vi.fn(async () => undefined)
const toastError = vi.fn()

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: (...args: unknown[]) => toastError(...args) }),
}))
vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({
    apiClient: {
      getList: vi.fn(async () => []),
      get: vi.fn(async () => ({})),
      getWithMeta,
      postResource: vi.fn(async () => ({ id: 'pref-1' })),
      patchResource: vi.fn(async () => ({})),
    },
    keltaClient: { getAxiosInstance: () => ({ get: vi.fn(async () => ({ data: {} })) }) },
  }),
}))
vi.mock('@/hooks/useMyIdentity', () => ({
  useMyIdentity: () => ({ identity: { userId: 'user-1' }, isLoading: false }),
}))
vi.mock('@/hooks/useCollectionSchema', () => ({
  useCollectionSchema: () => ({
    schema: { id: 'col-1', name: 'projects', displayName: 'Projects', displayFieldName: 'title' },
    fields: FIELDS,
    isLoading: false,
    error: null,
  }),
}))
vi.mock('@/hooks/useCollectionRecords', () => ({
  useCollectionRecords: () => ({
    data: RECORDS,
    total: RECORDS.length,
    isLoading: false,
    error: null,
    refetch,
    rawResponse: undefined,
  }),
}))
vi.mock('@/hooks/useRecordMutation', () => ({
  useRecordMutation: () => ({
    create: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
    update: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
    patch: { mutate: vi.fn(), mutateAsync: patchMutateAsync, isPending: false },
    remove: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
    bulkDelete: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
  }),
}))
vi.mock('@/hooks/useSystemPermissions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useSystemPermissions')>()),
  useSystemPermissions: () => ({ hasPermission: () => false, isLoading: false }),
}))
vi.mock('@/hooks/usePicklistOptions', () => ({
  usePicklistOptions: () => ({ options: [], isLoading: false }),
  usePicklistDisplayMap: () => ({ displayMap: new Map(), isLoading: false }),
  usePicklistDisplayMaps: () => ({ displayMaps: {}, isLoading: false }),
}))
vi.mock('@/context/CollectionStoreContext', () => ({
  useCollectionStore: () => ({ getCollectionByName: () => undefined, collections: [] }),
}))
vi.mock('@/components/QuickActions', () => ({
  QuickActionsMenu: () => null,
}))

function renderList() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  qc.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
    systemPermissions: {},
    objectPermissions: {
      projects: { canRead: true, canCreate: true, canEdit: true, canDelete: true },
    },
    fieldPermissions: {},
  })
  return render(
    <I18nProvider>
      <LiveRegionProvider>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={['/acme/app/o/projects']}>
            <Routes>
              <Route path="/:tenantSlug/app/o/:collection" element={<ObjectListPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </LiveRegionProvider>
    </I18nProvider>
  )
}

const REVERTED =
  'This record was changed by someone else, so your edit was not saved. The latest values are shown.'

async function editTitle(value: string) {
  fireEvent.click(await screen.findByTestId('inline-field-title'))
  const input = screen.getByRole('textbox')
  fireEvent.change(input, { target: { value } })
  fireEvent.keyDown(input, { key: 'Enter' })
}

describe('ObjectListPage — inline cell edit concurrency', () => {
  beforeEach(() => {
    localStorage.clear()
    stored = { updatedAt: LOADED_AT, etag: '"v1"' }
    getWithMeta.mockClear()
    patchMutateAsync.mockReset()
    patchMutateAsync.mockResolvedValue({ id: 'r-1' })
    refetch.mockClear()
    toastError.mockClear()
  })

  it("PATCHes the cell with the record's fresh ETag as If-Match", async () => {
    renderList()
    await editTitle('Renamed')

    await waitFor(() =>
      expect(patchMutateAsync).toHaveBeenCalledWith({
        id: 'r-1',
        data: { title: 'Renamed' },
        ifMatch: '"v1"',
      })
    )
    expect(getWithMeta).toHaveBeenCalledWith('/api/projects/r-1')
    await waitFor(() => expect(refetch).toHaveBeenCalled())
    expect(toastError).not.toHaveBeenCalled()
  })

  it('reverts without writing when the record changed after the list loaded', async () => {
    stored = { updatedAt: '2026-10-01T10:05:00Z', etag: '"v2"' }
    renderList()
    await editTitle('Renamed')

    await waitFor(() => expect(toastError).toHaveBeenCalledWith(REVERTED))
    expect(patchMutateAsync).not.toHaveBeenCalled()
    expect(refetch).toHaveBeenCalled()
    // The commit resolved: the editor closed, the cell shows the (refetched) row value.
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(screen.getByText('First')).toBeInTheDocument()
  })

  it('reverts when the worker rejects the write as a stale write (409)', async () => {
    patchMutateAsync.mockRejectedValueOnce(
      new ApiError(409, 'modified', [{ field: '', message: 'modified', code: 'STALE_WRITE' }])
    )
    renderList()
    await editTitle('Renamed')

    await waitFor(() => expect(toastError).toHaveBeenCalledWith(REVERTED))
    expect(refetch).toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
  })

  it('keeps any other save error inline in the open editor', async () => {
    patchMutateAsync.mockRejectedValueOnce(new ApiError(400, 'Title is too long'))
    renderList()
    await editTitle('Renamed')

    expect(await screen.findByTestId('inline-field-error-title')).toHaveTextContent(
      'Title is too long'
    )
    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(toastError).not.toHaveBeenCalled()
  })
})
