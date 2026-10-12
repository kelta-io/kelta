/**
 * CrudFormDialog
 *
 * The create/edit modal shared by admin CRUD pages. Built on the
 * `components/ui/dialog` primitive (Radix: focus trap, Escape, outside-click,
 * `role="dialog"` labelled by the title) with react-hook-form + a zod schema
 * for validation. Fields are rendered as children — usually `CrudTextField` /
 * `CrudCheckboxField`, which read the form from context.
 *
 * Validation runs on first blur and then on every change (`mode: 'onTouched'`)
 * and always on submit, matching the hand-rolled forms it replaces.
 *
 * Test ids derive from `testIdPrefix`: `<p>-form-modal`, `<p>-form-close`,
 * `<p>-form-cancel`, `<p>-form-submit` (`submitTestId` overrides the last).
 */

import React, { useRef } from 'react'
import { FormProvider, useForm, type DefaultValues, type FieldValues } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { z } from 'zod'
import { X } from 'lucide-react'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { CrudFormStateContext } from './crudFormState'

export interface CrudFormDialogProps<TValues extends FieldValues> {
  open: boolean
  /** Called on Cancel, the close button, Escape and outside click (not while submitting). */
  onClose: () => void
  title: string
  /** Optional sub-heading; wired to the dialog's `aria-describedby`. */
  description?: string
  schema: z.ZodType<TValues, TValues>
  /** Initial values; read once each time the dialog opens. */
  defaultValues: DefaultValues<TValues>
  /** Receives schema-validated values. */
  onSubmit: (values: TValues) => void
  isSubmitting?: boolean
  /** Prefix for the dialog's test ids, e.g. `profile` → `profile-form-modal`. */
  testIdPrefix: string
  /** Overrides the submit button's `<p>-form-submit` test id (for pages with an older id). */
  submitTestId?: string
  submitLabel?: string
  submittingLabel?: string
  cancelLabel?: string
  /** Extra classes for the dialog panel (width, max-height). */
  className?: string
  children: React.ReactNode
}

export function CrudFormDialog<TValues extends FieldValues>(
  props: CrudFormDialogProps<TValues>
): React.ReactElement {
  const { open, onClose, isSubmitting = false } = props
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !isSubmitting) onClose()
      }}
    >
      {/* Mount the form only while open so every open starts from defaultValues. */}
      {open && <CrudFormDialogBody {...props} />}
    </Dialog>
  )
}

function CrudFormDialogBody<TValues extends FieldValues>({
  onClose,
  title,
  description,
  schema,
  defaultValues,
  onSubmit,
  isSubmitting = false,
  testIdPrefix,
  submitTestId,
  submitLabel,
  submittingLabel,
  cancelLabel,
  className,
  children,
}: CrudFormDialogProps<TValues>): React.ReactElement {
  const { t } = useI18n()
  const contentRef = useRef<HTMLDivElement>(null)
  const form = useForm<TValues, unknown, TValues>({
    resolver: zodResolver(schema),
    defaultValues,
    mode: 'onTouched',
  })

  return (
    <DialogContent
      ref={contentRef}
      showCloseButton={false}
      className={cn('max-h-[90vh] gap-0 overflow-y-auto p-0 sm:max-w-[480px]', className)}
      data-testid={`${testIdPrefix}-form-modal`}
      {...(description ? {} : { 'aria-describedby': undefined })}
      onOpenAutoFocus={(e) => {
        // Focus the first editable field rather than the header close button.
        const field = contentRef.current?.querySelector<HTMLElement>(
          'input:not([disabled]):not([type="hidden"]), textarea:not([disabled]), select:not([disabled])'
        )
        if (field) {
          e.preventDefault()
          field.focus()
        }
      }}
    >
      <div className="flex items-center justify-between border-b border-border px-6 py-4">
        <div className="flex flex-col gap-1">
          <DialogTitle className="text-lg font-semibold text-foreground">{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </div>
        <DialogClose asChild>
          <button
            type="button"
            className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50"
            aria-label={t('common.close')}
            disabled={isSubmitting}
            data-testid={`${testIdPrefix}-form-close`}
          >
            <X size={16} />
          </button>
        </DialogClose>
      </div>

      <FormProvider {...form}>
        <CrudFormStateContext.Provider value={{ isSubmitting }}>
          <form noValidate onSubmit={form.handleSubmit(onSubmit)}>
            <div className="flex flex-col gap-4 px-6 py-4">{children}</div>
            <div className="flex justify-end gap-3 border-t border-border px-6 py-4">
              <Button
                type="button"
                variant="outline"
                onClick={onClose}
                disabled={isSubmitting}
                data-testid={`${testIdPrefix}-form-cancel`}
              >
                {cancelLabel ?? t('common.cancel')}
              </Button>
              <Button
                type="submit"
                disabled={isSubmitting}
                data-testid={submitTestId ?? `${testIdPrefix}-form-submit`}
              >
                {isSubmitting
                  ? (submittingLabel ?? t('common.saving'))
                  : (submitLabel ?? t('common.save'))}
              </Button>
            </div>
          </form>
        </CrudFormStateContext.Provider>
      </FormProvider>
    </DialogContent>
  )
}
