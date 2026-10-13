/**
 * Field controls for {@link CrudFormDialog}. Each reads the react-hook-form
 * instance from context, renders an uppercase-11px label (DESIGN.md field-label
 * rule), wires `aria-invalid` / `aria-describedby` and shows the field's
 * validation message as a `role="alert"` line under the control.
 */

import React from 'react'
import { get, useFormContext, useWatch, type FieldPath, type FieldValues } from 'react-hook-form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { CRUD_FIELD_LABEL_CLASS, useCrudFormState } from './crudFormState'

interface CrudFieldBaseProps<TValues extends FieldValues> {
  name: FieldPath<TValues>
  /** DOM id of the control; the label's `htmlFor` and the error's id derive from it. */
  id: string
  label: string
  testId?: string
  /** Disable this control (it is always disabled while the dialog submits). */
  disabled?: boolean
}

function describedBy(id: string, hint: boolean, error: boolean): string | undefined {
  const ids = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean)
  return ids.length > 0 ? ids.join(' ') : undefined
}

function FieldHint({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <span id={`${id}-hint`} className="text-xs text-muted-foreground">
      {children}
    </span>
  )
}

function FieldError({
  id,
  testId,
  children,
}: {
  id: string
  testId?: string
  children: React.ReactNode
}) {
  return (
    <span id={`${id}-error`} className="text-xs text-destructive" role="alert" data-testid={testId}>
      {children}
    </span>
  )
}

function FieldLabel({
  htmlFor,
  label,
  required,
}: {
  htmlFor: string
  label: string
  required?: boolean
}) {
  return (
    <label htmlFor={htmlFor} className={CRUD_FIELD_LABEL_CLASS}>
      {label}
      {required && (
        <span className="ml-0.5 text-destructive" aria-hidden="true">
          *
        </span>
      )}
    </label>
  )
}

export interface CrudTextFieldProps<
  TValues extends FieldValues,
> extends CrudFieldBaseProps<TValues> {
  required?: boolean
  placeholder?: string
  /** Test id for the validation message. */
  errorTestId?: string
  /** Help text under the control, wired to `aria-describedby` as `<id>-hint`. */
  hint?: React.ReactNode
  /** Render a `<textarea>` instead of an `<input>`. */
  multiline?: boolean
  rows?: number
  type?: React.HTMLInputTypeAttribute
  /** Content on the control's row, after it (e.g. an "Insert field" button). */
  trailing?: React.ReactNode
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
  hint,
  multiline,
  rows = 3,
  type = 'text',
  trailing,
}: CrudTextFieldProps<TValues>): React.ReactElement {
  const {
    register,
    formState: { errors },
  } = useFormContext<TValues>()
  const { isSubmitting } = useCrudFormState()
  const error = get(errors, name)?.message as string | undefined
  const controlProps = {
    id,
    placeholder,
    disabled: isSubmitting || disabled,
    'aria-required': required || undefined,
    'aria-invalid': !!error,
    'aria-describedby': describedBy(id, hint != null, !!error),
    'data-testid': testId,
    ...register(name),
  }

  const control = multiline ? (
    <Textarea rows={rows} className="resize-y" {...controlProps} />
  ) : (
    <Input type={type} {...controlProps} />
  )

  return (
    <div className="flex flex-col gap-1.5">
      <FieldLabel htmlFor={id} label={label} required={required} />
      {trailing != null ? (
        <div className={multiline ? 'flex items-start gap-2' : 'flex items-center gap-2'}>
          {control}
          <div className="shrink-0">{trailing}</div>
        </div>
      ) : (
        control
      )}
      {hint != null && <FieldHint id={id}>{hint}</FieldHint>}
      {error && (
        <FieldError id={id} testId={errorTestId}>
          {error}
        </FieldError>
      )}
    </div>
  )
}

export interface CrudSelectOption {
  value: string
  label: string
}

export interface CrudSelectFieldProps<
  TValues extends FieldValues,
> extends CrudFieldBaseProps<TValues> {
  options: readonly CrudSelectOption[]
  /** Leading empty-value option (e.g. "Select a collection"). */
  placeholder?: string
  required?: boolean
  /** Test id for the validation message. */
  errorTestId?: string
  /** Help text under the control, wired to `aria-describedby` as `<id>-hint`. */
  hint?: React.ReactNode
  /**
   * Called with the new value after the user picks an option (the form value is
   * already updated) — e.g. to reset fields that depend on this one.
   */
  onValueChange?: (value: string) => void
}

const SELECT_CLASS =
  'border-input h-9 w-full min-w-0 rounded-md border bg-transparent px-3 py-1 text-base shadow-xs outline-none transition-[color,box-shadow] disabled:cursor-not-allowed disabled:opacity-50 md:text-sm dark:bg-input/30 focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40'

/** A native `<select>` bound to the form; the value is always a string. */
export function CrudSelectField<TValues extends FieldValues = FieldValues>({
  name,
  id,
  label,
  testId,
  disabled,
  options,
  placeholder,
  required,
  errorTestId,
  hint,
  onValueChange,
}: CrudSelectFieldProps<TValues>): React.ReactElement {
  const {
    register,
    control,
    formState: { errors },
  } = useFormContext<TValues>()
  const { isSubmitting } = useCrudFormState()
  const error = get(errors, name)?.message as string | undefined
  // Controlled from the form value: an uncontrolled <select> keeps showing the placeholder when
  // its options arrive after the dialog opened (e.g. a collection list still loading), even
  // though the form holds — and submits — the stored value.
  const value = useWatch({ control, name })

  return (
    <div className="flex flex-col gap-1.5">
      <FieldLabel htmlFor={id} label={label} required={required} />
      <select
        id={id}
        className={SELECT_CLASS}
        disabled={isSubmitting || disabled}
        aria-required={required || undefined}
        aria-invalid={!!error}
        aria-describedby={describedBy(id, hint != null, !!error)}
        data-testid={testId}
        {...register(name, {
          onChange: onValueChange
            ? (e: React.ChangeEvent<HTMLSelectElement>) => onValueChange(e.target.value)
            : undefined,
        })}
        value={value == null ? '' : String(value)}
      >
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {hint != null && <FieldHint id={id}>{hint}</FieldHint>}
      {error && (
        <FieldError id={id} testId={errorTestId}>
          {error}
        </FieldError>
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
