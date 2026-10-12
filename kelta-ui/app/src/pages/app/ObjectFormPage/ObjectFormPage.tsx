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
 * (e.g. `?order_ref=<id>` from a related list), and the end-user breadcrumb.
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

  const routes = useMemo<RecordFormRoutes>(
    () => ({
      list: `${basePath}/o/${collectionName}`,
      detail: (id: string) => `${basePath}/o/${collectionName}/${id}`,
    }),
    [basePath, collectionName]
  )

  // Extract query parameter defaults for new records (e.g. ?order_ref=<id>)
  const queryDefaults = useMemo(() => {
    if (!isNew) return undefined
    const params: Record<string, string> = {}
    searchParams.forEach((value, key) => {
      params[key] = value
    })
    return Object.keys(params).length > 0 ? params : undefined
  }, [isNew, searchParams])

  const breadcrumb = ({ collectionLabel, isNew: creating }: RecordFormChromeContext) => (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          <BreadcrumbLink asChild>
            <Link to={`${basePath}/home`}>Home</Link>
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
          <BreadcrumbPage>{creating ? 'New' : 'Edit'}</BreadcrumbPage>
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
      breadcrumb={breadcrumb}
    />
  )
}
