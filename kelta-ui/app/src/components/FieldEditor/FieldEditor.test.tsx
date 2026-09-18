/**
 * FieldEditor Component Tests
 *
 * Tests for the FieldEditor component covering rendering, validation,
 * form submission, edit mode, field types, validation rules, and accessibility.
 *
 * Requirements tested:
 * - 4.2: Display form for entering field details
 * - 4.3: Support all field types: string, number, boolean, date, datetime, json, reference
 * - 4.4: Display dropdown to select target collection for reference fields
 * - 4.5: Add field via API and update field list
 * - 4.6: Display validation errors inline with form fields
 * - 4.7: Pre-populate form with current values in edit mode
 * - 4.11: Allow setting validation rules (required, min, max, pattern, email, url)
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FieldEditor } from './FieldEditor'
import type { FieldDefinition, CollectionSummary, FetchCollectionFields } from './FieldEditor'
import { I18nProvider } from '../../context/I18nContext'
import { ToastProvider } from '../Toast'

// Wrapper component to provide required contexts
function TestWrapper({ children }: { children: React.ReactNode }) {
  return (
    <I18nProvider>
      <ToastProvider>{children}</ToastProvider>
    </I18nProvider>
  )
}

// Helper to render with contexts
function renderWithProviders(ui: React.ReactElement) {
  return render(ui, { wrapper: TestWrapper })
}

// Mock field for edit mode tests
const mockField: FieldDefinition = {
  id: 'field-123',
  name: 'test_field',
  displayName: 'Test Field',
  type: 'string',
  required: true,
  unique: false,
  indexed: true,
  defaultValue: 'default',
  order: 0,
}

// Mock field with validation rules
const mockFieldWithValidation: FieldDefinition = {
  id: 'field-456',
  name: 'email_field',
  displayName: 'Email Field',
  type: 'string',
  required: true,
  unique: true,
  indexed: false,
  validation: [
    { type: 'email', message: 'Must be a valid email' },
    { type: 'max', value: 100, message: 'Max 100 characters' },
  ],
  order: 1,
}

// Mock master_detail field
const mockReferenceField: FieldDefinition = {
  id: 'field-789',
  name: 'user_ref',
  displayName: 'User Reference',
  type: 'master_detail',
  required: false,
  unique: false,
  indexed: true,
  referenceTarget: 'users',
  order: 2,
}

// Mock collections for reference dropdown
const mockCollections: CollectionSummary[] = [
  { id: 'col-1', name: 'users', displayName: 'Users' },
  { id: 'col-2', name: 'products', displayName: 'Products' },
  { id: 'col-3', name: 'orders', displayName: 'Orders' },
]

describe('FieldEditor Component', () => {
  const defaultProps = {
    collectionId: 'col-123',
    onSave: vi.fn().mockResolvedValue(undefined),
    onCancel: vi.fn(),
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Rendering - Create Mode', () => {
    it('should render all form fields', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-editor')).toBeInTheDocument()
      expect(screen.getByTestId('field-name-input')).toBeInTheDocument()
      expect(screen.getByTestId('field-display-name-input')).toBeInTheDocument()
      expect(screen.getByTestId('field-type-select')).toBeInTheDocument()
      expect(screen.getByTestId('field-required-checkbox')).toBeInTheDocument()
      expect(screen.getByTestId('field-unique-checkbox')).toBeInTheDocument()
      expect(screen.getByTestId('field-indexed-checkbox')).toBeInTheDocument()
      expect(screen.getByTestId('field-default-value-input')).toBeInTheDocument()
    })

    it('should render form with empty values in create mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-name-input')).toHaveValue('')
      expect(screen.getByTestId('field-display-name-input')).toHaveValue('')
      expect(screen.getByTestId('field-type-select')).toHaveValue('string')
      expect(screen.getByTestId('field-required-checkbox')).not.toBeChecked()
      expect(screen.getByTestId('field-unique-checkbox')).not.toBeChecked()
      expect(screen.getByTestId('field-indexed-checkbox')).not.toBeChecked()
    })

    it('should render submit button with "Create" text in create mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      const submitButton = screen.getByTestId('field-editor-submit')
      expect(submitButton).toHaveTextContent('Create')
    })

    it('should render cancel button', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-editor-cancel')).toBeInTheDocument()
    })

    it('should render name field as enabled in create mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-name-input')).not.toBeDisabled()
    })

    it('should render type field as enabled in create mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-type-select')).not.toBeDisabled()
    })

    it('should render hint text for name field', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-name-hint')).toBeInTheDocument()
    })

    it('should render form title for create mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByText('Add Field')).toBeInTheDocument()
    })
  })

  describe('Rendering - Edit Mode', () => {
    it('should pre-populate form with field data', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      expect(screen.getByTestId('field-name-input')).toHaveValue('test_field')
      expect(screen.getByTestId('field-display-name-input')).toHaveValue('Test Field')
      expect(screen.getByTestId('field-type-select')).toHaveValue('string')
      expect(screen.getByTestId('field-required-checkbox')).toBeChecked()
      expect(screen.getByTestId('field-unique-checkbox')).not.toBeChecked()
      expect(screen.getByTestId('field-indexed-checkbox')).toBeChecked()
      expect(screen.getByTestId('field-default-value-input')).toHaveValue('default')
    })

    it('keeps the stored global picklist selected when the options list arrives after mount', () => {
      // Regression: the picklists query is enabled on modal open, so on first
      // open the select mounted before options existed — an uncontrolled select
      // snapped to the placeholder and never re-applied the stored value.
      const picklistField: FieldDefinition = {
        ...mockField,
        type: 'picklist',
        fieldTypeConfig: { globalPicklistId: 'gp-late' },
      }
      const { rerender } = renderWithProviders(
        <FieldEditor {...defaultProps} field={picklistField} picklists={[]} />
      )
      rerender(
        <FieldEditor
          {...defaultProps}
          field={picklistField}
          picklists={[{ id: 'gp-late', name: 'Late Picklist' }]}
        />
      )
      expect(screen.getByTestId('field-global-picklist-select')).toHaveValue('gp-late')
    })

    it('binds the picklist from the legacy picklistSourceId config dialect', () => {
      // Fields written by the MCP admin tooling before #1222 store
      // { picklistSourceId, picklistSourceType: 'GLOBAL' } instead of
      // { globalPicklistId } — both must pre-select the bound picklist.
      const legacyField: FieldDefinition = {
        ...mockField,
        type: 'picklist',
        fieldTypeConfig: { picklistSourceId: 'gp-legacy', picklistSourceType: 'GLOBAL' },
      }
      renderWithProviders(
        <FieldEditor
          {...defaultProps}
          field={legacyField}
          picklists={[{ id: 'gp-legacy', name: 'Legacy Picklist' }]}
        />
      )
      expect(screen.getByTestId('field-global-picklist-select')).toHaveValue('gp-legacy')
    })

    it('shows a load error with a retry affordance when the picklists fetch fails', async () => {
      const user = userEvent.setup()
      const onRetry = vi.fn()
      const picklistField: FieldDefinition = {
        ...mockField,
        type: 'picklist',
        fieldTypeConfig: { globalPicklistId: 'gp-1' },
      }
      renderWithProviders(
        <FieldEditor
          {...defaultProps}
          field={picklistField}
          picklists={[]}
          picklistsError
          onRetryPicklists={onRetry}
        />
      )
      expect(screen.getByTestId('field-global-picklist-load-error')).toBeInTheDocument()
      expect(screen.queryByTestId('field-global-picklist-loading')).not.toBeInTheDocument()
      await user.click(screen.getByTestId('field-global-picklist-retry'))
      expect(onRetry).toHaveBeenCalledTimes(1)
    })

    it('shows a loading hint while picklists are loading and none have arrived', () => {
      const picklistField: FieldDefinition = {
        ...mockField,
        type: 'picklist',
        fieldTypeConfig: { globalPicklistId: 'gp-1' },
      }
      renderWithProviders(
        <FieldEditor {...defaultProps} field={picklistField} picklists={[]} picklistsLoading />
      )
      expect(screen.getByTestId('field-global-picklist-loading')).toBeInTheDocument()
      expect(screen.queryByTestId('field-global-picklist-load-error')).not.toBeInTheDocument()
    })

    it('should render submit button with "Save" text in edit mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      const submitButton = screen.getByTestId('field-editor-submit')
      expect(submitButton).toHaveTextContent('Save')
    })

    it('should make name field read-only in edit mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      expect(screen.getByTestId('field-name-input')).toHaveAttribute('readonly')
    })

    it('should make type field non-interactive in edit mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      const select = screen.getByTestId('field-type-select')
      expect(select).toHaveAttribute('aria-disabled', 'true')
      expect(select).toHaveAttribute('tabindex', '-1')
    })

    it('should pre-populate field type in edit mode', () => {
      const emailField: FieldDefinition = {
        ...mockField,
        type: 'email',
      }
      renderWithProviders(<FieldEditor {...defaultProps} field={emailField} />)

      expect(screen.getByTestId('field-type-select')).toHaveValue('email')
    })

    it('should not render name hint in edit mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      expect(screen.queryByTestId('field-name-hint')).not.toBeInTheDocument()
    })

    it('should disable submit button when form is not dirty in edit mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      expect(screen.getByTestId('field-editor-submit')).toBeDisabled()
    })

    it('should enable submit button when form is dirty in edit mode', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      await user.clear(screen.getByTestId('field-display-name-input'))
      await user.type(screen.getByTestId('field-display-name-input'), 'Updated Name')

      expect(screen.getByTestId('field-editor-submit')).not.toBeDisabled()
    })

    it('should render form title for edit mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      expect(screen.getByText('Edit Field')).toBeInTheDocument()
    })

    it('should pre-populate validation rules in edit mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} field={mockFieldWithValidation} />)

      expect(screen.getByTestId('validation-rule-0')).toBeInTheDocument()
      expect(screen.getByTestId('validation-rule-1')).toBeInTheDocument()
    })
  })

  describe('Field Types', () => {
    it('should have all field type options', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      const select = screen.getByTestId('field-type-select')
      const options = select.querySelectorAll('option')

      expect(options).toHaveLength(22)
      expect(options[0]).toHaveValue('string')
      expect(options[1]).toHaveValue('number')
      expect(options[2]).toHaveValue('boolean')
      expect(options[3]).toHaveValue('date')
      expect(options[4]).toHaveValue('datetime')
      expect(options[5]).toHaveValue('json')
      expect(options[6]).toHaveValue('master_detail')
      expect(options[7]).toHaveValue('lookup')
      expect(options[8]).toHaveValue('picklist')
      expect(options[9]).toHaveValue('multi_picklist')
      expect(options[10]).toHaveValue('currency')
    })

    it('should default to string type in create mode', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-type-select')).toHaveValue('string')
    })

    it('should show reference target dropdown when reference type is selected', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} collections={mockCollections} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'master_detail')

      expect(screen.getByTestId('field-reference-target-select')).toBeInTheDocument()
    })

    it('should show reference target dropdown when LOOKUP type is selected (regression)', async () => {
      // Regression: a lookup field must let the user pick its target collection — previously the
      // dropdown only appeared for master_detail, so lookups were created with a null referenceTarget.
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} collections={mockCollections} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'lookup')

      expect(screen.getByTestId('field-reference-target-select')).toBeInTheDocument()
    })

    it('should not show reference target dropdown for non-reference types', () => {
      renderWithProviders(<FieldEditor {...defaultProps} collections={mockCollections} />)

      expect(screen.queryByTestId('field-reference-target-select')).not.toBeInTheDocument()
    })

    it('should populate reference target dropdown with collections', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} collections={mockCollections} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'master_detail')

      const select = screen.getByTestId('field-reference-target-select')
      const options = select.querySelectorAll('option')

      // First option is placeholder, then 3 collections
      expect(options).toHaveLength(4)
      expect(options[1]).toHaveValue('users')
      expect(options[2]).toHaveValue('products')
      expect(options[3]).toHaveValue('orders')
    })

    it('should pre-populate reference target in edit mode', () => {
      renderWithProviders(
        <FieldEditor {...defaultProps} field={mockReferenceField} collections={mockCollections} />
      )

      expect(screen.getByTestId('field-reference-target-select')).toHaveValue('users')
    })
  })

  describe('Validation Rules Section', () => {
    it('should show validation rules section for string type', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByText('Validation Rules')).toBeInTheDocument()
      expect(screen.getByTestId('add-validation-rule-button')).toBeInTheDocument()
    })

    it('should show no validation rules message initially', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('no-validation-rules')).toBeInTheDocument()
    })

    it('should add a validation rule when add button is clicked', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('add-validation-rule-button'))

      expect(screen.getByTestId('validation-rule-0')).toBeInTheDocument()
      expect(screen.queryByTestId('no-validation-rules')).not.toBeInTheDocument()
    })

    it('should remove a validation rule when remove button is clicked', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('add-validation-rule-button'))
      expect(screen.getByTestId('validation-rule-0')).toBeInTheDocument()

      await user.click(screen.getByTestId('remove-validation-rule-0'))
      expect(screen.queryByTestId('validation-rule-0')).not.toBeInTheDocument()
    })

    it('should show value field for min rule', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('add-validation-rule-button'))

      expect(screen.getByTestId('validation-rule-value-0')).toBeInTheDocument()
    })

    it('should show value field for max rule', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('add-validation-rule-button'))
      await user.selectOptions(screen.getByTestId('validation-rule-type-0'), 'max')

      expect(screen.getByTestId('validation-rule-value-0')).toBeInTheDocument()
    })

    it('should show value field for pattern rule', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('add-validation-rule-button'))
      await user.selectOptions(screen.getByTestId('validation-rule-type-0'), 'pattern')

      expect(screen.getByTestId('validation-rule-value-0')).toBeInTheDocument()
    })

    it('should not show validation rules section for boolean type', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'boolean')

      expect(screen.queryByText('Validation Rules')).not.toBeInTheDocument()
    })

    it('should not show validation rules section for json type', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'json')

      expect(screen.queryByText('Validation Rules')).not.toBeInTheDocument()
    })

    it('should not show validation rules section for reference type', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} collections={mockCollections} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'master_detail')

      expect(screen.queryByText('Validation Rules')).not.toBeInTheDocument()
    })

    it('should show only min and max rules for number type', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'number')
      await user.click(screen.getByTestId('add-validation-rule-button'))

      const ruleTypeSelect = screen.getByTestId('validation-rule-type-0')
      const options = ruleTypeSelect.querySelectorAll('option')

      expect(options).toHaveLength(2)
      expect(options[0]).toHaveValue('min')
      expect(options[1]).toHaveValue('max')
    })

    it('should disable add rule button when all rules are added', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'number')

      // Add min rule
      await user.click(screen.getByTestId('add-validation-rule-button'))
      // Add max rule
      await user.click(screen.getByTestId('add-validation-rule-button'))

      // Button should be disabled now (only 2 rules for number)
      expect(screen.getByTestId('add-validation-rule-button')).toBeDisabled()
    })
  })

  describe('Validation - Name Field', () => {
    it('should show error when name is empty', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      const nameInput = screen.getByTestId('field-name-input')
      await user.click(nameInput)
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('field-name-error')).toBeInTheDocument()
      })
    })

    it('should accept camelCase names (backend FieldLifecycleHook contract)', async () => {
      // Field API names are camelCase (e.g. dataSource → column data_source);
      // only collection names are lowercase-only. The old lowercase-only rule
      // blocked saving any existing camelCase field from the edit dialog.
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.type(screen.getByTestId('field-name-input'), 'dataSource')
      await user.tab()

      await waitFor(() => {
        expect(screen.queryByTestId('field-name-error')).not.toBeInTheDocument()
      })
    })

    it('should show error when name starts with a number', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.type(screen.getByTestId('field-name-input'), '123field')
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('field-name-error')).toBeInTheDocument()
      })
    })

    it('should show error when name contains special characters', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.type(screen.getByTestId('field-name-input'), 'test-field')
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('field-name-error')).toBeInTheDocument()
      })
    })

    it('should accept valid name with lowercase and underscores', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.type(screen.getByTestId('field-name-input'), 'valid_field_name')
      await user.tab()

      await waitFor(() => {
        expect(screen.queryByTestId('field-name-error')).not.toBeInTheDocument()
      })
    })

    it('should mark name input as invalid when error exists', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('field-name-input'))
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('field-name-input')).toHaveAttribute('aria-invalid', 'true')
      })
    })
  })

  describe('Validation - Reference Target', () => {
    it('should show error when reference type is selected without target', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} collections={mockCollections} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_ref')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'master_detail')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(screen.getByTestId('field-reference-target-error')).toBeInTheDocument()
      })
    })

    it('should not show error when reference target is selected', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} collections={mockCollections} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_ref')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'master_detail')
      await user.selectOptions(screen.getByTestId('field-reference-target-select'), 'users')
      await user.tab()

      await waitFor(() => {
        expect(screen.queryByTestId('field-reference-target-error')).not.toBeInTheDocument()
      })
    })
  })

  describe('Form Submission - Create Mode', () => {
    it('should call onSave with form data when valid', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_field')
      await user.type(screen.getByTestId('field-display-name-input'), 'My Field')
      await user.click(screen.getByTestId('field-required-checkbox'))

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledTimes(1)
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            name: 'my_field',
            displayName: 'My Field',
            type: 'string',
            required: true,
            unique: false,
            indexed: false,
          })
        )
      })
    })

    it('should show the masking section for a string field and omit it for a number field', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      // string is the default type → masking section present
      expect(screen.getByTestId('masking-config')).toBeInTheDocument()

      // switch to a non-string type → masking section gone
      await user.selectOptions(screen.getByTestId('field-type-select'), 'number')
      expect(screen.queryByTestId('masking-config')).toBeNull()
    })

    it('should build fieldTypeConfig.masking when a masking strategy is chosen', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'ssn')
      await user.selectOptions(screen.getByTestId('field-masking-type-select'), 'LAST4')
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            name: 'ssn',
            fieldTypeConfig: expect.objectContaining({
              masking: expect.objectContaining({ type: 'LAST4' }),
            }),
          })
        )
      })
    })

    it('should not call onSave when form is invalid', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      // Submit without filling required fields
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).not.toHaveBeenCalled()
      })
    })

    it('should submit with validation rules', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_field')
      await user.click(screen.getByTestId('add-validation-rule-button'))
      await user.type(screen.getByTestId('validation-rule-value-0'), '5')
      await user.type(screen.getByTestId('validation-rule-message-0'), 'Min 5 chars')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            validation: [
              expect.objectContaining({
                type: 'min',
                value: 5,
                message: 'Min 5 chars',
              }),
            ],
          })
        )
      })
    })

    it('should submit reference field with target', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(
        <FieldEditor {...defaultProps} onSave={onSave} collections={mockCollections} />
      )

      await user.type(screen.getByTestId('field-name-input'), 'user_ref')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'master_detail')
      await user.selectOptions(screen.getByTestId('field-reference-target-select'), 'users')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            type: 'master_detail',
            referenceTarget: 'users',
          })
        )
      })
    })

    it('should generate unique ID for new field', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_field')
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            id: expect.stringMatching(/^field_\d+_[a-z0-9]+$/),
          })
        )
      })
    })

    it('should submit with default value', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_field')
      await user.type(screen.getByTestId('field-default-value-input'), 'default_value')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            defaultValue: 'default_value',
          })
        )
      })
    })

    it('should parse number default value for number type', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_number')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'number')
      await user.type(screen.getByTestId('field-default-value-input'), '42')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            defaultValue: 42,
          })
        )
      })
    })

    it('should parse boolean default value for boolean type', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'my_bool')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'boolean')
      await user.type(screen.getByTestId('field-default-value-input'), 'true')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            defaultValue: true,
          })
        )
      })
    })
  })

  describe('Form Submission - Edit Mode', () => {
    it('should call onSave with updated data', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} onSave={onSave} />)

      await user.clear(screen.getByTestId('field-display-name-input'))
      await user.type(screen.getByTestId('field-display-name-input'), 'Updated Field')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            id: 'field-123',
            name: 'test_field',
            displayName: 'Updated Field',
          })
        )
      })
    })

    it('should preserve field ID in edit mode', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} field={mockField} onSave={onSave} />)

      await user.click(screen.getByTestId('field-unique-checkbox'))
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            id: 'field-123',
          })
        )
      })
    })

    it('should preserve field order in edit mode', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      const fieldWithOrder = { ...mockField, order: 5 }
      renderWithProviders(<FieldEditor {...defaultProps} field={fieldWithOrder} onSave={onSave} />)

      await user.click(screen.getByTestId('field-unique-checkbox'))
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => {
        expect(onSave).toHaveBeenCalledWith(
          expect.objectContaining({
            order: 5,
          })
        )
      })
    })
  })

  describe('Loading State', () => {
    it('should disable all inputs when submitting', () => {
      renderWithProviders(<FieldEditor {...defaultProps} isSubmitting={true} />)

      expect(screen.getByTestId('field-name-input')).toBeDisabled()
      expect(screen.getByTestId('field-display-name-input')).toBeDisabled()
      expect(screen.getByTestId('field-type-select')).toBeDisabled()
      expect(screen.getByTestId('field-required-checkbox')).toBeDisabled()
      expect(screen.getByTestId('field-unique-checkbox')).toBeDisabled()
      expect(screen.getByTestId('field-indexed-checkbox')).toBeDisabled()
      expect(screen.getByTestId('field-default-value-input')).toBeDisabled()
    })

    it('should disable buttons when submitting', () => {
      renderWithProviders(<FieldEditor {...defaultProps} isSubmitting={true} />)

      expect(screen.getByTestId('field-editor-submit')).toBeDisabled()
      expect(screen.getByTestId('field-editor-cancel')).toBeDisabled()
    })

    it('should show loading spinner in submit button when submitting', () => {
      renderWithProviders(<FieldEditor {...defaultProps} isSubmitting={true} />)

      const submitButton = screen.getByTestId('field-editor-submit')
      expect(submitButton.querySelector('[data-testid="loading-spinner"]')).toBeInTheDocument()
    })
  })

  describe('Cancel Action', () => {
    it('should call onCancel when cancel button is clicked', async () => {
      const onCancel = vi.fn()
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} onCancel={onCancel} />)

      await user.click(screen.getByTestId('field-editor-cancel'))

      expect(onCancel).toHaveBeenCalledTimes(1)
    })
  })

  describe('Accessibility', () => {
    it('should have proper labels for all inputs', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByLabelText(/Field Name/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Display Name/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Field Type/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Required/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Unique/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Indexed/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Default Value/i)).toBeInTheDocument()
    })

    it('should have aria-required on required fields', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-name-input')).toHaveAttribute('aria-required', 'true')
      expect(screen.getByTestId('field-type-select')).toHaveAttribute('aria-required', 'true')
    })

    it('should have aria-describedby pointing to error when error exists', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('field-name-input'))
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('field-name-input')).toHaveAttribute(
          'aria-describedby',
          'field-name-error'
        )
      })
    })

    it('should have role="alert" on error messages', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('field-name-input'))
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('field-name-error')).toHaveAttribute('role', 'alert')
      })
    })

    it('should have noValidate on form to use custom validation', () => {
      renderWithProviders(<FieldEditor {...defaultProps} />)

      expect(screen.getByTestId('field-editor')).toHaveAttribute('noValidate')
    })

    it('should have aria-label on remove rule buttons', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...defaultProps} />)

      await user.click(screen.getByTestId('add-validation-rule-button'))

      expect(screen.getByTestId('remove-validation-rule-0')).toHaveAttribute('aria-label')
    })
  })

  describe('Form Reset on Field Change', () => {
    it('should reset form when field prop changes', async () => {
      const { rerender } = renderWithProviders(<FieldEditor {...defaultProps} field={mockField} />)

      expect(screen.getByTestId('field-display-name-input')).toHaveValue('Test Field')

      const updatedField: FieldDefinition = {
        ...mockField,
        displayName: 'Updated Field Name',
      }

      rerender(
        <TestWrapper>
          <FieldEditor {...defaultProps} field={updatedField} />
        </TestWrapper>
      )

      expect(screen.getByTestId('field-display-name-input')).toHaveValue('Updated Field Name')
    })
  })
})

describe('FieldEditor Integration', () => {
  it('should work with async submission', async () => {
    const onSave = vi
      .fn()
      .mockImplementation(() => new Promise((resolve) => setTimeout(resolve, 100)))
    const user = userEvent.setup()

    const TestComponent = () => {
      const [isSubmitting, setIsSubmitting] = React.useState(false)

      const handleSave = async (data: FieldDefinition) => {
        setIsSubmitting(true)
        try {
          await onSave(data)
        } finally {
          setIsSubmitting(false)
        }
      }

      return (
        <FieldEditor
          collectionId="col-123"
          onSave={handleSave}
          onCancel={() => {}}
          isSubmitting={isSubmitting}
        />
      )
    }

    renderWithProviders(<TestComponent />)

    // fireEvent.change instead of user.type: per-keystroke typing (~8 renders) is
    // what starved this test under concurrent GraalVM native builds on the CI
    // runner (same root cause + fix as Chat.test.tsx's MessageComposer, see
    // concerns.md -> "Frontend suite starves under concurrent image builds").
    // A single change event commits the same form value without the render count.
    fireEvent.change(screen.getByTestId('field-name-input'), { target: { value: 'my_field' } })
    await user.click(screen.getByTestId('field-editor-submit'))

    // Should show loading state
    await waitFor(() => {
      expect(screen.getByTestId('field-editor-submit')).toBeDisabled()
    })

    // Should complete submission
    await waitFor(() => {
      expect(onSave).toHaveBeenCalled()
    })

    // Wait for the 100ms mock to resolve and the wrapper's `finally` to flip
    // isSubmitting back, otherwise that setState lands after this test has
    // returned. Distinct root cause from the render-storm fix above: the
    // dangling real setTimeout leaks past the test boundary and its resolve()
    // callback then fires during whichever test runs next, competing for the
    // same starved event loop right as that test does its own waitFor — the
    // kind of interaction only `--repeat`-style rerunning surfaces. Same
    // hardening as CollectionForm.test.tsx's "should work with async submission".
    await waitFor(() => {
      expect(screen.getByTestId('field-editor-submit')).toBeEnabled()
    })
  })

  describe('Rollup Summary configuration', () => {
    const defaultProps = {
      collectionId: 'col-123',
      onSave: vi.fn().mockResolvedValue(undefined),
      onCancel: vi.fn(),
    }
    const childFields = [
      {
        name: 'orderId',
        displayName: 'Order',
        type: 'master_detail' as const,
        referenceTarget: 'orders',
      },
      { name: 'amount', displayName: 'Amount', type: 'number' as const },
      { name: 'placedAt', displayName: 'Placed At', type: 'datetime' as const },
      { name: 'note', displayName: 'Note', type: 'string' as const },
    ]

    function renderRollupForm(opts?: { fetcher?: ReturnType<typeof vi.fn> }) {
      const fetcher = opts?.fetcher ?? vi.fn().mockResolvedValue(childFields)
      const onSave = vi.fn().mockResolvedValue(undefined)
      const utils = renderWithProviders(
        <FieldEditor
          {...defaultProps}
          collectionName="orders"
          collections={mockCollections}
          fetchCollectionFields={fetcher as unknown as FetchCollectionFields}
          onSave={onSave}
        />
      )
      return { ...utils, fetcher, onSave }
    }

    it('renders no rollup config block by default', () => {
      renderRollupForm()
      expect(screen.queryByTestId('rollup-config')).not.toBeInTheDocument()
    })

    it('reveals rollup config inputs when rollup_summary is selected', async () => {
      const user = userEvent.setup()
      renderRollupForm()
      await user.selectOptions(screen.getByTestId('field-type-select'), 'rollup_summary')

      expect(screen.getByTestId('rollup-config')).toBeInTheDocument()
      expect(screen.getByTestId('field-rollup-child-select')).toBeInTheDocument()
      expect(screen.getByTestId('field-rollup-fn-select')).toBeInTheDocument()
    })

    it('builds fieldTypeConfig on submit including aggregateField for SUM', async () => {
      const user = userEvent.setup()
      const { onSave, fetcher } = renderRollupForm()

      await user.type(screen.getByTestId('field-name-input'), 'total_amount')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'rollup_summary')
      await user.selectOptions(screen.getByTestId('field-rollup-child-select'), 'products')
      await waitFor(() => expect(fetcher).toHaveBeenCalledWith('products'))

      await user.selectOptions(screen.getByTestId('field-rollup-fk-select'), 'orderId')
      await user.selectOptions(screen.getByTestId('field-rollup-fn-select'), 'SUM')
      await user.selectOptions(screen.getByTestId('field-rollup-field-select'), 'amount')

      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => expect(onSave).toHaveBeenCalled())
      const submitted = onSave.mock.calls[0][0]
      expect(submitted.type).toBe('rollup_summary')
      expect(submitted.fieldTypeConfig).toEqual({
        childCollection: 'products',
        foreignKeyField: 'orderId',
        aggregateFunction: 'SUM',
        aggregateField: 'amount',
      })
    })

    it('omits aggregateField for COUNT and skips the field selector', async () => {
      const user = userEvent.setup()
      const { onSave } = renderRollupForm()

      await user.type(screen.getByTestId('field-name-input'), 'line_count')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'rollup_summary')
      await user.selectOptions(screen.getByTestId('field-rollup-child-select'), 'products')
      await waitFor(() => expect(screen.getByTestId('field-rollup-fk-select')).not.toBeDisabled())
      await user.selectOptions(screen.getByTestId('field-rollup-fk-select'), 'orderId')
      await user.selectOptions(screen.getByTestId('field-rollup-fn-select'), 'COUNT')

      expect(screen.queryByTestId('field-rollup-field-select')).not.toBeInTheDocument()

      await user.click(screen.getByTestId('field-editor-submit'))
      await waitFor(() => expect(onSave).toHaveBeenCalled())
      expect(onSave.mock.calls[0][0].fieldTypeConfig).toEqual({
        childCollection: 'products',
        foreignKeyField: 'orderId',
        aggregateFunction: 'COUNT',
      })
    })

    it('blocks submit when child collection is missing', async () => {
      const user = userEvent.setup()
      const { onSave } = renderRollupForm()

      await user.type(screen.getByTestId('field-name-input'), 'total')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'rollup_summary')
      await user.click(screen.getByTestId('field-editor-submit'))

      await new Promise((r) => setTimeout(r, 50))
      expect(onSave).not.toHaveBeenCalled()
    })

    it('pre-populates rollup config from existing fieldTypeConfig in edit mode', () => {
      const existing: FieldDefinition = {
        id: 'rollup-1',
        name: 'total_amount',
        type: 'rollup_summary',
        required: false,
        unique: false,
        indexed: false,
        order: 0,
        fieldTypeConfig: {
          childCollection: 'products',
          foreignKeyField: 'orderId',
          aggregateFunction: 'AVG',
          aggregateField: 'amount',
        },
      }
      renderRollupForm()
      renderWithProviders(
        <FieldEditor
          {...defaultProps}
          collectionName="orders"
          collections={mockCollections}
          fetchCollectionFields={vi.fn().mockResolvedValue(childFields)}
          field={existing}
        />
      )

      const editorNodes = screen.getAllByTestId('rollup-config')
      expect(editorNodes.length).toBeGreaterThan(0)
      expect(screen.getAllByTestId('field-rollup-child-select')[0]).toHaveValue('products')
      expect(screen.getAllByTestId('field-rollup-fn-select')[0]).toHaveValue('AVG')
    })

    it('renders the warning sigil rather than literal "u26A0" on errors', async () => {
      const user = userEvent.setup()
      renderRollupForm()
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => expect(screen.getByTestId('field-name-error')).toBeInTheDocument())
      const styles = window.getComputedStyle(screen.getByTestId('field-name-error'), '::before')
      // jsdom returns the raw declared value; ensure we never carry the
      // literal "u26A0" escape that would render as text.
      expect(styles.content || '').not.toContain('u26A0')
    })
  })

  describe('Formula configuration', () => {
    const formulaProps = {
      collectionId: 'col-formula-1',
      onSave: vi.fn().mockResolvedValue(undefined),
      onCancel: vi.fn(),
    }

    it('renders no formula config block by default', () => {
      renderWithProviders(<FieldEditor {...formulaProps} />)
      expect(screen.queryByTestId('formula-config')).not.toBeInTheDocument()
    })

    it('reveals the formula config inputs when type=formula is selected', async () => {
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...formulaProps} />)

      await user.selectOptions(screen.getByTestId('field-type-select'), 'formula')

      expect(screen.getByTestId('formula-config')).toBeInTheDocument()
      expect(screen.getByTestId('field-formula-return-type-select')).toBeInTheDocument()
      expect(screen.getByTestId('field-formula-expression-input')).toBeInTheDocument()
      expect(screen.getByTestId('field-formula-insert-field')).toBeInTheDocument()
    })

    it('blocks submit when expression is missing', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...formulaProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'total')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'formula')
      await user.selectOptions(screen.getByTestId('field-formula-return-type-select'), 'NUMBER')
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() =>
        expect(screen.getByTestId('field-formula-expression-error')).toBeInTheDocument()
      )
      expect(onSave).not.toHaveBeenCalled()
    })

    it('blocks submit when return type is missing', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...formulaProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'total')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'formula')
      await user.type(screen.getByTestId('field-formula-expression-input'), 'amount * 2')
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() =>
        expect(screen.getByTestId('field-formula-return-type-error')).toBeInTheDocument()
      )
      expect(onSave).not.toHaveBeenCalled()
    })

    it('builds fieldTypeConfig with expression and returnType on submit', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<FieldEditor {...formulaProps} onSave={onSave} />)

      await user.type(screen.getByTestId('field-name-input'), 'total')
      await user.selectOptions(screen.getByTestId('field-type-select'), 'formula')
      await user.selectOptions(screen.getByTestId('field-formula-return-type-select'), 'NUMBER')
      await user.type(screen.getByTestId('field-formula-expression-input'), 'amount * 2')
      await user.click(screen.getByTestId('field-editor-submit'))

      await waitFor(() => expect(onSave).toHaveBeenCalled())
      const submitted = onSave.mock.calls[0][0]
      expect(submitted.type).toBe('formula')
      expect(submitted.fieldTypeConfig).toEqual({
        expression: 'amount * 2',
        returnType: 'NUMBER',
      })
    })

    it('disables the return type select in edit mode', () => {
      const existing: FieldDefinition = {
        id: 'formula-1',
        name: 'doubled',
        type: 'formula',
        required: false,
        unique: false,
        indexed: false,
        order: 0,
        fieldTypeConfig: { expression: 'amount * 2', returnType: 'NUMBER' },
      }
      renderWithProviders(<FieldEditor {...formulaProps} field={existing} />)

      const select = screen.getByTestId('field-formula-return-type-select')
      expect(select).toHaveAttribute('aria-disabled', 'true')
      expect(select).toHaveAttribute('tabindex', '-1')
    })

    it('pre-populates expression and return type from existing fieldTypeConfig', () => {
      const existing: FieldDefinition = {
        id: 'formula-1',
        name: 'doubled',
        type: 'formula',
        required: false,
        unique: false,
        indexed: false,
        order: 0,
        fieldTypeConfig: { expression: 'amount * 2', returnType: 'NUMBER' },
      }
      renderWithProviders(<FieldEditor {...formulaProps} field={existing} />)

      expect(screen.getByTestId('field-formula-expression-input')).toHaveValue('amount * 2')
      expect(screen.getByTestId('field-formula-return-type-select')).toHaveValue('NUMBER')
    })
  })
})
