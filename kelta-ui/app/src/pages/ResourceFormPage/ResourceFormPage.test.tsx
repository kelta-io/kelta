/**
 * ResourceFormPage — the admin wrapper over the shared `RecordForm` core.
 *
 * Integration-style: the real wrapper, `RecordForm`, `useRecordFormModel`,
 * `useRecordFormState` and the data hooks (`useCollectionSchema`, `useRecord`,
 * `useRecordMutation`, …) run against the mocked axios instance, so these tests pin the
 * request/response contract end to end. Only the page layout is stubbed (no layout →
 * the flat field grid).
 *
 * What the wrapper owns: the `/resources/...` navigation targets, clone mode
 * (`?clone=<id>` → the core's `cloneFromId`, plus the "cloning from" notice), the admin
 * breadcrumb, the record id under the title, and field type hints. Everything else is the
 * core's behaviour as the admin page exercises it (record-stack convergence P6):
 * fields, validation, save, server errors, plugin field renderers, the record-load error,
 * cache invalidation of the admin detail/list, and optimistic concurrency (If-Match + the
 * conflict prompt). The per-field control DOM is covered in
 * `components/record/form/FormFieldRow.test.tsx`; the end-user wrapper in
 * `pages/app/ObjectFormPage/ObjectFormPage.test.tsx`.
 */

import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  AuthWrapper,
  setupAuthMocks,
  mockAxios,
  resetMockAxios,
  createAxiosError,
} from '../../test/testUtils'
import { ResourceFormPage } from './ResourceFormPage'
import { PluginProvider } from '../../context/PluginContext'
import type { Plugin, FieldRendererProps } from '../../types/plugin'

// No page layout configured → the flat field grid
vi.mock('../../hooks/usePageLayout', () => ({
  usePageLayout: () => ({ layout: null, isLoading: false, error: null }),
}))

// Mock navigate
const mockNavigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

interface TestField {
  id: string
  name: string
  displayName: string
  type: string
  required: boolean
  order: number
  referenceCollectionId?: string
  constraints?: Record<string, unknown>
}

interface TestSchema {
  id: string
  name: string
  displayName: string
  fields: TestField[]
}

type TestRecord = Record<string, unknown> & { id: string }

// Test data
const mockSchema: TestSchema = {
  id: 'col-1',
  name: 'users',
  displayName: 'Users',
  fields: [
    {
      id: 'field-1',
      name: 'name',
      displayName: 'Full Name',
      type: 'string',
      required: true,
      order: 1,
    },
    {
      id: 'field-2',
      name: 'email',
      displayName: 'Email Address',
      type: 'string',
      required: true,
      order: 2,
      constraints: { email: true },
    },
    {
      id: 'field-3',
      name: 'age',
      displayName: 'Age',
      type: 'number',
      required: false,
      order: 3,
      constraints: { min: 0, max: 150 },
    },
    {
      id: 'field-4',
      name: 'active',
      displayName: 'Is Active',
      type: 'boolean',
      required: false,
      order: 4,
    },
    {
      id: 'field-5',
      name: 'birthDate',
      displayName: 'Birth Date',
      type: 'date',
      required: false,
      order: 5,
    },
    {
      id: 'field-6',
      name: 'lastLogin',
      displayName: 'Last Login',
      type: 'datetime',
      required: false,
      order: 6,
    },
    {
      id: 'field-7',
      name: 'metadata',
      displayName: 'Metadata',
      type: 'json',
      required: false,
      order: 7,
    },
    {
      id: 'field-8',
      name: 'organizationId',
      displayName: 'Organization',
      type: 'master_detail',
      required: false,
      referenceCollectionId: 'org-collection-id',
      order: 8,
    },
  ],
}

/**
 * Convert a test schema into the JSON:API response of /api/collections/{name}?include=fields.
 */
function toJsonApiWithFields(schema: TestSchema) {
  return {
    data: {
      type: 'collections',
      id: schema.id,
      attributes: { name: schema.name, displayName: schema.displayName },
    },
    included: schema.fields.map((f) => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { id: _id, referenceCollectionId: _ref, order, ...rest } = f
      return {
        type: 'fields',
        id: f.id,
        attributes: {
          ...rest,
          type: f.type.toUpperCase(),
          fieldOrder: order,
          active: true,
        },
        relationships: {
          collectionId: { data: { type: 'collections', id: schema.id } },
          ...(f.referenceCollectionId
            ? {
                referenceCollectionId: {
                  data: { type: 'collections', id: f.referenceCollectionId },
                },
              }
            : {}),
        },
      }
    }),
  }
}

const mockResource: TestRecord = {
  id: 'res-123',
  name: 'John Doe',
  email: 'john@example.com',
  age: 30,
  active: true,
  birthDate: '1993-05-15',
  lastLogin: '2024-01-15T10:30:00',
  metadata: { role: 'admin' },
  organizationId: 'org-456',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-15T10:30:00Z',
}

/** What the API serves: the schema and records by id (with their ETag). */
let schemaServed: TestSchema
let recordsServed: Record<string, { resource: TestRecord; etag?: string }>

function serveApi() {
  mockAxios.get.mockImplementation((url: string) => {
    if (url.includes('/api/collections/') && url.includes('include=fields')) {
      return Promise.resolve({ data: toJsonApiWithFields(schemaServed) })
    }
    const match = url.match(/^\/api\/[^/?]+\/([^/?]+)$/)
    const served = match ? recordsServed[match[1]] : undefined
    if (served && !url.startsWith('/api/collections')) {
      return Promise.resolve({
        data: served.resource,
        headers: served.etag ? { etag: served.etag } : {},
      })
    }
    return Promise.reject(createAxiosError(404))
  })
}

/** The signed-in user's permissions, as the app shell's `/api/me/permissions` fetch caches them. */
let myPermissions: {
  systemPermissions: Record<string, boolean>
  objectPermissions: Record<string, Record<string, boolean>>
  fieldPermissions: Record<string, Record<string, string>>
}

function seedPermissions(queryClient: QueryClient): QueryClient {
  queryClient.setQueryDefaults(['my-permissions'], { gcTime: Infinity })
  queryClient.setQueryData(['my-permissions'], myPermissions)
  return queryClient
}

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
}

function renderWithProviders(
  ui: React.ReactElement,
  {
    route = '/resources/users/new',
    plugins = [] as Plugin[],
    queryClient = createQueryClient(),
  }: { route?: string; plugins?: Plugin[]; queryClient?: QueryClient } = {}
) {
  return {
    ...render(
      <QueryClientProvider client={seedPermissions(queryClient)}>
        <AuthWrapper>
          <PluginProvider plugins={plugins}>
            <MemoryRouter initialEntries={[route]}>
              <Routes>
                <Route path="/resources/:collection/new" element={ui} />
                <Route path="/resources/:collection/:id/edit" element={ui} />
              </Routes>
            </MemoryRouter>
          </PluginProvider>
        </AuthWrapper>
      </QueryClientProvider>
    ),
    queryClient,
  }
}

async function waitForForm() {
  await waitFor(() => expect(screen.getByTestId('form-fields')).toBeInTheDocument())
}

/** The JSON:API body of the n-th call to an axios write method. */
function bodyOf(method: 'post' | 'put', callIndex = 0) {
  return (mockAxios[method].mock.calls[callIndex] as unknown[])[1] as {
    data: { type: string; id?: string; attributes: Record<string, unknown> }
  }
}

let cleanupAuthMocks: () => void

beforeEach(() => {
  cleanupAuthMocks = setupAuthMocks()
  resetMockAxios()
  mockNavigate.mockReset()
  schemaServed = mockSchema
  recordsServed = { 'res-123': { resource: mockResource, etag: '"v1"' } }
  myPermissions = { systemPermissions: {}, objectPermissions: {}, fieldPermissions: {} }
  serveApi()
})

afterEach(() => {
  cleanupAuthMocks()
  vi.clearAllMocks()
})

describe('ResourceFormPage', () => {
  describe('Loading and error states', () => {
    it('shows a loading indicator while the schema loads', () => {
      mockAxios.get.mockImplementation(() => new Promise(() => {})) // Never resolves

      renderWithProviders(<ResourceFormPage />)

      expect(screen.getByTestId('record-form-loading')).toBeInTheDocument()
    })

    it('shows an error when the schema fetch fails', async () => {
      mockAxios.get.mockRejectedValue(createAxiosError(500))

      renderWithProviders(<ResourceFormPage />)

      expect(await screen.findByTestId('record-form-error')).toBeInTheDocument()
    })

    it('shows an error, not an empty form, when the record to edit fails to load', async () => {
      recordsServed = {}

      renderWithProviders(<ResourceFormPage />, { route: '/resources/users/res-123/edit' })

      expect(await screen.findByTestId('record-form-error')).toHaveTextContent(
        /could not be loaded/i
      )
      expect(screen.queryByTestId('form-fields')).not.toBeInTheDocument()
    })

    it('gates create on the collection create permission, linking back to the admin list', async () => {
      myPermissions.objectPermissions.users = {
        canCreate: false,
        canRead: true,
        canEdit: true,
        canDelete: true,
      }

      renderWithProviders(<ResourceFormPage />)

      expect(await screen.findByText(/insufficient privileges/i)).toBeInTheDocument()
      expect(screen.queryByTestId('form-fields')).not.toBeInTheDocument()
    })

    it('shows a read-only field as disabled and leaves it out of the save', async () => {
      const user = userEvent.setup()
      myPermissions.fieldPermissions.users = { email: 'READ_ONLY', age: 'HIDDEN' }
      mockAxios.put.mockResolvedValueOnce({ data: mockResource })

      renderWithProviders(<ResourceFormPage />, { route: '/resources/users/res-123/edit' })
      await waitForForm()

      expect(screen.getByTestId('field-email')).toBeDisabled()
      expect(screen.queryByTestId('field-age')).not.toBeInTheDocument()
      await user.click(screen.getByTestId('save-button'))
      await waitFor(() => expect(mockAxios.put).toHaveBeenCalled())
      expect(bodyOf('put').data.attributes).not.toHaveProperty('email')
      expect(bodyOf('put').data.attributes).not.toHaveProperty('age')
    })
  })

  describe('Create mode', () => {
    it('renders a control for every schema field', async () => {
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      for (const name of [
        'name',
        'email',
        'age',
        'active',
        'birthDate',
        'lastLogin',
        'metadata',
        'organizationId',
      ]) {
        expect(screen.getByTestId(`field-${name}`)).toBeInTheDocument()
      }
    })

    it('titles the page with the collection and renders the admin breadcrumb', async () => {
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      expect(screen.getByTestId('page-title')).toHaveTextContent('New Users')
      const breadcrumb = screen.getByRole('navigation', { name: /breadcrumb/i })
      expect(breadcrumb).toHaveTextContent('Create Record')
      expect(screen.getByRole('link', { name: 'Resource Browser' })).toHaveAttribute(
        'href',
        '/default/resources'
      )
      expect(screen.getByRole('link', { name: 'Users' })).toHaveAttribute(
        'href',
        '/default/resources/users'
      )
      expect(screen.queryByTestId('resource-id')).not.toBeInTheDocument()
    })

    it('renders the input type for each field type', async () => {
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      expect(screen.getByTestId('field-name')).toHaveAttribute('type', 'text')
      expect(screen.getByTestId('field-age')).toHaveAttribute('type', 'number')
      expect(screen.getByTestId('field-active')).toHaveAttribute('type', 'checkbox')
      expect(screen.getByTestId('field-birthDate')).toHaveAttribute('type', 'date')
      expect(screen.getByTestId('field-lastLogin')).toHaveAttribute('type', 'datetime-local')
      expect(screen.getByTestId('field-metadata').tagName.toLowerCase()).toBe('textarea')
    })

    it("shows each field's type under its label", async () => {
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      expect(screen.getByTestId('field-type-name')).toHaveTextContent('String')
      expect(screen.getByTestId('field-type-age')).toHaveTextContent('Number')
    })

    it('marks required fields with an asterisk', async () => {
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      expect(screen.getByText('Full Name').parentElement).toHaveTextContent('*')
      expect(screen.getByText('Email Address').parentElement).toHaveTextContent('*')
    })

    it('shows required-field errors when saving an empty form, and saves nothing', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.click(screen.getByTestId('save-button'))

      expect(await screen.findByTestId('field-error-name')).toBeInTheDocument()
      expect(screen.getByTestId('field-error-email')).toBeInTheDocument()
      expect(mockAxios.post).not.toHaveBeenCalled()
    })

    it("validates a field's email constraint", async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'invalid-email')
      await user.click(screen.getByTestId('save-button'))

      expect(await screen.findByTestId('field-error-email')).toBeInTheDocument()
      expect(mockAxios.post).not.toHaveBeenCalled()
    })

    it('validates JSON fields', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')
      await user.type(screen.getByTestId('field-metadata'), 'invalid json')
      await user.click(screen.getByTestId('save-button'))

      expect(await screen.findByTestId('field-error-metadata')).toBeInTheDocument()
      expect(mockAxios.post).not.toHaveBeenCalled()
    })

    it("POSTs the record, confirms, and lands on the new record's admin detail page", async () => {
      const user = userEvent.setup()
      mockAxios.post.mockResolvedValueOnce({
        data: { id: 'new-res-123', name: 'John Doe', email: 'john@example.com' },
      })
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')
      await user.type(screen.getByTestId('field-metadata'), '{{"role": "admin"}')
      await user.click(screen.getByTestId('save-button'))

      await waitFor(() =>
        expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/new-res-123')
      )
      expect(mockAxios.post.mock.calls[0][0]).toBe('/api/users')
      const body = bodyOf('post')
      expect(body.data.type).toBe('users')
      expect(body.data.id).toBeUndefined()
      expect(body.data.attributes).toEqual({
        name: 'John Doe',
        email: 'john@example.com',
        active: false,
        metadata: { role: 'admin' },
      })
      expect(await screen.findByText('Record created successfully.')).toBeInTheDocument()
    })

    it('cancel returns to the admin list', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.click(screen.getByTestId('cancel-button'))

      expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users')
    })
  })

  describe('Edit mode', () => {
    const editRoute = '/resources/users/res-123/edit'

    it('titles the page for edit and shows the record id', async () => {
      renderWithProviders(<ResourceFormPage />, { route: editRoute })
      await waitForForm()

      expect(screen.getByTestId('page-title')).toHaveTextContent('Edit Users')
      expect(screen.getByRole('navigation', { name: /breadcrumb/i })).toHaveTextContent(
        'Edit Record'
      )
      expect(screen.getByTestId('resource-id')).toHaveTextContent('res-123')
    })

    it('pre-populates the form with the record', async () => {
      renderWithProviders(<ResourceFormPage />, { route: editRoute })
      await waitForForm()

      expect(screen.getByTestId('field-name')).toHaveValue('John Doe')
      expect(screen.getByTestId('field-email')).toHaveValue('john@example.com')
      expect(screen.getByTestId('field-age')).toHaveValue(30)
      expect(screen.getByTestId('field-active')).toBeChecked()
      expect(screen.getByTestId('field-birthDate')).toHaveValue('1993-05-15')
      expect(screen.getByTestId('field-lastLogin')).toHaveValue('2024-01-15T10:30')
      expect(screen.getByTestId('field-metadata')).toHaveValue(
        JSON.stringify({ role: 'admin' }, null, 2)
      )
    })

    it('loads the record even when the admin detail page has cached it', async () => {
      // ResourceDetailPage caches a `{ resource, etag }` envelope under ['resource', …]; the form
      // reads the record through its own query, so that envelope is never taken for the record.
      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 60_000, staleTime: 60_000 } },
      })
      queryClient.setQueryData(['resource', 'users', 'res-123'], {
        resource: mockResource,
        etag: '"v1"',
      })
      renderWithProviders(<ResourceFormPage />, { route: editRoute, queryClient })

      await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue('John Doe'))
      expect(screen.getByTestId('field-email')).toHaveValue('john@example.com')
    })

    it('saves the changed record and returns to its admin detail page', async () => {
      const user = userEvent.setup()
      mockAxios.put.mockResolvedValueOnce({ data: { ...mockResource, name: 'Jane Doe' } })
      renderWithProviders(<ResourceFormPage />, { route: editRoute })
      await waitForForm()

      const nameInput = screen.getByTestId('field-name')
      await user.clear(nameInput)
      await user.type(nameInput, 'Jane Doe')
      await user.click(screen.getByTestId('save-button'))

      await waitFor(() =>
        expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/res-123')
      )
      expect(mockAxios.put.mock.calls[0][0]).toBe('/api/users/res-123')
      const body = bodyOf('put')
      expect(body.data.id).toBe('res-123')
      expect(body.data.attributes.name).toBe('Jane Doe')
      // A lookup / master-detail id goes out as an attribute — the worker accepts either shape.
      expect(body.data.attributes.organizationId).toBe('org-456')
      expect(await screen.findByText('Record updated successfully.')).toBeInTheDocument()
    })

    it('sends null for an optional field the user cleared', async () => {
      const user = userEvent.setup()
      mockAxios.put.mockResolvedValueOnce({ data: mockResource })
      renderWithProviders(<ResourceFormPage />, { route: editRoute })
      await waitForForm()

      await user.clear(screen.getByTestId('field-age'))
      await user.click(screen.getByTestId('save-button'))

      await waitFor(() => expect(mockAxios.put).toHaveBeenCalled())
      expect(bodyOf('put').data.attributes.age).toBeNull()
    })

    it("invalidates the admin detail and list caches so they don't show the pre-save record", async () => {
      const user = userEvent.setup()
      mockAxios.put.mockResolvedValueOnce({ data: mockResource })
      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 60_000, staleTime: 60_000 } },
      })
      queryClient.setQueryData(['resource', 'users', 'res-123'], { resource: mockResource })
      queryClient.setQueryData(['resources', 'users', 0, 25, null, []], { content: [] })
      renderWithProviders(<ResourceFormPage />, { route: editRoute, queryClient })
      await waitForForm()

      await user.click(screen.getByTestId('save-button'))

      await waitFor(() => expect(mockNavigate).toHaveBeenCalled())
      expect(queryClient.getQueryState(['resource', 'users', 'res-123'])?.isInvalidated).toBe(true)
      expect(
        queryClient.getQueryState(['resources', 'users', 0, 25, null, []])?.isInvalidated
      ).toBe(true)
    })

    it('cancel returns to the admin detail page', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />, { route: editRoute })
      await waitForForm()

      await user.click(screen.getByTestId('cancel-button'))

      expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/res-123')
    })
  })

  describe('Clone mode (?clone=<id>)', () => {
    const cloneRoute = '/resources/users/new?clone=res-123'

    it("seeds a new record from the source's values, without its id or audit fields", async () => {
      const user = userEvent.setup()
      mockAxios.post.mockResolvedValueOnce({ data: { id: 'copy-1', name: 'John Doe' } })
      renderWithProviders(<ResourceFormPage />, { route: cloneRoute })
      await waitForForm()

      expect(screen.getByTestId('field-name')).toHaveValue('John Doe')
      expect(screen.getByTestId('field-email')).toHaveValue('john@example.com')
      expect(screen.getByTestId('field-age')).toHaveValue(30)
      expect(screen.getByTestId('field-birthDate')).toHaveValue('1993-05-15')

      await user.click(screen.getByTestId('save-button'))

      await waitFor(() =>
        expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/copy-1')
      )
      // A create: POST to the collection, no record id, no If-Match, no audit fields.
      expect(mockAxios.put).not.toHaveBeenCalled()
      expect(mockAxios.post.mock.calls[0]).toHaveLength(2)
      const body = bodyOf('post')
      expect(body.data.id).toBeUndefined()
      expect(body.data.attributes).toMatchObject({
        name: 'John Doe',
        email: 'john@example.com',
        age: 30,
        organizationId: 'org-456',
      })
      expect(body.data.attributes).not.toHaveProperty('createdAt')
      expect(body.data.attributes).not.toHaveProperty('updatedAt')
    })

    it('titles the page as a clone and links to the source record', async () => {
      renderWithProviders(<ResourceFormPage />, { route: cloneRoute })
      await waitForForm()

      expect(screen.getByTestId('page-title')).toHaveTextContent('Clone Users')
      const banner = screen.getByTestId('clone-banner')
      expect(banner).toHaveTextContent('res-123')
      expect(screen.getByRole('link', { name: 'View source' })).toHaveAttribute(
        'href',
        '/default/resources/users/res-123'
      )
    })

    it('shows an error when the source record cannot be loaded', async () => {
      recordsServed = {}

      renderWithProviders(<ResourceFormPage />, { route: cloneRoute })

      expect(await screen.findByTestId('record-form-error')).toBeInTheDocument()
    })

    it('is ignored on edit', async () => {
      recordsServed['other'] = { resource: { ...mockResource, id: 'other', name: 'Other' } }
      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/users/res-123/edit?clone=other',
      })
      await waitForForm()

      expect(screen.getByTestId('field-name')).toHaveValue('John Doe')
      expect(screen.getByTestId('page-title')).toHaveTextContent('Edit Users')
      expect(screen.queryByTestId('clone-banner')).not.toBeInTheDocument()
    })
  })

  describe('Field input', () => {
    it('toggles a boolean field', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      const activeCheckbox = screen.getByTestId('field-active')
      expect(activeCheckbox).not.toBeChecked()
      await user.click(activeCheckbox)
      expect(activeCheckbox).toBeChecked()
      await user.click(activeCheckbox)
      expect(activeCheckbox).not.toBeChecked()
    })

    it('accepts number and date input', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.type(screen.getByTestId('field-age'), '25')
      expect(screen.getByTestId('field-age')).toHaveValue(25)
      await user.type(screen.getByTestId('field-birthDate'), '2000-01-15')
      expect(screen.getByTestId('field-birthDate')).toHaveValue('2000-01-15')
    })
  })

  describe('Field constraints', () => {
    async function saveWithAge(age: string) {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()
      await user.type(screen.getByTestId('field-name'), 'John Doe')
      await user.type(screen.getByTestId('field-email'), 'john@example.com')
      await user.type(screen.getByTestId('field-age'), age)
      await user.click(screen.getByTestId('save-button'))
    }

    it('enforces a number minimum', async () => {
      await saveWithAge('-5')
      expect(await screen.findByTestId('field-error-age')).toBeInTheDocument()
      expect(mockAxios.post).not.toHaveBeenCalled()
    })

    it('enforces a number maximum', async () => {
      await saveWithAge('200')
      expect(await screen.findByTestId('field-error-age')).toBeInTheDocument()
      expect(mockAxios.post).not.toHaveBeenCalled()
    })

    it('clears a field error once the field is corrected', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.click(screen.getByTestId('save-button'))
      expect(await screen.findByTestId('field-error-name')).toBeInTheDocument()

      await user.type(screen.getByTestId('field-name'), 'John Doe')
      expect(screen.queryByTestId('field-error-name')).not.toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    it('labels every field control', async () => {
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      expect(screen.getByLabelText(/full name/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/email address/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/^age/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/is active/i)).toBeInTheDocument()
    })

    it('marks invalid fields and links them to their error', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()

      await user.click(screen.getByTestId('save-button'))

      await waitFor(() =>
        expect(screen.getByTestId('field-name')).toHaveAttribute('aria-invalid', 'true')
      )
      expect(screen.getByTestId('field-email')).toHaveAttribute('aria-invalid', 'true')
      expect(screen.getByTestId('field-name')).toHaveAttribute(
        'aria-describedby',
        'field-name-error'
      )
      expect(screen.getByTestId('field-error-name')).toHaveAttribute('role', 'alert')
    })
  })

  describe('Empty schema', () => {
    beforeEach(() => {
      schemaServed = { ...mockSchema, fields: [] }
    })

    it('shows the no-fields state', async () => {
      renderWithProviders(<ResourceFormPage />)
      expect(await screen.findByTestId('no-fields')).toBeInTheDocument()
    })

    it('disables Save', async () => {
      renderWithProviders(<ResourceFormPage />)
      await waitForForm()
      expect(screen.getByTestId('save-button')).toBeDisabled()
    })
  })

  describe('Plugin field renderers', () => {
    const CustomStringRenderer: React.FC<FieldRendererProps> = ({
      name,
      value,
      onChange,
      error,
      metadata,
    }) => (
      <div data-testid={`custom-string-renderer-${name}`}>
        <input
          type="text"
          data-testid={`custom-input-${name}`}
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
          className={error ? 'error' : ''}
          aria-label={`Custom ${name} input`}
        />
        {Boolean(metadata?.required) && <span data-testid="required-indicator">*</span>}
      </div>
    )

    const CustomRichTextRenderer: React.FC<FieldRendererProps> = ({ name, value, onChange }) => (
      <div data-testid={`rich-text-renderer-${name}`}>
        <textarea
          data-testid={`rich-text-input-${name}`}
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
          placeholder="Rich text editor"
        />
        <div data-testid="rich-text-toolbar">Bold | Italic | Underline</div>
      </div>
    )

    function pluginWith(fieldRenderers: Plugin['fieldRenderers']): Plugin {
      return { id: 'test-plugin', name: 'Test Plugin', version: '1.0.0', fieldRenderers }
    }

    it('uses a registered renderer for its type and the built-in control for other types', async () => {
      renderWithProviders(<ResourceFormPage />, {
        plugins: [pluginWith({ string: CustomStringRenderer })],
      })
      await waitForForm()

      expect(screen.getByTestId('custom-string-renderer-name')).toBeInTheDocument()
      expect(screen.getByTestId('custom-input-name')).toBeInTheDocument()
      // number is not registered → the built-in control
      expect(screen.getByTestId('field-age')).toHaveAttribute('type', 'number')
      expect(screen.queryByTestId('custom-string-renderer-age')).not.toBeInTheDocument()
    })

    it('uses the built-in controls when no plugin is registered', async () => {
      renderWithProviders(<ResourceFormPage />, { plugins: [] })
      await waitForForm()

      expect(screen.getByTestId('field-name')).toHaveAttribute('type', 'text')
      expect(screen.getByTestId('field-age')).toHaveAttribute('type', 'number')
      expect(screen.getByTestId('field-active')).toHaveAttribute('type', 'checkbox')
      expect(screen.queryByTestId('custom-string-renderer-name')).not.toBeInTheDocument()
    })

    it('passes the field name, value, onChange and metadata to the renderer', async () => {
      const capturedPropsMap: Map<string, FieldRendererProps> = new Map()
      const PropsCapturingRenderer: React.FC<FieldRendererProps> = (props) => {
        capturedPropsMap.set(props.name, { ...props })
        return (
          <input
            data-testid={`capturing-input-${props.name}`}
            value={String(props.value ?? '')}
            onChange={(e) => props.onChange(e.target.value)}
          />
        )
      }

      renderWithProviders(<ResourceFormPage />, {
        plugins: [pluginWith({ string: PropsCapturingRenderer })],
      })
      await waitForForm()

      const nameProps = capturedPropsMap.get('name')
      expect(nameProps?.name).toBe('name')
      expect(nameProps?.value).toBeUndefined()
      expect(typeof nameProps?.onChange).toBe('function')
      expect(nameProps?.metadata).toMatchObject({
        fieldId: 'field-name',
        type: 'string',
        fieldType: 'string',
        required: true,
        displayName: 'Full Name',
      })
      expect(capturedPropsMap.get('email')?.metadata?.required).toBe(true)
    })

    it("saves the renderer's values", async () => {
      const user = userEvent.setup()
      mockAxios.post.mockResolvedValueOnce({ data: { id: 'new-res-123' } })
      renderWithProviders(<ResourceFormPage />, {
        plugins: [pluginWith({ string: CustomStringRenderer })],
      })
      await waitForForm()

      await user.type(screen.getByTestId('custom-input-name'), 'Test Name')
      await user.type(screen.getByTestId('custom-input-email'), 'test@example.com')
      await user.click(screen.getByTestId('save-button'))

      await waitFor(() => expect(mockAxios.post).toHaveBeenCalled())
      const body = bodyOf('post')
      expect(body.data.type).toBe('users')
      expect(body.data.attributes.name).toBe('Test Name')
      expect(body.data.attributes.email).toBe('test@example.com')
    })

    it('shows field errors under a renderer', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ResourceFormPage />, {
        plugins: [pluginWith({ string: CustomStringRenderer })],
      })
      await waitForForm()

      await user.click(screen.getByTestId('save-button'))

      expect(await screen.findByTestId('field-error-name')).toBeInTheDocument()
      expect(screen.getByTestId('field-error-email')).toBeInTheDocument()
    })

    it('supports renderers for several field types at once', async () => {
      schemaServed = {
        id: 'col-2',
        name: 'articles',
        displayName: 'Articles',
        fields: [
          {
            id: 'f-1',
            name: 'title',
            displayName: 'Title',
            type: 'string',
            required: true,
            order: 1,
          },
          {
            id: 'f-2',
            name: 'content',
            displayName: 'Content',
            type: 'json',
            required: false,
            order: 2,
          },
        ],
      }
      renderWithProviders(<ResourceFormPage />, {
        route: '/resources/articles/new',
        plugins: [pluginWith({ string: CustomStringRenderer, json: CustomRichTextRenderer })],
      })
      await waitForForm()

      expect(screen.getByTestId('custom-string-renderer-title')).toBeInTheDocument()
      expect(screen.getByTestId('rich-text-renderer-content')).toBeInTheDocument()
      expect(screen.getByTestId('rich-text-toolbar')).toBeInTheDocument()
    })
  })
})

describe('ResourceFormPage — optimistic concurrency (If-Match)', () => {
  const STALE_WRITE_BODY = {
    errors: [
      {
        status: '409',
        code: 'STALE_WRITE',
        title: 'Conflict',
        detail:
          'This record was modified since you opened it. Reload the latest version and try again.',
      },
    ],
  }

  async function openEditForm() {
    renderWithProviders(<ResourceFormPage />, { route: '/resources/users/res-123/edit' })
    await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue('John Doe'))
  }

  function putHeaders(callIndex = 0): unknown {
    return (mockAxios.put.mock.calls[callIndex] as unknown[])[2]
  }

  it("sends the loaded record's ETag as If-Match on update", async () => {
    const user = userEvent.setup()
    mockAxios.put.mockResolvedValueOnce({ data: mockResource })
    await openEditForm()

    await user.click(screen.getByTestId('save-button'))

    await waitFor(() => expect(mockAxios.put).toHaveBeenCalled())
    expect(mockAxios.put.mock.calls[0][0]).toBe('/api/users/res-123')
    expect(putHeaders()).toEqual({ headers: { 'If-Match': '"v1"' } })
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith('/default/resources/users/res-123')
    )
  })

  it('on a stale write keeps the edits, shows the conflict prompt and does not navigate', async () => {
    const user = userEvent.setup()
    mockAxios.put.mockRejectedValueOnce(createAxiosError(409, STALE_WRITE_BODY))
    await openEditForm()

    const nameInput = screen.getByTestId('field-name')
    await user.clear(nameInput)
    await user.type(nameInput, 'Mine')
    await user.click(screen.getByTestId('save-button'))

    expect(await screen.findByTestId('record-conflict-dialog')).toBeInTheDocument()
    expect(screen.getByText('This record was changed')).toBeInTheDocument()
    expect(screen.getByTestId('field-name')).toHaveValue('Mine')
    expect(mockNavigate).not.toHaveBeenCalled()

    // Keep editing: the prompt closes, the edits stay, Save is usable again.
    await user.click(screen.getByTestId('record-conflict-keep-editing'))
    await waitFor(() => expect(screen.queryByTestId('record-conflict-dialog')).toBeNull())
    expect(screen.getByTestId('field-name')).toHaveValue('Mine')
    expect(screen.getByTestId('save-button')).toBeEnabled()
  })

  it('Reload refetches the record, resets the form to it and saves with the new ETag', async () => {
    const user = userEvent.setup()
    mockAxios.put
      .mockRejectedValueOnce(createAxiosError(409, STALE_WRITE_BODY))
      .mockResolvedValueOnce({ data: mockResource })
    await openEditForm()

    const nameInput = screen.getByTestId('field-name')
    await user.clear(nameInput)
    await user.type(nameInput, 'Mine')
    await user.click(screen.getByTestId('save-button'))
    expect(await screen.findByTestId('record-conflict-dialog')).toBeInTheDocument()

    recordsServed['res-123'] = { resource: { ...mockResource, name: 'Theirs' }, etag: '"v2"' }
    await user.click(screen.getByTestId('record-conflict-reload'))

    await waitFor(() => expect(screen.queryByTestId('record-conflict-dialog')).toBeNull())
    await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue('Theirs'))

    await user.click(screen.getByTestId('save-button'))
    await waitFor(() => expect(mockAxios.put).toHaveBeenCalledTimes(2))
    expect(putHeaders(1)).toEqual({ headers: { 'If-Match': '"v2"' } })
  })

  it('shows a non-stale 409 (unique violation) as a field error, not the conflict prompt', async () => {
    const user = userEvent.setup()
    mockAxios.put.mockRejectedValueOnce(
      createAxiosError(409, {
        errors: [
          {
            status: '409',
            code: 'UNIQUE_VIOLATION',
            title: 'Conflict',
            detail: 'Email must be unique',
            source: { pointer: '/data/attributes/email' },
          },
        ],
      })
    )
    await openEditForm()

    await user.click(screen.getByTestId('save-button'))

    await waitFor(() =>
      expect(screen.getByTestId('field-error-email')).toHaveTextContent('Email must be unique')
    )
    expect(screen.queryByTestId('record-conflict-dialog')).toBeNull()
  })
})
