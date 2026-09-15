/**
 * Shared views carry their renderer (K-8 item 11).
 *
 * The published `list-views` row is fetched, mapped and applied through the real
 * `useSharedListViews` → `mapSharedListView` → `applyView` path — only the schema,
 * records and identity edges are stubbed. Mocking the shared-view hook itself
 * would test nothing: the whole feature is whether a row written by an admin
 * reaches the renderer.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { LiveRegionProvider } from '@/components/LiveRegion'
import { MY_PERMISSIONS_QUERY_KEY } from '@/hooks/useSystemPermissions'
import { ObjectListPage } from './ObjectListPage'

const USER_ID = 'user-1'

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

/** The admin-authored row: a PUBLIC kanban board with lanes on `status`. */
const SHARED_KANBAN_ROW = {
  id: 'lv-board',
  name: 'The board',
  columns: ['title', 'status'],
  filters: [],
  isDefault: true,
  visibility: 'PUBLIC',
  viewType: 'KANBAN',
  typeConfig: { kanban: { laneField: 'status', cardFields: ['title'] } },
}

/** Rows the page's own `list-views` / `user-ui-preferences` queries get back. */
let listViewRows: unknown[] = []
let preferenceRows: unknown[] = []
const postedPreferences: unknown[] = []

const getList = vi.fn(async (url: string) => {
  if (url.startsWith('/api/list-views')) return listViewRows
  if (url.startsWith('/api/user-ui-preferences')) return preferenceRows
  return []
})

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({
    apiClient: {
      getList,
      get: vi.fn(async () => ({})),
      postResource: vi.fn(async (_url: string, body: unknown) => {
        postedPreferences.push(body)
        return { id: 'pref-1' }
      }),
      patchResource: vi.fn(async (_url: string, body: unknown) => {
        postedPreferences.push(body)
        return {}
      }),
    },
    keltaClient: { getAxiosInstance: () => ({ get: vi.fn(async () => ({ data: {} })) }) },
  }),
}))
vi.mock('@/hooks/useMyIdentity', () => ({
  useMyIdentity: () => ({ identity: { userId: USER_ID }, isLoading: false }),
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
    refetch: vi.fn(),
    rawResponse: undefined,
  }),
}))
vi.mock('@/hooks/useRecordMutation', () => ({
  useRecordMutation: () => ({
    create: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
    update: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
    patch: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
    remove: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
    bulkDelete: { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false },
  }),
}))
vi.mock('@/hooks/useSystemPermissions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useSystemPermissions')>()),
  useSystemPermissions: () => ({ hasPermission: () => false, isLoading: false }),
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
  QuickActionsMenu: () => null,
}))
// Render the shadcn DropdownMenu inline so the renderer switcher's items are
// clickable in jsdom (Radix pointer capture) — same approach as QuickActionsMenu.test.
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: React.PropsWithChildren<{ asChild?: boolean }>) => (
    <div>{children}</div>
  ),
  DropdownMenuContent: ({ children, ...props }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div {...props}>{children}</div>
  ),
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
  DropdownMenuCheckboxItem: ({
    children,
    onCheckedChange,
    ...props
  }: React.PropsWithChildren<{ onCheckedChange?: (v: boolean) => void } & Record<string, unknown>>) => (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/interactive-supports-focus
    <div role="menuitemcheckbox" onClick={() => onCheckedChange?.(true)} {...props}>
      {children}
    </div>
  ),
}))

function renderList() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // useCollectionPermissions reads this cache directly and reports isLoading until
  // it is populated — without it the page never leaves its spinner.
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

describe('ObjectListPage — a published shared view', () => {
  beforeEach(() => {
    listViewRows = []
    preferenceRows = []
    postedPreferences.length = 0
    localStorage.clear()
    getList.mockClear()
  })

  it('opens a PUBLIC KANBAN view as a board, with lanes on the published lane field', async () => {
    listViewRows = [SHARED_KANBAN_ROW]

    renderList()

    expect(await screen.findByTestId('kanban-board')).toBeInTheDocument()
    // lanes come from the published laneField's picklist values, not from a
    // toolbar choice the user never made
    expect(screen.getByTestId('kanban-lane-open')).toBeInTheDocument()
    expect(screen.getByTestId('kanban-lane-done')).toBeInTheDocument()
    expect(screen.getByTestId('kanban-card-r-1')).toBeInTheDocument()
  })

  it('renders a shared view without a viewType as a table, exactly as before', async () => {
    listViewRows = [{ ...SHARED_KANBAN_ROW, viewType: undefined, typeConfig: undefined }]

    renderList()

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.queryByTestId('kanban-board')).toBeNull()
  })

  it('falls back to a table for an unrecognized viewType instead of erroring', async () => {
    listViewRows = [{ ...SHARED_KANBAN_ROW, viewType: 'TIMELINE' }]

    renderList()

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.queryByTestId('kanban-board')).toBeNull()
  })

  it('lets the user switch the published board back to a table, and persists that per user', async () => {
    listViewRows = [SHARED_KANBAN_ROW]

    renderList()
    expect(await screen.findByTestId('kanban-board')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('view-type-table'))

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.queryByTestId('kanban-board')).toBeNull()
    // stored against this user for this shared view — the published row is untouched
    await waitFor(() => expect(postedPreferences.length).toBeGreaterThan(0))
    const attributes = (
      postedPreferences[0] as { data: { attributes: Record<string, unknown> } }
    ).data.attributes
    expect(attributes.prefType).toBe('list-view-type')
    expect(attributes.prefKey).toBe('projects')
    expect(attributes.value).toEqual({
      'shared:lv-board': { viewType: 'table', typeConfig: { kanban: expect.anything() } },
    })
  })

  it('honours a stored personal override over the published renderer on first open', async () => {
    listViewRows = [SHARED_KANBAN_ROW]
    preferenceRows = [
      {
        id: 'pref-1',
        userId: USER_ID,
        prefType: 'list-view-type',
        prefKey: 'projects',
        value: { 'shared:lv-board': { viewType: 'table' } },
      },
    ]

    renderList()

    // the board is never rendered, not even for a frame the user would see
    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.queryByTestId('kanban-board')).toBeNull()
  })
})
