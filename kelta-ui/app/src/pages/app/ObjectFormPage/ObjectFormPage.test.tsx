/**
 * ObjectFormPage — the end-user wrapper over the shared `RecordForm` core.
 *
 * Drives the real `RecordForm` / `useRecordFormModel` / `useRecordFormState`
 * through the route, stubbing only the data edges, and pins what the wrapper
 * owns: the `/app/o/...` navigation targets after save, on cancel and from the
 * permission gate; the create-mode prefill from query params; clone mode
 * (`?clone=<id>` → the core's `cloneFromId`, record-stack convergence P7); and the
 * end-user breadcrumb. It also pins the core behaviour the end-user form gained
 * from the admin form (record-stack convergence P3): client validation rules,
 * blur rules, schema defaults, clear-to-null on update, per-field validation,
 * create → the new record's detail page, and the edit-mode record type — and the
 * optimistic-concurrency contract (P5): If-Match on update, the conflict prompt on a
 * stale write, and Reload — and what the admin convergence (P6) added to the core: the success
 * toast, the record-load error, Save disabled with no fields, and no admin-only chrome here.
 * (`FormFieldRow` itself is covered in `components/record/form/FormFieldRow.test.tsx`.)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { ApiError } from '@/services/apiClient'
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
let etag: string | undefined
/** Stands in for `useRecord().refetch`; a test sets what the "server" returns next. */
let nextServed: { record: Record<string, unknown>; etag: string } | undefined
const refetchRecord = vi.fn(async () => {
  if (nextServed) {
    record = nextServed.record
    etag = nextServed.etag
  }
})
let layout: Record<string, unknown> | null = null
let permissions = { canCreate: true, canRead: true, canEdit: true, canDelete: true }
const createMutate = vi.fn()
const updateMutate = vi.fn()
const showToast = vi.fn()
const layoutRequests: Array<string | undefined> = []
let mutationOptions: {
  onSuccess?: (saved?: { id: string }) => void
  onError?: (error: Error) => void
} = {}
/** `apiClient.getList` responses by URL prefix (default: an empty list). */
let listResponses: Record<string, unknown[]> = {}
const routeList = async (url: string): Promise<unknown[]> => {
  const prefix = Object.keys(listResponses).find((p) => url.startsWith(p))
  return prefix ? listResponses[prefix] : []
}
const getList = vi.fn(routeList)

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
  useRecord: () => ({
    record: recordLoading ? undefined : record,
    isLoading: recordLoading,
    etag: recordLoading ? undefined : etag,
    refetch: refetchRecord,
  }),
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
  useRecordMutation: (opts: typeof mutationOptions) => {
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

interface RenderOptions {
  /** UI locale (default: English). */
  locale?: string
  /** Seeds the query cache before the first render. */
  seed?: (queryClient: QueryClient) => void
}

function renderAt(url: string, { locale, seed }: RenderOptions = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  seed?.(queryClient)
  const tree = () => (
    <I18nProvider initialLocale={locale}>
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
  etag = undefined
  nextServed = undefined
  refetchRecord.mockClear()
  layout = null
  listResponses = {}
  layoutRequests.length = 0
  permissions = { canCreate: true, canRead: true, canEdit: true, canDelete: true }
  createMutate.mockReset()
  updateMutate.mockReset()
  showToast.mockReset()
  getList.mockReset()
  getList.mockImplementation(routeList)
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

  it('translates the breadcrumb', () => {
    renderAt('/acme/app/o/accounts/new', { locale: 'de' })
    expect(screen.getByRole('link', { name: 'Startseite' })).toHaveAttribute(
      'href',
      '/acme/app/home'
    )
    expect(screen.getByText('Neu')).toBeInTheDocument()
    expect(screen.queryByText('Home')).toBeNull()
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

  it('confirms a successful create with a toast', () => {
    renderAt('/acme/app/o/accounts/new')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Ada' } })
    fireEvent.click(screen.getByTestId('save-button'))
    act(() => mutationOptions.onSuccess?.({ id: 'new-1' }))
    expect(showToast).toHaveBeenCalledWith('Record created successfully.', 'success')
  })

  it('shows no field type hints (admin chrome)', () => {
    renderAt('/acme/app/o/accounts/new')
    expect(screen.queryByTestId('field-type-name')).toBeNull()
  })

  it('disables Save on a collection without form fields', () => {
    fields = []
    renderAt('/acme/app/o/accounts/new')
    expect(screen.getByTestId('no-fields')).toBeInTheDocument()
    expect(screen.getByTestId('save-button')).toBeDisabled()
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

  const QTY_POSITIVE_RULE = {
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
  }

  it("requests the collection's active client-enforced validation rules", async () => {
    renderAt('/acme/app/o/accounts/new')
    await waitFor(() =>
      expect(getList).toHaveBeenCalledWith(
        expect.stringContaining(
          '/api/validation-rules?filter[collectionId][eq]=col-1&filter[active][eq]=true&filter[enforceOnClient][eq]=true'
        )
      )
    )
  })

  it('enforces collection validation rules marked enforceOnClient', () => {
    // The rules are served from the form's query cache entry, so they are in place on the first
    // render. (Waiting on the request instead raced react-query, which hands a resolved query to
    // its observers on a later macrotask — under load Save could run before the rules arrived.)
    fields = [
      ...BASE_FIELDS,
      { id: 'f-qty', name: 'qty', displayName: 'Qty', type: 'number', required: false },
    ]
    renderAt('/acme/app/o/accounts/new?name=Ada', {
      seed: (queryClient) =>
        queryClient.setQueryData(['client-validation-rules', 'col-1'], [QTY_POSITIVE_RULE]),
    })
    fireEvent.change(screen.getByTestId('field-qty'), { target: { value: '0' } })

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

describe('ObjectFormPage — record type picklist overrides', () => {
  const STATUS: TestField = {
    id: 'f-status',
    name: 'status',
    displayName: 'Status',
    type: 'picklist',
    required: false,
  }
  const TIER: TestField = {
    id: 'f-tier',
    name: 'tier',
    displayName: 'Tier',
    type: 'picklist',
    required: false,
  }
  const picklistValues = (...values: string[]) =>
    values.map((value, i) => ({
      value,
      label: value,
      isActive: true,
      isDefault: false,
      sortOrder: i,
    }))
  const optionValues = (testId: string) =>
    Array.from(screen.getByTestId(testId).querySelectorAll('option'))
      .map((o) => o.getAttribute('value'))
      .filter(Boolean)

  beforeEach(() => {
    fields = [...BASE_FIELDS, STATUS, TIER]
    listResponses = {
      '/api/record-types': [{ id: 'rt-1', name: 'Retail', isDefault: true }],
      '/api/picklist-values?filter[picklistSourceId][eq]=f-status': picklistValues(
        'open',
        'pending',
        'closed'
      ),
      '/api/picklist-values?filter[picklistSourceId][eq]=f-tier': picklistValues(
        'gold',
        'silver',
        'bronze'
      ),
    }
  })

  it("pre-selects the record type's default value on create and saves it", async () => {
    listResponses['/api/record-type-picklists'] = [
      {
        id: 'rtp-1',
        recordTypeId: 'rt-1',
        fieldId: 'f-status',
        availableValues: '["open","closed"]',
        defaultValue: 'closed',
      },
    ]
    renderAt('/acme/app/o/accounts/new?name=Ada')

    await waitFor(() => expect(screen.getByTestId('field-status')).toHaveValue('closed'))
    expect(optionValues('field-status')).toEqual(['open', 'closed'])
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledWith({
      name: 'Ada',
      status: 'closed',
      recordTypeId: 'rt-1',
    })
  })

  it("lets a prefill win over the record type's default", async () => {
    listResponses['/api/record-type-picklists'] = [
      {
        id: 'rtp-1',
        recordTypeId: 'rt-1',
        fieldId: 'f-status',
        availableValues: ['open', 'closed'],
        defaultValue: 'closed',
      },
    ]
    renderAt('/acme/app/o/accounts/new?name=Ada&status=open')
    await waitFor(() => expect(optionValues('field-status')).toEqual(['open', 'closed']))
    expect(screen.getByTestId('field-status')).toHaveValue('open')
  })

  it("keeps the user's choice when the record type's default arrives later", async () => {
    let releaseOverrides: (rows: unknown[]) => void = () => {}
    getList.mockImplementation(async (url: string) => {
      if (url.startsWith('/api/record-type-picklists')) {
        return new Promise<unknown[]>((resolve) => {
          releaseOverrides = resolve
        })
      }
      return routeList(url)
    })
    renderAt('/acme/app/o/accounts/new?name=Ada')
    await waitFor(() => expect(optionValues('field-status')).toEqual(['open', 'pending', 'closed']))
    fireEvent.change(screen.getByTestId('field-status'), { target: { value: 'pending' } })

    await act(async () => {
      releaseOverrides([
        {
          id: 'rtp-1',
          recordTypeId: 'rt-1',
          fieldId: 'f-status',
          availableValues: ['pending', 'closed'],
          defaultValue: 'closed',
        },
      ])
    })
    await waitFor(() => expect(optionValues('field-status')).toEqual(['pending', 'closed']))
    expect(screen.getByTestId('field-status')).toHaveValue('pending')
  })

  it('warns about malformed availableValues and falls back to the full picklist for that field only', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    listResponses['/api/record-type-picklists'] = [
      {
        id: 'rtp-bad',
        recordTypeId: 'rt-1',
        fieldId: 'f-status',
        availableValues: 'open, closed',
        defaultValue: 'closed',
      },
      {
        id: 'rtp-ok',
        recordTypeId: 'rt-1',
        fieldId: 'f-tier',
        availableValues: '["gold","silver"]',
      },
    ]
    renderAt('/acme/app/o/accounts/new?name=Ada')

    // The well-formed override still applies…
    await waitFor(() => expect(optionValues('field-tier')).toEqual(['gold', 'silver']))
    // …the malformed one shows the field's whole picklist (its default still applies)…
    expect(optionValues('field-status')).toEqual(['open', 'pending', 'closed'])
    expect(screen.getByTestId('field-status')).toHaveValue('closed')
    // …and the misconfiguration is reported rather than swallowed.
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('rtp-bad'))
    warn.mockRestore()
  })
})

describe('ObjectFormPage (clone, ?clone=<id>)', () => {
  beforeEach(() => {
    // The clone source, served by the (mocked) single-record fetch.
    record = { id: 'src-1', name: 'Ada', notes: 'hi', createdAt: '2026-01-01T00:00:00Z' }
  })

  it("seeds a new record from the source's values under the Clone title", () => {
    renderAt('/acme/app/o/accounts/new?clone=src-1')
    expect(screen.getByRole('heading', { name: 'Clone Accounts' })).toBeInTheDocument()
    expect(screen.getByText('Clone')).toBeInTheDocument()
    expect(screen.getByTestId('field-name')).toHaveValue('Ada')
    expect(screen.getByTestId('field-notes')).toHaveValue('hi')
  })

  it('creates a new record (no clone param, no source id) and lands on it', () => {
    renderAt('/acme/app/o/accounts/new?clone=src-1')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Ada (copy)' } })
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledWith({ name: 'Ada (copy)', notes: 'hi' })
    expect(updateMutate).not.toHaveBeenCalled()
    act(() => mutationOptions.onSuccess?.({ id: 'new-1' }))
    expect(screen.getByTestId('location').textContent).toBe('/acme/app/o/accounts/new-1')
  })

  it('lets a query-param prefill win over the cloned value', () => {
    renderAt('/acme/app/o/accounts/new?clone=src-1&notes=Override')
    expect(screen.getByTestId('field-name')).toHaveValue('Ada')
    expect(screen.getByTestId('field-notes')).toHaveValue('Override')
  })

  it('is ignored on edit', () => {
    record = { id: 'r1', name: 'Grace' }
    renderAt('/acme/app/o/accounts/r1/edit?clone=src-1')
    expect(screen.getByRole('heading', { name: 'Edit Accounts' })).toBeInTheDocument()
    expect(screen.getByTestId('field-name')).toHaveValue('Grace')
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

  it('confirms a successful update with a toast', () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.click(screen.getByTestId('save-button'))
    act(() => mutationOptions.onSuccess?.({ id: 'r1' }))
    expect(showToast).toHaveBeenCalledWith('Record updated successfully.', 'success')
  })

  it('shows an error, not an empty form, when the record cannot be loaded', () => {
    record = undefined
    renderAt('/acme/app/o/accounts/r1/edit')
    expect(screen.getByTestId('record-form-error')).toHaveTextContent(/could not be loaded/i)
    expect(screen.queryByTestId('save-button')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Go back' }))
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

describe('ObjectFormPage — optimistic concurrency (If-Match)', () => {
  const staleWrite = () =>
    new ApiError(409, 'This record was modified since you opened it.', [
      { field: '', message: 'This record was modified since you opened it.', code: 'STALE_WRITE' },
    ])

  beforeEach(() => {
    record = { id: 'r1', name: 'Ada', notes: 'hi' }
    etag = '"v1"'
  })

  it("sends the loaded record's ETag as If-Match on update", () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.click(screen.getByTestId('save-button'))
    expect(updateMutate).toHaveBeenCalledWith({
      id: 'r1',
      data: { name: 'Ada', notes: 'hi' },
      ifMatch: '"v1"',
    })
  })

  it('does not send If-Match on create', () => {
    renderAt('/acme/app/o/accounts/new?name=Ada')
    fireEvent.click(screen.getByTestId('save-button'))
    expect(createMutate).toHaveBeenCalledTimes(1)
    expect(createMutate.mock.calls[0]).toEqual([{ name: 'Ada' }])
  })

  it('keeps the ETag it loaded with even if the cached record moves on underneath the form', () => {
    const view = renderAt('/acme/app/o/accounts/r1/edit')
    etag = '"v2"' // a background refetch landed while the user was editing the v1 values
    view.rerender()
    fireEvent.click(screen.getByTestId('save-button'))
    expect(updateMutate).toHaveBeenCalledWith(expect.objectContaining({ ifMatch: '"v1"' }))
  })

  it('on a stale write keeps the edits and shows the conflict prompt instead of a toast', async () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Mine' } })
    fireEvent.click(screen.getByTestId('save-button'))
    act(() => mutationOptions.onError?.(staleWrite()))

    expect(await screen.findByTestId('record-conflict-dialog')).toBeInTheDocument()
    expect(screen.getByText('This record was changed')).toBeInTheDocument()
    expect(screen.getByTestId('field-name')).toHaveValue('Mine')
    expect(showToast).not.toHaveBeenCalled()
    expect(screen.queryByTestId('location')).toBeNull()

    // Keep editing: the prompt closes and the edits stay.
    fireEvent.click(screen.getByTestId('record-conflict-keep-editing'))
    await waitFor(() => expect(screen.queryByTestId('record-conflict-dialog')).toBeNull())
    expect(screen.getByTestId('field-name')).toHaveValue('Mine')
    expect(refetchRecord).not.toHaveBeenCalled()
  })

  it('Reload refetches the record, resets the form to it and saves with the new ETag', async () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Mine' } })
    fireEvent.click(screen.getByTestId('save-button'))
    act(() => mutationOptions.onError?.(staleWrite()))
    expect(await screen.findByTestId('record-conflict-dialog')).toBeInTheDocument()

    nextServed = { record: { id: 'r1', name: 'Theirs', notes: 'hi' }, etag: '"v2"' }
    fireEvent.click(screen.getByTestId('record-conflict-reload'))

    await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue('Theirs'))
    expect(refetchRecord).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByTestId('record-conflict-dialog')).toBeNull())

    updateMutate.mockClear()
    fireEvent.click(screen.getByTestId('save-button'))
    expect(updateMutate).toHaveBeenCalledWith({
      id: 'r1',
      data: { name: 'Theirs', notes: 'hi' },
      ifMatch: '"v2"',
    })
  })

  it('shows any other save error as before (toast + field errors), not the conflict prompt', () => {
    renderAt('/acme/app/o/accounts/r1/edit')
    fireEvent.click(screen.getByTestId('save-button'))
    act(() =>
      mutationOptions.onError?.(
        new ApiError(409, 'Name must be unique', [
          { field: 'name', message: 'Name must be unique', code: 'UNIQUE_VIOLATION' },
        ])
      )
    )
    expect(screen.queryByTestId('record-conflict-dialog')).toBeNull()
    expect(showToast).toHaveBeenCalledWith('Name must be unique', 'error')
    expect(screen.getByTestId('field-error-name')).toHaveTextContent('Name must be unique')
  })
})
