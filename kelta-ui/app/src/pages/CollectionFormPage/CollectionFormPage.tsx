/**
 * CollectionFormPage Component
 *
 * Page for creating or editing a collection.
 * Wraps the CollectionForm component with page layout.
 */

import React, { useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useApi } from '../../context/ApiContext'
import { CollectionForm, LoadingSpinner, ErrorMessage } from '../../components'
import type {
  Collection,
  CollectionFormData,
  AvailableField,
} from '../../components/CollectionForm/CollectionForm'
import type { CollectionDefinition } from '@kelta/sdk'

/**
 * Props for CollectionFormPage component
 */
export interface CollectionFormPageProps {
  /** Optional test ID for testing */
  testId?: string
}

/**
 * CollectionFormPage Component
 *
 * Page for creating or editing collections.
 */
export function CollectionFormPage({
  testId = 'collection-form-page',
}: CollectionFormPageProps): React.ReactElement {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { t } = useI18n()
  const { apiClient, keltaClient } = useApi()

  const isEditMode = Boolean(id)

  /** API response includes fields for the collection */
  interface CollectionWithFields extends Collection {
    fields?: Array<{
      id: string
      name: string
      displayName: string
      active: boolean
      type?: string
      referenceTarget?: string
      relationshipType?: string
    }>
  }

  // Fetch existing collection if in edit mode.
  // Fields are separate records in the "fields" collection, so we use
  // ?include=fields to fetch them via the JSON:API include mechanism.
  const {
    data: collectionData,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['collection', id],
    queryFn: async () => {
      const raw = await apiClient.get<{
        data: {
          type: string
          id: string
          attributes: Record<string, unknown>
          relationships?: Record<string, { data?: { type: string; id: string } | null }>
        }
        included?: { type: string; id: string; attributes: Record<string, unknown> }[]
      }>(`/api/collections/${id}?include=fields`)

      const result: CollectionWithFields = {
        id: raw.data.id,
        ...(raw.data.attributes as Omit<CollectionWithFields, 'id' | 'fields'>),
      } as CollectionWithFields

      // `displayFieldId` is a lookup (relationship), not an attribute — surface it so the
      // form's Display Field select reflects the saved value and can round-trip it.
      result.displayFieldId = raw.data.relationships?.displayFieldId?.data?.id ?? undefined

      if (raw.included && raw.included.length > 0) {
        result.fields = raw.included
          .filter((r) => r.type === 'fields')
          .map(
            (r) =>
              ({ id: r.id, ...r.attributes }) as {
                id: string
                name: string
                displayName: string
                active: boolean
              }
          )
      }

      return result
    },
    enabled: isEditMode,
  })

  // Extract collection (without fields) for the form and available fields for dropdown
  const collection = collectionData
  const availableFields: AvailableField[] = React.useMemo(() => {
    if (!collectionData?.fields) return []
    return collectionData.fields
      .filter((f) => f.active)
      .map((f) => ({
        id: f.id,
        name: f.name,
        displayName: f.displayName,
        type: f.type,
        referenceTarget: f.referenceTarget,
        relationshipType: f.relationshipType,
      }))
  }, [collectionData])

  // Handle form submission
  const handleSubmit = useCallback(
    async (data: CollectionFormData) => {
      if (isEditMode && id) {
        // Update existing collection
        const requestData: Record<string, unknown> = {
          name: data.name,
          displayName: data.displayName,
          description: data.description || '',
          active: data.active,
          trackHistory: data.trackHistory,
          captureGeo: data.captureGeo,
          ownerField: data.ownerField ?? null,
          ownerScope: data.ownerScope,
          ownerScopeReads: data.ownerScopeReads,
        }
        // Include displayFieldId — empty string clears it, undefined means no change
        if (data.displayFieldId !== undefined) {
          requestData.displayFieldId = data.displayFieldId || ''
        }
        await keltaClient.admin.collections.update(
          id,
          requestData as unknown as CollectionDefinition
        )
        navigate(`/${getTenantSlug()}/collections/${id}`)
      } else {
        // Create new collection
        const requestData = {
          name: data.name,
          description: data.description || '',
          ...(data.ownerScope !== 'NONE' && {
            ownerField: data.ownerField,
            ownerScope: data.ownerScope,
            ownerScopeReads: data.ownerScopeReads,
          }),
        }

        const created = await keltaClient.admin.collections.create(
          requestData as unknown as CollectionDefinition
        )

        console.log('Created collection:', created)
        navigate(`/${getTenantSlug()}/collections/${created.id!}`)
      }
    },
    [keltaClient, navigate, isEditMode, id]
  )

  // Handle cancel
  const handleCancel = useCallback(() => {
    if (isEditMode && id) {
      navigate(`/${getTenantSlug()}/collections/${id}`)
    } else {
      navigate(`/${getTenantSlug()}/collections`)
    }
  }, [navigate, isEditMode, id])

  // Show loading state while fetching collection in edit mode
  if (isEditMode && isLoading) {
    return (
      <div className="flex h-full flex-col overflow-y-auto p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Show error state if fetch failed
  if (isEditMode && error) {
    return (
      <div className="flex h-full flex-col overflow-y-auto p-8" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error(t('errors.generic'))}
          onRetry={() => navigate(`/${getTenantSlug()}/collections`)}
        />
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col overflow-y-auto p-8" data-testid={testId}>
      <header className="mb-8">
        <h1 className="m-0 text-[1.75rem] font-semibold text-foreground">
          {isEditMode ? t('collections.editCollection') : t('collections.createCollection')}
        </h1>
      </header>

      <div className="w-full flex-1">
        <CollectionForm
          collection={collection}
          availableFields={availableFields}
          onSubmit={handleSubmit}
          onCancel={handleCancel}
        />
      </div>
    </div>
  )
}

export default CollectionFormPage
