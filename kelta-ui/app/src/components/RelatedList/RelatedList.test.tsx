import { describe, it, expect, vi, beforeEach, onTestFinished } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RelatedList } from './RelatedList'
import { RecordStackProvider } from '@/context/RecordStackContext'
import { useCollectionStore } from '@/context/CollectionStoreContext'
import type { RecordStackVariant } from '@/utils/recordRoutes'

// Mock API context
const mockGet = vi.fn()
const mockPost = vi.fn()
const mockPatch = vi.fn()
const mockDelete = vi.fn()
vi.mock('@/context/ApiContext', () => ({
  useApi: vi.fn(() => ({
    apiClient: {
      get: mockGet,
      post: mockPost,
      put: vi.fn(),
      patch: mockPatch,
      delete: mockDelete,
    },
  })),
}))

// useToast lives on the @/components barrel; RelatedList uses it for CRUD/mass-edit feedback.
const { mockShowToast } = vi.hoisted(() => ({ mockShowToast: vi.fn() }))
vi.mock('@/components', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

// Mock collection store context
vi.mock('@/context/CollectionStoreContext', () => ({
  useCollectionStore: vi.fn(() => ({
    isLoading: false,
    collections: [],
    getCollectionByName: vi.fn(() => undefined),
    getCollectionById: vi.fn(() => undefined),
    getFieldById: vi.fn(() => undefined),
  })),
}))

// Mock object permissions — mutable so tests can flip individual flags.
const { mockObjectPermissions } = vi.hoisted(() => ({
  mockObjectPermissions: { canCreate: true, canRead: true, canEdit: true, canDelete: true },
}))
vi.mock('@/hooks/useObjectPermissions', () => ({
  useObjectPermissions: vi.fn(() => ({
    permissions: mockObjectPermissions,
    isLoading: false,
  })),
}))

// Mock system permissions (mass edit requires MANAGE_DATA for bulk jobs).
const { mockSystemPermissions } = vi.hoisted(() => ({
  mockSystemPermissions: { MANAGE_DATA: true } as Record<string, boolean>,
}))
vi.mock('@/hooks/useSystemPermissions', () => ({
  useSystemPermissions: vi.fn(() => ({
    permissions: mockSystemPermissions,
    hasPermission: (permission: string) => mockSystemPermissions[permission] === true,
    isLoading: false,
    error: null,
  })),
}))

function TestWrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/test-tenant/app/o/accounts/rec-123']}>
        {children}
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('RelatedList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Drop any queued mockResolvedValueOnce values left over from a prior test.
    // clearAllMocks only clears call history, not the implementation queue.
    mockGet.mockReset()
    mockPost.mockReset()
    mockPatch.mockReset()
    mockDelete.mockReset()
    // Restore default permissions mutated by individual tests.
    Object.assign(mockObjectPermissions, {
      canCreate: true,
      canRead: true,
      canEdit: true,
      canDelete: true,
    })
    mockSystemPermissions.MANAGE_DATA = true
  })

  it('shows empty state when no related records found', async () => {
    // First call for schema
    mockGet.mockResolvedValueOnce({
      content: [{ id: 'col-1', name: 'contacts', displayName: 'Contacts', fields: [] }],
    })
    // Second call for full schema
    mockGet.mockResolvedValueOnce({
      id: 'col-1',
      name: 'contacts',
      displayName: 'Contacts',
      fields: [
        { id: 'f1', name: 'name', type: 'STRING', required: false },
        { id: 'f2', name: 'email', type: 'STRING', required: false },
      ],
    })
    // Third call for related records
    mockGet.mockResolvedValueOnce({
      data: [],
      metadata: { totalCount: 0, currentPage: 1, pageSize: 5, totalPages: 0 },
    })

    render(
      <TestWrapper>
        <RelatedList
          collectionName="contacts"
          foreignKeyField="accountId"
          parentRecordId="rec-123"
          tenantSlug="test-tenant"
        />
      </TestWrapper>
    )

    await waitFor(() => {
      expect(screen.getByText(/No related/)).toBeDefined()
    })
  })

  it('renders collection label as title', async () => {
    // Schema calls
    mockGet.mockResolvedValueOnce({
      content: [{ id: 'col-1', name: 'contacts', displayName: 'Contacts', fields: [] }],
    })
    mockGet.mockResolvedValueOnce({
      id: 'col-1',
      name: 'contacts',
      displayName: 'Contacts',
      fields: [],
    })
    // Related records
    mockGet.mockResolvedValueOnce({
      data: [],
      metadata: { totalCount: 0, currentPage: 1, pageSize: 5, totalPages: 0 },
    })

    render(
      <TestWrapper>
        <RelatedList
          collectionName="contacts"
          foreignKeyField="accountId"
          parentRecordId="rec-123"
          tenantSlug="test-tenant"
        />
      </TestWrapper>
    )

    await waitFor(() => {
      expect(screen.getByText('Contacts')).toBeDefined()
    })
  })

  it('renders custom label when provided', async () => {
    mockGet.mockResolvedValueOnce({
      content: [{ id: 'col-1', name: 'contacts', displayName: 'Contacts', fields: [] }],
    })
    mockGet.mockResolvedValueOnce({
      id: 'col-1',
      name: 'contacts',
      displayName: 'Contacts',
      fields: [],
    })
    mockGet.mockResolvedValueOnce({
      data: [],
      metadata: { totalCount: 0, currentPage: 1, pageSize: 5, totalPages: 0 },
    })

    render(
      <TestWrapper>
        <RelatedList
          collectionName="contacts"
          foreignKeyField="accountId"
          parentRecordId="rec-123"
          tenantSlug="test-tenant"
          label="Team Members"
        />
      </TestWrapper>
    )

    await waitFor(() => {
      expect(screen.getByText('Team Members')).toBeDefined()
    })
  })

  describe('column resolution', () => {
    function mockSchemaWith(fieldNames: string[]) {
      // useCollectionSchema fetches `/api/collections/{name}?include=fields` and
      // expects a JSON:API envelope; field records arrive in the `included` array.
      mockGet.mockResolvedValueOnce({
        data: {
          id: 'col-1',
          type: 'collections',
          attributes: { name: 'contacts', displayName: 'Contacts' },
        },
        included: fieldNames.map((name, i) => ({
          id: `f-${i}`,
          type: 'fields',
          attributes: {
            name,
            displayName: name,
            type: 'STRING',
            required: false,
            active: true,
            fieldOrder: i,
          },
        })),
      })
    }

    function mockOneRecord(values: Record<string, unknown>) {
      mockGet.mockResolvedValueOnce({
        data: [{ id: 'rec-1', accountId: 'rec-123', ...values }],
        metadata: { totalCount: 1, currentPage: 1, pageSize: 5, totalPages: 1 },
      })
    }

    it('renders only configured displayColumns, in given order', async () => {
      mockSchemaWith(['name', 'email', 'phone', 'title'])
      mockOneRecord({ name: 'Ada', email: 'a@x', phone: '555', title: 'Eng' })

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['title', 'email']}
          />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByText('Eng')).toBeDefined()
      })

      const headers = screen.getAllByRole('columnheader').map((el) => el.textContent)
      expect(headers).toEqual(['title', 'email'])
      // 'Ada' is the `name` field — not in displayColumns, so should NOT render
      expect(screen.queryByText('Ada')).toBeNull()
    })

    it('falls back to first MAX_COLUMNS schema fields when displayColumns is empty', async () => {
      mockSchemaWith(['name', 'email', 'phone', 'title', 'notes'])
      mockOneRecord({ name: 'Ada' })

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={[]}
          />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByText('Ada')).toBeDefined()
      })

      const headers = screen.getAllByRole('columnheader').map((el) => el.textContent)
      expect(headers).toEqual(['name', 'email', 'phone', 'title'])
    })

    it('passes sortField as JSON:API sort param (asc default)', async () => {
      mockSchemaWith(['name'])
      mockOneRecord({ name: 'Ada' })

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name']}
            sortField="name"
          />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByText('Ada')).toBeDefined()
      })

      const recordsCall = mockGet.mock.calls.find(([url]) =>
        String(url).startsWith('/api/contacts')
      )
      expect(recordsCall).toBeDefined()
      expect(String(recordsCall![0])).toContain('sort=name')
      expect(String(recordsCall![0])).not.toContain('sort=-name')
    })

    it('prefixes sort param with - for desc direction', async () => {
      mockSchemaWith(['name'])
      mockOneRecord({ name: 'Ada' })

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name']}
            sortField="name"
            sortDirection="desc"
          />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByText('Ada')).toBeDefined()
      })

      const recordsCall = mockGet.mock.calls.find(([url]) =>
        String(url).startsWith('/api/contacts')
      )
      expect(String(recordsCall![0])).toContain('sort=-name')
    })

    it('omits sort param when sortField is not provided', async () => {
      mockSchemaWith(['name'])
      mockOneRecord({ name: 'Ada' })

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name']}
          />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByText('Ada')).toBeDefined()
      })

      const recordsCall = mockGet.mock.calls.find(([url]) =>
        String(url).startsWith('/api/contacts')
      )
      expect(String(recordsCall![0])).not.toContain('sort=')
    })

    it('silently drops unknown names from displayColumns', async () => {
      mockSchemaWith(['name', 'email'])
      mockOneRecord({ name: 'Ada', email: 'a@x' })

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['ghost', 'name', 'phantom']}
          />
        </TestWrapper>
      )

      await waitFor(() => {
        expect(screen.getByText('Ada')).toBeDefined()
      })

      const headers = screen.getAllByRole('columnheader').map((el) => el.textContent)
      expect(headers).toEqual(['name'])
    })
  })

  it('shows "+ New" button when user has canCreate permission', async () => {
    mockGet.mockResolvedValueOnce({
      content: [{ id: 'col-1', name: 'contacts', displayName: 'Contacts', fields: [] }],
    })
    mockGet.mockResolvedValueOnce({
      id: 'col-1',
      name: 'contacts',
      displayName: 'Contacts',
      fields: [],
    })
    mockGet.mockResolvedValueOnce({
      data: [],
      metadata: { totalCount: 0, currentPage: 1, pageSize: 5, totalPages: 0 },
    })

    render(
      <TestWrapper>
        <RelatedList
          collectionName="contacts"
          foreignKeyField="accountId"
          parentRecordId="rec-123"
          tenantSlug="test-tenant"
        />
      </TestWrapper>
    )

    await waitFor(() => {
      expect(screen.getByText('New')).toBeDefined()
    })
  })

  describe('inline CRUD (slice 4)', () => {
    // useCollectionSchema fetches `/api/collections/{name}?include=fields` (JSON:API).
    function mockSchema(fieldNames: string[]) {
      mockGet.mockResolvedValueOnce({
        data: {
          id: 'col-1',
          type: 'collections',
          attributes: { name: 'contacts', displayName: 'Contacts' },
        },
        included: fieldNames.map((name, i) => ({
          id: `f-${i}`,
          type: 'fields',
          attributes: {
            name,
            displayName: name,
            type: 'STRING',
            required: false,
            active: true,
            fieldOrder: i,
          },
        })),
      })
    }
    // Persistent (not -Once) so the post-mutation `refetch()` also resolves —
    // the schema GET is queued -Once first and takes priority.
    function mockRecords(records: Array<Record<string, unknown>>) {
      mockGet.mockResolvedValue({
        data: records,
        metadata: {
          totalCount: records.length,
          currentPage: 1,
          pageSize: 5,
          totalPages: 1,
        },
      })
    }

    it('deletes a row after inline confirm (DELETE child by id)', async () => {
      mockSchema(['name'])
      mockRecords([{ id: 'rec-1', accountId: 'rec-123', name: 'Ada' }])
      mockDelete.mockResolvedValue(undefined)

      const user = userEvent.setup()
      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name']}
            editable
          />
        </TestWrapper>
      )

      await waitFor(() => expect(screen.getByTestId('related-delete-rec-1')).toBeDefined())
      await user.click(screen.getByTestId('related-delete-rec-1'))
      await user.click(screen.getByTestId('related-delete-confirm-rec-1'))

      await waitFor(() => expect(mockDelete).toHaveBeenCalledTimes(1))
      expect(String(mockDelete.mock.calls[0][0])).toBe('/api/contacts/rec-1')
    })

    it('creates a child inline with the parent FK pre-filled (POST)', async () => {
      mockSchema(['name'])
      mockRecords([])
      mockPost.mockResolvedValue({ data: { id: 'new-1', type: 'contacts', attributes: {} } })

      const user = userEvent.setup()
      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name']}
            editable
          />
        </TestWrapper>
      )

      await waitFor(() => expect(screen.getByTestId('related-list-new')).toBeDefined())
      await user.click(screen.getByTestId('related-list-new'))
      await waitFor(() => expect(screen.getByTestId('related-create-row')).toBeDefined())

      fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Grace' } })
      await user.click(screen.getByTestId('related-create-save'))

      await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1))
      expect(String(mockPost.mock.calls[0][0])).toBe('/api/contacts')
      const body = mockPost.mock.calls[0][1] as { data: { attributes: Record<string, unknown> } }
      expect(body.data.attributes.name).toBe('Grace')
      // Parent FK pre-filled from the relationship's foreignKeyField.
      expect(body.data.attributes.accountId).toBe('rec-123')
    })

    it('hides inline CRUD affordances when not editable', async () => {
      mockSchema(['name'])
      mockRecords([{ id: 'rec-1', accountId: 'rec-123', name: 'Ada' }])

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name']}
          />
        </TestWrapper>
      )

      await waitFor(() => expect(screen.getByText('Ada')).toBeDefined())
      // No delete control and no inline-edit pencil when read-only.
      expect(screen.queryByTestId('related-delete-rec-1')).toBeNull()
      expect(screen.queryByTestId('inline-field-name')).toBeNull()
    })
  })

  describe('mass edit (bulk jobs)', () => {
    interface MockField {
      name: string
      required?: boolean
    }

    function jobResponse(attributes: Record<string, unknown>) {
      return { data: { id: 'job-1', type: 'bulk-jobs', attributes } }
    }

    /**
     * URL-dispatching GET mock: collection schema (JSON:API), bulk-job status
     * polls (queue — last response repeats), and the related-records list.
     */
    function mockMassEditApi({
      records,
      fields = [{ name: 'name' }, { name: 'status' }],
      pollResponses,
    }: {
      records: Array<Record<string, unknown>>
      fields?: MockField[]
      pollResponses?: Array<Record<string, unknown>>
    }) {
      const polls = [
        ...(pollResponses ?? [
          jobResponse({
            status: 'COMPLETED',
            processedRecords: records.length,
            successRecords: records.length,
            errorRecords: 0,
          }),
        ]),
      ]
      const schemaResponse = {
        data: {
          id: 'col-1',
          type: 'collections',
          attributes: { name: 'contacts', displayName: 'Contacts' },
        },
        included: fields.map((f, i) => ({
          id: `f-${i}`,
          type: 'fields',
          attributes: {
            name: f.name,
            displayName: f.name,
            type: 'STRING',
            required: f.required ?? false,
            active: true,
            fieldOrder: i,
          },
        })),
      }
      mockGet.mockImplementation((url: unknown) => {
        const u = String(url)
        if (u.startsWith('/api/collections/')) return Promise.resolve(schemaResponse)
        if (u.startsWith('/api/bulk-jobs/')) {
          return Promise.resolve(polls.length > 1 ? polls.shift() : polls[0])
        }
        return Promise.resolve({
          data: records,
          metadata: { totalCount: records.length, currentPage: 1, pageSize: 5, totalPages: 1 },
        })
      })
      mockPost.mockResolvedValue(jobResponse({ status: 'QUEUED' }))
    }

    const twoRecords = [
      { id: 'rec-1', accountId: 'rec-123', name: 'Ada', status: 'new' },
      { id: 'rec-2', accountId: 'rec-123', name: 'Grace', status: 'new' },
    ]

    function renderList(props: Partial<React.ComponentProps<typeof RelatedList>> = {}) {
      return render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name', 'status']}
            editable
            {...props}
          />
        </TestWrapper>
      )
    }

    it('renders row checkboxes and select-all when editable + canEdit + MANAGE_DATA', async () => {
      mockMassEditApi({ records: twoRecords })
      renderList()

      await waitFor(() => expect(screen.getByTestId('related-select-rec-1')).toBeDefined())
      expect(screen.getByTestId('related-select-rec-2')).toBeDefined()
      expect(screen.getByTestId('related-select-all')).toBeDefined()
    })

    it('hides checkboxes when not editable', async () => {
      mockMassEditApi({ records: twoRecords })
      renderList({ editable: false })

      await waitFor(() => expect(screen.getByText('Ada')).toBeDefined())
      expect(screen.queryByTestId('related-select-rec-1')).toBeNull()
      expect(screen.queryByTestId('related-select-all')).toBeNull()
    })

    it('hides checkboxes without the canEdit object permission', async () => {
      mockObjectPermissions.canEdit = false
      mockMassEditApi({ records: twoRecords })
      renderList()

      await waitFor(() => expect(screen.getByText('Ada')).toBeDefined())
      expect(screen.queryByTestId('related-select-rec-1')).toBeNull()
    })

    it('hides checkboxes without the MANAGE_DATA system permission', async () => {
      mockSystemPermissions.MANAGE_DATA = false
      mockMassEditApi({ records: twoRecords })
      renderList()

      await waitFor(() => expect(screen.getByText('Ada')).toBeDefined())
      expect(screen.queryByTestId('related-select-rec-1')).toBeNull()
      expect(screen.queryByTestId('related-select-all')).toBeNull()
    })

    it('shows the action bar with the selection count and clears it', async () => {
      mockMassEditApi({ records: twoRecords })
      const user = userEvent.setup()
      renderList()

      await waitFor(() => expect(screen.getByTestId('related-select-rec-1')).toBeDefined())
      expect(screen.queryByTestId('related-mass-edit-bar')).toBeNull()

      await user.click(screen.getByTestId('related-select-rec-1'))
      expect(screen.getByTestId('related-mass-edit-count').textContent).toBe('1 selected')

      await user.click(screen.getByTestId('related-select-rec-2'))
      expect(screen.getByTestId('related-mass-edit-count').textContent).toBe('2 selected')

      await user.click(screen.getByTestId('related-mass-edit-clear'))
      expect(screen.queryByTestId('related-mass-edit-bar')).toBeNull()
    })

    it('select-all selects every visible row', async () => {
      mockMassEditApi({ records: twoRecords })
      const user = userEvent.setup()
      renderList()

      await waitFor(() => expect(screen.getByTestId('related-select-all')).toBeDefined())
      await user.click(screen.getByTestId('related-select-all'))
      expect(screen.getByTestId('related-mass-edit-count').textContent).toBe('2 selected')
    })

    /** Selects all rows, opens the dialog, picks `status`, and enters a value. */
    async function fillMassEdit(user: ReturnType<typeof userEvent.setup>): Promise<HTMLElement> {
      await waitFor(() => expect(screen.getByTestId('related-select-all')).toBeDefined())
      await user.click(screen.getByTestId('related-select-all'))
      await user.click(screen.getByTestId('related-mass-edit-open'))
      const dialog = screen.getByTestId('mass-edit-dialog')
      fireEvent.change(within(dialog).getByTestId('mass-edit-field-select'), {
        target: { value: 'status' },
      })
      fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'closed' } })
      return dialog
    }

    it('POSTs an UPDATE bulk job with one record per selected row', async () => {
      mockMassEditApi({ records: twoRecords })
      const user = userEvent.setup()
      renderList()

      const dialog = await fillMassEdit(user)
      await user.click(within(dialog).getByTestId('mass-edit-submit'))

      await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1))
      expect(String(mockPost.mock.calls[0][0])).toBe('/api/bulk-jobs')
      expect(mockPost.mock.calls[0][1]).toEqual({
        collectionId: 'col-1',
        operation: 'UPDATE',
        records: [
          { id: 'rec-1', status: 'closed' },
          { id: 'rec-2', status: 'closed' },
        ],
      })
    })

    it('polls to COMPLETED, toasts, refetches, and clears the selection', async () => {
      mockMassEditApi({
        records: twoRecords,
        pollResponses: [
          jobResponse({
            status: 'COMPLETED',
            processedRecords: 2,
            successRecords: 2,
            errorRecords: 0,
          }),
        ],
      })
      const user = userEvent.setup()
      renderList()

      const dialog = await fillMassEdit(user)
      const recordFetchesBefore = mockGet.mock.calls.filter(([u]) =>
        String(u).startsWith('/api/contacts')
      ).length
      await user.click(within(dialog).getByTestId('mass-edit-submit'))

      await waitFor(() =>
        expect(mockShowToast).toHaveBeenCalledWith('Updated 2 of 2 records', 'success')
      )
      // Job status was polled to a terminal state.
      const pollCalls = mockGet.mock.calls.filter(([u]) =>
        String(u).startsWith('/api/bulk-jobs/job-1')
      )
      expect(pollCalls.length).toBeGreaterThanOrEqual(1)
      // Dialog closed, selection cleared, and the list refetched.
      await waitFor(() => expect(screen.queryByTestId('mass-edit-dialog')).toBeNull())
      expect(screen.queryByTestId('related-mass-edit-bar')).toBeNull()
      await waitFor(() => {
        const recordFetchesAfter = mockGet.mock.calls.filter(([u]) =>
          String(u).startsWith('/api/contacts')
        ).length
        expect(recordFetchesAfter).toBeGreaterThan(recordFetchesBefore)
      })
    })

    it('surfaces a FAILED job as an error toast and keeps the dialog open', async () => {
      mockMassEditApi({
        records: twoRecords,
        pollResponses: [
          jobResponse({
            status: 'FAILED',
            processedRecords: 2,
            successRecords: 0,
            errorRecords: 2,
          }),
        ],
      })
      const user = userEvent.setup()
      renderList()

      const dialog = await fillMassEdit(user)
      await user.click(within(dialog).getByTestId('mass-edit-submit'))

      await waitFor(() =>
        expect(mockShowToast).toHaveBeenCalledWith(
          'Bulk update failed — check the Bulk Jobs page for details',
          'error'
        )
      )
      // Rejection keeps the dialog open with the message inline.
      expect(screen.getByTestId('mass-edit-dialog')).toBeDefined()
      expect(screen.getByTestId('mass-edit-error').textContent).toContain('Bulk update failed')
    })

    it('blocks submit when validation fails (required field left empty)', async () => {
      mockMassEditApi({
        records: twoRecords,
        fields: [{ name: 'name', required: true }],
      })
      const user = userEvent.setup()
      renderList({ displayColumns: ['name'] })

      await waitFor(() => expect(screen.getByTestId('related-select-all')).toBeDefined())
      await user.click(screen.getByTestId('related-select-all'))
      await user.click(screen.getByTestId('related-mass-edit-open'))
      const dialog = screen.getByTestId('mass-edit-dialog')

      // Default field is `name` (required) — submit with no value.
      await user.click(within(dialog).getByTestId('mass-edit-submit'))

      await waitFor(() =>
        expect(screen.getByTestId('mass-edit-error').textContent).toContain('is required')
      )
      expect(mockPost).not.toHaveBeenCalled()
    })
  })

  describe('lookup labels', () => {
    it("labels a lookup cell by the target's label field when the target has no display field", async () => {
      // `people` has no configured display field and no `name` field: the shared rule
      // (resolveLookupDisplayField) falls back to its first string field, not a missing `name`.
      const storeMock = vi.mocked(useCollectionStore)
      const defaultStore = storeMock.getMockImplementation()
      onTestFinished(() => {
        if (defaultStore) storeMock.mockImplementation(defaultStore)
      })
      storeMock.mockReturnValue({
        isLoading: false,
        collections: [],
        getCollectionByName: (name: string) =>
          name === 'people'
            ? ({
                id: 'col-people',
                name: 'people',
                displayName: 'People',
                fields: [
                  { id: 'p-f1', name: 'age', type: 'number', required: false },
                  { id: 'p-f2', name: 'fullName', type: 'string', required: false },
                ],
              } as never)
            : undefined,
        getCollectionById: () => undefined,
        getFieldById: () => undefined,
      })
      mockGet.mockResolvedValueOnce({
        data: {
          id: 'col-1',
          type: 'collections',
          attributes: { name: 'contacts', displayName: 'Contacts' },
        },
        included: [
          {
            id: 'f-0',
            type: 'fields',
            attributes: { name: 'title', type: 'STRING', active: true, fieldOrder: 0 },
          },
          {
            id: 'f-1',
            type: 'fields',
            attributes: {
              name: 'owner',
              type: 'LOOKUP',
              referenceTarget: 'people',
              active: true,
              fieldOrder: 1,
            },
          },
        ],
      })
      mockGet.mockResolvedValueOnce({
        data: [{ id: 'rec-1', accountId: 'rec-123', title: 'Eng', owner: 'p-1' }],
        included: [
          { type: 'people', id: 'p-1', attributes: { age: 36, fullName: 'Ada Lovelace' } },
        ],
        metadata: { totalCount: 1, currentPage: 1, pageSize: 5, totalPages: 1 },
      })

      render(
        <TestWrapper>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['title', 'owner']}
          />
        </TestWrapper>
      )

      expect(await screen.findByText('Ada Lovelace')).toBeDefined()
      expect(screen.queryByText('p-1')).toBeNull()
    })
  })

  // Record-stack convergence P7: the related list renders on both the admin and the
  // end-user detail page, so its links must stay in the stack it renders in.
  describe('record links stay in the rendering stack', () => {
    function LocationProbe() {
      const location = useLocation()
      return <div data-testid="location">{location.pathname + location.search}</div>
    }

    function renderIn(variant: RecordStackVariant | 'none', props: { editable?: boolean } = {}) {
      mockGet.mockResolvedValueOnce({
        data: {
          id: 'col-1',
          type: 'collections',
          attributes: { name: 'contacts', displayName: 'Contacts' },
        },
        included: [
          {
            id: 'f-0',
            type: 'fields',
            attributes: { name: 'name', displayName: 'name', type: 'STRING', active: true },
          },
        ],
      })
      mockGet.mockResolvedValue({
        data: [
          { id: 'rec-1', accountId: 'rec-123', name: 'Ada' },
          { id: 'rec-2', accountId: 'rec-123', name: 'Grace' },
        ],
        metadata: { totalCount: 2, currentPage: 1, pageSize: 1, totalPages: 2 },
      })
      const list = (
        <>
          <RelatedList
            collectionName="contacts"
            foreignKeyField="accountId"
            parentRecordId="rec-123"
            tenantSlug="test-tenant"
            displayColumns={['name']}
            limit={1}
            {...props}
          />
          <LocationProbe />
        </>
      )
      render(
        <TestWrapper>
          {variant === 'none' ? (
            list
          ) : (
            <RecordStackProvider variant={variant}>{list}</RecordStackProvider>
          )}
        </TestWrapper>
      )
    }

    const VIEW_ALL_FILTER = JSON.stringify([
      { id: 'parent', field: 'accountId', operator: 'equals', value: 'rec-123' },
    ])

    it.each([
      ['admin', '/test-tenant/resources/contacts'],
      ['enduser', '/test-tenant/app/o/contacts'],
      ['none', '/test-tenant/app/o/contacts'],
    ] as const)('under %s, a row opens %s/<id>', async (variant, base) => {
      renderIn(variant)
      await waitFor(() => expect(screen.getByText('Ada')).toBeDefined())
      fireEvent.click(screen.getByText('Ada'))
      expect(screen.getByTestId('location').textContent).toBe(`${base}/rec-1`)
    })

    it.each([
      ['admin', '/test-tenant/resources/contacts'],
      ['enduser', '/test-tenant/app/o/contacts'],
    ] as const)(
      'under %s, "View All" links to %s filtered by the parent',
      async (variant, base) => {
        renderIn(variant)
        const viewAll = await screen.findByRole('link', { name: /View All/ })
        const href = viewAll.getAttribute('href') ?? ''
        expect(href.startsWith(`${base}?filter=`)).toBe(true)
        const filter = new URL(href, 'http://x').searchParams.get('filter')
        expect(filter).toBe(VIEW_ALL_FILTER)
      }
    )

    it.each([
      ['admin', '/test-tenant/resources/contacts/new?accountId=rec-123'],
      ['enduser', '/test-tenant/app/o/contacts/new?accountId=rec-123'],
    ] as const)('under %s, "New" opens %s', async (variant, expected) => {
      renderIn(variant)
      await waitFor(() => expect(screen.getByTestId('related-list-new')).toBeDefined())
      fireEvent.click(screen.getByTestId('related-list-new'))
      expect(screen.getByTestId('location').textContent).toBe(expected)
    })
  })
})
