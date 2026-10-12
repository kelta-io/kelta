/**
 * `RecordList` — the record list core. Features are switched on by props (not by `variant`),
 * the list state lives in the URL or in a local copy of it (`urlState`), chrome comes from
 * slots, the permission gate / schema error replace the frame, and the state is local to one
 * collection. Inline edit and shared views have their own suites (`RecordList.inlineEdit`,
 * `RecordList.sharedView`); the end-user wrapper is covered by `ObjectListPage.test.tsx`.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { LiveRegionProvider } from '@/components/LiveRegion'
import { MY_PERMISSIONS_QUERY_KEY } from '@/hooks/useSystemPermissions'
import type { UseCollectionRecordsOptions } from '@/hooks/useCollectionRecords'
import { recordRoutes } from '@/utils/recordRoutes'
import { RecordList, type RecordListProps } from './RecordList'
import type { RecordListFeatures } from './useRecordListModel'

const FIELDS = [
  { id: 'f-title', name: 'title', displayName: 'Title', type: 'string', required: false },
  {
    id: 'f-status',
    name: 'status',
    displayName: 'Status',
    type: 'picklist',
    required: false,
    enumValues: ['open', 'done'],
  },
]
const RECORDS = [
  { id: 'r-1', title: 'First', status: 'open' },
  { id: 'r-2', title: 'Second', status: 'done' },
]

let schemaError: Error | null = null
const recordsCalls: UseCollectionRecordsOptions[] = []

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({
    apiClient: {
      getList: vi.fn(async () => []),
      get: vi.fn(async () => ({})),
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
  useCollectionSchema: (name: string) => ({
    schema: schemaError
      ? undefined
      : { id: `col-${name}`, name, displayName: `Label ${name}`, displayFieldName: 'title' },
    fields: schemaError ? [] : FIELDS,
    isLoading: false,
    error: schemaError,
  }),
}))
vi.mock('@/hooks/useCollectionRecords', () => ({
  useCollectionRecords: (options: UseCollectionRecordsOptions) => {
    recordsCalls.push(options)
    return {
      data: RECORDS,
      total: RECORDS.length,
      isLoading: false,
      error: null,
      refetch: vi.fn(),
      rawResponse: undefined,
    }
  },
}))
vi.mock('@/hooks/useRecordMutation', () => ({
  useRecordMutation: () => ({
    patch: { mutateAsync: vi.fn(), isPending: false },
    remove: { mutateAsync: vi.fn(), isPending: false },
    bulkDelete: { mutateAsync: vi.fn(), isPending: false },
  }),
}))
vi.mock('@/hooks/useSystemPermissions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useSystemPermissions')>()),
  useSystemPermissions: () => ({ hasPermission: () => true, isLoading: false }),
}))
vi.mock('@/hooks/usePicklistOptions', () => ({
  usePicklistOptions: () => ({ options: ['open', 'done'], isLoading: false }),
  usePicklistDisplayMap: () => ({ displayMap: new Map(), isLoading: false }),
  usePicklistDisplayMaps: () => ({ displayMaps: {}, isLoading: false }),
}))
vi.mock('@/context/CollectionStoreContext', () => ({
  useCollectionStore: () => ({ getCollectionByName: () => undefined, collections: [] }),
}))
vi.mock('@/components/QuickActions', () => ({
  QuickActionsMenu: () => <div data-testid="quick-actions" />,
}))
// Render the shadcn DropdownMenu inline so menu items are visible/clickable in jsdom.
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: React.PropsWithChildren<{ asChild?: boolean }>) => (
    <div>{children}</div>
  ),
  DropdownMenuContent: ({
    children,
    ...props
  }: React.PropsWithChildren<Record<string, unknown>>) => <div {...props}>{children}</div>,
  DropdownMenuItem: ({
    children,
    onClick,
    ...props
  }: React.PropsWithChildren<{ onClick?: () => void } & Record<string, unknown>>) => (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/interactive-supports-focus
    <div role="menuitem" onClick={onClick} {...props}>
      {children}
    </div>
  ),
  DropdownMenuSeparator: () => <hr />,
  DropdownMenuLabel: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  DropdownMenuCheckboxItem: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
}))

const ALL_FEATURES: RecordListFeatures = {
  savedViews: true,
  sharedViews: true,
  columnChooser: true,
  density: true,
  groupBy: true,
  altViews: true,
  massEdit: true,
  import: true,
  export: true,
  quickActions: true,
  inlineEdit: true,
}

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

let canRead = true

function renderList(
  props: Partial<RecordListProps> = {},
  url = '/acme/app/o/projects'
): ReturnType<typeof render> & { rerenderList: (next: Partial<RecordListProps>) => void } {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  qc.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
    systemPermissions: {},
    objectPermissions: {
      projects: { canRead, canCreate: true, canEdit: true, canDelete: true },
      tasks: { canRead: true, canCreate: true, canEdit: true, canDelete: true },
    },
    fieldPermissions: {},
  })
  const tree = (p: Partial<RecordListProps>) => (
    <I18nProvider>
      <LiveRegionProvider>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={[url]}>
            <Routes>
              <Route
                path="*"
                element={
                  <>
                    <RecordList
                      variant="enduser"
                      collectionName="projects"
                      tenantSlug="acme"
                      routes={recordRoutes('enduser', 'acme')}
                      permissionBackPath="/acme/app/home"
                      {...p}
                    />
                    <LocationProbe />
                  </>
                }
              />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </LiveRegionProvider>
    </I18nProvider>
  )
  const result = render(tree(props))
  return { ...result, rerenderList: (next) => result.rerender(tree({ ...props, ...next })) }
}

describe('RecordList', () => {
  beforeEach(() => {
    schemaError = null
    canRead = true
    recordsCalls.length = 0
    localStorage.clear()
  })

  it('renders only the core list when no optional feature is on', async () => {
    renderList()

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.getByTestId('new-record-button')).toBeInTheDocument()
    for (const id of [
      'view-selector',
      'column-chooser-trigger',
      'density-toggle',
      'group-by-trigger',
      'view-type-trigger',
      'quick-actions',
    ]) {
      expect(screen.queryByTestId(id)).toBeNull()
    }
    expect(screen.queryByText('Export as CSV')).toBeNull()
    expect(screen.queryByText('Import CSV')).toBeNull()
  })

  it('shows each optional control when its feature is on', async () => {
    renderList({ features: ALL_FEATURES })

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    for (const id of [
      'view-selector',
      'column-chooser-trigger',
      'density-toggle',
      'group-by-trigger',
      'view-type-trigger',
      'quick-actions',
    ]) {
      expect(screen.getByTestId(id)).toBeInTheDocument()
    }
    expect(screen.getByText('Export as CSV')).toBeInTheDocument()
    expect(screen.getByText('Export as JSON')).toBeInTheDocument()
    expect(screen.getByText('Import CSV')).toBeInTheDocument()
  })

  it("writes the sort to the URL with urlState='sync'", async () => {
    renderList({ urlState: 'sync' })

    fireEvent.click(await screen.findByRole('columnheader', { name: /Title/ }))

    expect(screen.getByTestId('location')).toHaveTextContent('/acme/app/o/projects?sort=title')
    await waitFor(() =>
      expect(recordsCalls[recordsCalls.length - 1].sort).toEqual([
        { field: 'title', direction: 'asc' },
      ])
    )
  })

  it("keeps the state local with urlState='seed': seeded from the URL, never written back", async () => {
    renderList({ urlState: 'seed' }, '/acme/resources/projects?sort=-status')
    expect(recordsCalls[recordsCalls.length - 1].sort).toEqual([
      { field: 'status', direction: 'desc' },
    ])

    fireEvent.click(await screen.findByRole('columnheader', { name: /Title/ }))

    await waitFor(() =>
      expect(recordsCalls[recordsCalls.length - 1].sort).toEqual([
        { field: 'title', direction: 'asc' },
      ])
    )
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/acme/resources/projects?sort=-status'
    )
  })

  it('hands slots the loaded collection label and the model (filter editor writes filters)', async () => {
    renderList({
      breadcrumb: ({ collectionLabel }) => <nav data-testid="crumb">{collectionLabel}</nav>,
      header: <header data-testid="custom-header" />,
      toolbarExtras: <span data-testid="extra-control" />,
      belowTable: <p data-testid="below" />,
      filterEditor: ({ model }) => (
        <button
          type="button"
          data-testid="apply-filter"
          onClick={() =>
            model.setFilters([{ id: 'f1', field: 'status', operator: 'equals', value: 'open' }])
          }
        >
          {model.params.filters.length} filters
        </button>
      ),
    })

    expect(await screen.findByTestId('crumb')).toHaveTextContent('Label projects')
    expect(screen.getByTestId('custom-header')).toBeInTheDocument()
    expect(screen.getByTestId('extra-control')).toBeInTheDocument()
    expect(screen.getByTestId('below')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('apply-filter'))

    expect(screen.getByTestId('apply-filter')).toHaveTextContent('1 filters')
    await waitFor(() =>
      expect(recordsCalls[recordsCalls.length - 1].filters).toEqual([
        { id: 'f1', field: 'status', operator: 'equals', value: 'open' },
      ])
    )
  })

  it('uses the variant for the frame and the caller test id', async () => {
    renderList({ variant: 'admin', testId: 'resource-list-page' })

    expect(await screen.findByTestId('resource-list-page')).toHaveAttribute('data-variant', 'admin')
  })

  it('shows the permission gate, whose Back goes to permissionBackPath', async () => {
    canRead = false
    renderList()

    expect(await screen.findByText('Insufficient privileges')).toBeInTheDocument()
    expect(screen.queryByTestId('data-table')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Go Back' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/acme/app/home')
  })

  it('shows the schema error with a retry in place of the list', async () => {
    schemaError = new Error('Collection exploded')
    renderList()

    expect(await screen.findByText('Error loading collection')).toBeInTheDocument()
    expect(screen.getByText('Collection exploded')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.queryByTestId('data-table')).toBeNull()
  })

  it('starts fresh when the collection changes: selection, grouping, density and renderer reset', async () => {
    const { rerenderList } = renderList({ features: ALL_FEATURES })

    fireEvent.click(await screen.findByLabelText('Select row r-1'))
    fireEvent.click(screen.getByTestId('group-by-status'))
    fireEvent.click(screen.getByTestId('density-toggle'))
    expect(screen.getByText('1 record selected')).toBeInTheDocument()
    expect(screen.getByTestId('group-caption')).toBeInTheDocument()
    expect(screen.getByTestId('density-toggle')).toHaveTextContent('Compact')
    fireEvent.click(screen.getByTestId('view-type-kanban'))
    expect(await screen.findByTestId('kanban-board')).toBeInTheDocument()

    rerenderList({ collectionName: 'tasks' })

    expect(await screen.findByRole('heading', { name: /Label tasks/ })).toBeInTheDocument()
    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.queryByTestId('kanban-board')).toBeNull()
    expect(screen.queryByText('1 record selected')).toBeNull()
    expect(screen.queryByTestId('group-caption')).toBeNull()
    expect(screen.getByTestId('group-by-trigger')).toHaveTextContent('Group by')
    expect(screen.getByTestId('density-toggle')).toHaveTextContent('Normal')
  })
})
