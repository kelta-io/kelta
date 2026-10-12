/**
 * `RecordList` capabilities added for the admin list (record-stack convergence P10), each a
 * prop rather than a `variant` check: the filter builder (`features.filterBuilder` — AND-only,
 * operator-mapped, written through `model.setFilters`), the toolbar heading switch
 * (`toolbarHeading`), the id column (`features.idColumn`) and the summary stats bar
 * (`features.summaryStats`). The shadcn Select is rendered inline (clickable options) so the
 * builder's operator menu can be driven in jsdom.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { LiveRegionProvider } from '@/components/LiveRegion'
import { MY_PERMISSIONS_QUERY_KEY } from '@/hooks/useSystemPermissions'
import type { UseCollectionRecordsOptions } from '@/hooks/useCollectionRecords'
import { recordRoutes } from '@/utils/recordRoutes'
import { RecordList, type RecordListProps } from './RecordList'

const FIELDS = [
  { id: 'f-title', name: 'title', displayName: 'Title', type: 'string', required: false },
  { id: 'f-amount', name: 'amount', displayName: 'Amount', type: 'number', required: false },
]
const RECORDS = [
  { id: 'r-1', title: 'First', amount: 3 },
  { id: 'r-2', title: 'Second', amount: 4 },
]

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
      data: RECORDS,
      total: 12,
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
  usePicklistOptions: () => ({ options: [], isLoading: false }),
  usePicklistDisplayMap: () => ({ displayMap: new Map(), isLoading: false }),
  usePicklistDisplayMaps: () => ({ displayMaps: {}, isLoading: false }),
}))
vi.mock('@/context/CollectionStoreContext', () => ({
  useCollectionStore: () => ({ getCollectionByName: () => undefined, collections: [] }),
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
vi.mock('@/components/ui/select', async () => {
  const { createContext, useContext } = await import('react')
  const Pick = createContext<(value: string) => void>(() => {})
  return {
    Select: ({
      children,
      onValueChange,
    }: React.PropsWithChildren<{ onValueChange?: (value: string) => void }>) => (
      <Pick.Provider value={onValueChange ?? (() => {})}>
        <div>{children}</div>
      </Pick.Provider>
    ),
    SelectTrigger: ({ children, ...props }: React.PropsWithChildren<Record<string, unknown>>) => (
      <div {...props}>{children}</div>
    ),
    SelectValue: () => null,
    SelectContent: ({ children }: React.PropsWithChildren) => <div role="listbox">{children}</div>,
    SelectItem: function SelectItem({
      children,
      value,
    }: React.PropsWithChildren<{ value: string }>) {
      const pick = useContext(Pick)
      return (
        // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/interactive-supports-focus
        <div role="option" aria-selected={false} data-value={value} onClick={() => pick(value)}>
          {children}
        </div>
      )
    },
  }
})

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{decodeURIComponent(location.search)}</div>
}

function renderList(props: Partial<RecordListProps> = {}, url = '/acme/resources/projects') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const allowed = { canRead: true, canCreate: true, canEdit: true, canDelete: true }
  qc.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
    systemPermissions: {},
    objectPermissions: { projects: allowed },
    fieldPermissions: {},
  })
  return render(
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
                      variant="admin"
                      collectionName="projects"
                      tenantSlug="acme"
                      routes={recordRoutes('admin', 'acme')}
                      permissionBackPath="/acme/resources"
                      urlState="sync"
                      {...props}
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
}

const lastFilters = () => recordsCalls[recordsCalls.length - 1].filters

/** Pick an option from one of the builder's (inline-rendered) selects. */
function pick(triggerTestId: string, value: string) {
  const select = screen.getByTestId(triggerTestId).parentElement as HTMLElement
  const option = within(select)
    .getAllByRole('option')
    .find((o) => o.getAttribute('data-value') === value)
  if (!option) throw new Error(`no option ${value} under ${triggerTestId}`)
  fireEvent.click(option)
}

describe('RecordList — filter builder', () => {
  beforeEach(() => {
    recordsCalls.length = 0
    localStorage.clear()
  })

  it('is off unless the feature is on', async () => {
    renderList()
    expect(await screen.findByTestId('data-table')).toBeInTheDocument()
    expect(screen.queryByTestId('filter-toggle')).toBeNull()
    expect(screen.queryByTestId('list-filter-builder')).toBeNull()
  })

  it('opens from the toggle and applies a mapped, AND-only condition to the fetch and the URL', async () => {
    renderList({ features: { filterBuilder: true } })

    fireEvent.click(await screen.findByTestId('filter-toggle'))
    const panel = screen.getByTestId('list-filter-builder')
    // no OR, no null checks: only the operators the list grammar can express
    fireEvent.click(within(panel).getByTestId('list-filter-add'))
    fireEvent.click(within(panel).getByTestId('list-filter-add'))
    expect(within(panel).queryByTestId('list-filter-logic')).toBeNull()
    expect(within(panel).getByTestId('list-filter-and-note')).toBeInTheDocument()
    const ops = within(screen.getByTestId('list-filter-op-0').parentElement as HTMLElement)
      .getAllByRole('option')
      .map((o) => o.getAttribute('data-value'))
    expect(ops).not.toContain('is_null')
    expect(ops).toContain('gte')

    pick('list-filter-field-0', 'amount')
    pick('list-filter-op-0', 'gte')
    fireEvent.change(screen.getByTestId('list-filter-value-0'), { target: { value: '10' } })
    // the second clause stays blank, so it is dropped
    fireEvent.click(screen.getByTestId('list-filter-apply'))

    await waitFor(() =>
      expect(lastFilters()).toEqual([
        { id: 'f1', field: 'amount', operator: 'greater_than_or_equal', value: '10' },
      ])
    )
    expect(screen.getByTestId('location')).toHaveTextContent(
      '?filter=[{"id":"f1","field":"amount","operator":"greater_than_or_equal","value":"10"}]'
    )
    expect(screen.getByTestId('filter-count')).toHaveTextContent('1')
  })

  it('starts from the applied conditions (list operators shown as builder operators)', async () => {
    const applied = JSON.stringify([
      { id: 'x', field: 'amount', operator: 'greater_than', value: '5' },
    ])
    renderList({ features: { filterBuilder: true } }, `/acme/resources/projects?filter=${applied}`)

    fireEvent.click(await screen.findByTestId('filter-toggle'))
    expect(screen.getByTestId('list-filter-value-0')).toHaveValue('5')
    fireEvent.change(screen.getByTestId('list-filter-value-0'), { target: { value: '7' } })
    fireEvent.click(screen.getByTestId('list-filter-apply'))

    await waitFor(() =>
      expect(lastFilters()).toEqual([
        { id: 'f1', field: 'amount', operator: 'greater_than', value: '7' },
      ])
    )
  })

  it('restarts the draft when a chip removes a condition, and Clear all removes them all', async () => {
    const applied = JSON.stringify([
      { id: 'a', field: 'title', operator: 'contains', value: 'Fir' },
      { id: 'b', field: 'amount', operator: 'less_than', value: '9' },
    ])
    renderList({ features: { filterBuilder: true } }, `/acme/resources/projects?filter=${applied}`)

    fireEvent.click(await screen.findByTestId('filter-toggle'))
    expect(screen.getByTestId('list-filter-clause-1')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^Remove filter: amount/i }))
    await waitFor(() => expect(screen.queryByTestId('list-filter-clause-1')).toBeNull())

    fireEvent.click(screen.getByTestId('list-filter-clear'))
    await waitFor(() => expect(lastFilters()).toBeUndefined())
    expect(screen.queryByTestId('list-filter-clause-0')).toBeNull()
  })

  it('gives way to a caller filterEditor slot', async () => {
    renderList({
      features: { filterBuilder: true },
      filterEditor: <div data-testid="custom-editor" />,
    })

    fireEvent.click(await screen.findByTestId('filter-toggle'))
    expect(screen.getByTestId('custom-editor')).toBeInTheDocument()
    expect(screen.queryByTestId('list-filter-builder')).toBeNull()
  })
})

describe('RecordList — admin chrome capabilities', () => {
  beforeEach(() => {
    recordsCalls.length = 0
    localStorage.clear()
  })

  it('hides the toolbar title and New with toolbarHeading={false}; the header slot owns them', async () => {
    renderList({
      toolbarHeading: false,
      header: ({ collectionLabel }) => <h1>{collectionLabel} header</h1>,
    })

    expect(await screen.findByRole('heading', { name: 'Projects header' })).toBeInTheDocument()
    expect(screen.getAllByRole('heading')).toHaveLength(1)
    expect(screen.queryByTestId('new-record-button')).toBeNull()
    expect(screen.getByRole('button', { name: /Actions/ })).toBeInTheDocument()
  })

  it('leads the default columns with a read-only id column when idColumn is on', async () => {
    renderList({ features: { idColumn: true, columnChooser: true, inlineEdit: true } })

    await screen.findByTestId('data-table')
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent?.trim())
    expect(headers.slice(1, 4)).toEqual(['ID', 'Title', 'Amount'])
    expect(screen.getByText('r-1')).toBeInTheDocument()
    // inline edit is on, yet the id cell has no editor
    expect(screen.getAllByTestId('inline-field-title')).toHaveLength(2)
    expect(screen.queryByTestId('inline-field-id')).toBeNull()
  })

  it('has no id column without the feature', async () => {
    renderList()

    await screen.findByTestId('data-table')
    expect(screen.queryByRole('columnheader', { name: /^ID/ })).toBeNull()
    expect(screen.queryByText('r-1')).toBeNull()
  })

  it('shows the summary stats bar of the loaded page only with summaryStats', async () => {
    const { unmount } = renderList()
    await screen.findByTestId('data-table')
    expect(screen.queryByTestId('summary-stats-bar')).toBeNull()
    unmount()

    renderList({ features: { summaryStats: true } })
    expect(await screen.findByTestId('summary-stats-bar')).toBeInTheDocument()
    expect(screen.getByTestId('summary-stats-bar')).toHaveTextContent('7')
  })
})
