/**
 * ObjectListPage — the end-user wrapper over the `RecordList` core. Covers only what the
 * wrapper maps: the route's collection, the end-user feature set, URL-as-state, end-user record
 * routes, the breadcrumb, and that the reused route element starts fresh on another collection
 * (record-stack convergence P9). The core's behaviour is in `components/record/list/`.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { LiveRegionProvider } from '@/components/LiveRegion'
import { MY_PERMISSIONS_QUERY_KEY } from '@/hooks/useSystemPermissions'
import { ObjectListPage } from './ObjectListPage'

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

/** A PUBLIC default kanban view published for `tasks` only. */
const TASKS_BOARD = {
  id: 'lv-tasks',
  name: 'Task board',
  columns: ['title', 'status'],
  filters: [],
  isDefault: true,
  visibility: 'PUBLIC',
  viewType: 'KANBAN',
  typeConfig: { kanban: { laneField: 'status' } },
}

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({
    apiClient: {
      getList: vi.fn(async (url: string) =>
        url.startsWith('/api/list-views') && url.includes('col-tasks') ? [TASKS_BOARD] : []
      ),
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
    schema: {
      id: `col-${name}`,
      name,
      displayName: name === 'tasks' ? 'Tasks' : 'Projects',
      displayFieldName: 'title',
    },
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

/** Shows the current URL and navigates elsewhere, as the app's sidebar would. */
function RouterProbe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <div data-testid="location">{location.pathname + location.search}</div>
      <button type="button" onClick={() => navigate('/acme/app/o/tasks')}>
        go to tasks
      </button>
    </>
  )
}

function renderPage(url = '/acme/app/o/projects') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const allowed = { canRead: true, canCreate: true, canEdit: true, canDelete: true }
  qc.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
    systemPermissions: {},
    objectPermissions: { projects: allowed, tasks: allowed },
    fieldPermissions: {},
  })
  return render(
    <I18nProvider>
      <LiveRegionProvider>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={[url]}>
            <Routes>
              <Route path="/:tenantSlug/app/o/:collection" element={<ObjectListPage />} />
              <Route path="*" element={null} />
            </Routes>
            <RouterProbe />
          </MemoryRouter>
        </QueryClientProvider>
      </LiveRegionProvider>
    </I18nProvider>
  )
}

describe('ObjectListPage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it("renders the route's collection with the end-user breadcrumb", async () => {
    renderPage()

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.getByTestId('list-shell')).toHaveAttribute('data-variant', 'enduser')
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/acme/app/home')
    expect(screen.getByRole('heading', { name: /Projects/ })).toBeInTheDocument()
  })

  it('turns on the end-user feature set', async () => {
    renderPage()

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
    expect(screen.getByText('Import CSV')).toBeInTheDocument()
  })

  it('keeps the list state in the URL', async () => {
    renderPage('/acme/app/o/projects?pageSize=50')

    fireEvent.click(await screen.findByRole('columnheader', { name: /Title/ }))

    expect(screen.getByTestId('location')).toHaveTextContent(
      '/acme/app/o/projects?pageSize=50&sort=title'
    )
  })

  it('opens the create form in the end-user stack', async () => {
    renderPage()

    fireEvent.click(await screen.findByTestId('new-record-button'))

    expect(screen.getByTestId('location')).toHaveTextContent('/acme/app/o/projects/new')
  })

  it("starts fresh on another collection and applies that collection's default view", async () => {
    renderPage()
    fireEvent.click(await screen.findByLabelText('Select row r-1'))
    fireEvent.click(screen.getByTestId('group-by-status'))
    expect(screen.getByText('1 record selected')).toBeInTheDocument()
    expect(screen.getByTestId('group-caption')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'go to tasks' }))

    expect(await screen.findByRole('heading', { name: /Tasks/ })).toBeInTheDocument()
    expect(screen.queryByText('1 record selected')).toBeNull()
    expect(screen.queryByTestId('group-caption')).toBeNull()
    // the same route element was reused, yet the new collection's default board applies
    expect(await screen.findByTestId('kanban-board')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/acme/app/o/tasks?view=shared%3Alv-tasks'
      )
    )
  })
})
