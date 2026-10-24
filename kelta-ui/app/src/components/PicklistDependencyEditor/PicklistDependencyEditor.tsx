import React, { useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner } from '../LoadingSpinner'
import type { FieldDefinition, PicklistValue, PicklistDependency } from '../../types/collections'

export interface PicklistDependencyEditorProps {
  collectionId: string
  fields: FieldDefinition[]
  dependency?: PicklistDependency
  onSave: (data: {
    controllingFieldId: string
    dependentFieldId: string
    mapping: Record<string, string[]>
  }) => Promise<void>
  onCancel: () => void
  isSubmitting: boolean
}

export function PicklistDependencyEditor({
  fields,
  dependency,
  onSave,
  onCancel,
  isSubmitting,
}: PicklistDependencyEditorProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const isEditMode = !!dependency

  const picklistFields = fields.filter((f) => f.type === 'picklist' || f.type === 'multi_picklist')

  const [controllingFieldId, setControllingFieldId] = useState(dependency?.controllingFieldId ?? '')
  const [dependentFieldId, setDependentFieldId] = useState(dependency?.dependentFieldId ?? '')
  const [mapping, setMapping] = useState<Record<string, string[]>>(dependency?.mapping ?? {})

  const { data: controllingValues, isLoading: loadingControlling } = useQuery({
    queryKey: ['field-picklist-values', controllingFieldId],
    queryFn: () =>
      apiClient.getList<PicklistValue>(
        `/api/picklist-values?filter[picklistSourceId][eq]=${controllingFieldId}&filter[picklistSourceType][eq]=FIELD`
      ),
    enabled: !!controllingFieldId,
  })

  const { data: dependentValues, isLoading: loadingDependent } = useQuery({
    queryKey: ['field-picklist-values', dependentFieldId],
    queryFn: () =>
      apiClient.getList<PicklistValue>(
        `/api/picklist-values?filter[picklistSourceId][eq]=${dependentFieldId}&filter[picklistSourceType][eq]=FIELD`
      ),
    enabled: !!dependentFieldId,
  })

  const handleControllingFieldChange = useCallback(
    (newId: string) => {
      setControllingFieldId(newId)
      if (!isEditMode) setMapping({})
    },
    [isEditMode]
  )

  const handleDependentFieldChange = useCallback(
    (newId: string) => {
      setDependentFieldId(newId)
      if (!isEditMode) setMapping({})
    },
    [isEditMode]
  )

  const handleMappingToggle = useCallback((controllingValue: string, dependentValue: string) => {
    setMapping((prev) => {
      const current = prev[controllingValue] ?? []
      const exists = current.includes(dependentValue)
      return {
        ...prev,
        [controllingValue]: exists
          ? current.filter((v) => v !== dependentValue)
          : [...current, dependentValue],
      }
    })
  }, [])

  const handleSave = useCallback(async () => {
    if (!controllingFieldId || !dependentFieldId) return
    await onSave({ controllingFieldId, dependentFieldId, mapping })
  }, [controllingFieldId, dependentFieldId, mapping, onSave])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel()
      }
    },
    [onCancel]
  )

  const controllingFieldName = picklistFields.find((f) => f.id === controllingFieldId)?.name ?? ''
  const dependentFieldName = picklistFields.find((f) => f.id === dependentFieldId)?.name ?? ''

  const availableDependentFields = picklistFields.filter((f) => f.id !== controllingFieldId)

  const canSave =
    !!controllingFieldId &&
    !!dependentFieldId &&
    !isSubmitting &&
    !loadingControlling &&
    !loadingDependent

  const showMappingMatrix =
    controllingFieldId &&
    dependentFieldId &&
    controllingValues &&
    controllingValues.length > 0 &&
    dependentValues &&
    dependentValues.length > 0

  const selectClasses = cn(
    'px-3.5 py-2.5 text-sm text-foreground bg-background border border-input rounded-md',
    'transition-colors focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring/10',
    'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed'
  )

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4"
      onMouseDown={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={handleKeyDown}
      role="presentation"
      data-testid="dependency-editor-overlay"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="bg-background dark:bg-card rounded-lg shadow-xl max-w-[900px] w-full max-h-[90vh] flex flex-col"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dependency-editor-title"
        onMouseDown={(e) => e.stopPropagation()}
        data-testid="dependency-editor-modal"
      >
        <div className="flex justify-between items-center p-6 border-b border-border shrink-0">
          <h2 id="dependency-editor-title" className="text-xl font-semibold m-0 text-foreground">
            {isEditMode
              ? t('picklistDependencies.editDependency')
              : t('picklistDependencies.addDependency')}
          </h2>
          <button
            type="button"
            className="p-2 text-2xl leading-none text-muted-foreground bg-transparent border-none cursor-pointer rounded transition-colors hover:text-foreground hover:bg-muted"
            onClick={onCancel}
            aria-label={t('common.close')}
            data-testid="dependency-editor-close"
          >
            &times;
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1">
          {picklistFields.length < 2 ? (
            <div className="py-8 text-center text-muted-foreground text-sm">
              <p>
                {picklistFields.length === 0
                  ? t('picklistDependencies.noPicklistFields')
                  : t('picklistDependencies.needTwoPicklistFields')}
              </p>
            </div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-6 mb-6 max-md:grid-cols-1">
                <div className="flex flex-col gap-2">
                  <label
                    htmlFor="controlling-field"
                    className="text-sm font-medium text-foreground"
                  >
                    {t('picklistDependencies.controllingField')}
                    <span className="text-destructive ml-1" aria-hidden="true">
                      *
                    </span>
                  </label>
                  <select
                    id="controlling-field"
                    className={selectClasses}
                    value={controllingFieldId}
                    onChange={(e) => handleControllingFieldChange(e.target.value)}
                    disabled={isEditMode || isSubmitting}
                    data-testid="controlling-field-select"
                  >
                    <option value="">{t('picklistDependencies.selectControllingField')}</option>
                    {picklistFields.map((f) => (
                      <option key={f.id} value={f.id}>
                        {f.displayName || f.name} ({f.type})
                      </option>
                    ))}
                  </select>
                </div>

                <div className="flex flex-col gap-2">
                  <label htmlFor="dependent-field" className="text-sm font-medium text-foreground">
                    {t('picklistDependencies.dependentField')}
                    <span className="text-destructive ml-1" aria-hidden="true">
                      *
                    </span>
                  </label>
                  <select
                    id="dependent-field"
                    className={selectClasses}
                    value={dependentFieldId}
                    onChange={(e) => handleDependentFieldChange(e.target.value)}
                    disabled={isEditMode || isSubmitting || !controllingFieldId}
                    data-testid="dependent-field-select"
                  >
                    <option value="">{t('picklistDependencies.selectDependentField')}</option>
                    {availableDependentFields.map((f) => (
                      <option key={f.id} value={f.id}>
                        {f.displayName || f.name} ({f.type})
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {(loadingControlling || loadingDependent) && (
                <div className="flex justify-center items-center py-12">
                  <LoadingSpinner size="medium" label={t('common.loading')} />
                </div>
              )}

              {controllingFieldId &&
                dependentFieldId &&
                !loadingControlling &&
                !loadingDependent &&
                (!controllingValues || controllingValues.length === 0) && (
                  <div className="py-8 text-center text-muted-foreground text-sm">
                    <p>
                      {t('picklistDependencies.noValuesForField')} ({controllingFieldName})
                    </p>
                  </div>
                )}

              {controllingFieldId &&
                dependentFieldId &&
                !loadingControlling &&
                !loadingDependent &&
                controllingValues &&
                controllingValues.length > 0 &&
                (!dependentValues || dependentValues.length === 0) && (
                  <div className="py-8 text-center text-muted-foreground text-sm">
                    <p>
                      {t('picklistDependencies.noValuesForField')} ({dependentFieldName})
                    </p>
                  </div>
                )}

              {showMappingMatrix && (
                <div className="mt-4">
                  <h3 className="text-base font-semibold mb-2 text-foreground">
                    {t('picklistDependencies.mapping')}
                  </h3>
                  <p className="text-xs text-muted-foreground mb-4">
                    {t('picklistDependencies.mappingHint')}
                  </p>
                  <table
                    className="w-full border-collapse border border-border rounded-md overflow-hidden max-md:text-xs"
                    data-testid="mapping-matrix"
                  >
                    <thead>
                      <tr className="bg-muted">
                        <th className="px-3 py-2.5 text-left text-xs font-semibold text-muted-foreground uppercase tracking-wider border-b border-border">
                          {controllingFieldName}
                        </th>
                        {dependentValues!.map((dv) => (
                          <th
                            key={dv.value}
                            className="px-3 py-2.5 text-center text-xs font-semibold text-muted-foreground uppercase tracking-wider border-b border-border"
                          >
                            {dv.label || dv.value}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {controllingValues!.map((cv) => (
                        <tr key={cv.value}>
                          <td className="px-3 py-2 text-left text-[0.8125rem] font-medium text-foreground bg-muted border-b border-border">
                            {cv.label || cv.value}
                          </td>
                          {dependentValues!.map((dv) => (
                            <td
                              key={dv.value}
                              className="px-3 py-2 text-center text-[0.8125rem] border-b border-border last:[&_tr]:border-b-0"
                            >
                              <input
                                type="checkbox"
                                className="w-4 h-4 accent-primary cursor-pointer"
                                checked={mapping[cv.value]?.includes(dv.value) ?? false}
                                onChange={() => handleMappingToggle(cv.value, dv.value)}
                                disabled={isSubmitting}
                                aria-label={`${cv.label || cv.value} → ${dv.label || dv.value}`}
                                data-testid={`mapping-${cv.value}-${dv.value}`}
                              />
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </div>

        <div className="flex gap-3 justify-end px-6 py-4 border-t border-border shrink-0">
          <button
            type="button"
            className="px-5 py-2.5 text-sm font-medium text-foreground bg-background border border-input rounded-md cursor-pointer transition-colors hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={onCancel}
            disabled={isSubmitting}
            data-testid="dependency-editor-cancel"
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="px-5 py-2.5 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={handleSave}
            disabled={!canSave}
            data-testid="dependency-editor-save"
          >
            {isSubmitting ? (
              <>
                <LoadingSpinner size="small" />
                <span>{t('common.saving')}</span>
              </>
            ) : (
              t('common.save')
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
