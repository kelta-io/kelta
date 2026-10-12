/**
 * Admin record list on the shared record-list data layer (record-stack convergence P8).
 *
 * ResourceListPage reads through the same hooks as the end-user ObjectListPage —
 * `useCollectionSchema`, `useCollectionRecords` (`['collection-records', …]`), `useRecordMutation`
 * and the observed `useCollectionPermissions` — and resets its list state when the route moves to
 * another collection without remounting.
 */
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MemoryRouter, Routes, Route, Link } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createTestWrapper,
  setupAuthMocks,
  AuthWrapper,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'
import { MY_PERMISSIONS_QUERY_KEY } from '../../hooks/useSystemPermissions'
import { ResourceListPage } from './ResourceListPage'

function schemaResponse(name: string, displayName: string) {
  return {
    data: { type: 'collections', id: `col-${name}`, attributes: { name, displayName } },
    included: [
      {
        type: 'fields',
        id: `${name}-f1`,
        attributes: { name: 'name', displayName: 'Name', type: 'string', required: false },
      },
    ],
  }
}

function recordsResponse(collection: string, rows: Array<{ id: string; name: string }>) {
  return {
    data: rows.map((row) => ({ type: collection, id: row.id, attributes: { name: row.name } })),
    metadata: { totalCount: rows.length, currentPage: 1, pageSize: 25 },
  }
}

function setupHandlers() {
  mockAxios.get.mockImplementation((url: string) => {
    if (url === '/api/collections') {
      return Promise.resolve({
        data: { content: [], totalElements: 0, totalPages: 0, size: 1000, number: 0 },
      })
    }
    if (url.startsWith('/api/collections/users')) {
      return Promise.resolve({ data: schemaResponse('users', 'Users') })
    }
    if (url.startsWith('/api/collections/accounts')) {
      return Promise.resolve({ data: schemaResponse('accounts', 'Accounts') })
    }
    if (url.startsWith('/api/users?')) {
      return Promise.resolve({
        data: recordsResponse('users', [
          { id: 'r1', name: 'John Doe' },
          { id: 'r2', name: 'Jane Smith' },
        ]),
      })
    }
    if (url.startsWith('/api/accounts?')) {
      return Promise.resolve({ data: recordsResponse('accounts', [{ id: 'a1', name: 'Acme' }]) })
    }
    return Promise.reject(createAxiosError(404))
  })
}

function renderList(initialEntry = '/resources/users') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  createTestWrapper()
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <AuthWrapper>
          <Link to="/resources/accounts" data-testid="go-accounts">
            Accounts
          </Link>
          <Routes>
            <Route path="/resources/:collection" element={<ResourceListPage />} />
          </Routes>
        </AuthWrapper>
      </MemoryRouter>
    </QueryClientProvider>
  )
  return { queryClient }
}

/** Query params of every records request made for `collection`, in call order. */
function recordRequests(collection: string): URLSearchParams[] {
  return mockAxios.get.mock.calls
    .map(([url]) => String(url))
    .filter((url) => url.startsWith(`/api/${collection}?`))
    .map((url) => new URLSearchParams(url.split('?')[1]))
}

describe('ResourceListPage — shared record-list data layer', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn()
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
    setupHandlers()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.clearAllMocks()
  })

  it("reads records through the end-user list's collection-records cache entry", async () => {
    const { queryClient } = renderList()
    expect(await screen.findByText('John Doe')).toBeInTheDocument()

    expect(
      queryClient.getQueryCache().findAll({ queryKey: ['collection-records', 'users'] })
    ).not.toHaveLength(0)
    expect(queryClient.getQueryCache().findAll({ queryKey: ['resources'] })).toHaveLength(0)
  })

  it('seeds sort, page and page size from the shared list URL format', async () => {
    renderList('/resources/users?sort=-name&page=2&pageSize=50')

    await waitFor(() => expect(recordRequests('users').length).toBeGreaterThan(0))
    const params = recordRequests('users')[0]
    expect(params.get('sort')).toBe('-name')
    expect(params.get('page[number]')).toBe('2')
    expect(params.get('page[size]')).toBe('50')
  })

  it('resets filters, sort and paging when the route switches to another collection', async () => {
    const filter = JSON.stringify([{ id: 'f1', field: 'name', operator: 'equals', value: 'John' }])
    renderList(`/resources/users?filter=${encodeURIComponent(filter)}&sort=-name&page=3`)

    await waitFor(() => expect(recordRequests('users').length).toBeGreaterThan(0))
    expect(recordRequests('users')[0].get('filter[name][eq]')).toBe('John')
    expect(screen.getByTestId('filter-count')).toHaveTextContent('1')

    // Same route element, new :collection param — no remount from the router.
    fireEvent.click(screen.getByTestId('go-accounts'))

    expect(await screen.findByText('Acme')).toBeInTheDocument()
    const accounts = recordRequests('accounts')
    expect(accounts.length).toBeGreaterThan(0)
    for (const params of accounts) {
      expect([...params.keys()].some((key) => key.startsWith('filter['))).toBe(false)
      expect(params.get('sort')).toBeNull()
      expect(params.get('page[number]')).toBe('1')
    }
    expect(screen.queryByTestId('filter-count')).not.toBeInTheDocument()
  })

  it('bulk-deletes through the shared record mutation and refreshes the list', async () => {
    mockAxios.post.mockResolvedValueOnce({ data: {} })
    renderList()
    await screen.findByText('John Doe')
    const before = recordRequests('users').length

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select row r1' }))
    fireEvent.click(await screen.findByTestId('bulk-delete-button'))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
    const [url, body] = mockAxios.post.mock.calls[0]
    expect(url).toBe('/api/users/bulk-delete')
    expect(body).toEqual({ ids: ['r1'] })
    await waitFor(() => expect(recordRequests('users').length).toBeGreaterThan(before))
    await waitFor(() => expect(screen.queryByTestId('bulk-actions')).not.toBeInTheDocument())
  })

  it('keeps the selection and shows the error when a bulk delete fails', async () => {
    mockAxios.post.mockRejectedValueOnce(
      createAxiosError(403, {
        errors: [{ status: '403', title: 'Forbidden', detail: 'Not allowed to delete' }],
      })
    )
    renderList()
    await screen.findByText('John Doe')

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select row r1' }))
    fireEvent.click(await screen.findByTestId('bulk-delete-button'))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
    expect(await screen.findByText('Not allowed to delete')).toBeInTheDocument()
    expect(screen.getByTestId('bulk-actions')).toBeInTheDocument()
  })

  it('re-renders when the permissions land after the list rendered', async () => {
    const { queryClient } = renderList()
    await screen.findByText('John Doe')
    // Permissive defaults while the shell's permissions fetch is in flight.
    expect(screen.getByTestId('create-record-button')).toBeInTheDocument()

    act(() => {
      queryClient.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
        systemPermissions: {},
        objectPermissions: {
          users: { canCreate: false, canRead: true, canEdit: true, canDelete: false },
        },
        fieldPermissions: {},
      })
    })

    await waitFor(() =>
      expect(screen.queryByTestId('create-record-button')).not.toBeInTheDocument()
    )
  })
})
