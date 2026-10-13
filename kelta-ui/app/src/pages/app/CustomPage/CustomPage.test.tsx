import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { Toaster } from 'sonner'
import { OfflineProvider, InMemoryOfflineStore } from '@/offline'
import { CustomPage } from './CustomPage'
import { componentRegistry } from '@/services/componentRegistry'

const mockGet = vi.fn()
const mockGetList = vi.fn()
const mockPostResource = vi.fn()
vi.mock('@/context/ApiContext', () => ({
  useApi: vi.fn(() => ({
    apiClient: {
      get: mockGet,
      getList: mockGetList,
      getOne: vi.fn(),
      post: vi.fn(),
      postResource: mockPostResource,
      put: vi.fn(),
      patch: vi.fn(),
      delete: vi.fn(),
    },
  })),
}))

// The `form` widget embeds the shared RecordForm, which needs the shell's auth/toast/plugin providers.
// CustomPage tests only assert tree wiring, so we stub RecordForm to a marker exposing its collection.
// The real create/edit flow is covered in widgets/builtins/form.test.tsx.
vi.mock('@/components/record/form/RecordForm', () => ({
  RecordForm: ({
    collectionName,
    presentation,
  }: {
    collectionName: string
    presentation: string
  }) => (
    <div
      data-testid="record-form"
      data-collection={collectionName}
      data-presentation={presentation}
    />
  ),
}))

function contract(components: unknown[], title = 'My Page') {
  return { version: '1.0', slug: 'my-page', title, path: '/my-page', tree: { components } }
}

function renderAt(slug = 'my-page') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <>
          <MemoryRouter initialEntries={[`/test-tenant/app/p/${slug}`]}>
            <Routes>
              <Route path=":tenantSlug/app/p/:pageSlug" element={<CustomPage />} />
            </Routes>
          </MemoryRouter>
          <Toaster />
        </>
      </I18nProvider>
    </QueryClientProvider>
  )
}

/** Renders under an OfflineProvider (as in EndUserShell) with an injected in-memory replica. */
function renderWithOffline(store: InMemoryOfflineStore, slug = 'my-page') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <OfflineProvider store={store}>
          <MemoryRouter initialEntries={[`/test-tenant/app/p/${slug}`]}>
            <Routes>
              <Route path=":tenantSlug/app/p/:pageSlug" element={<CustomPage />} />
            </Routes>
          </MemoryRouter>
        </OfflineProvider>
      </I18nProvider>
    </QueryClientProvider>
  )
}

describe('CustomPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    componentRegistry.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows page not found when the render endpoint 404s', async () => {
    mockGet.mockRejectedValueOnce(new Error('not found'))
    renderAt('missing')
    await waitFor(() => expect(screen.getByText('Page not found')).toBeDefined())
  })

  it('renders the component tree from the render contract', async () => {
    mockGet.mockResolvedValueOnce(
      contract([
        { id: 'h', type: 'heading', props: { text: 'Welcome' } },
        { id: 't', type: 'text', props: { content: 'Hello there' } },
        { id: 'b', type: 'button', props: { label: 'Go' } },
      ])
    )
    renderAt()
    await waitFor(() => {
      expect(screen.getByTestId('page-node-heading')).toHaveTextContent('Welcome')
      expect(screen.getByTestId('page-node-text')).toHaveTextContent('Hello there')
      expect(screen.getByTestId('page-node-button')).toHaveTextContent('Go')
    })
  })

  it('renders nested children inside a container', async () => {
    mockGet.mockResolvedValueOnce(
      contract([
        {
          id: 'c',
          type: 'container',
          children: [{ id: 'h2', type: 'heading', props: { text: 'Nested' } }],
        },
      ])
    )
    renderAt()
    await waitFor(() => expect(screen.getByTestId('page-node-heading')).toHaveTextContent('Nested'))
  })

  it('shows an empty-state when the tree has no components', async () => {
    mockGet.mockResolvedValueOnce(contract([]))
    renderAt()
    await waitFor(() => expect(screen.getByTestId('page-empty')).toBeDefined())
  })

  it('falls back to a placeholder for an unknown node type', async () => {
    mockGet.mockResolvedValueOnce(contract([{ id: 'x', type: 'mystery_widget', props: {} }]))
    renderAt()
    await waitFor(() =>
      expect(screen.getByTestId('page-node-unknown')).toHaveTextContent('mystery_widget')
    )
  })

  it('renders a registered plugin component for a custom node type', async () => {
    const Widget = ({ config }: { config?: Record<string, unknown> }) => (
      <div data-testid="plugin-widget">Plugin: {config?.message as string}</div>
    )
    componentRegistry.registerPageComponent('custom_widget', Widget)
    mockGet.mockResolvedValueOnce(
      contract([{ id: 'w', type: 'custom_widget', props: { message: 'hi' } }])
    )
    renderAt()
    await waitFor(() => expect(screen.getByTestId('plugin-widget')).toHaveTextContent('Plugin: hi'))
  })

  it('shows the page title in the breadcrumb', async () => {
    mockGet.mockResolvedValueOnce(contract([], 'Sales Dashboard'))
    renderAt()
    await waitFor(() => {
      expect(screen.getByText('Sales Dashboard')).toBeDefined()
      expect(screen.getByText('Home')).toBeDefined()
    })
  })

  it('binds a table node to a collection and renders the declared columns', async () => {
    mockGet.mockResolvedValueOnce(
      contract([
        {
          id: 'tbl',
          type: 'table',
          props: { dataView: { collection: 'orders', fields: ['id', 'status'] } },
        },
      ])
    )
    mockGetList.mockResolvedValueOnce([
      { id: 'o1', status: 'open' },
      { id: 'o2', status: 'shipped' },
    ])

    renderAt()

    await waitFor(() => {
      expect(screen.getByText('shipped')).toBeInTheDocument()
    })
    expect(screen.getByText('open')).toBeInTheDocument()
    // Fetches the bound collection through the authorized JSON:API path.
    expect(mockGetList).toHaveBeenCalledWith(expect.stringContaining('/api/orders'))
  })

  it('shows a placeholder for a table node without a data source', async () => {
    mockGet.mockResolvedValueOnce(contract([{ id: 'tbl', type: 'table', props: {} }]))
    renderAt()
    await waitFor(() =>
      expect(screen.getByTestId('page-node-table')).toHaveTextContent(/no data source/i)
    )
    expect(mockGetList).not.toHaveBeenCalled()
  })

  it('renders a form node bound to a collection through the embedded RecordForm', async () => {
    mockGet.mockResolvedValueOnce(
      contract([
        {
          id: 'f',
          type: 'form',
          props: { dataView: { collection: 'leads' } },
        },
      ])
    )

    renderAt()

    // The `form` widget mounts the app's record form, embedded, bound to the configured collection.
    await waitFor(() => expect(screen.getByTestId('page-node-form')).toBeInTheDocument())
    const form = screen.getByTestId('record-form')
    expect(form).toHaveAttribute('data-collection', 'leads')
    expect(form).toHaveAttribute('data-presentation', 'embedded')
  })

  it('shows a placeholder for a form node without a data source', async () => {
    mockGet.mockResolvedValueOnce(contract([{ id: 'f', type: 'form', props: {} }]))
    renderAt()
    await waitFor(() =>
      expect(screen.getByTestId('page-node-form')).toHaveTextContent(/no data source/i)
    )
  })

  describe('offline (Rec 2B-2)', () => {
    it('writes the fetched contract through to the offline replica while online', async () => {
      vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
      const store = new InMemoryOfflineStore()
      mockGet.mockResolvedValueOnce(
        contract([{ id: 'h', type: 'heading', props: { text: 'Welcome' } }])
      )

      renderWithOffline(store)

      await waitFor(() =>
        expect(screen.getByTestId('page-node-heading')).toHaveTextContent('Welcome')
      )
      await waitFor(async () =>
        expect(await store.getPageContract('my-page')).toMatchObject({ slug: 'my-page' })
      )
    })

    it('renders the cached contract cold-offline without hitting the network', async () => {
      vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
      const store = new InMemoryOfflineStore()
      await store.putPageContract(
        'my-page',
        contract([{ id: 'h', type: 'heading', props: { text: 'Cached hello' } }])
      )

      renderWithOffline(store)

      await waitFor(() =>
        expect(screen.getByTestId('page-node-heading')).toHaveTextContent('Cached hello')
      )
      expect(mockGet).not.toHaveBeenCalled()
    })

    it('shows the offline-unavailable state cold-offline with no cached contract', async () => {
      vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)

      renderWithOffline(new InMemoryOfflineStore(), 'never-visited')

      await waitFor(() =>
        expect(screen.getByText("This page isn't available offline")).toBeInTheDocument()
      )
      expect(screen.queryByText('Page not found')).not.toBeInTheDocument()
      expect(mockGet).not.toHaveBeenCalled()
    })

    it('falls back to the cached contract when the online fetch fails', async () => {
      vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
      const store = new InMemoryOfflineStore()
      await store.putPageContract(
        'my-page',
        contract([{ id: 'h', type: 'heading', props: { text: 'Stale but served' } }])
      )
      mockGet.mockRejectedValueOnce(new Error('network down'))

      renderWithOffline(store)

      await waitFor(() =>
        expect(screen.getByTestId('page-node-heading')).toHaveTextContent('Stale but served')
      )
    })
  })
})
