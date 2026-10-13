/**
 * DataSourceSettings — admin UI to back a collection with an external connector
 * (Rec 4 slice 4e).
 *
 * A collection's storage adapter is selected by `adapterConfig.adapterType`
 * (persisted on the `collections` system collection, Rec 4d-1; routed by
 * `DispatchingStorageAdapter`, Rec 4a). This panel edits that config: pick a
 * source (physical table / external REST / external JDBC) and supply its
 * connection settings + a credential reference (resolved from the vault, never
 * stored here). Saving PATCHes the collection's `adapterConfig`.
 */
import { useState } from 'react'
import { toast } from 'sonner'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { useI18n } from '../../context/I18nContext'
import { LoadingSpinner } from '../LoadingSpinner'
import { ErrorMessage } from '../ErrorMessage'

type AdapterType = 'physical-table' | 'external-rest' | 'external-jdbc'

export interface DataSourceSettingsProps {
  collectionId: string
}

interface CollectionRecord {
  id: string
  adapterConfig?: Record<string, unknown> | string | null
}

/** Normalise the persisted adapterConfig (object | JSON string | null) to a flat string map. */
function toConfigMap(raw: CollectionRecord['adapterConfig']): Record<string, string> {
  if (!raw) return {}
  let obj: unknown = raw
  if (typeof raw === 'string') {
    try {
      obj = JSON.parse(raw)
    } catch {
      return {}
    }
  }
  if (obj && typeof obj === 'object') {
    const out: Record<string, string> = {}
    for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
      if (v != null) out[k] = String(v)
    }
    return out
  }
  return {}
}

const REST_FIELDS = ['baseUrl', 'path', 'dataPath', 'idAttribute', 'credentialRef'] as const
const JDBC_FIELDS = ['jdbcUrl', 'table', 'idColumn', 'credentialRef'] as const

/** Outer component: loads the collection's current adapterConfig, then mounts the form. */
export function DataSourceSettings({ collectionId }: DataSourceSettingsProps) {
  const { apiClient } = useApi()

  const { data, isLoading, error } = useQuery({
    queryKey: ['collection-adapter-config', collectionId],
    queryFn: () => apiClient.getOne<CollectionRecord>(`/api/collections/${collectionId}`),
    enabled: !!collectionId,
  })

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={(error as Error).message} />

  // Keyed so the form re-initialises if the collection changes.
  return (
    <DataSourceForm
      key={collectionId}
      collectionId={collectionId}
      initialConfig={toConfigMap(data?.adapterConfig)}
    />
  )
}

/**
 * Inner form — mounted only once the config has loaded, so its local state is
 * initialised straight from props (no state-syncing effect).
 */
function DataSourceForm({
  collectionId,
  initialConfig,
}: {
  collectionId: string
  initialConfig: Record<string, string>
}) {
  const { apiClient } = useApi()
  const { t } = useI18n()
  const queryClient = useQueryClient()

  const seededType = initialConfig.adapterType
  const [adapterType, setAdapterType] = useState<AdapterType>(
    seededType === 'external-rest' || seededType === 'external-jdbc' ? seededType : 'physical-table'
  )
  const [values, setValues] = useState<Record<string, string>>(initialConfig)

  const fields =
    adapterType === 'external-rest'
      ? REST_FIELDS
      : adapterType === 'external-jdbc'
        ? JDBC_FIELDS
        : []
  const requiredKey =
    adapterType === 'external-rest' ? 'baseUrl' : adapterType === 'external-jdbc' ? 'jdbcUrl' : null
  const missingRequired = !!requiredKey && !values[requiredKey]?.trim()

  const save = useMutation({
    mutationFn: async () => {
      let adapterConfig: Record<string, string> = {}
      if (adapterType !== 'physical-table') {
        adapterConfig = { adapterType }
        for (const key of fields) {
          const v = values[key]?.trim()
          if (v) adapterConfig[key] = v
        }
      }
      return apiClient.patchResource(`/api/collections/${collectionId}`, { adapterConfig })
    },
    onSuccess: () => {
      toast.success(t('dataSource.saved'))
      queryClient.invalidateQueries({ queryKey: ['collection-adapter-config', collectionId] })
      queryClient.invalidateQueries({ queryKey: ['collection', collectionId] })
    },
    onError: (e: Error) => toast.error(e.message || t('errors.generic')),
  })

  return (
    <section
      id="data-source-panel"
      aria-labelledby="data-source-tab"
      data-testid="data-source-panel"
    >
      <div className="max-w-xl space-y-4">
        <div>
          <label htmlFor="adapter-type" className="block text-sm font-medium mb-1">
            {t('dataSource.type')}
          </label>
          <select
            id="adapter-type"
            data-testid="adapter-type-select"
            className="w-full rounded border border-input bg-background px-3 py-2 text-sm"
            value={adapterType}
            onChange={(e) => setAdapterType(e.target.value as AdapterType)}
          >
            <option value="physical-table">{t('dataSource.physical')}</option>
            <option value="external-rest">{t('dataSource.rest')}</option>
            <option value="external-jdbc">{t('dataSource.jdbc')}</option>
          </select>
          <p className="text-xs text-muted-foreground mt-1">{t('dataSource.help')}</p>
        </div>

        {fields.map((key) => (
          <div key={key}>
            <label htmlFor={`ds-${key}`} className="block text-sm font-medium mb-1">
              {t(`dataSource.field.${key}`)}
              {key === requiredKey && <span className="text-destructive"> *</span>}
            </label>
            <input
              id={`ds-${key}`}
              data-testid={`ds-field-${key}`}
              type="text"
              className="w-full rounded border border-input bg-background px-3 py-2 text-sm"
              value={values[key] ?? ''}
              onChange={(e) => setValues((prev) => ({ ...prev, [key]: e.target.value }))}
            />
            {key === 'credentialRef' && (
              <p className="text-xs text-muted-foreground mt-1">
                {t('dataSource.field.credentialRefHelp')}
              </p>
            )}
          </div>
        ))}

        <button
          type="button"
          data-testid="data-source-save"
          className="rounded bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
          disabled={missingRequired || save.isPending}
          onClick={() => save.mutate()}
        >
          {save.isPending ? t('common.saving') : t('common.save')}
        </button>
      </div>
    </section>
  )
}
