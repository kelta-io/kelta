/**
 * Unit tests for the menu-config → nav-tab mapping (collections + custom pages).
 */
import { describe, it, expect } from 'vitest'
import {
  activeMenus,
  buildNavTabs,
  menuItemToTab,
  parseResourcePath,
  resolveActiveMenu,
} from './navTabs'
import type { MenuConfig } from '@/types/config'

describe('menuItemToTab', () => {
  it('maps a /resources/<collection> item to a collection tab', () => {
    expect(menuItemToTab({ id: '1', label: 'Titles', path: '/resources/titles' })).toEqual({
      key: '/resources/titles',
      kind: 'collection',
      target: 'titles',
      label: 'Titles',
      icon: undefined,
    })
  })

  it('maps a /app/p/<slug> item to a page tab', () => {
    expect(
      menuItemToTab({
        id: '2',
        label: 'Dashboard',
        path: '/app/p/dashboard',
        icon: 'LayoutDashboard',
      })
    ).toEqual({
      key: '/app/p/dashboard',
      kind: 'page',
      target: 'dashboard',
      label: 'Dashboard',
      icon: 'LayoutDashboard',
    })
  })

  it('also accepts the short /p/<slug> page form', () => {
    const tab = menuItemToTab({ id: '3', label: 'Reports', path: '/p/reports' })
    expect(tab).toMatchObject({ kind: 'page', target: 'reports' })
  })

  it('falls back to the collection name / slug when label is empty', () => {
    expect(menuItemToTab({ id: '4', label: '', path: '/resources/providers' })?.label).toBe(
      'providers'
    )
    expect(menuItemToTab({ id: '5', label: '', path: '/p/home' })?.label).toBe('home')
  })

  it('ignores items with no path or an unsupported path', () => {
    expect(menuItemToTab({ id: '6', label: 'External', path: 'https://example.com' })).toBeNull()
    expect(menuItemToTab({ id: '7', label: 'Settings', path: '/settings' })).toBeNull()
    expect(menuItemToTab({ id: '8', label: 'No path' })).toBeNull()
    expect(menuItemToTab({ id: '9', label: 'Empty resource', path: '/resources/' })).toBeNull()
  })

  it('keeps a query string on a /resources/<collection>?... item, target stays the collection', () => {
    expect(
      menuItemToTab({
        id: '10',
        label: 'Shared queue',
        path: '/resources/tasks?view=shared:abc&pageSize=50',
      })
    ).toEqual({
      key: '/resources/tasks?view=shared:abc&pageSize=50',
      kind: 'collection',
      target: 'tasks',
      label: 'Shared queue',
      icon: undefined,
      query: 'view=shared:abc&pageSize=50',
    })
  })

  it('omits query on a plain /resources/<collection> item — behaves exactly as before', () => {
    const tab = menuItemToTab({ id: '11', label: 'Tasks', path: '/resources/tasks' })
    expect(tab?.query).toBeUndefined()
  })
})

describe('parseResourcePath', () => {
  it('returns the collection name with no query for a plain path', () => {
    expect(parseResourcePath('/resources/tasks')).toEqual({ collectionName: 'tasks' })
  })

  it('splits the collection name from its query string', () => {
    expect(parseResourcePath('/resources/tasks?view=shared:abc&pageSize=100')).toEqual({
      collectionName: 'tasks',
      query: 'view=shared:abc&pageSize=100',
    })
  })

  it('returns null for a non-resource path', () => {
    expect(parseResourcePath('/p/dashboard')).toBeNull()
  })

  it('returns null when the collection segment is empty, with or without a query', () => {
    expect(parseResourcePath('/resources/')).toBeNull()
    expect(parseResourcePath('/resources/?view=shared:abc')).toBeNull()
  })
})

describe('buildNavTabs', () => {
  it('returns [] for undefined menus', () => {
    expect(buildNavTabs(undefined)).toEqual([])
  })

  it('flattens collection and page items across menus, preserving order', () => {
    const menus: MenuConfig[] = [
      {
        id: 'm1',
        name: 'Main',
        items: [
          { id: 'a', label: 'Titles', path: '/resources/titles' },
          { id: 'b', label: 'Dashboard', path: '/app/p/dashboard' },
          { id: 'c', label: 'Junk', path: '/nope' },
          { id: 'd', label: 'Providers', path: '/resources/providers' },
        ],
      },
    ]
    const tabs = buildNavTabs(menus)
    expect(tabs.map((t) => `${t.kind}:${t.target}`)).toEqual([
      'collection:titles',
      'page:dashboard',
      'collection:providers',
    ])
  })

  it('tolerates a menu with no items', () => {
    expect(buildNavTabs([{ id: 'm', name: 'Empty', items: [] }])).toEqual([])
  })

  it('maps dashboard and report paths to analytics tabs', () => {
    expect(menuItemToTab({ id: 'd', label: 'Sales', path: '/dashboards/dash-1' })).toEqual({
      key: '/dashboards/dash-1',
      kind: 'dashboard',
      target: 'dash-1',
      label: 'Sales',
      icon: undefined,
    })
    expect(menuItemToTab({ id: 'r', label: 'Pipeline', path: '/app/reports/rep-9' })).toEqual({
      key: '/app/reports/rep-9',
      kind: 'report',
      target: 'rep-9',
      label: 'Pipeline',
      icon: undefined,
    })
  })

  it('still ignores unrelated paths after the analytics kinds', () => {
    expect(menuItemToTab({ id: 'x', label: 'Nope', path: '/dashboards' })).toBeNull()
    expect(menuItemToTab({ id: 'y', label: 'Nope', path: '/reporting/thing' })).toBeNull()
  })
})

describe('activeMenus (apps/nav v2)', () => {
  const menus: MenuConfig[] = [
    { id: 'b', name: 'Beta', items: [], displayOrder: 2 },
    { id: 'a', name: 'Alpha', items: [], displayOrder: 1 },
    { id: 'c', name: 'Closed', items: [], active: false },
  ]

  it('filters inactive menus and sorts by displayOrder', () => {
    expect(activeMenus(menus).map((m) => m.id)).toEqual(['c', 'a', 'b'].slice(1))
  })

  it('treats absent active as active and absent displayOrder as 0', () => {
    const out = activeMenus([
      { id: 'x', name: 'X', items: [] },
      { id: 'y', name: 'Y', items: [], displayOrder: -1 },
    ])
    expect(out.map((m) => m.id)).toEqual(['y', 'x'])
  })

  it('returns empty for undefined', () => {
    expect(activeMenus(undefined)).toEqual([])
  })
})

describe('resolveActiveMenu (apps/nav v2)', () => {
  const menus: MenuConfig[] = [
    { id: 'a', name: 'Alpha', items: [] },
    { id: 'b', name: 'Beta', items: [], isDefault: true },
    { id: 'c', name: 'Gamma', items: [] },
  ]

  it('prefers the stored app when it still exists', () => {
    expect(resolveActiveMenu(menus, 'c')?.id).toBe('c')
  })

  it('falls back to the default app when the stored id is stale', () => {
    expect(resolveActiveMenu(menus, 'gone')?.id).toBe('b')
  })

  it('falls back to the first app when nothing is default', () => {
    const noDefault = menus.map((m) => ({ ...m, isDefault: false }))
    expect(resolveActiveMenu(noDefault, null)?.id).toBe('a')
  })

  it('returns null with no apps', () => {
    expect(resolveActiveMenu([], 'a')).toBeNull()
  })
})

describe('submenu groups (nested menu items)', () => {
  it('maps an item with children to a group tab of child tabs', () => {
    const tab = menuItemToTab({
      id: 'g1',
      label: 'Programs',
      icon: 'award',
      children: [
        { id: 'c1', label: 'Incentive Programs', path: '/resources/incentive-programs' },
        { id: 'c2', label: 'Sources', path: '/resources/sources' },
      ],
    })
    expect(tab).toMatchObject({
      key: 'group:g1',
      kind: 'group',
      label: 'Programs',
      icon: 'award',
    })
    expect(tab?.children).toHaveLength(2)
    expect(tab?.children?.[0]).toMatchObject({ kind: 'collection', target: 'incentive-programs' })
  })

  it('ignores the group header path — groups organize, they do not navigate', () => {
    const tab = menuItemToTab({
      id: 'g2',
      label: 'Places',
      path: '/resources/countries',
      children: [{ id: 'c1', label: 'Countries', path: '/resources/countries' }],
    })
    expect(tab?.kind).toBe('group')
  })

  it('drops a group whose children all fail to map', () => {
    expect(
      menuItemToTab({
        id: 'g3',
        label: 'Broken',
        children: [{ id: 'c1', label: 'External', path: 'https://example.com' }],
      })
    ).toBeNull()
  })

  it('flattens nested groups to one level (child groups are excluded)', () => {
    const tab = menuItemToTab({
      id: 'g4',
      label: 'Outer',
      children: [
        { id: 'c1', label: 'Leaf', path: '/resources/faqs' },
        {
          id: 'g5',
          label: 'Inner',
          children: [{ id: 'c2', label: 'Deep', path: '/resources/sources' }],
        },
      ],
    })
    expect(tab?.children).toHaveLength(1)
    expect(tab?.children?.[0]).toMatchObject({ target: 'faqs' })
  })

  it('buildNavTabs surfaces groups alongside flat tabs', () => {
    const menus: MenuConfig[] = [
      {
        id: 'm1',
        name: 'app',
        items: [
          { id: 'i1', label: 'Countries', path: '/resources/countries' },
          {
            id: 'g1',
            label: 'FAQs',
            children: [
              { id: 'i2', label: 'FAQs', path: '/resources/faqs' },
              { id: 'i3', label: 'FAQ Translations', path: '/resources/faq-translations' },
            ],
          },
        ],
      },
    ]
    const tabs = buildNavTabs(menus)
    expect(tabs).toHaveLength(2)
    expect(tabs[0]).toMatchObject({ kind: 'collection', target: 'countries' })
    expect(tabs[1]).toMatchObject({ kind: 'group', label: 'FAQs' })
    expect(tabs[1].children).toHaveLength(2)
  })

  it('maps /chat and /app/chat to a chat tab (telehealth slice 3)', () => {
    expect(menuItemToTab({ id: 'c1', label: 'Support', path: '/chat' })).toMatchObject({
      kind: 'chat',
      label: 'Support',
    })
    expect(menuItemToTab({ id: 'c2', label: '', path: '/app/chat' })).toMatchObject({
      kind: 'chat',
      label: 'Chat',
    })
    expect(menuItemToTab({ id: 'c3', label: 'Nope', path: '/chatter' })).toBeNull()
  })
})
