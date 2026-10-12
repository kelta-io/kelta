/**
 * Field controls for {@link CrudFormDialog}. Each reads the react-hook-form
 * instance from context, renders an uppercase-11px label (DESIGN.md field-label
 * rule), wires `aria-invalid` / `aria-describedby` and shows the field's
 * validation message as a `role="alert"` line under the control.
 */

import React from 'react'
import { get, useFormContext, type FieldPath, type FieldValues } from 'react-hook-form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useCrudFormState } from './crudFormState'

const FIELD_LABEL_CLASS =
  'text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground'

interface CrudFieldBaseProps<TValues extends FieldValues> {
  name: FieldPath<TValues>
  /** DOM id of the control; the label's `htmlFor` and the error's id derive from it. */
  id: string
  label: string
  testId?: string
  /** Disable this control (it is always disabled while the dialog submits). */
  disabled?: boolean
}

export interface CrudTextFieldProps<
  TValues extends FieldValues,
> extends CrudFieldBaseProps<TValues> {
  required?: boolean
  placeholder?: string
  /** Test id for the validation message. */
  errorTestId?: string
  /** Render a `<textarea>` instead of an `<input>`. */
  multiline?: boolean
  rows?: number
  type?: React.HTMLInputTypeAttribute
}

export function CrudTextField<TValues extends FieldValues = FieldValues>({
  name,
  id,
  label,
  testId,
  disabled,
  required,
  placeholder,
  errorTestId,
  multiline,
  rows = 3,
  type = 'text',
}: CrudTextFieldProps<TValues>): React.ReactElement {
  const {
    register,
    formState: { errors },
  } = useFormContext<TValues>()
  const { isSubmitting } = useCrudFormState()
  const error = get(errors, name)?.message as string | undefined
  const errorId = `${id}-error`
  const controlProps = {
    id,
    placeholder,
    disabled: isSubmitting || disabled,
    'aria-required': required || undefined,
    'aria-invalid': !!error,
    'aria-describedby': error ? errorId : undefined,
    'data-testid': testId,
    ...register(name),
  }

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
      {multiline ? (
        <Textarea rows={rows} className="resize-y" {...controlProps} />
      ) : (
        <Input type={type} {...controlProps} />
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

export type CrudCheckboxFieldProps<TValues extends FieldValues> = CrudFieldBaseProps<TValues>

export function CrudCheckboxField<TValues extends FieldValues = FieldValues>({
  name,
  id,
  label,
  testId,
  disabled,
}: CrudCheckboxFieldProps<TValues>): React.ReactElement {
  const { register } = useFormContext<TValues>()
  const { isSubmitting } = useCrudFormState()
  return (
    <div className="flex items-center gap-2">
      <input
        id={id}
        type="checkbox"
        className="h-4 w-4 accent-primary"
        disabled={isSubmitting || disabled}
        data-testid={testId}
        {...register(name)}
      />
      <label htmlFor={id} className="text-sm font-medium text-foreground">
        {label}
      </label>
    </div>
  )
}
