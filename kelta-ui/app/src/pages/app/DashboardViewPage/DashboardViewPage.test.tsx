import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '../../../context/I18nContext'
import { DashboardViewPage } from './DashboardViewPage'

const mockGetList = vi.fn()
const mockPost = vi.fn()
const mockNavigate = vi.fn()

vi.mock('../../../context/ApiContext', () => ({
  useApi: vi.fn(() => ({
    apiClient: {
      getList: (...a: unknown[]) => mockGetList(...a),
      post: (...a: unknown[]) => mockPost(...a),
    },
  })),
}))
vi.mock('../../../context/CollectionStoreContext', () => ({
  useCollectionStore: vi.fn(() => ({
    collections: [{ id: 'col-orders', name: 'orders' }],
  })),
}))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <I18nProvider>
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/acme/app/dashboards/dash-1']}>
          <Routes>
            <Route path="/:tenantSlug/app/dashboards/:id" element={<DashboardViewPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </I18nProvider>
  )
}

const COMPONENTS = [
  {
    id: 'c-metric',
    componentType: 'metric',
    title: 'Open deals',
    columnPosition: 1,
    rowPosition: 1,
    columnSpan: 1,
    rowSpan: 1,
    sortOrder: 1,
    config: {},
  },
  {
    id: 'c-chart',
    componentType: 'chart',
    title: 'By stage',
    columnPosition: 2,
    rowPosition: 1,
    columnSpan: 2,
    rowSpan: 2,
    sortOrder: 2,
    config: { collectionId: 'col-orders' },
  },
  {
    id: 'c-broken',
    componentType: 'table',
    title: 'Masked',
    columnPosition: 1,
    rowPosition: 2,
    columnSpan: 1,
    rowSpan: 1,
    sortOrder: 3,
    config: {},
  },
]

const TIME_SCOPE_COMPONENTS = [
  ...COMPONENTS,
  {
    id: 'c-ignore',
    componentType: 'metric',
    title: 'In progress',
    columnPosition: 2,
    rowPosition: 2,
    columnSpan: 1,
    rowSpan: 1,
    sortOrder: 4,
    config: { ignoreTimeRange: true },
  },
  {
    id: 'c-fixed',
    componentType: 'metric',
    title: 'New this week',
    columnPosition: 3,
    rowPosition: 2,
    columnSpan: 1,
    rowSpan: 1,
    sortOrder: 5,
    config: { fixedTimeRange: '7D' },
  },
]

const WIDGETS = {
  'c-metric': { type: 'metric', data: { value: 42, label: 'Open' } },
  'c-chart': {
    type: 'chart',
    data: {
      groupByField: 'stage',
      series: [
        { label: 'Won', count: 3, value: 3 },
        { label: '(empty)', count: 1, value: 1 },
      ],
    },
  },
  'c-broken': { error: 'Cannot filter on a masked field: ssn' },
}

const TIME_SCOPE_WIDGETS = {
  ...WIDGETS,
  'c-ignore': { type: 'metric', data: { value: 3, label: 'In progress' } },
  'c-fixed': { type: 'metric', data: { value: 5, label: 'New this week' } },
}

beforeEach(() => {
  vi.clearAllMocks()
  mockGetList.mockResolvedValue(COMPONENTS)
  mockPost.mockResolvedValue({
    data: { attributes: { dashboardName: 'Sales Overview', widgets: WIDGETS } },
  })
})

describe('DashboardViewPage', () => {
  it('joins layout rows with widget payloads and renders per type', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Sales Overview')).toBeTruthy())

    expect(screen.getByTestId('metric-widget').textContent).toContain('42')
    expect(screen.getByTestId('chart-widget')).toBeTruthy()
    const error = screen.getByTestId('widget-error')
    expect(error.textContent).toContain('Cannot filter on a masked field: ssn')

    const listUrl = mockGetList.mock.calls[0][0] as string
    expect(listUrl).toContain('filter[dashboardId][eq]=dash-1')
    expect(mockPost).toHaveBeenCalledWith('/api/dashboards/dash-1/data', { timeRange: '30D' })
  })

  it('posts the default preset time range (re-query on change rides the queryKey)', async () => {
    // Opening the Radix Select needs scrollIntoView (absent in jsdom); the range→body
    // mapping and the timeRange-in-queryKey re-query live in useDashboardData and are
    // asserted through the initial call here.
    renderPage()
    await waitFor(() => expect(mockPost).toHaveBeenCalled())
    expect(mockPost).toHaveBeenCalledWith('/api/dashboards/dash-1/data', { timeRange: '30D' })
    expect(screen.getByTestId('time-range-select')).toBeTruthy()
  })

  it('renders a chip on widgets that opt out of the page time range', async () => {
    mockGetList.mockResolvedValue(TIME_SCOPE_COMPONENTS)
    mockPost.mockResolvedValue({
      data: { attributes: { dashboardName: 'Sales Overview', widgets: TIME_SCOPE_WIDGETS } },
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Sales Overview')).toBeTruthy())

    const chips = screen.getAllByTestId('widget-time-scope-chip')
    expect(chips).toHaveLength(2)
    expect(chips.map((c) => c.textContent)).toEqual(
      expect.arrayContaining(['All time', 'Last 7 days'])
    )
  })

  it('renders the empty state when the dashboard has no components', async () => {
    mockGetList.mockResolvedValue([])
    mockPost.mockResolvedValue({ data: { attributes: { dashboardName: 'Empty', widgets: {} } } })
    renderPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-empty')).toBeTruthy())
  })
})
