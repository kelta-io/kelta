/**
 * ResourceListPage — the admin wrapper over the `RecordList` core (record-stack convergence
 * P10). Covers what the wrapper maps — the route's collection, the admin frame and header, the
 * admin feature set and the permissions each feature needs, URL-as-state, admin record routes,
 * the permission gate's way back, a fresh list on another collection — plus the shared table's
 * selection and accessibility contract as the admin page renders it. The core's behaviour is in
 * `components/record/list/`; the real data layer end to end is `ResourceListPage.dataLayer`.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { LiveRegionProvider } from '@/components/LiveRegion'
import { MY_PERMISSIONS_QUERY_KEY } from '@/hooks/useSystemPermissions'
import { ResourceListPage } from './ResourceListPage'

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
  { id: 'f-amount', name: 'amount', displayName: 'Amount', type: 'number', required: false },
]
const RECORDS = [
  { id: 'r-1', title: 'First', status: 'open', amount: 2 },
  { id: 'r-2', title: 'Second', status: 'done', amount: 5 },
  { id: 'r-3', title: 'Third', status: 'open', amount: 1 },
]

let manageData = true
let schemaError: Error | null = null
let records: typeof RECORDS = RECORDS

vi.mock('@/context/TenantContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/context/TenantContext')>()),
  getTenantSlug: () => 'acme',
}))
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
      : {
          id: `col-${name}`,
          name,
          displayName: name === 'tasks' ? 'Tasks' : 'Projects',
          displayFieldName: 'title',
        },
    fields: schemaError ? [] : FIELDS,
    isLoading: false,
    error: schemaError,
  }),
}))
vi.mock('@/hooks/useCollectionRecords', () => ({
  useCollectionRecords: () => ({
    data: records,
    total: records.length,
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
  useSystemPermissions: () => ({ hasPermission: () => manageData, isLoading: false }),
}))
vi.mock('@/hooks/usePicklistOptions', () => ({
  usePicklistOptions: () => ({ options: ['open', 'done'], isLoading: false }),
  usePicklistDisplayMap: () => ({ displayMap: new Map(), isLoading: false }),
  usePicklistDisplayMaps: () => ({ displayMaps: {}, isLoading: false }),
}))
vi.mock('@/context/CollectionStoreContext', () => ({
  useCollectionStore: () => ({ getCollectionByName: () => undefined, collections: [] }),
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

/** Shows the current URL and navigates elsewhere, as the admin sidebar would. */
function RouterProbe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <div data-testid="location">{location.pathname + location.search}</div>
      <button type="button" onClick={() => navigate('/acme/resources/tasks')}>
        go to tasks
      </button>
    </>
  )
}

type Perms = { canRead: boolean; canCreate: boolean; canEdit: boolean; canDelete: boolean }
const ALL: Perms = { canRead: true, canCreate: true, canEdit: true, canDelete: true }

function renderPage(url = '/acme/resources/projects', projects: Perms = ALL) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  qc.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
    systemPermissions: {},
    objectPermissions: { projects, tasks: ALL },
    fieldPermissions: {},
  })
  return render(
    <I18nProvider>
      <LiveRegionProvider>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={[url]}>
            <Routes>
              <Route path="/:tenantSlug/resources/:collection" element={<ResourceListPage />} />
              <Route path="*" element={null} />
            </Routes>
            <RouterProbe />
          </MemoryRouter>
        </QueryClientProvider>
      </LiveRegionProvider>
    </I18nProvider>
  )
}

const location = () => screen.getByTestId('location')

describe('ResourceListPage', () => {
  beforeEach(() => {
    manageData = true
    schemaError = null
    records = RECORDS
    localStorage.clear()
    // jsdom has no scrollIntoView; the shared table scrolls the focused row into view.
    Element.prototype.scrollIntoView = vi.fn()
  })

  it('renders the collection in the admin frame with the admin header in place of the toolbar heading', async () => {
    renderPage()

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.getByTestId('resource-list-page')).toHaveAttribute('data-variant', 'admin')
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Resource Browser' })).toHaveAttribute(
      'href',
      '/acme/resources'
    )
    expect(screen.getByRole('heading', { level: 1, name: 'Projects' })).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.queryByTestId('new-record-button')).toBeNull()
  })

  it('turns on the admin feature set', async () => {
    renderPage()

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    for (const id of [
      'view-selector',
      'column-chooser-trigger',
      'view-type-trigger',
      'filter-toggle',
      'summary-stats-bar',
    ]) {
      expect(screen.getByTestId(id)).toBeInTheDocument()
    }
    for (const id of ['density-toggle', 'group-by-trigger', 'quick-actions']) {
      expect(screen.queryByTestId(id)).toBeNull()
    }
    expect(screen.getByText('Export as CSV')).toBeInTheDocument()
    expect(screen.getByText('Export as JSON')).toBeInTheDocument()
    expect(screen.getByText('Import CSV')).toBeInTheDocument()
    // the id column leads the default columns; inline edit needs no toggle
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent?.trim())
    expect(headers.slice(1, 3)).toEqual(['ID', 'Title'])
    expect(screen.getAllByTestId('inline-field-title')).toHaveLength(3)
  })

  it('offers the alternate views', async () => {
    renderPage()

    fireEvent.click(await screen.findByTestId('view-type-kanban'))

    expect(await screen.findByTestId('kanban-board')).toBeInTheDocument()
    expect(location()).toHaveTextContent('/acme/resources/projects')
  })

  it('offers mass edit with edit permission and MANAGE_DATA only', async () => {
    const { unmount } = renderPage()
    fireEvent.click(await screen.findByLabelText('Select row r-1'))
    expect(screen.getByTestId('mass-edit-button')).toBeInTheDocument()
    unmount()

    manageData = false
    const second = renderPage()
    fireEvent.click(await screen.findByLabelText('Select row r-1'))
    expect(screen.getByText('1 record selected')).toBeInTheDocument()
    expect(screen.queryByTestId('mass-edit-button')).toBeNull()
    second.unmount()

    manageData = true
    renderPage('/acme/resources/projects', { ...ALL, canEdit: false })
    fireEvent.click(await screen.findByLabelText('Select row r-1'))
    expect(screen.queryByTestId('mass-edit-button')).toBeNull()
    // and no cell editor without edit permission
    expect(screen.queryByTestId('inline-field-title')).toBeNull()
  })

  it('hides Create Record and Import without create permission', async () => {
    renderPage('/acme/resources/projects', { ...ALL, canCreate: false })

    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.queryByTestId('create-record-button')).toBeNull()
    expect(screen.queryByText('Import CSV')).toBeNull()
    expect(screen.getByText('Export as CSV')).toBeInTheDocument()
  })

  it('opens the create form in the admin stack', async () => {
    renderPage()

    fireEvent.click(await screen.findByTestId('create-record-button'))
    expect(location()).toHaveTextContent('/acme/resources/projects/new')
  })

  it('opens a record in the admin stack from its row', async () => {
    renderPage()

    fireEvent.click(await screen.findByText('Second'))
    expect(location()).toHaveTextContent('/acme/resources/projects/r-2')
  })

  it('opens the edit form in the admin stack from the row actions', async () => {
    renderPage()

    const row = (await screen.findByText('First')).closest('tr') as HTMLElement
    fireEvent.click(within(row).getByRole('menuitem', { name: /Edit/ }))
    expect(location()).toHaveTextContent('/acme/resources/projects/r-1/edit')
  })

  it('keeps the list state in the URL', async () => {
    renderPage('/acme/resources/projects?pageSize=50')

    fireEvent.click(await screen.findByRole('columnheader', { name: /Title/ }))

    expect(location()).toHaveTextContent('/acme/resources/projects?pageSize=50&sort=title')
  })

  it('sends the permission gate back to the Resource Browser', async () => {
    renderPage('/acme/resources/projects', { ...ALL, canRead: false })

    expect(await screen.findByText('Insufficient privileges')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Go Back' }))
    expect(location()).toHaveTextContent('/acme/resources')
  })

  it('shows a schema error with a retry in place of the list', async () => {
    schemaError = new Error('Collection not found')
    renderPage()

    expect(await screen.findByText('Collection not found')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.queryByTestId('data-table')).toBeNull()
  })

  it('says so when the collection has no records', async () => {
    records = []
    renderPage()

    expect(await screen.findByText('No records found.')).toBeInTheDocument()
    expect(screen.queryByTestId('summary-stats-bar')).toBeNull()
    expect(screen.queryByTestId('pagination')).toBeNull()
  })

  it('starts fresh on another collection (the route element is reused)', async () => {
    renderPage('/acme/resources/projects?sort=-title')
    fireEvent.click(await screen.findByLabelText('Select row r-1'))
    expect(screen.getByText('1 record selected')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'go to tasks' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Tasks' })).toBeInTheDocument()
    expect(screen.queryByText('1 record selected')).toBeNull()
    await waitFor(() => expect(location()).toHaveTextContent(/^\/acme\/resources\/tasks$/))
  })

  describe('the shared table as the admin list renders it', () => {
    it('selects every row and clears the selection again from the header checkbox', async () => {
      renderPage()

      fireEvent.click(await screen.findByRole('checkbox', { name: 'Select all rows' }))
      for (const box of screen.getAllByRole('checkbox', { name: /^Select row / })) {
        expect(box).toBeChecked()
      }
      expect(screen.getByText('3 records selected')).toBeInTheDocument()

      fireEvent.click(screen.getByRole('checkbox', { name: 'Deselect all rows' }))
      for (const box of screen.getAllByRole('checkbox', { name: /^Select row / })) {
        expect(box).not.toBeChecked()
      }
      expect(screen.queryByText(/records? selected/)).toBeNull()
    })

    it('labels the grid, its sortable headers and its checkboxes', async () => {
      renderPage()

      expect(await screen.findByRole('grid')).toHaveAttribute('aria-label', 'projects records')
      const header = screen.getByRole('columnheader', { name: /Title/ })
      expect(header).toHaveAttribute('aria-sort', 'none')
      expect(header).toHaveAttribute('tabindex', '0')
      fireEvent.click(header)
      expect(screen.getByRole('columnheader', { name: /Title/ })).toHaveAttribute(
        'aria-sort',
        'ascending'
      )
      expect(screen.getByRole('checkbox', { name: 'Select row r-1' })).toBeInTheDocument()
    })
  })
})
