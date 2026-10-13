/**
 * ObjectFormPage
 *
 * End-user (`/:tenantSlug/app/o/:collection/new` and `…/:id/edit`) create/edit
 * page. A thin `variant="enduser"` wrapper over the shared `RecordForm` core
 * (`components/record/form/`), which owns the schema-driven form, record types,
 * picklist/lookup options, client layout rules, permissions and save.
 *
 * This wrapper only maps the route onto the core: the collection + record id,
 * the end-user navigation targets, the create-mode prefill from query params
 * (e.g. `?order_ref=<id>` from a related list), clone mode — `?clone=<id>` (the
 * detail page's Clone action) seeds the new record from that record through the
 * core's `cloneFromId` — and the end-user breadcrumb.
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
import { useI18n } from '@/context/I18nContext'
import { RecordForm } from '@/components/record/form/RecordForm'
import type { RecordFormChromeContext, RecordFormRoutes } from '@/components/record/form/RecordForm'
import { recordRoutes } from '@/utils/recordRoutes'

/** The query param that turns the create form into a clone of an existing record. */
const CLONE_PARAM = 'clone'

export function ObjectFormPage(): React.ReactElement {
  const {
    tenantSlug,
    collection: collectionName,
    id: recordId,
  } = useParams<{
    tenantSlug: string
    collection: string
    id?: string
  }>()
  const basePath = `/${tenantSlug}/app`
  const isNew = !recordId
  const [searchParams] = useSearchParams()
  const { t } = useI18n()

  const routes = useMemo<RecordFormRoutes>(() => {
    const enduser = recordRoutes('enduser', tenantSlug ?? '')
    return {
      list: enduser.list(collectionName ?? ''),
      detail: (id: string) => enduser.detail(collectionName ?? '', id),
    }
  }, [tenantSlug, collectionName])

  // Clone applies to create only (the core ignores it on edit).
  const cloneSourceId = (isNew && searchParams.get(CLONE_PARAM)) || undefined

  // Extract query parameter defaults for new records (e.g. ?order_ref=<id>)
  const queryDefaults = useMemo(() => {
    if (!isNew) return undefined
    const params: Record<string, string> = {}
    searchParams.forEach((value, key) => {
      if (key !== CLONE_PARAM) params[key] = value
    })
    return Object.keys(params).length > 0 ? params : undefined
  }, [isNew, searchParams])

  const breadcrumb = ({ collectionLabel, isNew: creating, isClone }: RecordFormChromeContext) => (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          <BreadcrumbLink asChild>
            <Link to={`${basePath}/home`}>{t('navigation.home')}</Link>
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
            {t(
              isClone
                ? 'recordForm.breadcrumbClone'
                : creating
                  ? 'recordForm.breadcrumbNew'
                  : 'recordForm.breadcrumbEdit'
            )}
          </BreadcrumbPage>
        </BreadcrumbItem>
      </BreadcrumbList>
    </Breadcrumb>
  )

  return (
    <RecordForm
      variant="enduser"
      collectionName={collectionName || ''}
      recordId={recordId}
      routes={routes}
      prefill={queryDefaults}
      cloneFromId={cloneSourceId}
      breadcrumb={breadcrumb}
    />
  )
}
