/**
 * Inline cell edit on the admin list — optimistic concurrency (record-stack convergence P5),
 * through the real data layer. Since P10 the admin list is the `RecordList` core with inline edit
 * always on for a viewer who can edit (no toggle); the core's own suite is
 * `components/record/list/RecordList.inlineEdit.test.tsx`.
 *
 * List rows carry no ETag, so a cell edit checks the row's `updatedAt` against the stored record
 * (fresh GET) and PATCHes with that GET's ETag as If-Match. A conflict reverts the cell (the
 * editor closes on the refetched list) and says why; nothing is written up front.
 */
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createTestWrapper,
  setupAuthMocks,
  AuthWrapper,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'
import { LiveRegionProvider } from '../../components/LiveRegion'
import { ResourceListPage } from './ResourceListPage'

vi.mock('@/context/CollectionStoreContext', () => ({
  useCollectionStore: () => ({ getCollectionByName: () => undefined, collections: [] }),
}))

const LOADED_AT = '2026-10-01T10:00:00Z'

/** The stored record the single-record GET serves (version + ETag). */
let stored: { name: string; updatedAt: string; etag: string }

function setupHandlers() {
  mockAxios.get.mockImplementation((url: string) => {
    if (url === '/api/me/permissions') {
      return Promise.resolve({
        data: {
          systemPermissions: { MANAGE_DATA: true },
          objectPermissions: {},
          fieldPermissions: {},
        },
      })
    }
    if (url === '/api/collections') {
      return Promise.resolve({
        data: { content: [], totalElements: 0, totalPages: 0, size: 1000, number: 0 },
      })
    }
    if (url.startsWith('/api/collections/')) {
      return Promise.resolve({
        data: {
          data: {
            type: 'collections',
            id: 'col-1',
            attributes: { name: 'users', displayName: 'Users' },
          },
          included: [
            {
              type: 'fields',
              id: 'f1',
              attributes: { name: 'name', displayName: 'Name', type: 'string', required: false },
            },
          ],
        },
      })
    }
    if (url === '/api/users/r1') {
      return Promise.resolve({
        data: {
          data: {
            type: 'users',
            id: 'r1',
            attributes: { name: stored.name, updatedAt: stored.updatedAt },
          },
        },
        headers: { etag: stored.etag },
      })
    }
    if (url.startsWith('/api/users?')) {
      return Promise.resolve({
        data: {
          data: [
            {
              type: 'users',
              id: 'r1',
              attributes: { name: 'John Doe', updatedAt: LOADED_AT },
            },
          ],
          metadata: { totalCount: 1, currentPage: 1, pageSize: 25 },
        },
      })
    }
    return Promise.reject(createAxiosError(404))
  })
}

function renderList() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  createTestWrapper()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/resources/users']}>
        <AuthWrapper>
          <LiveRegionProvider>
            <Routes>
              <Route path="/resources/:collection" element={<ResourceListPage />} />
            </Routes>
          </LiveRegionProvider>
        </AuthWrapper>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

async function editName(value: string) {
  fireEvent.click(await screen.findByTestId('inline-field-name'))
  const input = screen.getByRole('textbox')
  fireEvent.change(input, { target: { value } })
  fireEvent.keyDown(input, { key: 'Enter' })
}

function listFetches(): number {
  return mockAxios.get.mock.calls.filter(([url]) => String(url).startsWith('/api/users?')).length
}

describe('ResourceListPage — inline cell edit concurrency', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
    stored = { name: 'John Doe', updatedAt: LOADED_AT, etag: '"v1"' }
    setupHandlers()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.clearAllMocks()
  })

  it('offers the cell editor without a toggle', async () => {
    renderList()

    expect(await screen.findByTestId('inline-field-name')).toBeInTheDocument()
    expect(screen.queryByTestId('inline-edit-toggle')).toBeNull()
  })

  it("PATCHes the cell with the record's fresh ETag as If-Match", async () => {
    mockAxios.patch.mockResolvedValueOnce({ data: {} })
    renderList()
    await editName('Jane')

    await waitFor(() => expect(mockAxios.patch).toHaveBeenCalled())
    const [url, body, config] = mockAxios.patch.mock.calls[0]
    expect(url).toBe('/api/users/r1')
    expect(body).toEqual({ data: { type: 'users', id: 'r1', attributes: { name: 'Jane' } } })
    expect(config).toEqual({ headers: { 'If-Match': '"v1"' } })
  })

  it('reverts without writing when the record changed after the list loaded', async () => {
    stored = { name: 'Someone else', updatedAt: '2026-10-01T10:05:00Z', etag: '"v2"' }
    renderList()
    const before = await waitFor(() => {
      const count = listFetches()
      expect(count).toBeGreaterThan(0)
      return count
    })
    await editName('Jane')

    expect(
      await screen.findByText(
        'This record was changed by someone else, so your edit was not saved. The latest values are shown.'
      )
    ).toBeInTheDocument()
    expect(mockAxios.patch).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(listFetches()).toBeGreaterThan(before)
  })

  it('reverts when the worker rejects the write as a stale write (409)', async () => {
    mockAxios.patch.mockRejectedValueOnce(
      createAxiosError(409, {
        errors: [
          { status: '409', code: 'STALE_WRITE', title: 'Conflict', detail: 'Record was modified' },
        ],
      })
    )
    renderList()
    await editName('Jane')

    expect(
      await screen.findByText(
        'This record was changed by someone else, so your edit was not saved. The latest values are shown.'
      )
    ).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
  })
})
