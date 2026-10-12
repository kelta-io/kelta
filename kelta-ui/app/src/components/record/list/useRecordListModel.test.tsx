/**
 * `useRecordListModel` — the data and state side of the record list core: what it asks the
 * records hook for (include, grouping sort, calendar range), how it labels lookups, which
 * features gate which actions, and the list-state actions. Rendering is covered by the
 * `RecordList.*` and `ObjectListPage` suites.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import React from 'react'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { LiveRegionProvider } from '@/components/LiveRegion'
import type { UseCollectionRecordsOptions } from '@/hooks/useCollectionRecords'
import { recordRoutes } from '@/utils/recordRoutes'
import { useRecordListModel, type RecordListFeatures } from './useRecordListModel'

const FIELDS = [
  { id: 'f-title', name: 'title', displayName: 'Title', type: 'string', required: false },
  { id: 'f-status', name: 'status', displayName: 'Status', type: 'picklist', required: false },
  { id: 'f-due', name: 'due', displayName: 'Due', type: 'date', required: false },
  {
    id: 'f-owner',
    name: 'owner',
    displayName: 'Owner',
    type: 'lookup',
    required: false,
    referenceTarget: 'people',
  },
  {
    id: 'f-account',
    name: 'account',
    displayName: 'Account',
    type: 'master_detail',
    required: false,
    referenceTarget: 'accounts',
  },
]

const RAW_RESPONSE = {
  data: [],
  included: [
    { type: 'people', id: 'p-1', attributes: { fullName: 'Ada', email: 'ada@example.test' } },
    { type: 'accounts', id: 'a-1', attributes: { label: 'Acme Ltd', code: 'ACME' } },
  ],
}

/** Target schemas the collection store knows. */
const STORE: Record<string, unknown> = {
  // no display field, no `name` field → the first string field labels the record
  people: {
    name: 'people',
    fields: [
      { name: 'age', type: 'number' },
      { name: 'fullName', type: 'string' },
    ],
  },
  accounts: { name: 'accounts', displayFieldName: 'label', fields: [] },
}

const recordsCalls: UseCollectionRecordsOptions[] = []
let canEdit = true
let manageData = true

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({
    apiClient: {
      getList: vi.fn(async () => []),
      get: vi.fn(async () => ({})),
      postResource: vi.fn(async () => ({ id: 'pref-1' })),
      patchResource: vi.fn(async () => ({})),
    },
  }),
}))
vi.mock('@/hooks/useMyIdentity', () => ({
  useMyIdentity: () => ({ identity: { userId: 'user-1' }, isLoading: false }),
}))
vi.mock('@/context/CollectionStoreContext', () => ({
  useCollectionStore: () => ({ getCollectionByName: (name: string) => STORE[name] }),
}))
vi.mock('@/hooks/useCollectionSchema', () => ({
  useCollectionSchema: (name: string) => ({
    schema: { id: `col-${name}`, name, displayName: 'Projects', displayFieldName: 'title' },
    fields: FIELDS,
    isLoading: false,
    error: null,
  }),
}))
vi.mock('@/hooks/useCollectionRecords', () => ({
  useCollectionRecords: (options: UseCollectionRecordsOptions) => {
    recordsCalls.push(options)
    return {
      data: [{ id: 'r-1', title: 'First', owner: 'p-1', account: 'a-1' }],
      total: 1,
      isLoading: false,
      error: null,
      refetch: vi.fn(),
      rawResponse: RAW_RESPONSE,
    }
  },
}))
vi.mock('@/hooks/useCollectionPermissions', () => ({
  useCollectionPermissions: () => ({
    permissions: { canCreate: true, canRead: true, canEdit, canDelete: true },
    isFieldVisible: () => true,
    isLoading: false,
  }),
}))
vi.mock('@/hooks/useSystemPermissions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useSystemPermissions')>()),
  useSystemPermissions: () => ({ hasPermission: () => manageData, isLoading: false }),
}))
vi.mock('@/hooks/usePicklistOptions', () => ({
  usePicklistDisplayMaps: () => ({ displayMaps: {}, isLoading: false }),
}))
vi.mock('@/hooks/useRecordMutation', () => ({
  useRecordMutation: () => ({
    patch: { mutateAsync: vi.fn(), isPending: false },
    remove: { mutateAsync: vi.fn(), isPending: false },
    bulkDelete: { mutateAsync: vi.fn(), isPending: false },
  }),
}))

function renderModel(features: Partial<RecordListFeatures>, url = '/acme/app/o/projects') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <I18nProvider>
      <LiveRegionProvider>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[url]}>{children}</MemoryRouter>
        </QueryClientProvider>
      </LiveRegionProvider>
    </I18nProvider>
  )
  const routes = recordRoutes('enduser', 'acme')
  return renderHook(
    () => ({
      model: useRecordListModel({
        collectionName: 'projects',
        tenantSlug: 'acme',
        routes,
        features,
      }),
      location: useLocation(),
    }),
    { wrapper }
  )
}

const lastRecordsCall = () => recordsCalls[recordsCalls.length - 1]

describe('useRecordListModel', () => {
  beforeEach(() => {
    recordsCalls.length = 0
    canEdit = true
    manageData = true
    localStorage.clear()
  })

  it('includes the visible lookup columns by field name and labels them by the target display field', () => {
    const { result } = renderModel({})

    expect(lastRecordsCall().include).toBe('owner,account')
    expect(result.current.model.lookupDisplayMap).toEqual({
      owner: { 'p-1': 'Ada' },
      account: { 'a-1': 'Acme Ltd' },
    })
  })

  it('reads page, page size, sort and filters from the URL into the fetch', () => {
    renderModel({}, '/acme/app/o/projects?page=3&pageSize=50&sort=-title,status')

    expect(lastRecordsCall()).toMatchObject({
      collectionName: 'projects',
      page: 3,
      pageSize: 50,
      sort: [
        { field: 'title', direction: 'desc' },
        { field: 'status', direction: 'asc' },
      ],
      enabled: true,
    })
  })

  it('prepends the group-by field to the fetched sort, only with the groupBy feature', async () => {
    const off = renderModel({}, '/acme/app/o/projects?sort=-title')
    act(() => off.result.current.model.view.setGroupBy('status'))
    expect(off.result.current.model.view.groupBy).toBeNull()
    expect(lastRecordsCall().sort).toEqual([{ field: 'title', direction: 'desc' }])
    off.unmount()

    const on = renderModel({ groupBy: true }, '/acme/app/o/projects?sort=-title')
    act(() => on.result.current.model.view.setGroupBy('status'))
    await waitFor(() =>
      expect(lastRecordsCall().sort).toEqual([
        { field: 'status', direction: 'asc' },
        { field: 'title', direction: 'desc' },
      ])
    )
  })

  it('fetches the whole visible month in calendar view', async () => {
    const { result } = renderModel({ altViews: true })
    act(() => result.current.model.view.changeViewType('calendar'))

    await waitFor(() => expect(lastRecordsCall().pageSize).toBe(200))
    expect(lastRecordsCall().page).toBe(1)
    expect(lastRecordsCall().filters?.map((f) => [f.field, f.operator])).toEqual([
      ['due', 'greater_than_or_equal'],
      ['due', 'less_than_or_equal'],
    ])
  })

  it('stays on the table renderer without the altViews feature', () => {
    const { result } = renderModel({})
    act(() => result.current.model.view.changeViewType('kanban'))
    expect(result.current.model.view.viewType).toBe('table')
  })

  it('offers mass edit only with the feature, edit permission and MANAGE_DATA', () => {
    expect(renderModel({}).result.current.model.canMassEdit).toBe(false)
    expect(renderModel({ massEdit: true }).result.current.model.canMassEdit).toBe(true)
    manageData = false
    expect(renderModel({ massEdit: true }).result.current.model.canMassEdit).toBe(false)
    manageData = true
    canEdit = false
    expect(renderModel({ massEdit: true }).result.current.model.canMassEdit).toBe(false)
  })

  it('turns inline edit on only with the feature and edit permission', () => {
    expect(renderModel({}).result.current.model.inlineEditable).toBe(false)
    expect(renderModel({ inlineEdit: true }).result.current.model.inlineEditable).toBe(true)
    canEdit = false
    expect(renderModel({ inlineEdit: true }).result.current.model.inlineEditable).toBe(false)
  })

  it('writes sort, filter and paging changes to the list params and clears the selection on paging', () => {
    const { result } = renderModel({})

    act(() => result.current.model.changeSort('title'))
    expect(result.current.location.search).toBe('?sort=title')

    act(() =>
      result.current.model.setFilters([
        { id: 'f1', field: 'status', operator: 'equals', value: 'open' },
      ])
    )
    expect(result.current.model.params.filters).toHaveLength(1)

    act(() => result.current.model.setSelectedIds(new Set(['r-1'])))
    act(() => result.current.model.changePage(2))
    expect(result.current.model.params.page).toBe(2)
    expect(result.current.model.selectedIds.size).toBe(0)

    act(() => result.current.model.removeFilter('f1'))
    expect(result.current.model.params.filters).toEqual([])
    // a filter change returns to page 1
    expect(result.current.model.params.page).toBe(1)
  })
})
