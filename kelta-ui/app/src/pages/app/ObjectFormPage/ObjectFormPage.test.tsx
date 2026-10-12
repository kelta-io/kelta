/**
 * ObjectFormPage — the end-user wrapper over the shared `RecordForm` core.
 *
 * Drives the real `RecordForm` / `useRecordFormModel` / `useRecordFormState`
 * through the route, stubbing only the data edges, and pins what the wrapper
 * owns: the `/app/o/...` navigation targets after save, on cancel and from the
 * permission gate; the create-mode prefill from query params; and the
 * end-user breadcrumb. It also pins the core behaviour the end-user form gained
 * from the admin form (record-stack convergence P3): client validation rules,
 * blur rules, schema defaults, clear-to-null on update, per-field validation,
 * create → the new record's detail page, and the edit-mode record type.
 * (`FormFieldRow` itself is covered in `components/record/form/FormFieldRow.test.tsx`.)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { ObjectFormPage } from './ObjectFormPage'

interface TestField {
  id: string
  name: string
  displayName: string
  type: string
  required: boolean
  defaultValue?: unknown
  constraints?: Record<string, unknown>
}

const BASE_FIELDS: TestField[] = [
  { id: 'f-name', name: 'name', displayName: 'Name', type: 'string', required: true },
  { id: 'f-notes', name: 'notes', displayName: 'Notes', type: 'string', required: false },
]

let fields: TestField[] = BASE_FIELDS
let record: Record<string, unknown> | undefined
let recordLoading = false
let layout: Record<string, unknown> | null = null
let permissions = { canCreate: true, canRead: true, canEdit: true, canDelete: true }
const createMutate = vi.fn()
const updateMutate = vi.fn()
const showToast = vi.fn()
const layoutRequests: Array<string | undefined> = []
let mutationOptions: { onSuccess?: (saved?: { id: string }) => void } = {}
/** `apiClient.getList` responses by URL prefix (default: an empty list). */
let listResponses: Record<string, unknown[]> = {}
const getList = vi.fn(async (url: string) => {
  const prefix = Object.keys(listResponses).find((p) => url.startsWith(p))
  return prefix ? listResponses[prefix] : []
})

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: { getList } }),
}))
vi.mock('@/context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' } }),
}))
vi.mock('@/context/PluginContext', () => ({
  usePlugins: () => ({ getFieldRenderer: () => undefined }),
}))
vi.mock('@/components/Toast', () => ({
  useToast: () => ({ showToast }),
}))
vi.mock('@/hooks/useCollectionSchema', () => ({
  useCollectionSchema: () => ({
    schema: { id: 'col-1', name: 'accounts', displayName: 'Accounts' },
    fields,
    isLoading: false,
    error: null,
  }),
}))
vi.mock('@/hooks/useRecord', () => ({
  useRecord: () => ({ record: recordLoading ? undefined : record, isLoading: recordLoading }),
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
  usePageLayout: (_collectionId?: string, _userId?: string, recordTypeId?: string) => {
    layoutRequests.push(recordTypeId)
    return { layout, isLoading: false }
  },
}))
vi.mock('@/hooks/useLookupDisplayMap', () => ({
  useLookupDisplayMap: () => ({ lookupDisplayMap: undefined }),
}))
vi.mock('@/hooks/useRecordMutation', () => ({
  useRecordMutation: (opts: { onSuccess?: (saved?: { id: string }) => void }) => {
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
  const tree = () => (
    <I18nProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[url]}>
          <Routes>
            <Route path="/:tenantSlug/app/o/:collection/new" element={<ObjectFormPage />} />
            <Route path="/:tenantSlug/app/o/:collection/:id/edit" element={<ObjectFormPage />} />
            <Route path="*" element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </I18nProvider>
  )
  const result = render(tree())
  return { ...result, rerender: () => result.rerender(tree()) }
}

beforeEach(() => {
  fields = BASE_FIELDS
  record = undefined
  recordLoading = false
  layout = null
  listResponses = {}
  layoutRequests.length = 0
  permissions = { canCreate: true, canRead: true, canEdit: true, canDelete: true }
  createMutate.mockReset()
  updateMutate.mockReset()
  showToast.mockReset()
  getList.mockClear()
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

  it("navigates to the new record's detail page after a successful create", () => {
    renderAt('/acme/app/o/accounts/new')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Ada' } })
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledWith({ name: 'Ada' })
    act(() => mutationOptions.onSuccess?.({ id: 'new-1' }))
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts/new-1')
  })

  it('falls back to the collection list when the create response has no id', () => {
    renderAt('/acme/app/o/accounts/new')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Ada' } })
    fireEvent.click(screen.getByTestId('save-button'))
    act(() => mutationOptions.onSuccess?.())
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts')
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

  it('applies schema default values, with a query-param prefill winning', () => {
    fields = [
      { ...BASE_FIELDS[0], defaultValue: 'Unnamed' },
      { ...BASE_FIELDS[1], defaultValue: 'From schema' },
    ]
    renderAt('/acme/app/o/accounts/new?notes=From%20query')
    expect(screen.getByTestId('field-name')).toHaveValue('Unnamed')
    expect(screen.getByTestId('field-notes')).toHaveValue('From query')
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledWith({ name: 'Unnamed', notes: 'From query' })
  })

  it('blocks save on a blank required field with a linked, announced field error', () => {
    renderAt('/acme/app/o/accounts/new')
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).not.toHaveBeenCalled()
    const error = screen.getByTestId('field-error-name')
    expect(error).toHaveTextContent('Required')
    expect(error).toHaveAttribute('role', 'alert')
    const input = screen.getByTestId('field-name')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', error.id)
    expect(showToast).toHaveBeenCalledWith('Please correct the errors in the form.', 'error')

    // Typing a value clears the error and lets the save through.
    fireEvent.change(input, { target: { value: 'Ada' } })
    expect(screen.queryByTestId('field-error-name')).toBeNull()
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledWith({ name: 'Ada' })
  })

  it("checks a field's configured constraints before saving", () => {
    fields = [{ ...BASE_FIELDS[0], constraints: { maxLength: 3 } }, BASE_FIELDS[1]]
    renderAt('/acme/app/o/accounts/new')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Too long' } })
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).not.toHaveBeenCalled()
    expect(screen.getByTestId('field-error-name')).toHaveTextContent('Must be at most 3 characters')
  })

  it('enforces collection validation rules marked enforceOnClient', async () => {
    listResponses = {
      '/api/validation-rules': [
        {
          id: 'vr-1',
          collectionId: 'col-1',
          name: 'qty-positive',
          active: true,
          errorConditionFormula: 'qty <= 0',
          errorMessage: 'Quantity must be positive',
          errorField: 'qty',
          evaluateOn: 'CREATE_AND_UPDATE',
          enforceOnClient: true,
          severity: 'ERROR',
        },
      ],
    }
    fields = [
      ...BASE_FIELDS,
      { id: 'f-qty', name: 'qty', displayName: 'Qty', type: 'number', required: false },
    ]
    renderAt('/acme/app/o/accounts/new?name=Ada')
    fireEvent.change(screen.getByTestId('field-qty'), { target: { value: '0' } })
    await waitFor(() =>
      expect(getList).toHaveBeenCalledWith(
        expect.stringContaining(
          '/api/validation-rules?filter[collectionId][eq]=col-1&filter[active][eq]=true&filter[enforceOnClient][eq]=true'
        )
      )
    )
    // Let the rules query settle into the engine before saving.
    await act(async () => {})

    fireEvent.click(screen.getByTestId('save-button'))
    expect(screen.getByTestId('field-error-qty')).toHaveTextContent('Quantity must be positive')
    expect(createMutate).not.toHaveBeenCalled()
    expect(showToast).toHaveBeenCalledWith('Quantity must be positive', 'error')
  })

  it('runs onBlur layout rules when a field loses focus', () => {
    layout = {
      id: 'layout-1',
      sections: [],
      rules: [
        {
          id: 'rule-upper',
          layoutId: 'layout-1',
          name: 'Upper-case notes',
          kind: 'TRANSFORM',
          active: true,
          whenEvents: ['onBlur'],
          targetField: 'notes',
          body: { transform: { type: 'upper' } },
          sortOrder: 0,
        },
      ],
    }
    renderAt('/acme/app/o/accounts/new')
    const notes = screen.getByTestId('field-notes')
    fireEvent.change(notes, { target: { value: 'quiet' } })
    expect(notes).toHaveValue('quiet')
    fireEvent.blur(notes)
    expect(notes).toHaveValue('QUIET')
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

  it('sends null for an optional field the user cleared', () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.change(screen.getByTestId('field-notes'), { target: { value: '' } })
    fireEvent.click(screen.getByTestId('save-button'))
    expect(updateMutate).toHaveBeenCalledWith({ id: 'r1', data: { name: 'Ada', notes: null } })
  })

  it('does not start sending blank fields that were never filled', () => {
    record = { id: 'r1', name: 'Ada' }
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.click(screen.getByTestId('save-button'))
    expect(updateMutate).toHaveBeenCalledWith({ id: 'r1', data: { name: 'Ada' } })
  })

  it("preselects the loaded record's type once the record arrives", async () => {
    listResponses = {
      '/api/record-types': [
        { id: 'rt-1', name: 'Standard', isDefault: true },
        { id: 'rt-2', name: 'Partner', isDefault: false },
      ],
    }
    record = { id: 'r1', name: 'Ada', notes: 'hi', recordTypeId: 'rt-2' }
    recordLoading = true
    const view = renderAt('/acme/app/o/accounts/r1/edit')
    expect(screen.queryByTestId('form-fields')).toBeNull()

    recordLoading = false
    view.rerender()
    const select = await screen.findByTestId('record-type-select')
    expect(select).toHaveValue('rt-2')
    expect(screen.getByRole('option', { name: 'Standard (Default)' })).toBeInTheDocument()
    expect(layoutRequests).toContain('rt-2')

    fireEvent.click(screen.getByTestId('save-button'))
    expect(updateMutate).toHaveBeenCalledWith({
      id: 'r1',
      data: { name: 'Ada', notes: 'hi', recordTypeId: 'rt-2' },
    })
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
