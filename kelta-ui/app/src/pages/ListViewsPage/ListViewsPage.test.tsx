/**
 * Setup → List Views: publishing a renderer (K-8 item 11).
 *
 * The form is what an admin actually uses to hand out a board, so these cover the
 * two things that make the row usable: the lane picker offers only picklist fields
 * (lanes are a picklist's values — anything else produces a board with no lanes),
 * and the submitted body carries `viewType` + a `typeConfig` object the end-user
 * list can read back.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { ListViewsPage } from './ListViewsPage'

// Typed as variadic so the assertions can index the call args the page passes.
const create = vi.fn<(...args: unknown[]) => Promise<{ id: string }>>(async () => ({ id: 'lv-1' }))
const update = vi.fn<(...args: unknown[]) => Promise<{ id: string }>>(async () => ({ id: 'lv-1' }))

const EXISTING = {
  id: 'lv-1',
  name: 'The board',
  collectionId: 'col-1',
  visibility: 'PUBLIC',
  columns: ['title'],
  filters: [],
  sortField: '',
  sortDirection: 'ASC',
  createdBy: 'u1',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  viewType: 'KANBAN',
  typeConfig: { kanban: { laneField: 'status', cardFields: ['title'] } },
}

let listRows: unknown[] = []

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({
    keltaClient: {
      admin: {
        listViews: {
          list: vi.fn(async () => listRows),
          create: (...args: unknown[]) => create(...(args as [])),
          update: (...args: unknown[]) => update(...(args as [])),
          delete: vi.fn(async () => undefined),
        },
      },
    },
  }),
}))
vi.mock('../../hooks/useCollectionSummaries', () => ({
  useCollectionSummaries: () => ({
    summaries: [{ id: 'col-1', name: 'projects', displayName: 'Projects' }],
    isLoading: false,
    error: null,
  }),
}))
vi.mock('../../hooks/useCollectionSchema', () => ({
  useCollectionSchema: () => ({
    schema: { id: 'col-1', name: 'projects' },
    fields: [
      { id: 'f-title', name: 'title', displayName: 'Title', type: 'string' },
      { id: 'f-status', name: 'status', displayName: 'Status', type: 'picklist' },
      { id: 'f-stage', name: 'stage', displayName: 'Stage', type: 'picklist' },
      { id: 'f-due', name: 'dueAt', displayName: 'Due', type: 'date' },
    ],
    isLoading: false,
    error: null,
  }),
}))
vi.mock('../../components', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  useToast: () => ({ showToast: vi.fn() }),
}))

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <I18nProvider>
      <QueryClientProvider client={qc}>
        <ListViewsPage />
      </QueryClientProvider>
    </I18nProvider>
  )
}

describe('ListViewsPage renderer editors', () => {
  beforeEach(() => {
    listRows = []
    create.mockClear()
    update.mockClear()
  })

  it('offers the kanban editors only for a KANBAN view, scoped to picklist fields', async () => {
    renderPage()
    fireEvent.click(await screen.findByTestId('add-listview-button'))

    fireEvent.change(screen.getByTestId('listview-collectionId-input'), {
      target: { value: 'col-1' },
    })
    // a table view has no lane picker at all
    expect(screen.queryByTestId('listview-laneField-input')).toBeNull()

    fireEvent.change(screen.getByTestId('listview-viewType-input'), {
      target: { value: 'KANBAN' },
    })

    const laneSelect = await screen.findByTestId('listview-laneField-input')
    const laneOptions = [...laneSelect.querySelectorAll('option')].map((o) => o.value)
    // lanes are a picklist's values — a date or text field cannot define them
    expect(laneOptions).toEqual(['', 'status', 'stage'])
  })

  it('posts viewType and a typeConfig object when a board is published', async () => {
    renderPage()
    fireEvent.click(await screen.findByTestId('add-listview-button'))

    fireEvent.change(screen.getByTestId('listview-name-input'), { target: { value: 'The board' } })
    fireEvent.change(screen.getByTestId('listview-collectionId-input'), {
      target: { value: 'col-1' },
    })
    fireEvent.change(screen.getByTestId('listview-visibility-input'), {
      target: { value: 'PUBLIC' },
    })
    fireEvent.change(screen.getByTestId('listview-viewType-input'), {
      target: { value: 'KANBAN' },
    })
    fireEvent.change(await screen.findByTestId('listview-laneField-input'), {
      target: { value: 'status' },
    })
    fireEvent.click(await screen.findByTestId('listview-card-add-column-title'))
    fireEvent.click(screen.getByTestId('listview-form-submit'))

    await waitFor(() => expect(create).toHaveBeenCalled())
    const payload = create.mock.calls[0][2] as Record<string, unknown>
    expect(payload.viewType).toBe('KANBAN')
    expect(payload.visibility).toBe('PUBLIC')
    // an object, not a JSON string: the end-user list reads this shape directly
    expect(payload.typeConfig).toEqual({ kanban: { laneField: 'status', cardFields: ['title'] } })
  })

  it('keeps renderer settings it has no editor for when a view is edited', async () => {
    listRows = [
      {
        ...EXISTING,
        viewType: 'CALENDAR',
        typeConfig: { calendar: { dateField: 'dueAt' } },
      },
    ]
    renderPage()
    fireEvent.click(await screen.findByTestId('edit-button-0'))

    fireEvent.change(screen.getByTestId('listview-name-input'), { target: { value: 'Renamed' } })
    fireEvent.click(screen.getByTestId('listview-form-submit'))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const payload = update.mock.calls[0][1] as Record<string, unknown>
    // the calendar section is authorable via API/CLI/MCP; renaming the view here
    // must not silently drop it
    expect(payload.viewType).toBe('CALENDAR')
    expect(payload.typeConfig).toEqual({
      calendar: { dateField: 'dueAt', endDateField: undefined },
    })
  })

  it('sends a null typeConfig for a plain table view', async () => {
    renderPage()
    fireEvent.click(await screen.findByTestId('add-listview-button'))

    fireEvent.change(screen.getByTestId('listview-name-input'), { target: { value: 'Plain' } })
    fireEvent.change(screen.getByTestId('listview-collectionId-input'), {
      target: { value: 'col-1' },
    })
    fireEvent.click(screen.getByTestId('listview-form-submit'))

    await waitFor(() => expect(create).toHaveBeenCalled())
    const payload = create.mock.calls[0][2] as Record<string, unknown>
    expect(payload.viewType).toBe('TABLE')
    expect(payload.typeConfig).toBeNull()
  })
})
