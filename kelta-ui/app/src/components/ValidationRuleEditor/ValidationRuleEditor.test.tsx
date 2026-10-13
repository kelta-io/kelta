import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ValidationRuleEditor } from './ValidationRuleEditor'
import { I18nProvider } from '../../context/I18nContext'
import { Toaster } from 'sonner'

vi.mock('../FieldExpressionPicker', () => ({
  FieldExpressionPicker: vi.fn(
    ({
      open,
      mode,
      rootCollectionId,
      allowedTypes,
      onInsert,
      testId,
    }: {
      open: boolean
      mode?: string
      rootCollectionId: string | null
      allowedTypes?: string[]
      onInsert: (token: string) => void
      testId?: string
    }) => {
      if (!open) return null
      return (
        <div
          data-testid={testId ?? 'field-expression-picker'}
          data-mode={mode}
          data-collection-id={rootCollectionId}
          data-allowed-types={allowedTypes?.join(',')}
        >
          <button onClick={() => onInsert('amount')}>Insert amount</button>
        </div>
      )
    }
  ),
}))

function TestWrapper({ children }: { children: React.ReactNode }) {
  return (
    <I18nProvider>
      <>
        {children}
        <Toaster />
      </>
    </I18nProvider>
  )
}

const defaultProps = {
  collectionId: 'col-orders',
  onSave: vi.fn().mockResolvedValue(undefined),
  onCancel: vi.fn(),
}

describe('ValidationRuleEditor – FieldExpressionPicker adoption', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the Insert field button next to the formula label', () => {
    render(
      <TestWrapper>
        <ValidationRuleEditor {...defaultProps} />
      </TestWrapper>
    )
    expect(screen.getByTestId('rule-formula-insert-field')).toBeInTheDocument()
  })

  it('opens the picker with mode=expression and the correct rootCollectionId', async () => {
    render(
      <TestWrapper>
        <ValidationRuleEditor {...defaultProps} />
      </TestWrapper>
    )

    await userEvent.click(screen.getByTestId('rule-formula-insert-field'))

    const picker = screen.getByTestId('validation-rule-formula-picker')
    expect(picker).toBeInTheDocument()
    expect(picker).toHaveAttribute('data-mode', 'expression')
    expect(picker).toHaveAttribute('data-collection-id', 'col-orders')
  })

  it('passes allowedTypes=[boolean] to the picker', async () => {
    render(
      <TestWrapper>
        <ValidationRuleEditor {...defaultProps} />
      </TestWrapper>
    )

    await userEvent.click(screen.getByTestId('rule-formula-insert-field'))

    expect(screen.getByTestId('validation-rule-formula-picker')).toHaveAttribute(
      'data-allowed-types',
      'boolean'
    )
  })

  it('inserts the token into the formula textarea when picker fires onInsert', async () => {
    render(
      <TestWrapper>
        <ValidationRuleEditor {...defaultProps} />
      </TestWrapper>
    )

    await userEvent.click(screen.getByTestId('rule-formula-insert-field'))
    await userEvent.click(screen.getByText('Insert amount'))

    const formulaInput = screen.getByTestId('rule-formula-input') as HTMLTextAreaElement
    expect(formulaInput.value).toBe('amount')
  })

  it('closes the picker after insertion', async () => {
    render(
      <TestWrapper>
        <ValidationRuleEditor {...defaultProps} />
      </TestWrapper>
    )

    await userEvent.click(screen.getByTestId('rule-formula-insert-field'))
    expect(screen.getByTestId('validation-rule-formula-picker')).toBeInTheDocument()

    await userEvent.click(screen.getByText('Insert amount'))
    expect(screen.queryByTestId('validation-rule-formula-picker')).not.toBeInTheDocument()
  })
})
