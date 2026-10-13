/**
 * useListViewState — the saved-view side of the record list core.
 *
 * Owns which view is applied and the per-view presentation state that is not in the list
 * params: the active view, the column chooser's override, density, group-by, the renderer
 * (`table` / `kanban` / `calendar` / `gallery`) with its per-type config, and the calendar's
 * visible month. Views come from two sources merged into one list:
 *
 * - personal views — `useSavedViews` (server-persisted preference, localStorage fallback);
 * - shared views — admin-published `list-views` rows (`useSharedListViews`), read-only, with a
 *   `shared:` id prefix. A shared view publishes its renderer; the viewer's own renderer choice
 *   for it (`useViewTypeOverrides`) wins and is persisted per user.
 *
 * Applying a view writes its filter / sort / pageSize / `view` params through the caller's
 * list params (`useListParams`), so URL-as-state lists keep deep links working. On a clean first
 * visit the default view (personal before shared) is applied; explicit params always win, and a
 * `view=<id>` param selects that view.
 *
 * The state is local to one collection: `RecordList` remounts its body when the collection
 * changes, so nothing here carries over to another collection.
 *
 * Moved out of `ObjectListPage` (record-stack convergence P9).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { useI18n } from '@/context/I18nContext'
import { currentMonthKey } from '@/components/CalendarMonthView'
import {
  useSavedViews,
  viewSorts,
  type SavedView,
  type SavedViewDensity,
  type SavedViewType,
} from '@/hooks/useSavedViews'
import { useSharedListViews } from '@/hooks/useSharedListViews'
import { useViewTypeOverrides } from '@/hooks/useViewTypeOverrides'
import { buildSortParam } from './listUrlState'
import { isSharedViewId } from './listViewMapping'
import type { UseListParamsReturn } from './useListParams'

export type ListTypeConfig = SavedView['typeConfig'] | null

export interface UseListViewStateOptions {
  collectionName: string
  /** The collection's id (schema.id) — shared views are keyed on it. */
  collectionId: string | undefined
  /** The list's params store (`useListParams`). */
  listParams: UseListParamsReturn
  /** Personal saved views + view selection. Off: no views, nothing auto-applied. */
  savedViews: boolean
  /** Include admin-published shared views (only with `savedViews`). */
  sharedViews: boolean
  /** Honour group-by. Off: `groupBy` is always null. */
  groupBy: boolean
  /** Honour alternate renderers. Off: `viewType` is always `table`. */
  altViews: boolean
}

export interface UseListViewStateReturn {
  /** Shared then personal views, as the ViewSelector lists them. */
  views: SavedView[]
  activeView: SavedView | null
  /** Column names from the chooser; wins over the active view until saved or cleared. */
  columnOverride: string[] | null
  setColumnOverride: (columns: string[] | null) => void
  density: SavedViewDensity
  setDensity: (density: SavedViewDensity) => void
  /** Field the table groups this page by (null = no grouping). */
  groupBy: string | null
  setGroupBy: (field: string | null) => void
  viewType: SavedViewType
  typeConfig: ListTypeConfig
  /** Switch renderer; on a shared view this is persisted as the viewer's override. */
  changeViewType: (viewType: SavedViewType) => void
  /** Change per-renderer config; persisted like `changeViewType`. */
  changeTypeConfig: (typeConfig: ListTypeConfig) => void
  /** The calendar's visible month (`YYYY-MM`). */
  calendarMonth: string
  setCalendarMonth: (month: string) => void
  /** Apply a view by id (null = no view: clears the view's params). */
  selectView: (viewId: string | null) => void
  /** Save the current state as a new personal view. */
  saveView: (name: string, visibleColumns: string[]) => void
  deleteView: (viewId: string) => void
  renameView: (viewId: string, name: string) => void
  setDefaultView: (viewId: string) => void
}

export function useListViewState({
  collectionName,
  collectionId,
  listParams,
  savedViews: savedViewsEnabled,
  sharedViews: sharedViewsEnabled,
  groupBy: groupByEnabled,
  altViews: altViewsEnabled,
}: UseListViewStateOptions): UseListViewStateReturn {
  const { t } = useI18n()
  const { searchParams, params, update } = listParams
  const { filters, sort, sorts, pageSize, viewId } = params

  // Preferences are fetched only for the features that use them: personal views need
  // `savedViews`, the per-user renderer overrides only apply to shared views.
  const savedViews = useSavedViews(collectionName, { enabled: savedViewsEnabled })
  const { sharedViews: publishedViews } = useSharedListViews(
    savedViewsEnabled && sharedViewsEnabled ? collectionName : undefined,
    collectionId
  )
  const {
    isLoaded: viewTypeOverridesLoaded,
    overrideFor: viewTypeOverrideFor,
    saveOverride: saveViewTypeOverride,
  } = useViewTypeOverrides(collectionName, {
    enabled: savedViewsEnabled && sharedViewsEnabled,
  })

  const sharedViews = useMemo(
    () => (savedViewsEnabled && sharedViewsEnabled ? publishedViews : []),
    [savedViewsEnabled, sharedViewsEnabled, publishedViews]
  )
  const personalViews = useMemo(
    () => (savedViewsEnabled ? savedViews.views : []),
    [savedViewsEnabled, savedViews.views]
  )
  const views = useMemo(() => [...sharedViews, ...personalViews], [sharedViews, personalViews])

  const [activeViewId, setActiveViewId] = useState<string | null>(null)
  const activeView = useMemo(
    () => views.find((v) => v.id === activeViewId) ?? null,
    [views, activeViewId]
  )
  const [columnOverride, setColumnOverride] = useState<string[] | null>(null)
  const [density, setDensity] = useState<SavedViewDensity>('normal')
  const [groupByState, setGroupBy] = useState<string | null>(null)
  const [viewTypeState, setViewType] = useState<SavedViewType>('table')
  const [typeConfigState, setTypeConfig] = useState<ListTypeConfig>(null)
  const [calendarMonth, setCalendarMonth] = useState<string>(() => currentMonthKey())

  const groupBy = groupByEnabled ? groupByState : null
  const viewType: SavedViewType = altViewsEnabled ? viewTypeState : 'table'
  const typeConfig = altViewsEnabled ? typeConfigState : null

  /** Applies a view onto the list params (deep links keep working); null clears it. */
  const applyView = useCallback(
    (view: SavedView | null) => {
      setActiveViewId(view?.id ?? null)
      setColumnOverride(null)
      setDensity(view?.density ?? 'normal')
      setGroupBy(view?.groupBy ?? null)
      // The user's own renderer choice for a shared view wins over the published one.
      const override = viewTypeOverrideFor(view?.id)
      setViewType(override?.viewType ?? view?.viewType ?? 'table')
      setTypeConfig(override?.typeConfig ?? view?.typeConfig ?? null)
      if (view) {
        update({
          filter: view.filters.length > 0 ? JSON.stringify(view.filters) : undefined,
          sort: buildSortParam(viewSorts(view)),
          pageSize: String(view.pageSize),
          page: undefined,
          view: view.id,
        })
      } else {
        update({
          filter: undefined,
          sort: undefined,
          pageSize: undefined,
          page: undefined,
          view: undefined,
        })
      }
    },
    [update, viewTypeOverrideFor]
  )

  const selectView = useCallback(
    (id: string | null) => {
      applyView(id ? (views.find((v) => v.id === id) ?? null) : null)
    },
    [applyView, views]
  )

  /**
   * On a shared view the toolbar renderer switch is the user's personal override of what the
   * admin published — persisted per user so it survives a reload; the published row is never
   * touched. Elsewhere it stays page state (a personal view records it when saved).
   */
  const persistViewTypeOverride = useCallback(
    (nextViewType: SavedViewType, nextTypeConfig: ListTypeConfig) => {
      if (!activeViewId || !isSharedViewId(activeViewId)) return
      saveViewTypeOverride(activeViewId, {
        viewType: nextViewType,
        typeConfig: nextTypeConfig ?? undefined,
      })
    },
    [activeViewId, saveViewTypeOverride]
  )

  const changeViewType = useCallback(
    (nextViewType: SavedViewType) => {
      setViewType(nextViewType)
      persistViewTypeOverride(nextViewType, typeConfigState)
    },
    [persistViewTypeOverride, typeConfigState]
  )

  const changeTypeConfig = useCallback(
    (nextTypeConfig: ListTypeConfig) => {
      setTypeConfig(nextTypeConfig)
      persistViewTypeOverride(viewTypeState, nextTypeConfig)
    },
    [persistViewTypeOverride, viewTypeState]
  )

  const saveView = useCallback(
    (name: string, visibleColumns: string[]) => {
      savedViews.saveView(name, {
        filters,
        sortField: sort?.field ?? null,
        sortDirection: sort?.direction ?? 'asc',
        sorts,
        density,
        groupBy,
        viewType,
        typeConfig: typeConfig ?? undefined,
        visibleColumns,
        pageSize,
        isDefault: false,
      })
      setColumnOverride(null)
    },
    [savedViews, filters, sort, sorts, density, groupBy, viewType, typeConfig, pageSize]
  )

  const rejectSharedViewEdit = useCallback(() => {
    toast.error(t('savedViews.sharedReadOnly', 'Shared views are managed in Setup → List Views'))
  }, [t])

  const deleteView = useCallback(
    (id: string) => {
      if (isSharedViewId(id)) return rejectSharedViewEdit()
      if (activeViewId === id) setActiveViewId(null)
      savedViews.deleteView(id)
    },
    [savedViews, activeViewId, rejectSharedViewEdit]
  )

  const renameView = useCallback(
    (id: string, name: string) => {
      if (isSharedViewId(id)) return rejectSharedViewEdit()
      savedViews.renameView(id, name)
    },
    [savedViews, rejectSharedViewEdit]
  )

  const setDefaultView = useCallback(
    (id: string) => {
      if (isSharedViewId(id)) return rejectSharedViewEdit()
      savedViews.setDefaultView(id)
    },
    [savedViews, rejectSharedViewEdit]
  )

  // Auto-apply the default view on a clean first visit — explicit list params always win.
  // A `view=<id>` param selects that view (its state params are already present when shared
  // from an applied view; selecting keeps columns/density in sync).
  const appliedDefaultRef = useRef(false)
  useEffect(() => {
    if (appliedDefaultRef.current) return
    // Wait for the user's renderer overrides, or a shared board would flash in
    // for one paint before the user's own choice of table replaced it.
    if (!viewTypeOverridesLoaded) return
    if (viewId) {
      const linked = views.find((v) => v.id === viewId)
      if (linked) {
        appliedDefaultRef.current = true
        const override = viewTypeOverrideFor(linked.id)
        // Deferred: no synchronous setState inside the effect (cascading-render rule).
        const timer = setTimeout(() => {
          setActiveViewId(linked.id)
          setDensity(linked.density ?? 'normal')
          setGroupBy(linked.groupBy ?? null)
          setViewType(override?.viewType ?? linked.viewType ?? 'table')
          setTypeConfig(override?.typeConfig ?? linked.typeConfig ?? null)
        }, 0)
        return () => clearTimeout(timer)
      }
      return
    }
    const hasState = ['page', 'pageSize', 'sort', 'filter'].some((k) => searchParams.has(k))
    if (hasState) {
      appliedDefaultRef.current = true
      return
    }
    const def = personalViews.find((v) => v.isDefault) ?? sharedViews.find((v) => v.isDefault)
    if (def) {
      appliedDefaultRef.current = true
      // Deferred so the apply (setState + params write) never runs synchronously inside
      // the effect — avoids a cascading render on first mount.
      const timer = setTimeout(() => applyView(def), 0)
      return () => clearTimeout(timer)
    }
  }, [
    personalViews,
    sharedViews,
    searchParams,
    applyView,
    viewId,
    views,
    viewTypeOverridesLoaded,
    viewTypeOverrideFor,
  ])

  return {
    views,
    activeView,
    columnOverride,
    setColumnOverride,
    density,
    setDensity,
    groupBy,
    setGroupBy,
    viewType,
    typeConfig,
    changeViewType,
    changeTypeConfig,
    calendarMonth,
    setCalendarMonth,
    selectView,
    saveView,
    deleteView,
    renameView,
    setDefaultView,
  }
}
