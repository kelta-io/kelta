/**
 * CollectionForm Component Tests
 *
 * Tests for the CollectionForm component covering rendering, validation,
 * form submission, edit mode, and accessibility.
 *
 * Requirements tested:
 * - 3.4: Display form for entering collection details
 * - 3.5: Create collection via API and display success message
 * - 3.6: Display validation errors inline with form fields
 * - 3.9: Pre-populate form with current values in edit mode
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CollectionForm } from './CollectionForm'
import type { Collection, CollectionFormData } from './CollectionForm'
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

// Mock collection for edit mode tests
const mockCollection: Collection = {
  id: 'col-123',
  name: 'test_collection',
  displayName: 'Test Collection',
  description: 'A test collection for testing',
  active: true,
  currentVersion: 1,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-15T00:00:00Z',
}

describe('CollectionForm Component', () => {
  const defaultProps = {
    onSubmit: vi.fn().mockResolvedValue(undefined),
    onCancel: vi.fn(),
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Rendering - Create Mode', () => {
    it('should render all form fields', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-form')).toBeInTheDocument()
      expect(screen.getByTestId('collection-name-input')).toBeInTheDocument()
      expect(screen.getByTestId('collection-display-name-input')).toBeInTheDocument()
      expect(screen.getByTestId('collection-description-input')).toBeInTheDocument()
      expect(screen.getByTestId('collection-active-checkbox')).toBeInTheDocument()
    })

    it('should render form with empty values in create mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-name-input')).toHaveValue('')
      expect(screen.getByTestId('collection-display-name-input')).toHaveValue('')
      expect(screen.getByTestId('collection-description-input')).toHaveValue('')
      expect(screen.getByTestId('collection-active-checkbox')).toBeChecked()
    })

    it('should render submit button with "Create" text in create mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      const submitButton = screen.getByTestId('collection-form-submit')
      expect(submitButton).toHaveTextContent('Create')
    })

    it('should render cancel button', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-form-cancel')).toBeInTheDocument()
    })

    it('should render name field as enabled in create mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-name-input')).not.toBeDisabled()
    })

    it('should render hint text for name field', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('name-hint')).toBeInTheDocument()
    })
  })

  describe('Rendering - Edit Mode', () => {
    it('should pre-populate form with collection data', () => {
      renderWithProviders(<CollectionForm {...defaultProps} collection={mockCollection} />)

      expect(screen.getByTestId('collection-name-input')).toHaveValue('test_collection')
      expect(screen.getByTestId('collection-display-name-input')).toHaveValue('Test Collection')
      expect(screen.getByTestId('collection-description-input')).toHaveValue(
        'A test collection for testing'
      )
      expect(screen.getByTestId('collection-active-checkbox')).toBeChecked()
    })

    it('should render submit button with "Save" text in edit mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} collection={mockCollection} />)

      const submitButton = screen.getByTestId('collection-form-submit')
      expect(submitButton).toHaveTextContent('Save')
    })

    it('should disable name field in edit mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} collection={mockCollection} />)

      expect(screen.getByTestId('collection-name-input')).toBeDisabled()
    })

    it('should not render name hint in edit mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} collection={mockCollection} />)

      expect(screen.queryByTestId('name-hint')).not.toBeInTheDocument()
    })

    it('should disable submit button when form is not dirty in edit mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} collection={mockCollection} />)

      expect(screen.getByTestId('collection-form-submit')).toBeDisabled()
    })

    it('should enable submit button when form is dirty in edit mode', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} collection={mockCollection} />)

      await user.clear(screen.getByTestId('collection-display-name-input'))
      await user.type(screen.getByTestId('collection-display-name-input'), 'Updated Name')

      expect(screen.getByTestId('collection-form-submit')).not.toBeDisabled()
    })
  })

  describe('Validation - Name Field', () => {
    it('should show error when name is empty', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      const nameInput = screen.getByTestId('collection-name-input')
      await user.click(nameInput)
      await user.tab() // Blur the field

      await waitFor(() => {
        expect(screen.getByTestId('name-error')).toBeInTheDocument()
      })
    })

    it('should show error when name contains uppercase letters', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.type(screen.getByTestId('collection-name-input'), 'TestCollection')
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('name-error')).toBeInTheDocument()
      })
    })

    it('should show error when name starts with a number', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.type(screen.getByTestId('collection-name-input'), '123collection')
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('name-error')).toBeInTheDocument()
      })
    })

    it('should show error when name contains special characters', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.type(screen.getByTestId('collection-name-input'), 'test-collection')
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('name-error')).toBeInTheDocument()
      })
    })

    it('should accept valid name with lowercase and underscores', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.type(screen.getByTestId('collection-name-input'), 'valid_collection_name')
      await user.tab()

      await waitFor(() => {
        expect(screen.queryByTestId('name-error')).not.toBeInTheDocument()
      })
    })

    it('should mark name input as invalid when error exists', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.click(screen.getByTestId('collection-name-input'))
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('collection-name-input')).toHaveAttribute('aria-invalid', 'true')
      })
    })
  })

  describe('Validation - Display Name Field', () => {
    it('should show error when display name is empty', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      const displayNameInput = screen.getByTestId('collection-display-name-input')
      await user.click(displayNameInput)
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('display-name-error')).toBeInTheDocument()
      })
    })

    it('should accept valid display name', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.type(screen.getByTestId('collection-display-name-input'), 'My Collection')
      await user.tab()

      await waitFor(() => {
        expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument()
      })
    })
  })

  describe('Form Submission - Create Mode', () => {
    it('should call onSubmit with form data when valid', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} onSubmit={onSubmit} />)

      await user.type(screen.getByTestId('collection-name-input'), 'my_collection')
      await user.type(screen.getByTestId('collection-display-name-input'), 'My Collection')
      await user.type(screen.getByTestId('collection-description-input'), 'A description')

      await user.click(screen.getByTestId('collection-form-submit'))

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledTimes(1)
        expect(onSubmit).toHaveBeenCalledWith({
          name: 'my_collection',
          displayName: 'My Collection',
          description: 'A description',
          active: true,
          trackHistory: false,
          captureGeo: false,
        })
      })
    })

    it('should not call onSubmit when form is invalid', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} onSubmit={onSubmit} />)

      // Submit without filling required fields
      await user.click(screen.getByTestId('collection-form-submit'))

      await waitFor(() => {
        expect(onSubmit).not.toHaveBeenCalled()
      })
    })

    it('should submit with empty description as undefined', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} onSubmit={onSubmit} />)

      await user.type(screen.getByTestId('collection-name-input'), 'my_collection')
      await user.type(screen.getByTestId('collection-display-name-input'), 'My Collection')
      // Leave description empty

      await user.click(screen.getByTestId('collection-form-submit'))

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledWith(
          expect.objectContaining({
            description: undefined,
          })
        )
      })
    })

    it('should submit with active unchecked', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} onSubmit={onSubmit} />)

      await user.type(screen.getByTestId('collection-name-input'), 'my_collection')
      await user.type(screen.getByTestId('collection-display-name-input'), 'My Collection')
      await user.click(screen.getByTestId('collection-active-checkbox')) // Uncheck

      await user.click(screen.getByTestId('collection-form-submit'))

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledWith(
          expect.objectContaining({
            active: false,
          })
        )
      })
    })
  })

  describe('Form Submission - Edit Mode', () => {
    it('should call onSubmit with updated data', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(
        <CollectionForm {...defaultProps} collection={mockCollection} onSubmit={onSubmit} />
      )

      await user.clear(screen.getByTestId('collection-display-name-input'))
      await user.type(screen.getByTestId('collection-display-name-input'), 'Updated Collection')

      await user.click(screen.getByTestId('collection-form-submit'))

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledWith({
          name: 'test_collection',
          displayName: 'Updated Collection',
          description: 'A test collection for testing',
          active: true,
          trackHistory: false,
          captureGeo: false,
        })
      })
    })
  })

  describe('Loading State', () => {
    it('should disable all inputs when submitting', () => {
      renderWithProviders(<CollectionForm {...defaultProps} isSubmitting={true} />)

      expect(screen.getByTestId('collection-name-input')).toBeDisabled()
      expect(screen.getByTestId('collection-display-name-input')).toBeDisabled()
      expect(screen.getByTestId('collection-description-input')).toBeDisabled()
      expect(screen.getByTestId('collection-active-checkbox')).toBeDisabled()
      expect(screen.getByTestId('collection-track-history-checkbox')).toBeDisabled()
    })

    it('should disable buttons when submitting', () => {
      renderWithProviders(<CollectionForm {...defaultProps} isSubmitting={true} />)

      expect(screen.getByTestId('collection-form-submit')).toBeDisabled()
      expect(screen.getByTestId('collection-form-cancel')).toBeDisabled()
    })

    it('should show loading spinner in submit button when submitting', () => {
      renderWithProviders(<CollectionForm {...defaultProps} isSubmitting={true} />)

      const submitButton = screen.getByTestId('collection-form-submit')
      expect(submitButton.querySelector('[data-testid="loading-spinner"]')).toBeInTheDocument()
    })
  })

  describe('Track History Field', () => {
    it('should render the track history checkbox', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-track-history-checkbox')).toBeInTheDocument()
    })

    it('should default to unchecked in create mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-track-history-checkbox')).not.toBeChecked()
    })

    it('should be unchecked in edit mode when the collection has no trackHistory', () => {
      renderWithProviders(<CollectionForm {...defaultProps} collection={mockCollection} />)

      expect(screen.getByTestId('collection-track-history-checkbox')).not.toBeChecked()
    })

    it('should be checked in edit mode when collection.trackHistory is true', () => {
      renderWithProviders(
        <CollectionForm {...defaultProps} collection={{ ...mockCollection, trackHistory: true }} />
      )

      expect(screen.getByTestId('collection-track-history-checkbox')).toBeChecked()
    })

    it('should include trackHistory in the submit payload when checked', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} onSubmit={onSubmit} />)

      await user.type(screen.getByTestId('collection-name-input'), 'my_collection')
      await user.type(screen.getByTestId('collection-display-name-input'), 'My Collection')
      await user.click(screen.getByTestId('collection-track-history-checkbox'))

      await user.click(screen.getByTestId('collection-form-submit'))

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledWith(
          expect.objectContaining({
            trackHistory: true,
          })
        )
      })
    })
  })

  describe('Capture Geo Field', () => {
    it('should render the capture geo checkbox, unchecked by default', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-capture-geo-checkbox')).toBeInTheDocument()
      expect(screen.getByTestId('collection-capture-geo-checkbox')).not.toBeChecked()
    })

    it('should be checked in edit mode when collection.captureGeo is true', () => {
      renderWithProviders(
        <CollectionForm {...defaultProps} collection={{ ...mockCollection, captureGeo: true }} />
      )

      expect(screen.getByTestId('collection-capture-geo-checkbox')).toBeChecked()
    })

    it('should include captureGeo in the submit payload when checked', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} onSubmit={onSubmit} />)

      await user.type(screen.getByTestId('collection-name-input'), 'my_collection')
      await user.type(screen.getByTestId('collection-display-name-input'), 'My Collection')
      await user.click(screen.getByTestId('collection-capture-geo-checkbox'))

      await user.click(screen.getByTestId('collection-form-submit'))

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledWith(
          expect.objectContaining({
            captureGeo: true,
          })
        )
      })
    })
  })

  describe('Cancel Action', () => {
    it('should call onCancel when cancel button is clicked', async () => {
      const onCancel = vi.fn()
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} onCancel={onCancel} />)

      await user.click(screen.getByTestId('collection-form-cancel'))

      expect(onCancel).toHaveBeenCalledTimes(1)
    })
  })

  describe('Accessibility', () => {
    it('should have proper labels for all inputs', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByLabelText(/Collection Name/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Display Name/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Description/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/Active/i)).toBeInTheDocument()
    })

    it('should have aria-required on required fields', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-name-input')).toHaveAttribute('aria-required', 'true')
      expect(screen.getByTestId('collection-display-name-input')).toHaveAttribute(
        'aria-required',
        'true'
      )
    })

    it('should have aria-describedby pointing to hint in create mode', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-name-input')).toHaveAttribute(
        'aria-describedby',
        'name-hint'
      )
      expect(screen.getByTestId('collection-active-checkbox')).toHaveAttribute(
        'aria-describedby',
        'active-hint'
      )
    })

    it('should have aria-describedby pointing to error when error exists', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.click(screen.getByTestId('collection-name-input'))
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('collection-name-input')).toHaveAttribute(
          'aria-describedby',
          'name-error'
        )
      })
    })

    it('should have role="alert" on error messages', async () => {
      const user = userEvent.setup()
      renderWithProviders(<CollectionForm {...defaultProps} />)

      await user.click(screen.getByTestId('collection-name-input'))
      await user.tab()

      await waitFor(() => {
        expect(screen.getByTestId('name-error')).toHaveAttribute('role', 'alert')
      })
    })

    it('should have noValidate on form to use custom validation', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('collection-form')).toHaveAttribute('noValidate')
    })

    it('should have hint IDs matching aria-describedby', () => {
      renderWithProviders(<CollectionForm {...defaultProps} />)

      expect(screen.getByTestId('name-hint')).toHaveAttribute('id', 'name-hint')
      expect(screen.getByTestId('active-hint')).toHaveAttribute('id', 'active-hint')
    })
  })

  describe('Form Reset on Collection Change', () => {
    it('should reset form when collection prop changes', async () => {
      const { rerender } = renderWithProviders(
        <CollectionForm {...defaultProps} collection={mockCollection} />
      )

      expect(screen.getByTestId('collection-display-name-input')).toHaveValue('Test Collection')

      const updatedCollection: Collection = {
        ...mockCollection,
        displayName: 'Updated Collection Name',
      }

      rerender(
        <TestWrapper>
          <CollectionForm {...defaultProps} collection={updatedCollection} />
        </TestWrapper>
      )

      expect(screen.getByTestId('collection-display-name-input')).toHaveValue(
        'Updated Collection Name'
      )
    })
  })
})

describe('CollectionForm Integration', () => {
  it('should work with async submission', async () => {
    const onSubmit = vi
      .fn()
      .mockImplementation(() => new Promise((resolve) => setTimeout(resolve, 100)))
    const user = userEvent.setup()

    const TestComponent = () => {
      const [isSubmitting, setIsSubmitting] = React.useState(false)

      const handleSubmit = async (data: CollectionFormData) => {
        setIsSubmitting(true)
        try {
          await onSubmit(data)
        } finally {
          setIsSubmitting(false)
        }
      }

      return (
        <CollectionForm onSubmit={handleSubmit} onCancel={() => {}} isSubmitting={isSubmitting} />
      )
    }

    renderWithProviders(<TestComponent />)

    await user.type(screen.getByTestId('collection-name-input'), 'my_collection')
    await user.type(screen.getByTestId('collection-display-name-input'), 'My Collection')
    await user.click(screen.getByTestId('collection-form-submit'))

    // Should show loading state
    await waitFor(() => {
      expect(screen.getByTestId('collection-form-submit')).toBeDisabled()
    })

    // Should complete submission
    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled()
    })

    // Wait for the 100ms mock to resolve and the wrapper's `finally` to flip
    // isSubmitting back, otherwise that setState lands after jsdom teardown and
    // vitest reports an unhandled "window is not defined" rejection.
    await waitFor(() => {
      expect(screen.getByTestId('collection-form-submit')).toBeEnabled()
    })
  })
})
