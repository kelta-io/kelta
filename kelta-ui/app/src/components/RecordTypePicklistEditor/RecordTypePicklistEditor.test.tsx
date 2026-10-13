/**
 * RecordTypePicklistEditor Component Tests
 *
 * Renders the per-record-type picklist override modal against a mocked API: the overrides load
 * from the `record-type-picklists` system collection (the rows the record form and
 * `RecordTypeEnforcementHook` read), each field's values resolve like the record form's, and
 * Save creates / updates / deletes exactly the changed fields' rows — plus the load and save
 * error states.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RecordTypePicklistEditor } from './RecordTypePicklistEditor'
import type { RecordTypePicklistEditorProps } from './RecordTypePicklistEditor'
import type { RecordType, FieldDefinition } from '../../types/collections'

const { mockGetList, mockPostResource, mockPatchResource, mockDeleteResource, mockToast } =
  vi.hoisted(() => ({
    mockGetList: vi.fn(),
    mockPostResource: vi.fn(),
    mockPatchResource: vi.fn(),
    mockDeleteResource: vi.fn(),
    mockToast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
  }))

vi.mock('@/context/ApiContext', () => ({
  useApi: vi.fn(() => ({
    apiClient: {
      getList: mockGetList,
      postResource: mockPostResource,
      patchResource: mockPatchResource,
      deleteResource: mockDeleteResource,
    },
  })),
}))

vi.mock('@/context/I18nContext', () => ({
  useI18n: vi.fn(() => ({
    locale: 'en',
    setLocale: vi.fn(),
    t: (key: string, params?: Record<string, string | number>) => {
      const translations: Record<string, string> = {
        'recordTypes.picklistOverrides': 'Picklist Overrides',
        'recordTypes.noPicklistFields': 'This collection has no picklist fields to configure.',
        'recordTypes.allValuesAvailable': 'All values available',
        'recordTypes.restrictedValues': `${params?.count ?? '?'} of ${params?.total ?? '?'} values available`,
        'recordTypes.defaultValue': 'Default Value',
        'recordTypes.noDefault': 'No default',
        'recordTypes.overrideSaved': 'Picklist overrides saved successfully',
        'common.close': 'Close',
        'common.cancel': 'Cancel',
        'common.save': 'Save',
        'common.saving': 'Saving...',
        'common.loading': 'Loading...',
        'common.retry': 'Retry',
        'common.selectAll': 'Select All',
        'common.deselectAll': 'Deselect All',
        'picklistDependencies.noValuesForField': 'No values for this field',
        'errors.generic': 'An error occurred',
      }
      return translations[key] || key
    },
    formatDate: vi.fn(),
    formatNumber: vi.fn(),
    direction: 'ltr' as const,
  })),
}))

vi.mock('sonner', async (importOriginal) => ({
  ...(await importOriginal<typeof import('sonner')>()),
  toast: mockToast,
}))

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const mockRecordType: RecordType = {
  id: 'rt-001',
  collectionId: 'col-001',
  name: 'Standard',
  description: 'Standard record type',
  isActive: true,
  isDefault: true,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const statusField: FieldDefinition = {
  id: 'field-pl-1',
  name: 'status',
  displayName: 'Status',
  type: 'picklist',
  required: false,
  unique: false,
  indexed: false,
  order: 0,
}

const tagsField: FieldDefinition = {
  id: 'field-pl-2',
  name: 'tags',
  displayName: 'Tags',
  type: 'multi_picklist',
  required: false,
  unique: false,
  indexed: false,
  order: 1,
}

const nameField: FieldDefinition = {
  id: 'field-str-1',
  name: 'name',
  displayName: 'Name',
  type: 'string',
  required: true,
  unique: false,
  indexed: false,
  order: 2,
}

function pv(value: string, sortOrder: number, isActive = true) {
  return { value, label: value.toUpperCase(), isDefault: false, isActive, sortOrder }
}

/** Picklist values by `picklistSourceId` (field id for FIELD sources, picklist id for GLOBAL). */
let picklistValues: Record<string, unknown[]>
/** The record type's stored override rows, or an Error to reject the list with. */
let overrides: unknown[] | Error

const OVERRIDES_URL = '/api/record-type-picklists?filter[recordTypeId][eq]=rt-001&page[size]=200'

function routeGetList(url: string): Promise<unknown[]> {
  if (url.startsWith('/api/record-type-picklists')) {
    return overrides instanceof Error ? Promise.reject(overrides) : Promise.resolve(overrides)
  }
  if (url.startsWith('/api/picklist-values')) {
    const sourceId = decodeURIComponent(/picklistSourceId\]\[eq\]=([^&]+)/.exec(url)?.[1] ?? '')
    return Promise.resolve(picklistValues[sourceId] ?? [])
  }
  return Promise.resolve([])
}

function renderEditor(props: Partial<RecordTypePicklistEditorProps> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const allProps: RecordTypePicklistEditorProps = {
    recordType: mockRecordType,
    fields: [statusField, tagsField, nameField],
    onClose: vi.fn(),
    onSaved: vi.fn(),
    ...props,
  }
  render(
    <QueryClientProvider client={queryClient}>
      <RecordTypePicklistEditor {...allProps} />
    </QueryClientProvider>
  )
  return { ...allProps, queryClient }
}

const checkbox = (fieldId: string, value: string) =>
  screen.getByTestId(`value-checkbox-${fieldId}-${value}`) as HTMLInputElement

describe('RecordTypePicklistEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    picklistValues = {
      'field-pl-1': [pv('closed', 1), pv('open', 0), pv('pending', 2), pv('retired', 3, false)],
      'field-pl-2': [pv('bug', 0), pv('feature', 1)],
    }
    overrides = []
    mockGetList.mockImplementation(routeGetList)
    mockPostResource.mockResolvedValue({})
    mockPatchResource.mockResolvedValue({})
    mockDeleteResource.mockResolvedValue(undefined)
  })

  describe('Rendering', () => {
    it('renders the modal with the record type name and both actions', async () => {
      renderEditor()

      expect(screen.getByTestId('picklist-override-modal')).toBeInTheDocument()
      expect(screen.getByText(/Picklist Overrides/)).toBeInTheDocument()
      expect(screen.getByText(/Standard/)).toBeInTheDocument()
      expect(screen.getByTestId('picklist-override-cancel')).toBeInTheDocument()
      expect(await screen.findByTestId('field-section-field-pl-1')).toBeInTheDocument()
      expect(screen.getByTestId('picklist-override-save')).toBeEnabled()
    })

    it('shows the empty state when the collection has no picklist fields', async () => {
      renderEditor({ fields: [nameField] })

      expect(await screen.findByTestId('no-picklist-fields')).toBeInTheDocument()
      expect(screen.getByTestId('picklist-override-save')).toBeDisabled()
    })

    it('renders sections for picklist fields only', async () => {
      renderEditor()

      expect(await screen.findByTestId('field-section-field-pl-1')).toBeInTheDocument()
      expect(screen.getByTestId('field-section-field-pl-2')).toBeInTheDocument()
      expect(screen.queryByTestId('field-section-field-str-1')).not.toBeInTheDocument()
      expect(screen.getByText('Status')).toBeInTheDocument()
      expect(screen.getByText('Tags')).toBeInTheDocument()
    })
  })

  describe('Loading overrides', () => {
    it('reads the record type rows from /api/record-type-picklists', async () => {
      renderEditor()

      await screen.findByTestId('field-section-field-pl-1')
      expect(mockGetList).toHaveBeenCalledWith(OVERRIDES_URL)
      expect(mockGetList).not.toHaveBeenCalledWith(
        expect.stringContaining('record-type-picklist-values')
      )
    })

    it('offers each field its active values in sort order, every value allowed without an override', async () => {
      renderEditor()

      await screen.findByTestId('value-checkbox-field-pl-1-open')
      const items = screen
        .getAllByTestId(/^value-item-field-pl-1-/)
        .map((el) => el.getAttribute('data-testid'))
      expect(items).toEqual([
        'value-item-field-pl-1-open',
        'value-item-field-pl-1-closed',
        'value-item-field-pl-1-pending',
      ])
      expect(checkbox('field-pl-1', 'open').checked).toBe(true)
      expect(checkbox('field-pl-1', 'pending').checked).toBe(true)
      expect(screen.getAllByText('All values available')).toHaveLength(2)
    })

    it('resolves a global-picklist field to its GLOBAL source, as the record form does', async () => {
      picklistValues['gp-1'] = [pv('red', 0), pv('blue', 1)]
      renderEditor({
        fields: [{ ...statusField, fieldTypeConfig: { globalPicklistId: 'gp-1' } }],
      })

      expect(await screen.findByTestId('value-checkbox-field-pl-1-red')).toBeInTheDocument()
      expect(mockGetList).toHaveBeenCalledWith(
        expect.stringContaining(
          'filter[picklistSourceId][eq]=gp-1&filter[picklistSourceType][eq]=GLOBAL'
        )
      )
    })

    it('applies a stored override: allowed values checked, default selected', async () => {
      overrides = [
        {
          id: 'ov-1',
          recordTypeId: 'rt-001',
          fieldId: 'field-pl-1',
          availableValues: ['open', 'closed'],
          defaultValue: 'open',
        },
      ]
      renderEditor()

      await screen.findByTestId('value-checkbox-field-pl-1-open')
      await waitFor(() => expect(checkbox('field-pl-1', 'pending').checked).toBe(false))
      expect(checkbox('field-pl-1', 'open').checked).toBe(true)
      expect(checkbox('field-pl-1', 'closed').checked).toBe(true)
      expect(screen.getByTestId('default-select-field-pl-1')).toHaveValue('open')
      expect(screen.getByText('2 of 3 values available')).toBeInTheDocument()
    })

    it('accepts the legacy JSON-string encoding of availableValues', async () => {
      overrides = [
        {
          id: 'ov-1',
          recordTypeId: 'rt-001',
          fieldId: 'field-pl-1',
          availableValues: '["pending"]',
          defaultValue: null,
        },
      ]
      renderEditor()

      await screen.findByTestId('value-checkbox-field-pl-1-open')
      await waitFor(() => expect(checkbox('field-pl-1', 'open').checked).toBe(false))
      expect(checkbox('field-pl-1', 'pending').checked).toBe(true)
    })

    it('shows every value allowed for a malformed override, like the record form', async () => {
      overrides = [
        {
          id: 'ov-1',
          recordTypeId: 'rt-001',
          fieldId: 'field-pl-1',
          availableValues: 'open, closed',
        },
      ]
      renderEditor()

      await screen.findByTestId('value-checkbox-field-pl-1-open')
      expect(checkbox('field-pl-1', 'pending').checked).toBe(true)
    })

    it('shows an error with retry and blocks saving when the overrides fail to load', async () => {
      overrides = new Error('Overrides unavailable')
      renderEditor()

      expect(await screen.findByText('Overrides unavailable')).toBeInTheDocument()
      expect(screen.queryByTestId('field-section-field-pl-1')).not.toBeInTheDocument()
      expect(screen.getByTestId('picklist-override-save')).toBeDisabled()

      overrides = []
      fireEvent.click(screen.getByRole('button', { name: /Retry/ }))
      expect(await screen.findByTestId('field-section-field-pl-1')).toBeInTheDocument()
      expect(screen.getByTestId('picklist-override-save')).toBeEnabled()
    })
  })

  describe('Saving', () => {
    it('creates a row for a newly restricted field and leaves untouched fields alone', async () => {
      const user = userEvent.setup()
      const { onSaved } = renderEditor()

      await user.click(await screen.findByTestId('value-checkbox-field-pl-1-pending'))
      await user.click(screen.getByTestId('picklist-override-save'))

      await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
      expect(mockPostResource).toHaveBeenCalledTimes(1)
      expect(mockPostResource).toHaveBeenCalledWith('/api/record-type-picklists', {
        recordTypeId: 'rt-001',
        fieldId: 'field-pl-1',
        availableValues: ['open', 'closed'],
        defaultValue: null,
      })
      expect(mockPatchResource).not.toHaveBeenCalled()
      expect(mockDeleteResource).not.toHaveBeenCalled()
      expect(mockToast.success).toHaveBeenCalledWith('Picklist overrides saved successfully')
    })

    it('stores a default with every value allowed as an override', async () => {
      const user = userEvent.setup()
      const { onSaved } = renderEditor()

      await user.selectOptions(await screen.findByTestId('default-select-field-pl-2'), 'feature')
      await user.click(screen.getByTestId('picklist-override-save'))

      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      expect(mockPostResource).toHaveBeenCalledWith('/api/record-type-picklists', {
        recordTypeId: 'rt-001',
        fieldId: 'field-pl-2',
        availableValues: ['bug', 'feature'],
        defaultValue: 'feature',
      })
    })

    it('updates an existing row by id', async () => {
      overrides = [
        {
          id: 'ov-1',
          recordTypeId: 'rt-001',
          fieldId: 'field-pl-1',
          availableValues: ['open', 'closed'],
          defaultValue: 'open',
        },
      ]
      const user = userEvent.setup()
      const { onSaved } = renderEditor()

      await screen.findByTestId('value-checkbox-field-pl-1-open')
      await waitFor(() => expect(checkbox('field-pl-1', 'pending').checked).toBe(false))
      // Unchecking the default also clears it
      await user.click(checkbox('field-pl-1', 'open'))
      await user.click(screen.getByTestId('picklist-override-save'))

      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      expect(mockPatchResource).toHaveBeenCalledWith('/api/record-type-picklists/ov-1', {
        availableValues: ['closed'],
        defaultValue: null,
      })
      expect(mockPostResource).not.toHaveBeenCalled()
    })

    it('deletes the row once a field allows every value again with no default', async () => {
      overrides = [
        {
          id: 'ov-1',
          recordTypeId: 'rt-001',
          fieldId: 'field-pl-1',
          availableValues: ['open'],
          defaultValue: 'open',
        },
      ]
      const user = userEvent.setup()
      const { onSaved } = renderEditor()

      await screen.findByTestId('value-checkbox-field-pl-1-open')
      await waitFor(() => expect(checkbox('field-pl-1', 'closed').checked).toBe(false))
      await user.click(screen.getByTestId('select-all-field-pl-1'))
      await user.selectOptions(screen.getByTestId('default-select-field-pl-1'), '')
      await user.click(screen.getByTestId('picklist-override-save'))

      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      expect(mockDeleteResource).toHaveBeenCalledWith('/api/record-type-picklists/ov-1')
      expect(mockPostResource).not.toHaveBeenCalled()
      expect(mockPatchResource).not.toHaveBeenCalled()
    })

    it('writes nothing when nothing changed', async () => {
      const user = userEvent.setup()
      const { onSaved } = renderEditor()

      // Toggle a value off and back on: back to the stored state
      await user.click(await screen.findByTestId('value-checkbox-field-pl-1-open'))
      await user.click(checkbox('field-pl-1', 'open'))
      await user.click(screen.getByTestId('picklist-override-save'))

      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      expect(mockPostResource).not.toHaveBeenCalled()
      expect(mockPatchResource).not.toHaveBeenCalled()
      expect(mockDeleteResource).not.toHaveBeenCalled()
    })

    it('reports a failed write and keeps the dialog open', async () => {
      mockPostResource.mockRejectedValue(new Error('Value not allowed'))
      const user = userEvent.setup()
      const { onSaved, queryClient } = renderEditor()
      const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

      await user.click(await screen.findByTestId('value-checkbox-field-pl-1-pending'))
      await user.click(screen.getByTestId('picklist-override-save'))

      await waitFor(() => expect(mockToast.error).toHaveBeenCalledWith('Value not allowed'))
      expect(onSaved).not.toHaveBeenCalled()
      expect(screen.getByTestId('picklist-override-modal')).toBeInTheDocument()
      expect(screen.getByTestId('picklist-override-save')).toBeEnabled()
      // The draft survives for a retry, and the caches are refreshed either way
      expect(checkbox('field-pl-1', 'pending').checked).toBe(false)
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: ['record-type-picklist-overrides', 'rt-001'],
      })
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['record-form-picklist-overrides'] })
    })
  })

  describe('Interactions', () => {
    it('closes from the close button, cancel, overlay and Escape', async () => {
      const onClose = vi.fn()
      renderEditor({ onClose })

      fireEvent.click(screen.getByTestId('picklist-override-close'))
      fireEvent.click(screen.getByTestId('picklist-override-cancel'))
      fireEvent.mouseDown(screen.getByTestId('picklist-override-overlay'))
      fireEvent.keyDown(screen.getByTestId('picklist-override-overlay'), { key: 'Escape' })

      expect(onClose).toHaveBeenCalledTimes(4)
    })

    it('does not close when the modal content is clicked', () => {
      const onClose = vi.fn()
      renderEditor({ onClose })

      fireEvent.mouseDown(screen.getByTestId('picklist-override-modal'))

      expect(onClose).not.toHaveBeenCalled()
    })

    it('collapses and expands a field section', async () => {
      const user = userEvent.setup()
      renderEditor()

      const header = await screen.findByTestId('field-header-field-pl-1')
      expect(header).toHaveAttribute('aria-expanded', 'true')
      await user.click(header)
      expect(header).toHaveAttribute('aria-expanded', 'false')
      await user.click(header)
      expect(header).toHaveAttribute('aria-expanded', 'true')
    })
  })

  describe('Accessibility', () => {
    it('is a labelled modal dialog with a labelled close button', () => {
      renderEditor()

      const modal = screen.getByTestId('picklist-override-modal')
      expect(modal).toHaveAttribute('role', 'dialog')
      expect(modal).toHaveAttribute('aria-modal', 'true')
      expect(modal).toHaveAttribute('aria-labelledby', 'picklist-override-title')
      expect(screen.getByTestId('picklist-override-close')).toHaveAttribute('aria-label', 'Close')
    })
  })
})
