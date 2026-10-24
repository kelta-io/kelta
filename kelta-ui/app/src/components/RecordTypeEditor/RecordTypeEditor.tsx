import React, { useEffect, useCallback } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { LoadingSpinner } from '../LoadingSpinner'
import type { RecordType } from '../../types/collections'

const recordTypeSchema = z.object({
  name: z.string().min(1, 'Name is required').max(100, 'Name must be 100 characters or less'),
  description: z
    .string()
    .max(500, 'Description must be 500 characters or less')
    .optional()
    .or(z.literal('')),
  isDefault: z.boolean(),
})

type RecordTypeFormData = z.infer<typeof recordTypeSchema>

export interface RecordTypeEditorProps {
  collectionId: string
  recordType?: RecordType
  onSave: (data: RecordTypeFormData) => Promise<void>
  onCancel: () => void
  isSubmitting?: boolean
}

const inputClasses = cn(
  'px-3 py-2 text-base leading-6 text-foreground bg-background border border-input rounded-md',
  'transition-colors duration-150 motion-reduce:transition-none',
  'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
  'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed',
  'placeholder:text-muted-foreground'
)

const errorInputClasses = 'border-destructive focus:border-destructive focus:ring-destructive/25'

export function RecordTypeEditor({
  recordType,
  onSave,
  onCancel,
  isSubmitting = false,
}: RecordTypeEditorProps): React.ReactElement {
  const { t } = useI18n()
  const isEditMode = !!recordType

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<RecordTypeFormData>({
    resolver: zodResolver(recordTypeSchema),
    defaultValues: {
      name: recordType?.name ?? '',
      description: recordType?.description ?? '',
      isDefault: recordType?.isDefault ?? false,
    },
    mode: 'onBlur',
  })

  useEffect(() => {
    if (recordType) {
      reset({
        name: recordType.name,
        description: recordType.description ?? '',
        isDefault: recordType.isDefault,
      })
    }
  }, [recordType, reset])

  const handleFormSubmit = useCallback(
    async (data: RecordTypeFormData) => {
      await onSave({
        name: data.name,
        description: data.description || undefined,
        isDefault: data.isDefault,
      })
    },
    [onSave]
  )

  return (
    <form
      className="flex flex-col gap-6 max-w-[600px] w-full"
      onSubmit={handleSubmit(handleFormSubmit)}
      data-testid="record-type-editor"
      noValidate
    >
      <h3 className="m-0 mb-4 text-lg font-semibold text-foreground">
        {isEditMode ? t('recordTypes.editRecordType') : t('recordTypes.addRecordType')}
      </h3>

      <div className="flex flex-col gap-1">
        <label
          htmlFor="rt-name"
          className="flex items-center gap-1 text-sm font-medium text-foreground"
        >
          {t('common.name')}
          <span className="text-destructive font-semibold" aria-hidden="true">
            *
          </span>
        </label>
        <input
          id="rt-name"
          type="text"
          className={cn(inputClasses, errors.name && errorInputClasses)}
          placeholder="e.g., Standard"
          disabled={isEditMode || isSubmitting}
          aria-required="true"
          aria-invalid={!!errors.name}
          data-testid="rt-name-input"
          {...register('name')}
        />
        {errors.name && (
          <span className="flex items-center gap-1 text-sm text-destructive mt-1" role="alert">
            {errors.name.message}
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1">
        <label
          htmlFor="rt-description"
          className="flex items-center gap-1 text-sm font-medium text-foreground"
        >
          {t('collections.description')}
          <span className="text-xs font-normal text-muted-foreground ml-1">
            ({t('common.optional')})
          </span>
        </label>
        <textarea
          id="rt-description"
          className={cn(inputClasses, 'resize-y', errors.description && errorInputClasses)}
          placeholder="Describe this record type..."
          disabled={isSubmitting}
          rows={3}
          data-testid="rt-description-input"
          {...register('description')}
        />
        {errors.description && (
          <span className="flex items-center gap-1 text-sm text-destructive mt-1" role="alert">
            {errors.description.message}
          </span>
        )}
      </div>

      <div className="flex items-center gap-2">
        <input
          id="rt-is-default"
          type="checkbox"
          className="w-[1.125rem] h-[1.125rem] accent-primary cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
          disabled={isSubmitting}
          data-testid="rt-is-default-checkbox"
          {...register('isDefault')}
        />
        <label htmlFor="rt-is-default" className="text-sm text-foreground cursor-pointer">
          {t('recordTypes.isDefaultLabel')}
        </label>
      </div>
      <span className="text-xs text-muted-foreground -mt-4">{t('recordTypes.isDefaultHint')}</span>

      <div className="flex justify-end gap-3 mt-6 pt-6 border-t border-border max-md:flex-col-reverse max-md:gap-2">
        <button
          type="button"
          className={cn(
            'inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-6 rounded-md cursor-pointer',
            'text-foreground bg-secondary border border-input',
            'transition-colors duration-150 motion-reduce:transition-none',
            'hover:bg-accent disabled:opacity-50 disabled:cursor-not-allowed',
            'max-md:w-full'
          )}
          onClick={onCancel}
          disabled={isSubmitting}
          data-testid="rt-editor-cancel"
        >
          {t('common.cancel')}
        </button>
        <button
          type="submit"
          className={cn(
            'inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-6 rounded-md cursor-pointer',
            'text-primary-foreground bg-primary border border-transparent',
            'transition-colors duration-150 motion-reduce:transition-none',
            'hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed',
            'max-md:w-full'
          )}
          disabled={isSubmitting || (!isDirty && isEditMode)}
          data-testid="rt-editor-submit"
        >
          {isSubmitting ? (
            <>
              <LoadingSpinner size="small" />
              <span>{t('common.saving')}</span>
            </>
          ) : isEditMode ? (
            t('common.save')
          ) : (
            t('common.create')
          )}
        </button>
      </div>
    </form>
  )
}
