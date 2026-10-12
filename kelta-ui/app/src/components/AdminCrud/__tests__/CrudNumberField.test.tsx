/**
 * CrudNumberField inside a CrudFormDialog: the form value is a number, the
 * page-supplied `parse` rule decides how raw input is coerced (or ignored),
 * zod errors render under the control and the hint is wired into
 * aria-describedby.
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { z } from 'zod'
import { I18nProvider } from '../../../context/I18nContext'
import { CrudFormDialog, CrudNumberField } from '..'

const schema = z.object({
  count: z.number().min(1, 'Count must be at least 1'),
})
type Values = z.infer<typeof schema>

function renderForm(
  onSubmit: (values: Values) => void,
  options: { parse?: (raw: string) => number | null; hint?: string } = {}
) {
  return render(
    <I18nProvider>
      <CrudFormDialog<Values>
        open
        onClose={() => {}}
        title="Thing"
        schema={schema}
        defaultValues={{ count: 5 }}
        onSubmit={onSubmit}
        testIdPrefix="thing"
      >
        <CrudNumberField<Values>
          name="count"
          id="thing-count"
          label="Count"
          required
          min={0}
          parse={options.parse}
          hint={options.hint}
          testId="thing-count-input"
          errorTestId="thing-count-error"
        />
      </CrudFormDialog>
    </I18nProvider>
  )
}

describe('CrudNumberField', () => {
  it('renders the default value and submits a number', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderForm(onSubmit)

    const input = screen.getByLabelText(/Count/)
    expect(input).toBe(screen.getByTestId('thing-count-input'))
    expect(input).toHaveValue(5)

    await user.clear(input)
    // Default coercion: empty input is 0.
    expect(input).toHaveValue(0)
    await user.type(input, '42')
    await user.click(screen.getByTestId('thing-form-submit'))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toEqual({ count: 42 })
  })

  it('applies the page-supplied parse rule', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    // e.g. a rate limit: anything that is not a positive integer becomes 1.
    renderForm(onSubmit, { parse: (raw) => parseInt(raw, 10) || 1 })

    const input = screen.getByTestId('thing-count-input')
    await user.clear(input)
    expect(input).toHaveValue(1)
    await user.click(screen.getByTestId('thing-form-submit'))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toEqual({ count: 1 })
  })

  it('shows the zod error and wires hint + error into aria-describedby', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderForm(onSubmit, { hint: 'How many' })

    const input = screen.getByTestId('thing-count-input')
    expect(input).toHaveAttribute('aria-describedby', 'thing-count-hint')

    await user.clear(input)
    await user.click(screen.getByTestId('thing-form-submit'))

    expect(await screen.findByTestId('thing-count-error')).toHaveTextContent(
      'Count must be at least 1'
    )
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'thing-count-hint thing-count-error')
    expect(onSubmit).not.toHaveBeenCalled()
  })
})
