/**
 * Pure mapping from bootstrap menu config to end-user navigation tabs.
 *
 * Two kinds of menu item are surfaced in the top nav:
 *  - collection lists — path `/resources/<collection>` → routes to `…/o/<collection>`
 *  - custom pages      — path `/p/<slug>` or `/app/p/<slug>` → routes to `…/p/<slug>`
 *
 * Any other path is ignored. Kept as a standalone, dependency-free module so it is unit-testable
 * without pulling the shell's React/context graph.
 */
import type { NavTab } from './TopNavBar'
import type { MenuConfig, MenuItemConfig } from '@/types/config'

/** Matches a custom-page menu path: `/p/<slug>` or `/app/p/<slug>` → captures the slug. */
const PAGE_PATH_RE = /^\/(?:app\/)?p\/([^/]+)/
/** Matches an analytics menu path: `/dashboards/<id>` or `/reports/<id>` (± `/app` prefix). */
const DASHBOARD_PATH_RE = /^\/(?:app\/)?dashboards\/([^/]+)/
const REPORT_PATH_RE = /^\/(?:app\/)?reports\/([^/]+)/
/** Matches the chat console (telehealth slice 3): `/chat` or `/app/chat`. */
const CHAT_PATH_RE = /^\/(?:app\/)?chat\/?$/

/**
 * Parse a `/resources/<collection>[?query]` menu path into the collection name and its
 * query string (without the leading `?`). Returns `null` when there's no collection segment
 * (e.g. `/resources/` or `/resources/?view=x`). The query string is kept verbatim — deep-link
 * params like `view=shared:<id>`, `filter`, `sort`, `pageSize` are `ObjectListPage`'s to parse.
 */
export function parseResourcePath(path: string): { collectionName: string; query?: string } | null {
  if (!path.startsWith('/resources/')) return null
  const rest = path.slice('/resources/'.length)
  const queryIndex = rest.indexOf('?')
  const withoutQuery = queryIndex === -1 ? rest : rest.slice(0, queryIndex)
  const query = queryIndex === -1 ? undefined : rest.slice(queryIndex + 1)
  const collectionName = withoutQuery.split('/')[0]
  if (!collectionName) return null
  return query ? { collectionName, query } : { collectionName }
}

/**
 * Map a single menu item to a nav tab, or `null` if its path is not a surfaceable target.
 * An item with children becomes a `group` tab (a dropdown in the top nav); its own path,
 * if any, is ignored — group headers organize, they don't navigate. Groups whose children
 * all fail to map are dropped entirely.
 */
export function menuItemToTab(item: MenuItemConfig): NavTab | null {
  if (item.children && item.children.length > 0) {
    const children = item.children
      .map((child) => menuItemToTab(child))
      .filter((tab): tab is NavTab => tab !== null && tab.kind !== 'group')
    if (children.length === 0) return null
    return {
      key: `group:${item.id || item.label}`,
      kind: 'group',
      target: '',
      label: item.label,
      icon: item.icon,
      children,
    }
  }

  const path = item.path
  if (!path) return null

  if (path.startsWith('/resources/')) {
    const parsed = parseResourcePath(path)
    if (!parsed) return null
    const tab: NavTab = {
      key: path,
      kind: 'collection',
      target: parsed.collectionName,
      label: item.label || parsed.collectionName,
      icon: item.icon,
    }
    if (parsed.query) tab.query = parsed.query
    return tab
  }

  const pageMatch = PAGE_PATH_RE.exec(path)
  if (pageMatch) {
    const slug = pageMatch[1]
    return { key: path, kind: 'page', target: slug, label: item.label || slug, icon: item.icon }
  }

  const dashboardMatch = DASHBOARD_PATH_RE.exec(path)
  if (dashboardMatch) {
    const id = dashboardMatch[1]
    return { key: path, kind: 'dashboard', target: id, label: item.label || id, icon: item.icon }
  }

  const reportMatch = REPORT_PATH_RE.exec(path)
  if (reportMatch) {
    const id = reportMatch[1]
    return { key: path, kind: 'report', target: id, label: item.label || id, icon: item.icon }
  }

  if (CHAT_PATH_RE.test(path)) {
    return { key: path, kind: 'chat', target: '', label: item.label || 'Chat', icon: item.icon }
  }

  return null
}

/**
 * The app list (apps/nav v2): menus with `active !== false`, sorted by `displayOrder`
 * then name. An "app" IS a ui-menu — the shell renders one app's items at a time.
 */
export function activeMenus(menus: MenuConfig[] | undefined): MenuConfig[] {
  if (!menus) return []
  return menus
    .filter((m) => m.active !== false)
    .sort(
      (a, b) =>
        (a.displayOrder ?? 0) - (b.displayOrder ?? 0) || (a.name || '').localeCompare(b.name || '')
    )
}

/**
 * Resolve the app to render: the user's stored preference when it still exists, else
 * the `isDefault` app, else the first by display order. Null when there are no apps.
 */
export function resolveActiveMenu(
  menus: MenuConfig[],
  preferredId: string | null | undefined
): MenuConfig | null {
  if (menus.length === 0) return null
  if (preferredId) {
    const preferred = menus.find((m) => m.id === preferredId)
    if (preferred) return preferred
  }
  return menus.find((m) => m.isDefault) ?? menus[0]
}

/**
 * Extract navigation tabs (collections + custom pages) from menu config, preserving menu and
 * `displayOrder` order (the bootstrap loader already sorts items by `displayOrder`).
 * Pure function — safe for React compiler optimization.
 */
export function buildNavTabs(menus: MenuConfig[] | undefined): NavTab[] {
  if (!menus) return []

  const tabs: NavTab[] = []
  for (const menu of menus) {
    for (const item of menu.items ?? []) {
      const tab = menuItemToTab(item)
      if (tab) tabs.push(tab)
    }
  }
  return tabs
}
