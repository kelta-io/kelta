/**
 * ResourceListPage
 *
 * Admin Resource Browser (`/:tenantSlug/resources/:collection`) record list. A thin
 * `variant="admin"` wrapper over the shared `RecordList` core (`components/record/list/`), which
 * owns the data, the list state, saved views, the table and alternate renderers, the filter
 * builder, selection, bulk actions, inline edit, mass edit, export/import and the loading /
 * permission / error states.
 *
 * This wrapper only maps the route onto the core: the collection, the admin record routes, the
 * admin feature set, URL-as-state (`urlState="sync"` — the same shareable `filter` / `sort` /
 * `page` / `pageSize` / `view` query string as the end-user list, so a related list's "View All"
 * still opens filtered), the permission gate's way back to the Resource Browser, and the admin
 * header (breadcrumb, title and Create Record — so the toolbar's own title and New are hidden).
 *
 * Record-stack convergence P10.
 */

import React, { useMemo } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useI18n } from '@/context/I18nContext'
import { getTenantSlug } from '@/context/TenantContext'
import { RecordList } from '@/components/record/list/RecordList'
import type { RecordListChromeContext } from '@/components/record/list/RecordList'
import type { RecordListFeatures } from '@/components/record/list/useRecordListModel'
import { recordRoutes } from '@/utils/recordRoutes'

/** The admin list's feature set (module-level so the core sees a stable object). */
const ADMIN_LIST_FEATURES: RecordListFeatures = {
  savedViews: true,
  sharedViews: false,
  columnChooser: true,
  density: false,
  groupBy: false,
  altViews: true,
  massEdit: true,
  import: true,
  export: true,
  quickActions: false,
  inlineEdit: true,
  filterBuilder: true,
  idColumn: true,
  summaryStats: true,
}

export interface ResourceListPageProps {
  /** Optional test ID for testing */
  testId?: string
}

export function ResourceListPage({
  testId = 'resource-list-page',
}: ResourceListPageProps): React.ReactElement {
  const { t } = useI18n()
  const { collection: collectionName } = useParams<{ collection: string }>()
  const tenantSlug = getTenantSlug()
  const routes = useMemo(() => recordRoutes('admin', tenantSlug), [tenantSlug])
  const browserPath = `/${tenantSlug}/resources`

  const header = ({ collectionLabel, model }: RecordListChromeContext) => (
    <header className="flex flex-wrap items-start justify-between gap-4 max-md:flex-col max-md:items-stretch">
      <div className="flex flex-col gap-1">
        <nav
          className="flex items-center gap-1 text-sm text-muted-foreground"
          aria-label="Breadcrumb"
        >
          <Link to={browserPath} className="text-primary no-underline hover:underline">
            {t('resources.title')}
          </Link>
          <span className="text-muted-foreground/60" aria-hidden="true">
            /
          </span>
          <span>{collectionLabel}</span>
        </nav>
        <h1 className="m-0 text-2xl font-semibold text-foreground max-md:text-xl">
          {collectionLabel}
        </h1>
      </div>
      {model.permissions.canCreate && (
        <button
          type="button"
          className="inline-flex cursor-pointer items-center justify-center rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors duration-200 hover:bg-primary/90 focus:outline-2 focus:outline-offset-2 focus:outline-primary active:bg-primary/80 max-md:w-full"
          onClick={model.goToNew}
          data-testid="create-record-button"
        >
          {t('resources.createRecord')}
        </button>
      )}
    </header>
  )

  return (
    <RecordList
      variant="admin"
      collectionName={collectionName || ''}
      tenantSlug={tenantSlug}
      routes={routes}
      features={ADMIN_LIST_FEATURES}
      urlState="sync"
      permissionBackPath={browserPath}
      header={header}
      toolbarHeading={false}
      testId={testId}
    />
  )
}
