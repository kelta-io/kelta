/**
 * ObjectFormPage — the end-user wrapper over the shared `RecordForm` core.
 *
 * Drives the real `RecordForm` / `useRecordFormModel` / `useRecordFormState`
 * through the route, stubbing only the data edges, and pins what the wrapper
 * owns: the `/app/o/...` navigation targets after save, on cancel and from the
 * permission gate; the create-mode prefill from query params; and the
 * end-user breadcrumb. (`FormField` itself is covered in
 * `components/record/form/FormField.test.tsx`.)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ObjectFormPage } from './ObjectFormPage'

const FIELDS = [
  { id: 'f-name', name: 'name', displayName: 'Name', type: 'string', required: true },
  { id: 'f-notes', name: 'notes', displayName: 'Notes', type: 'string', required: false },
]

let record: Record<string, unknown> | undefined
let permissions = { canCreate: true, canRead: true, canEdit: true, canDelete: true }
const createMutate = vi.fn()
const updateMutate = vi.fn()
let mutationOptions: { onSuccess?: () => void } = {}

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: { getList: vi.fn(async () => []) } }),
}))
vi.mock('@/context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' } }),
}))
vi.mock('@/context/PluginContext', () => ({
  usePlugins: () => ({ getFieldRenderer: () => undefined }),
}))
vi.mock('@/components/Toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))
vi.mock('@/hooks/useCollectionSchema', () => ({
  useCollectionSchema: () => ({
    schema: { id: 'col-1', name: 'accounts', displayName: 'Accounts' },
    fields: FIELDS,
    isLoading: false,
    error: null,
  }),
}))
vi.mock('@/hooks/useRecord', () => ({
  useRecord: () => ({ record, isLoading: false }),
}))
vi.mock('@/hooks/useCollectionPermissions', () => ({
  useCollectionPermissions: () => ({
    permissions,
    isFieldVisible: () => true,
    isFieldEditable: () => true,
    isLoading: false,
  }),
}))
vi.mock('@/hooks/usePageLayout', () => ({
  usePageLayout: () => ({ layout: null, isLoading: false }),
}))
vi.mock('@/hooks/useLookupDisplayMap', () => ({
  useLookupDisplayMap: () => ({ lookupDisplayMap: undefined }),
}))
vi.mock('@/hooks/useRecordMutation', () => ({
  useRecordMutation: (opts: { onSuccess?: () => void }) => {
    mutationOptions = opts
    return {
      create: { mutate: createMutate, isPending: false },
      update: { mutate: updateMutate, isPending: false },
    }
  },
}))

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}</div>
}

function renderAt(url: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/:tenantSlug/app/o/:collection/new" element={<ObjectFormPage />} />
          <Route path="/:tenantSlug/app/o/:collection/:id/edit" element={<ObjectFormPage />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

beforeEach(() => {
  record = undefined
  permissions = { canCreate: true, canRead: true, canEdit: true, canDelete: true }
  createMutate.mockReset()
  updateMutate.mockReset()
  mutationOptions = {}
})

describe('ObjectFormPage (create)', () => {
  it('renders the end-user breadcrumb and the New title', () => {
    renderAt('/acme/app/o/accounts/new')
    expect(screen.getByRole('heading', { name: 'New Accounts' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/acme/app/home')
    expect(screen.getByRole('link', { name: 'Accounts' })).toHaveAttribute(
      'href',
      '/acme/app/o/accounts'
    )
    expect(screen.getByText('New')).toBeInTheDocument()
    expect(screen.getByTestId('form-fields')).toBeInTheDocument()
  })

  it('prefills schema fields from query params and ignores unknown keys on save', () => {
    renderAt('/acme/app/o/accounts/new?name=Prefilled&bogus=x')
    expect(screen.getByTestId('field-name')).toHaveValue('Prefilled')
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledWith({ name: 'Prefilled' })
    expect(updateMutate).not.toHaveBeenCalled()
  })

  it('navigates to the collection list after a successful create', () => {
    renderAt('/acme/app/o/accounts/new')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Ada' } })
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledWith({ name: 'Ada' })
    act(() => mutationOptions.onSuccess?.())
    expect(screen.getByTestId('location')).toHaveTextContent('/acme/app/o/accounts')
  })

  it('cancel returns to the collection list', () => {
    renderAt('/acme/app/o/accounts/new')
    fireEvent.click(screen.getByTestId('cancel-button'))
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts')
  })

  it('gates on canCreate and sends Go Back to the list', () => {
    permissions = { ...permissions, canCreate: false }
    renderAt('/acme/app/o/accounts/new')
    expect(screen.getByText('Insufficient privileges')).toBeInTheDocument()
    expect(screen.queryByTestId('save-button')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Go Back' }))
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts')
  })
})

describe('ObjectFormPage (edit)', () => {
  beforeEach(() => {
    record = { id: 'r1', name: 'Ada', notes: 'hi' }
  })

  it('loads the record into the form under the Edit title', () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    expect(screen.getByRole('heading', { name: 'Edit Accounts' })).toBeInTheDocument()
    expect(screen.getByText('Edit')).toBeInTheDocument()
    expect(screen.getByTestId('field-name')).toHaveValue('Ada')
  })

  it('ignores query params in edit mode', () => {
    renderAt('/acme/app/o/accounts/r1/edit?name=Ignored')
    expect(screen.getByTestId('field-name')).toHaveValue('Ada')
  })

  it('updates the record and navigates to its detail page', () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.click(screen.getByTestId('save-button'))
    expect(updateMutate).toHaveBeenCalledWith({ id: 'r1', data: { name: 'Ada', notes: 'hi' } })
    expect(createMutate).not.toHaveBeenCalled()
    act(() => mutationOptions.onSuccess?.())
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts/r1')
  })

  it('cancel returns to the record detail page', () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.click(screen.getByTestId('cancel-button'))
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts/r1')
  })

  it('gates on canEdit and sends Go Back to the record', () => {
    permissions = { ...permissions, canEdit: false }
    renderAt('/acme/app/o/accounts/r1/edit')
    expect(screen.getByText('Insufficient privileges')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Go Back' }))
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts/r1')
  })
})
