/**
 * Numeric `<input type="number">` control for {@link CrudFormDialog}. The form
 * value is a `number` (not the raw input string): every keystroke goes through
 * `parse`, so a page keeps its own coercion rules (e.g. "empty means 0", or
 * "ignore input that is not a number"). Same label / hint / error contract as
 * `CrudTextField`.
 */

import React from 'react'
import { useController, useFormContext, type FieldPath, type FieldValues } from 'react-hook-form'
import { Input } from '@/components/ui/input'
import { useCrudFormState } from './crudFormState'

/** Same uppercase-11px field label as the CrudFormFields controls (DESIGN.md). */
const FIELD_LABEL_CLASS =
  'text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground'

/** Default coercion: empty input is 0; anything else that is not a finite number is ignored. */
function parseNumberInput(raw: string): number | null {
  if (raw.trim() === '') return 0
  const value = Number(raw)
  return Number.isFinite(value) ? value : null
}

export interface CrudNumberFieldProps<TValues extends FieldValues> {
  name: FieldPath<TValues>
  /** DOM id of the control; the label's `htmlFor`, hint and error ids derive from it. */
  id: string
  label: string
  testId?: string
  errorTestId?: string
  /** Helper text under the control; wired to its `aria-describedby`. */
  hint?: string
  required?: boolean
  /** Disable this control (it is always disabled while the dialog submits). */
  disabled?: boolean
  min?: number
  max?: number
  step?: number
  /**
   * Maps the raw input string to the stored number. Return `null` to ignore
   * the change (the field keeps its previous value). Defaults to: empty input
   * is 0, any other finite number is kept as typed, anything else is ignored.
   */
  parse?: (raw: string) => number | null
}

export function CrudNumberField<TValues extends FieldValues = FieldValues>({
  name,
  id,
  label,
  testId,
  errorTestId,
  hint,
  required,
  disabled,
  min,
  max,
  step,
  parse = parseNumberInput,
}: CrudNumberFieldProps<TValues>): React.ReactElement {
  const { control } = useFormContext<TValues>()
  const {
    field: { ref: inputRef, name: fieldName, value: rawValue, onChange, onBlur },
    fieldState,
  } = useController<TValues>({ name, control })
  const { isSubmitting } = useCrudFormState()
  const error = fieldState.error?.message
  const errorId = `${id}-error`
  const hintId = `${id}-hint`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')
  const value = rawValue as number | null | undefined

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className={FIELD_LABEL_CLASS}>
        {label}
        {required && (
          <span className="ml-0.5 text-destructive" aria-hidden="true">
            *
          </span>
        )}
      </label>
      <Input
        id={id}
        type="number"
        name={fieldName}
        ref={inputRef}
        value={value ?? ''}
        onChange={(e) => {
          const next = parse(e.target.value)
          if (next !== null) onChange(next)
        }}
        onBlur={onBlur}
        min={min}
        max={max}
        step={step}
        disabled={isSubmitting || disabled}
        aria-required={required || undefined}
        aria-invalid={!!error}
        aria-describedby={describedBy || undefined}
        data-testid={testId}
      />
      {hint && (
        <span id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </span>
      )}
      {error && (
        <span
          id={errorId}
          className="text-xs text-destructive"
          role="alert"
          data-testid={errorTestId}
        >
          {error}
        </span>
      )}
    </div>
  )
}
