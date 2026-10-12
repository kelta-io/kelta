/**
 * CrudSelectField + the `hint` prop of CrudTextField: rendering inside a
 * CrudFormDialog, aria wiring (label, required, describedby for hint + error),
 * zod validation and the submitted value.
 */

import { describe, it, expect, vi, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { z } from 'zod'
import { I18nProvider } from '../../../context/I18nContext'
import { CrudFormDialog, CrudSelectField, CrudTextField } from '..'

const schema = z.object({
  kind: z.string().refine((v) => v !== '', 'Kind is required'),
  note: z.string().max(3, 'Note is too long'),
})
type Values = z.infer<typeof schema>

function renderForm({
  onSubmit = vi.fn<(values: Values) => void>(),
  kind = '',
  kindDisabled = false,
}: {
  onSubmit?: Mock<(values: Values) => void>
  kind?: string
  kindDisabled?: boolean
} = {}) {
  render(
    <I18nProvider>
      <CrudFormDialog<Values>
        open
        onClose={() => {}}
        title="Form"
        schema={schema}
        defaultValues={{ kind, note: '' }}
        onSubmit={onSubmit}
        testIdPrefix="f"
      >
        <CrudSelectField<Values>
          name="kind"
          id="f-kind"
          label="Kind"
          required
          placeholder="Pick one"
          options={[
            { value: 'a', label: 'Alpha' },
            { value: 'b', label: 'Beta' },
          ]}
          disabled={kindDisabled}
          hint="What sort of thing"
          testId="f-kind-input"
          errorTestId="f-kind-error"
        />
        <CrudTextField<Values>
          name="note"
          id="f-note"
          label="Note"
          hint="Short note"
          testId="f-note-input"
        />
      </CrudFormDialog>
    </I18nProvider>
  )
  return { onSubmit }
}

describe('CrudSelectField', () => {
  it('renders a labelled select with the placeholder first', () => {
    renderForm()
    const select = screen.getByLabelText(/Kind/) as HTMLSelectElement
    expect(select).toBe(screen.getByTestId('f-kind-input'))
    expect(select).toHaveAttribute('aria-required', 'true')
    expect([...select.options].map((o) => [o.value, o.textContent])).toEqual([
      ['', 'Pick one'],
      ['a', 'Alpha'],
      ['b', 'Beta'],
    ])
    expect(select).toHaveAttribute('aria-describedby', 'f-kind-hint')
    expect(screen.getByText('What sort of thing')).toHaveAttribute('id', 'f-kind-hint')
  })

  it('shows the zod error and wires it next to the hint', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderForm()
    await user.click(screen.getByTestId('f-form-submit'))

    const error = await screen.findByTestId('f-kind-error')
    expect(error).toHaveTextContent('Kind is required')
    expect(error).toHaveAttribute('role', 'alert')
    const select = screen.getByTestId('f-kind-input')
    expect(select).toHaveAttribute('aria-invalid', 'true')
    expect(select).toHaveAttribute('aria-describedby', 'f-kind-hint f-kind-error')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits the selected value', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderForm()
    await user.selectOptions(screen.getByTestId('f-kind-input'), 'b')
    await user.click(screen.getByTestId('f-form-submit'))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toEqual({ kind: 'b', note: '' })
  })

  it('keeps a disabled select in the submitted values', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderForm({ kind: 'a', kindDisabled: true })
    expect(screen.getByTestId('f-kind-input')).toBeDisabled()
    await user.click(screen.getByTestId('f-form-submit'))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toEqual({ kind: 'a', note: '' })
  })

  it('shows the stored value when its options arrive after the dialog opened', () => {
    const form = (options: { value: string; label: string }[]) => (
      <I18nProvider>
        <CrudFormDialog<Values>
          open
          onClose={() => {}}
          title="Form"
          schema={schema}
          defaultValues={{ kind: 'b', note: '' }}
          onSubmit={vi.fn()}
          testIdPrefix="f"
        >
          <CrudSelectField<Values>
            name="kind"
            id="f-kind"
            label="Kind"
            placeholder="Pick one"
            options={options}
            testId="f-kind-input"
          />
        </CrudFormDialog>
      </I18nProvider>
    )
    const { rerender } = render(form([]))
    rerender(
      form([
        { value: 'a', label: 'Alpha' },
        { value: 'b', label: 'Beta' },
      ])
    )

    expect(screen.getByTestId('f-kind-input')).toHaveValue('b')
  })
})

describe('CrudTextField hint', () => {
  it('describes the control by its hint, then by hint and error', async () => {
    const user = userEvent.setup()
    renderForm({ kind: 'a' })
    const note = screen.getByTestId('f-note-input')
    expect(note).toHaveAttribute('aria-describedby', 'f-note-hint')
    expect(screen.getByText('Short note')).toHaveAttribute('id', 'f-note-hint')

    await user.type(note, 'toolong')
    await user.click(screen.getByTestId('f-form-submit'))

    expect(await screen.findByText('Note is too long')).toHaveAttribute('id', 'f-note-error')
    expect(note).toHaveAttribute('aria-describedby', 'f-note-hint f-note-error')
  })
})
