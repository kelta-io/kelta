import React, { useState, useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner } from '../LoadingSpinner'
import { ErrorMessage } from '../ErrorMessage'
import type { PicklistValue } from '../../types/collections'

export interface PicklistValuesEditorProps {
  picklistId: string
  picklistName: string
  onClose: () => void
}

interface LocalValue {
  key: string
  value: string
  label: string
  color: string
  isDefault: boolean
  isActive: boolean
  description: string
}

interface ValueErrors {
  value?: string
  label?: string
}

let nextKey = 0
function generateKey(): string {
  return `new-${++nextKey}`
}

function toLocalValues(values: PicklistValue[]): LocalValue[] {
  return values.map((v) => ({
    key: v.id ?? generateKey(),
    value: v.value,
    label: v.label,
    color: v.color ?? '',
    isDefault: v.isDefault,
    isActive: v.isActive,
    description: v.description ?? '',
  }))
}

export function PicklistValuesEditor({
  picklistId,
  picklistName,
  onClose,
}: PicklistValuesEditorProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const [editedValues, setEditedValues] = useState<LocalValue[] | null>(null)
  const [errors, setErrors] = useState<Record<string, ValueErrors>>({})

  const {
    data: serverValues,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['picklist-values', picklistId],
    queryFn: () =>
      apiClient.getList<PicklistValue>(
        `/api/picklist-values?filter[picklistSourceId][eq]=${picklistId}&filter[picklistSourceType][eq]=GLOBAL`
      ),
  })

  const initialValues = useMemo(
    () => (serverValues ? toLocalValues(serverValues) : []),
    [serverValues]
  )

  const localValues = editedValues ?? initialValues

  const saveMutation = useMutation({
    mutationFn: (values: PicklistValue[]) =>
      apiClient.putResource<PicklistValue[]>(
        `/api/picklist-values?filter[picklistSourceId][eq]=${picklistId}&filter[picklistSourceType][eq]=GLOBAL`,
        values
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['picklist-values', picklistId] })
      toast.success(t('picklists.valueSaved'))
      onClose()
    },
    onError: (err: Error) => {
      toast.error(err.message || t('errors.generic'))
    },
  })

  const validate = useCallback(
    (values: LocalValue[]): Record<string, ValueErrors> => {
      const newErrors: Record<string, ValueErrors> = {}
      const seenValues = new Set<string>()

      for (const v of values) {
        const rowErrors: ValueErrors = {}
        if (!v.value.trim()) {
          rowErrors.value = t('picklists.valueRequired')
        } else if (seenValues.has(v.value.trim())) {
          rowErrors.value = t('picklists.duplicateValue')
        } else {
          seenValues.add(v.value.trim())
        }
        if (!v.label.trim()) {
          rowErrors.label = t('picklists.labelRequired')
        }
        if (Object.keys(rowErrors).length > 0) {
          newErrors[v.key] = rowErrors
        }
      }
      return newErrors
    },
    [t]
  )

  const handleAdd = useCallback(() => {
    setEditedValues([
      ...localValues,
      {
        key: generateKey(),
        value: '',
        label: '',
        color: '',
        isDefault: false,
        isActive: true,
        description: '',
      },
    ])
  }, [localValues])

  const handleRemove = useCallback(
    (key: string) => {
      setEditedValues(localValues.filter((v) => v.key !== key))
      setErrors((prev) => {
        const next = { ...prev }
        delete next[key]
        return next
      })
    },
    [localValues]
  )

  const handleChange = useCallback(
    (key: string, field: keyof LocalValue, fieldValue: string | boolean) => {
      setEditedValues(localValues.map((v) => (v.key === key ? { ...v, [field]: fieldValue } : v)))
      if (typeof fieldValue === 'string') {
        setErrors((prev) => {
          const rowErrors = { ...prev[key] }
          delete rowErrors[field as keyof ValueErrors]
          if (Object.keys(rowErrors).length === 0) {
            const next = { ...prev }
            delete next[key]
            return next
          }
          return { ...prev, [key]: rowErrors }
        })
      }
    },
    [localValues]
  )

  const handleMoveUp = useCallback(
    (index: number) => {
      if (index <= 0) return
      const next = [...localValues]
      ;[next[index - 1], next[index]] = [next[index], next[index - 1]]
      setEditedValues(next)
    },
    [localValues]
  )

  const handleMoveDown = useCallback(
    (index: number) => {
      if (index >= localValues.length - 1) return
      const next = [...localValues]
      ;[next[index], next[index + 1]] = [next[index + 1], next[index]]
      setEditedValues(next)
    },
    [localValues]
  )

  const handleSave = useCallback(() => {
    const validationErrors = validate(localValues)
    setErrors(validationErrors)
    if (Object.keys(validationErrors).length > 0) return

    const payload: PicklistValue[] = localValues.map((v, index) => ({
      value: v.value.trim(),
      label: v.label.trim(),
      isDefault: v.isDefault,
      isActive: v.isActive,
      sortOrder: index,
      color: v.color.trim() || undefined,
      description: v.description.trim() || undefined,
    }))
    saveMutation.mutate(payload)
  }, [localValues, validate, saveMutation])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    },
    [onClose]
  )

  const isSaving = saveMutation.isPending

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
      onKeyDown={handleKeyDown}
      role="presentation"
      data-testid="picklist-values-overlay"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="bg-background dark:bg-card rounded-lg shadow-xl max-w-[800px] w-full max-h-[90vh] flex flex-col"
        role="dialog"
        aria-modal="true"
        aria-labelledby="values-editor-title"
        onMouseDown={(e) => e.stopPropagation()}
        data-testid="picklist-values-modal"
      >
        <div className="flex justify-between items-center p-6 border-b border-border shrink-0">
          <h2 id="values-editor-title" className="text-xl font-semibold m-0 text-foreground">
            {t('picklists.valuesTitle')} — {picklistName}
          </h2>
          <button
            type="button"
            className="p-2 text-2xl leading-none text-muted-foreground bg-transparent border-none cursor-pointer rounded transition-colors hover:text-foreground hover:bg-muted"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="values-editor-close"
          >
            &times;
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1">
          {isLoading ? (
            <div className="flex justify-center items-center py-12">
              <LoadingSpinner size="medium" label={t('common.loading')} />
            </div>
          ) : error ? (
            <ErrorMessage error={error instanceof Error ? error : new Error(t('errors.generic'))} />
          ) : localValues.length === 0 ? (
            <div
              className="py-8 text-center text-muted-foreground text-sm"
              data-testid="values-empty-state"
            >
              <p>{t('picklists.noValues')}</p>
            </div>
          ) : (
            <div className="flex flex-col gap-3" data-testid="values-list">
              {localValues.map((v, index) => (
                <div
                  key={v.key}
                  className="grid grid-cols-[auto_1fr_1fr_120px_60px_60px_auto] gap-2 items-start p-3 bg-muted border border-border rounded-md max-md:grid-cols-1"
                  data-testid={`value-row-${index}`}
                >
                  <div className="flex flex-col gap-0.5 max-md:flex-row">
                    <button
                      type="button"
                      className="px-1.5 py-0.5 text-xs leading-none text-muted-foreground bg-background border border-input rounded cursor-pointer transition-colors hover:bg-muted hover:border-primary hover:text-primary disabled:opacity-30 disabled:cursor-not-allowed"
                      onClick={() => handleMoveUp(index)}
                      disabled={index === 0 || isSaving}
                      aria-label={t('picklists.moveUp')}
                      data-testid={`value-move-up-${index}`}
                    >
                      &#9650;
                    </button>
                    <button
                      type="button"
                      className="px-1.5 py-0.5 text-xs leading-none text-muted-foreground bg-background border border-input rounded cursor-pointer transition-colors hover:bg-muted hover:border-primary hover:text-primary disabled:opacity-30 disabled:cursor-not-allowed"
                      onClick={() => handleMoveDown(index)}
                      disabled={index === localValues.length - 1 || isSaving}
                      aria-label={t('picklists.moveDown')}
                      data-testid={`value-move-down-${index}`}
                    >
                      &#9660;
                    </button>
                  </div>

                  <div className="flex flex-col gap-1">
                    <label
                      className="text-[0.6875rem] font-semibold text-muted-foreground uppercase tracking-wider"
                      htmlFor={`value-${v.key}`}
                    >
                      Value *
                    </label>
                    <input
                      id={`value-${v.key}`}
                      type="text"
                      className={cn(
                        'px-2.5 py-2 text-[0.8125rem] text-foreground bg-background border border-input rounded w-full box-border',
                        'transition-colors focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring/10',
                        errors[v.key]?.value && 'border-destructive'
                      )}
                      value={v.value}
                      onChange={(e) => handleChange(v.key, 'value', e.target.value)}
                      placeholder={t('picklists.valuePlaceholder')}
                      disabled={isSaving}
                      data-testid={`value-input-${index}`}
                    />
                    {errors[v.key]?.value && (
                      <span className="text-[0.6875rem] text-destructive">
                        {errors[v.key].value}
                      </span>
                    )}
                  </div>

                  <div className="flex flex-col gap-1">
                    <label
                      className="text-[0.6875rem] font-semibold text-muted-foreground uppercase tracking-wider"
                      htmlFor={`label-${v.key}`}
                    >
                      Label *
                    </label>
                    <input
                      id={`label-${v.key}`}
                      type="text"
                      className={cn(
                        'px-2.5 py-2 text-[0.8125rem] text-foreground bg-background border border-input rounded w-full box-border',
                        'transition-colors focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring/10',
                        errors[v.key]?.label && 'border-destructive'
                      )}
                      value={v.label}
                      onChange={(e) => handleChange(v.key, 'label', e.target.value)}
                      placeholder={t('picklists.labelPlaceholder')}
                      disabled={isSaving}
                      data-testid={`label-input-${index}`}
                    />
                    {errors[v.key]?.label && (
                      <span className="text-[0.6875rem] text-destructive">
                        {errors[v.key].label}
                      </span>
                    )}
                  </div>

                  <div className="flex flex-col gap-1">
                    <label
                      className="text-[0.6875rem] font-semibold text-muted-foreground uppercase tracking-wider"
                      htmlFor={`color-${v.key}`}
                    >
                      Color
                    </label>
                    <input
                      id={`color-${v.key}`}
                      type="text"
                      className="px-2.5 py-2 text-[0.8125rem] text-foreground bg-background border border-input rounded w-full box-border transition-colors focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring/10"
                      value={v.color}
                      onChange={(e) => handleChange(v.key, 'color', e.target.value)}
                      placeholder={t('picklists.colorPlaceholder')}
                      disabled={isSaving}
                      data-testid={`color-input-${index}`}
                    />
                  </div>

                  <div className="flex items-center gap-1.5 pt-1">
                    <input
                      id={`default-${v.key}`}
                      type="checkbox"
                      className="w-3.5 h-3.5 accent-primary"
                      checked={v.isDefault}
                      onChange={(e) => handleChange(v.key, 'isDefault', e.target.checked)}
                      disabled={isSaving}
                      data-testid={`default-checkbox-${index}`}
                    />
                    <label className="text-xs text-foreground" htmlFor={`default-${v.key}`}>
                      Default
                    </label>
                  </div>

                  <div className="flex items-center gap-1.5 pt-1">
                    <input
                      id={`active-${v.key}`}
                      type="checkbox"
                      className="w-3.5 h-3.5 accent-primary"
                      checked={v.isActive}
                      onChange={(e) => handleChange(v.key, 'isActive', e.target.checked)}
                      disabled={isSaving}
                      data-testid={`active-checkbox-${index}`}
                    />
                    <label className="text-xs text-foreground" htmlFor={`active-${v.key}`}>
                      Active
                    </label>
                  </div>

                  <button
                    type="button"
                    className="p-1.5 text-base leading-none text-destructive bg-transparent border border-transparent rounded cursor-pointer self-center transition-colors hover:bg-destructive/10 hover:border-destructive/10"
                    onClick={() => handleRemove(v.key)}
                    disabled={isSaving}
                    aria-label={t('picklists.removeValue')}
                    data-testid={`value-remove-${index}`}
                  >
                    &times;
                  </button>
                </div>
              ))}
            </div>
          )}

          {!isLoading && !error && (
            <button
              type="button"
              className="w-full mt-2 px-4 py-2 text-sm font-medium text-primary bg-transparent border border-dashed border-primary rounded-md cursor-pointer transition-colors hover:bg-primary/5"
              onClick={handleAdd}
              disabled={isSaving}
              data-testid="add-value-button"
            >
              + {t('picklists.addValue')}
            </button>
          )}
        </div>

        <div className="flex gap-3 justify-end px-6 py-4 border-t border-border shrink-0">
          <button
            type="button"
            className="px-5 py-2.5 text-sm font-medium text-foreground bg-background border border-input rounded-md cursor-pointer transition-colors hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={onClose}
            disabled={isSaving}
            data-testid="values-editor-cancel"
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="px-5 py-2.5 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={handleSave}
            disabled={isSaving || isLoading}
            data-testid="values-editor-save"
          >
            {isSaving ? (
              <>
                <LoadingSpinner size="small" />
                <span>{t('common.saving')}</span>
              </>
            ) : (
              t('picklists.saveValues')
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
