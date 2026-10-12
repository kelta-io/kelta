/**
 * ResourceFormPage
 *
 * Admin (`/:tenant/resources/:collection/new` and `…/:id/edit`) create/edit page. A thin
 * `variant="admin"` wrapper over the shared `RecordForm` core (`components/record/form/`),
 * which owns the schema-driven form, record types, picklist/lookup options, client
 * validation and layout rules, permissions, plugin field renderers, save (If-Match +
 * conflict prompt) and the loading / error states — the same core `ObjectFormPage` wraps.
 *
 * This wrapper only maps the route onto the core and adds the admin chrome:
 * - the `/resources/...` navigation targets (list, and the record's detail page);
 * - clone mode — `?clone=<id>` (the detail page's Clone action) seeds the new record from
 *   that record through the core's `cloneFromId`, with a "cloning from" notice linking to it;
 * - the admin breadcrumb, the record id under the title on edit, and field type hints.
 *
 * Record-stack convergence P6.
 */

import React, { useMemo } from 'react'
import { useParams, useSearchParams, Link } from 'react-router-dom'
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb'
import { RecordForm } from '@/components/record/form/RecordForm'
import type { RecordFormChromeContext, RecordFormRoutes } from '@/components/record/form/RecordForm'
import { useI18n } from '@/context/I18nContext'
import { getTenantSlug } from '@/context/TenantContext'
import { recordRoutes } from '@/utils/recordRoutes'

/**
 * Props for ResourceFormPage component
 */
export interface ResourceFormPageProps {
  /** Collection name (optional — defaults to the `:collection` route param) */
  collectionName?: string
  /** Resource ID for edit mode (optional — defaults to the `:id` route param) */
  resourceId?: string
  /** Optional test ID for testing */
  testId?: string
}

export function ResourceFormPage({
  collectionName: propCollectionName,
  resourceId: propResourceId,
  testId = 'resource-form-page',
}: ResourceFormPageProps): React.ReactElement {
  const { collection: routeCollection, id: routeResourceId } = useParams<{
    collection: string
    id?: string
  }>()
  const { t } = useI18n()
  const [searchParams] = useSearchParams()

  const collectionName = propCollectionName || routeCollection || ''
  const resourceId = propResourceId || routeResourceId
  const tenantSlug = getTenantSlug()
  const basePath = `/${tenantSlug}/resources`
  // Clone applies to create only (the core ignores it on edit).
  const cloneSourceId = (!resourceId && searchParams.get('clone')) || undefined

  const routes = useMemo<RecordFormRoutes>(() => {
    const admin = recordRoutes('admin', tenantSlug)
    return {
      list: admin.list(collectionName),
      detail: (id: string) => admin.detail(collectionName, id),
    }
  }, [tenantSlug, collectionName])

  const breadcrumb = ({ collectionLabel, isNew, isClone }: RecordFormChromeContext) => (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          <BreadcrumbLink asChild>
            <Link to={basePath}>{t('resources.title')}</Link>
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbSeparator />
        <BreadcrumbItem>
          <BreadcrumbLink asChild>
            <Link to={routes.list}>{collectionLabel}</Link>
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbSeparator />
        <BreadcrumbItem>
          <BreadcrumbPage>
            {isClone
              ? t('resources.cloneRecord', { collection: collectionLabel })
              : isNew
                ? t('resources.createRecord')
                : t('resources.editRecord')}
          </BreadcrumbPage>
        </BreadcrumbItem>
      </BreadcrumbList>
    </Breadcrumb>
  )

  const banner = ({ isClone }: RecordFormChromeContext) => {
    if (resourceId) {
      return (
        <p className="m-0 font-mono text-sm text-muted-foreground" data-testid="resource-id">
          ID: {resourceId}
        </p>
      )
    }
    if (isClone && cloneSourceId) {
      return (
        <div
          className="flex items-center gap-2 rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
          data-testid="clone-banner"
        >
          <span>
            {t('resources.cloningFrom')} <span className="font-mono">{cloneSourceId}</span>
          </span>
          <Link
            to={routes.detail(cloneSourceId)}
            className="font-medium text-primary no-underline hover:underline"
          >
            {t('resources.viewSource')}
          </Link>
        </div>
      )
    }
    return null
  }

  return (
    <RecordForm
      variant="admin"
      collectionName={collectionName}
      recordId={resourceId}
      routes={routes}
      cloneFromId={cloneSourceId}
      breadcrumb={breadcrumb}
      banner={banner}
      showFieldTypeHints
      testId={testId}
    />
  )
}

export default ResourceFormPage
