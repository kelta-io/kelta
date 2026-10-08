/**
 * CollectionForm Component
 *
 * Form for creating and editing collections with validation.
 * Uses React Hook Form with Zod validation schema.
 *
 * Requirements:
 * - 3.4: Display form for entering collection details
 * - 3.5: Create collection via API and display success message
 * - 3.6: Display validation errors inline with form fields
 * - 3.9: Pre-populate form with current values in edit mode
 */

import React, { useEffect, useCallback } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { LoadingSpinner } from '../LoadingSpinner'
import type { OwnerScope } from '../../types/collections'

/** The system audit field every record carries — always a valid owner field */
const CREATED_BY_OWNER_FIELD = 'createdBy'

const OWNER_SCOPES: OwnerScope[] = ['NONE', 'PORTAL', 'ALL']

/**
 * Collection data for the form
 */
export interface CollectionFormData {
  /** Collection name (alphanumeric, underscores, lowercase) */
  name: string
  /** Display name for the collection */
  displayName: string
  /** Optional description */
  description?: string
  /** Whether the collection is active */
  active: boolean
  /** Whether every record change is captured as a record_version snapshot */
  trackHistory: boolean
  /** Whether HTTP record writes stamp the request-origin geolocation */
  captureGeo: boolean
  /** ID of the field used as display field in lookup dropdowns */
  displayFieldId?: string
  /** Field holding each record's owning user id; undefined = not owned */
  ownerField?: string
  /** Which callers see and change only the records they own */
  ownerScope: OwnerScope
  /** Whether owner scoping also hides other people's records from reads */
  ownerScopeReads: boolean
}

/**
 * Available field for display field dropdown
 */
export interface AvailableField {
  id: string
  name: string
  displayName: string
  /** Field type (used to offer lookups to users as owner fields) */
  type?: string
  /** Target collection name of a relationship field */
  referenceTarget?: string
  /** LOOKUP or MASTER_DETAIL for relationship fields */
  relationshipType?: string
}

/** A field can own records when it is a lookup to users. */
function isUsersLookup(field: AvailableField): boolean {
  const isLookup =
    field.type?.toLowerCase() === 'lookup' || field.relationshipType?.toUpperCase() === 'LOOKUP'
  return isLookup && field.referenceTarget === 'users'
}

/**
 * Full collection interface for edit mode
 */
export interface Collection {
  id: string
  name: string
  displayName: string
  description?: string
  active: boolean
  trackHistory?: boolean
  captureGeo?: boolean
  displayFieldId?: string
  ownerField?: string | null
  ownerScope?: OwnerScope
  ownerScopeReads?: boolean
  currentVersion: number
  createdAt: string
  updatedAt: string
}

/**
 * Props for the CollectionForm component
 */
export interface CollectionFormProps {
  /** Existing collection data for edit mode */
  collection?: Collection
  /** Available fields for display field dropdown (edit mode only) */
  availableFields?: AvailableField[]
  /** Callback when form is submitted successfully */
  onSubmit: (data: CollectionFormData) => Promise<void>
  /** Callback when form is cancelled */
  onCancel: () => void
  /** Whether the form is in a loading/submitting state */
  isSubmitting?: boolean
  /** Optional test ID for testing */
  testId?: string
}

/**
 * Zod validation schema for collection form
 *
 * Name validation:
 * - Required
 * - 1-50 characters
 * - Lowercase alphanumeric and underscores only
 * - Must start with a letter
 */
// eslint-disable-next-line react-refresh/only-export-components
export const collectionFormSchema = z
  .object({
    name: z
      .string()
      .min(1, 'validation.nameRequired')
      .max(50, 'validation.nameTooLong')
      .regex(/^[a-z][a-z0-9_]*$/, 'validation.nameFormat'),
    displayName: z
      .string()
      .min(1, 'validation.displayNameRequired')
      .max(100, 'validation.displayNameTooLong'),
    description: z.string().max(500, 'validation.descriptionTooLong').optional().or(z.literal('')),
    active: z.boolean(),
    trackHistory: z.boolean(),
    captureGeo: z.boolean(),
    displayFieldId: z.string().optional().or(z.literal('')),
    ownerField: z.string().optional().or(z.literal('')),
    ownerScope: z.enum(['NONE', 'PORTAL', 'ALL']),
    ownerScopeReads: z.boolean(),
  })
  .refine((data) => data.ownerScope === 'NONE' || !!data.ownerField, {
    message: 'validation.ownerFieldRequired',
    path: ['ownerField'],
  })

/**
 * Type inferred from the Zod schema
 */
export type CollectionFormSchema = z.infer<typeof collectionFormSchema>

/**
 * CollectionForm Component
 *
 * Provides a form for creating and editing collections with:
 * - Name validation (alphanumeric, underscores, lowercase)
 * - Display name validation
 * - Optional description
 * - Active status toggle
 * - Inline validation errors
 * - Loading state during submission
 */
export function CollectionForm({
  collection,
  availableFields = [],
  onSubmit,
  onCancel,
  isSubmitting = false,
  testId = 'collection-form',
}: CollectionFormProps): React.ReactElement {
  const { t } = useI18n()
  const isEditMode = !!collection

  // Initialize form with React Hook Form and Zod resolver
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<CollectionFormSchema>({
    resolver: zodResolver(collectionFormSchema),
    defaultValues: {
      name: collection?.name ?? '',
      displayName: collection?.displayName ?? '',
      description: collection?.description ?? '',
      active: collection?.active ?? true,
      trackHistory: collection?.trackHistory ?? false,
      captureGeo: collection?.captureGeo ?? false,
      displayFieldId: collection?.displayFieldId ?? '',
      ownerField: collection?.ownerField ?? '',
      ownerScope: collection?.ownerScope ?? 'NONE',
      ownerScopeReads: collection?.ownerScopeReads ?? true,
    },
    mode: 'onBlur',
  })

  // Reset form when collection prop changes (for edit mode)
  useEffect(() => {
    if (collection) {
      reset({
        name: collection.name,
        displayName: collection.displayName,
        description: collection.description ?? '',
        active: collection.active,
        trackHistory: collection.trackHistory ?? false,
        captureGeo: collection.captureGeo ?? false,
        displayFieldId: collection.displayFieldId ?? '',
        ownerField: collection.ownerField ?? '',
        ownerScope: collection.ownerScope ?? 'NONE',
        ownerScopeReads: collection.ownerScopeReads ?? true,
      })
    }
  }, [collection, reset])

  const ownerFieldOptions = availableFields.filter(isUsersLookup)

  // Handle form submission
  const handleFormSubmit = useCallback(
    async (data: CollectionFormSchema) => {
      const formData: CollectionFormData = {
        name: data.name,
        displayName: data.displayName,
        description: data.description || undefined,
        active: data.active,
        trackHistory: data.trackHistory,
        captureGeo: data.captureGeo,
        displayFieldId: data.displayFieldId || undefined,
        ownerField: data.ownerField || undefined,
        ownerScope: data.ownerScope,
        ownerScopeReads: data.ownerScopeReads,
      }
      await onSubmit(formData)
    },
    [onSubmit]
  )

  // Get translated error message
  const getErrorMessage = useCallback(
    (errorKey: string | undefined): string | undefined => {
      if (!errorKey) return undefined
      // Check if it's a translation key
      if (errorKey.startsWith('validation.')) {
        return t(`collectionForm.${errorKey}`)
      }
      return errorKey
    },
    [t]
  )

  return (
    <form
      className="flex flex-col gap-6 max-w-[600px] w-full md:gap-4"
      onSubmit={handleSubmit(handleFormSubmit)}
      data-testid={testId}
      noValidate
    >
      {/* Name Field */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="collection-name"
          className="flex items-center gap-1 text-sm font-medium text-foreground"
        >
          {t('collections.collectionName')}
          <span className="text-destructive font-semibold" aria-hidden="true">
            *
          </span>
        </label>
        <input
          id="collection-name"
          type="text"
          className={cn(
            'px-3 py-2 text-base leading-6 text-foreground bg-background border border-input rounded-md',
            'transition-colors duration-150 motion-reduce:transition-none',
            'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
            'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed',
            'placeholder:text-muted-foreground',
            errors.name && 'border-destructive focus:border-destructive focus:ring-destructive/25'
          )}
          placeholder={t('collectionForm.namePlaceholder')}
          disabled={isEditMode || isSubmitting}
          aria-required="true"
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? 'name-error' : !isEditMode ? 'name-hint' : undefined}
          data-testid="collection-name-input"
          {...register('name')}
        />
        {errors.name && (
          <span
            id="name-error"
            className="flex items-center gap-1 text-sm text-destructive mt-1 before:content-['\u26A0'] before:text-xs"
            role="alert"
            data-testid="name-error"
          >
            {getErrorMessage(errors.name.message)}
          </span>
        )}
        {!isEditMode && (
          <span
            id="name-hint"
            className="text-xs text-muted-foreground mt-1"
            data-testid="name-hint"
          >
            {t('collectionForm.nameHint')}
          </span>
        )}
      </div>

      {/* Display Name Field */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="collection-display-name"
          className="flex items-center gap-1 text-sm font-medium text-foreground"
        >
          {t('collections.displayName')}
          <span className="text-destructive font-semibold" aria-hidden="true">
            *
          </span>
        </label>
        <input
          id="collection-display-name"
          type="text"
          className={cn(
            'px-3 py-2 text-base leading-6 text-foreground bg-background border border-input rounded-md',
            'transition-colors duration-150 motion-reduce:transition-none',
            'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
            'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed',
            'placeholder:text-muted-foreground',
            errors.displayName &&
              'border-destructive focus:border-destructive focus:ring-destructive/25'
          )}
          placeholder={t('collectionForm.displayNamePlaceholder')}
          disabled={isSubmitting}
          aria-required="true"
          aria-invalid={!!errors.displayName}
          aria-describedby={errors.displayName ? 'display-name-error' : undefined}
          data-testid="collection-display-name-input"
          {...register('displayName')}
        />
        {errors.displayName && (
          <span
            id="display-name-error"
            className="flex items-center gap-1 text-sm text-destructive mt-1 before:content-['\u26A0'] before:text-xs"
            role="alert"
            data-testid="display-name-error"
          >
            {getErrorMessage(errors.displayName.message)}
          </span>
        )}
      </div>

      {/* Description Field */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="collection-description"
          className="flex items-center gap-1 text-sm font-medium text-foreground"
        >
          {t('collections.description')}
          <span className="text-xs font-normal text-muted-foreground ml-1">
            ({t('common.optional')})
          </span>
        </label>
        <textarea
          id="collection-description"
          className={cn(
            'px-3 py-2 text-base leading-6 text-foreground bg-background border border-input rounded-md',
            'resize-y min-h-[80px]',
            'transition-colors duration-150 motion-reduce:transition-none',
            'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
            'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed',
            'placeholder:text-muted-foreground',
            errors.description &&
              'border-destructive focus:border-destructive focus:ring-destructive/25'
          )}
          placeholder={t('collectionForm.descriptionPlaceholder')}
          disabled={isSubmitting}
          rows={3}
          aria-invalid={!!errors.description}
          aria-describedby={errors.description ? 'description-error' : undefined}
          data-testid="collection-description-input"
          {...register('description')}
        />
        {errors.description && (
          <span
            id="description-error"
            className="flex items-center gap-1 text-sm text-destructive mt-1 before:content-['\u26A0'] before:text-xs"
            role="alert"
            data-testid="description-error"
          >
            {getErrorMessage(errors.description.message)}
          </span>
        )}
      </div>

      {/* Display Field (edit mode only, when fields exist) */}
      {isEditMode && availableFields.length > 0 && (
        <div className="flex flex-col gap-1">
          <label
            htmlFor="collection-display-field"
            className="flex items-center gap-1 text-sm font-medium text-foreground"
          >
            {t('collections.displayField', 'Display Field')}
            <span className="text-xs font-normal text-muted-foreground ml-1">
              ({t('common.optional')})
            </span>
          </label>
          <select
            id="collection-display-field"
            className={cn(
              'px-3 py-2 pr-10 text-base leading-6 text-foreground bg-background border border-input rounded-md',
              'appearance-none bg-[length:1.5em_1.5em] bg-[right_0.5rem_center] bg-no-repeat cursor-pointer',
              "bg-[url(\"data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' fill='none' viewBox='0 0 20 20'%3e%3cpath stroke='%236b7280' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.5' d='M6 8l4 4 4-4'/%3e%3c/svg%3e\")]",
              'transition-colors duration-150 motion-reduce:transition-none',
              'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
              'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed'
            )}
            disabled={isSubmitting}
            aria-describedby="display-field-hint"
            data-testid="collection-display-field-select"
            {...register('displayFieldId')}
          >
            <option value="">{t('collectionForm.displayFieldNone', 'Auto-detect')}</option>
            {availableFields.map((field) => (
              <option key={field.id} value={field.id}>
                {field.displayName || field.name}
              </option>
            ))}
          </select>
          <span
            id="display-field-hint"
            className="text-xs text-muted-foreground mt-1"
            data-testid="display-field-hint"
          >
            {t(
              'collectionForm.displayFieldHint',
              'The field shown when records appear in lookup dropdowns. If not set, defaults to a field named "name" or the first text field.'
            )}
          </span>
        </div>
      )}

      {/* Active Status Field */}
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <input
            id="collection-active"
            type="checkbox"
            className="w-[1.125rem] h-[1.125rem] accent-primary cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
            disabled={isSubmitting}
            aria-describedby="active-hint"
            data-testid="collection-active-checkbox"
            {...register('active')}
          />
          <label htmlFor="collection-active" className="text-base text-foreground cursor-pointer">
            {t('collections.active')}
          </label>
        </div>
        <span
          id="active-hint"
          className="text-xs text-muted-foreground mt-1"
          data-testid="active-hint"
        >
          {t('collectionForm.activeHint')}
        </span>
      </div>

      {/* Track History Field */}
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <input
            id="collection-track-history"
            type="checkbox"
            className="w-[1.125rem] h-[1.125rem] accent-primary cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
            disabled={isSubmitting}
            aria-describedby="track-history-hint"
            data-testid="collection-track-history-checkbox"
            {...register('trackHistory')}
          />
          <label
            htmlFor="collection-track-history"
            className="text-base text-foreground cursor-pointer"
          >
            {t('collectionForm.trackHistory')}
          </label>
        </div>
        <span
          id="track-history-hint"
          className="text-xs text-muted-foreground mt-1"
          data-testid="track-history-hint"
        >
          {t('collectionForm.trackHistoryHint')}
        </span>
      </div>

      {/* Capture Geo Field */}
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2">
          <input
            id="collection-capture-geo"
            type="checkbox"
            className="w-[1.125rem] h-[1.125rem] accent-primary cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
            disabled={isSubmitting}
            aria-describedby="capture-geo-hint"
            data-testid="collection-capture-geo-checkbox"
            {...register('captureGeo')}
          />
          <label
            htmlFor="collection-capture-geo"
            className="text-base text-foreground cursor-pointer"
          >
            {t('collectionForm.captureGeo')}
          </label>
        </div>
        <span
          id="capture-geo-hint"
          className="text-xs text-muted-foreground mt-1"
          data-testid="capture-geo-hint"
        >
          {t('collectionForm.captureGeoHint')}
        </span>
      </div>

      {/* Ownership — member data ownership: who sees and changes only their own records */}
      <fieldset
        className="flex flex-col gap-3 rounded-md border border-border p-4"
        data-testid="collection-ownership-group"
      >
        <legend className="px-1 text-sm font-medium text-foreground">
          {t('collectionForm.ownership')}
        </legend>

        <div className="flex flex-col gap-1">
          <label
            htmlFor="collection-owner-field"
            className="flex items-center gap-1 text-sm font-medium text-foreground"
          >
            {t('collectionForm.ownerField')}
          </label>
          <select
            id="collection-owner-field"
            className={cn(
              'px-3 py-2 text-base leading-6 text-foreground bg-background border border-input rounded-md',
              'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
              'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed',
              errors.ownerField &&
                'border-destructive focus:border-destructive focus:ring-destructive/25'
            )}
            disabled={isSubmitting}
            aria-invalid={!!errors.ownerField}
            aria-describedby={errors.ownerField ? 'owner-field-error' : 'owner-field-hint'}
            data-testid="collection-owner-field-select"
            {...register('ownerField')}
          >
            <option value="">{t('collectionForm.ownerFieldNone')}</option>
            <option value={CREATED_BY_OWNER_FIELD}>
              {t('collectionForm.ownerFieldCreatedBy')}
            </option>
            {ownerFieldOptions.map((field) => (
              <option key={field.id} value={field.name}>
                {field.displayName || field.name}
              </option>
            ))}
          </select>
          {errors.ownerField ? (
            <span
              id="owner-field-error"
              className="flex items-center gap-1 text-sm text-destructive mt-1 before:content-['\u26A0'] before:text-xs"
              role="alert"
              data-testid="owner-field-error"
            >
              {getErrorMessage(errors.ownerField.message)}
            </span>
          ) : (
            <span id="owner-field-hint" className="text-xs text-muted-foreground mt-1">
              {t('collectionForm.ownerFieldHint')}
            </span>
          )}
        </div>

        <div className="flex flex-col gap-1" role="radiogroup" aria-labelledby="owner-scope-label">
          <span id="owner-scope-label" className="text-sm font-medium text-foreground">
            {t('collectionForm.ownerScope')}
          </span>
          {OWNER_SCOPES.map((scope) => (
            <label
              key={scope}
              className="flex items-center gap-2 text-base text-foreground cursor-pointer"
            >
              <input
                type="radio"
                value={scope}
                className="w-[1.125rem] h-[1.125rem] accent-primary cursor-pointer disabled:cursor-not-allowed"
                disabled={isSubmitting}
                data-testid={`collection-owner-scope-${scope.toLowerCase()}`}
                {...register('ownerScope')}
              />
              {t(`collectionForm.ownerScope${scope.charAt(0)}${scope.slice(1).toLowerCase()}`)}
            </label>
          ))}
        </div>

        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <input
              id="collection-owner-scope-reads"
              type="checkbox"
              className="w-[1.125rem] h-[1.125rem] accent-primary cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
              disabled={isSubmitting}
              aria-describedby="owner-scope-reads-hint"
              data-testid="collection-owner-scope-reads-checkbox"
              {...register('ownerScopeReads')}
            />
            <label
              htmlFor="collection-owner-scope-reads"
              className="text-base text-foreground cursor-pointer"
            >
              {t('collectionForm.ownerScopeReads')}
            </label>
          </div>
          <span id="owner-scope-reads-hint" className="text-xs text-muted-foreground mt-1">
            {t('collectionForm.ownerScopeReadsHint')}
          </span>
        </div>
      </fieldset>

      {/* Form Actions */}
      <div className="flex justify-end gap-3 mt-6 pt-6 border-t border-border max-md:flex-col-reverse max-md:gap-2">
        <button
          type="button"
          className={cn(
            'inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-6 rounded-md cursor-pointer',
            'text-foreground bg-secondary border border-input',
            'transition-colors duration-150 motion-reduce:transition-none',
            'hover:bg-accent focus:outline-none focus:ring-2 focus:ring-ring',
            'disabled:opacity-50 disabled:cursor-not-allowed',
            'max-md:w-full'
          )}
          onClick={onCancel}
          disabled={isSubmitting}
          data-testid="collection-form-cancel"
        >
          {t('common.cancel')}
        </button>
        <button
          type="submit"
          className={cn(
            'inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-6 rounded-md cursor-pointer',
            'text-primary-foreground bg-primary border border-transparent',
            'transition-colors duration-150 motion-reduce:transition-none',
            'hover:bg-primary/90 focus:outline-none focus:ring-2 focus:ring-ring',
            'disabled:opacity-50 disabled:cursor-not-allowed',
            'max-md:w-full'
          )}
          disabled={isSubmitting || (!isDirty && isEditMode)}
          data-testid="collection-form-submit"
        >
          {isSubmitting ? (
            <>
              <LoadingSpinner size="small" />
              <span className="ml-1">{isEditMode ? t('common.save') : t('common.create')}</span>
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

export default CollectionForm
