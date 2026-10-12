/**
 * ObjectListPage
 *
 * End-user (`/:tenantSlug/app/o/:collection`) record list. A thin `variant="enduser"` wrapper
 * over the shared `RecordList` core (`components/record/list/`), which owns the data, the list
 * state, saved/shared views, the table and alternate renderers, selection, bulk actions, inline
 * edit, export/import and the loading / permission / error states.
 *
 * This wrapper only maps the route onto the core: the collection, the end-user record routes,
 * the end-user feature set, URL-as-state (`urlState="sync"`: filter / sort / page / pageSize /
 * view live in the query string), the permission gate's way back to the app home, and the
 * end-user breadcrumb.
 */

import React, { useMemo } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb'
import { RecordList } from '@/components/record/list/RecordList'
import type { RecordListChromeContext } from '@/components/record/list/RecordList'
import type { RecordListFeatures } from '@/components/record/list/useRecordListModel'
import { recordRoutes } from '@/utils/recordRoutes'

/** Everything the end-user list offers (module-level so the core sees a stable object). */
const ENDUSER_LIST_FEATURES: RecordListFeatures = {
  savedViews: true,
  sharedViews: true,
  columnChooser: true,
  density: true,
  groupBy: true,
  altViews: true,
  massEdit: true,
  import: true,
  export: true,
  quickActions: true,
  inlineEdit: true,
}

export function ObjectListPage(): React.ReactElement {
  const { tenantSlug, collection: collectionName } = useParams<{
    tenantSlug: string
    collection: string
  }>()
  const basePath = `/${tenantSlug}/app`
  const routes = useMemo(() => recordRoutes('enduser', tenantSlug ?? ''), [tenantSlug])

  const breadcrumb = ({ collectionLabel }: RecordListChromeContext) => (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          <BreadcrumbLink asChild>
            <Link to={`${basePath}/home`}>Home</Link>
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbSeparator />
        <BreadcrumbItem>
          <BreadcrumbPage>{collectionLabel}</BreadcrumbPage>
        </BreadcrumbItem>
      </BreadcrumbList>
    </Breadcrumb>
  )

  return (
    <RecordList
      variant="enduser"
      collectionName={collectionName || ''}
      tenantSlug={tenantSlug || ''}
      routes={routes}
      features={ENDUSER_LIST_FEATURES}
      urlState="sync"
      permissionBackPath={`${basePath}/home`}
      breadcrumb={breadcrumb}
    />
  )
}
