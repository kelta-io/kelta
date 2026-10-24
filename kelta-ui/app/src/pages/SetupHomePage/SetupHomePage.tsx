/**
 * SetupHomePage Component
 *
 * Modernized admin/configuration landing page. Hero card with breadcrumb +
 * search + stats panel; pinned + recently visited shortcut rows backed by
 * localStorage; category filter tabs that group cards into sections.
 */

import React, { useCallback, useMemo, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  Activity,
  AppWindow,
  Archive,
  ArrowUpFromLine,
  BarChart3,
  Building2,
  CheckCircle,
  ChevronRight,
  Clock,
  Code2,
  Database,
  FileJson,
  FileText,
  Folder,
  Gauge,
  GitBranch,
  History,
  IdCard,
  Bot,
  KeyRound,
  LayoutGrid,
  Link as LinkIcon,
  CopyCheck,
  List,
  ListChecks,
  Lock,
  LogIn,
  Inbox,
  Mail,
  Megaphone,
  Menu,
  Network,
  Package,
  PackageOpen,
  Palette,
  Pin,
  PinOff,
  Puzzle,
  Search,
  SearchCheck,
  Settings,
  Shield,
  ShieldAlert,
  ShieldCheck,
  Table2,
  User,
  Users,
  Webhook,
  Workflow,
  X,
  Zap,
  Languages,
} from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useTenant } from '../../context/TenantContext'
import { useConfig } from '../../context/ConfigContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { useCollectionSummaries } from '../../hooks/useCollectionSummaries'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { cn } from '@/lib/utils'
import { usePinnedSetup } from './usePinnedSetup'
import { useRecentSetup } from './useRecentSetup'

export interface SetupHomePageProps {
  testId?: string
}

type GroupKey = 'core' | 'automation' | 'platform'

interface SetupItem {
  name: string
  path: string
  description: string
  icon: React.ComponentType<{ className?: string }>
  permission?: string
}

interface SetupCategory {
  key: string
  titleKey: string
  descriptionKey: string
  icon: React.ComponentType<{ className?: string }>
  items: SetupItem[]
}

interface PaginatedResponse {
  totalElements?: number
  content?: unknown[]
}

const CATEGORIES: SetupCategory[] = [
  {
    key: 'dataModel',
    titleKey: 'setup.categories.dataModel',
    descriptionKey: 'setup.categoryDescriptions.dataModel',
    icon: Database,
    items: [
      {
        name: 'Collections',
        path: '/collections',
        description: 'Define data objects and schemas',
        icon: Database,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Resources',
        path: '/resources',
        description: 'Browse and edit records in every collection',
        icon: Folder,
      },
      {
        name: 'Picklists',
        path: '/picklists',
        description: 'Manage global picklist values',
        icon: List,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Page Layouts',
        path: '/layouts',
        description: 'Configure record page layouts',
        icon: LayoutGrid,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'List Views',
        path: '/listviews',
        description: 'Manage list view configurations',
        icon: Table2,
        permission: 'MANAGE_LISTVIEWS',
      },
    ],
  },
  {
    key: 'administration',
    titleKey: 'setup.categories.administration',
    descriptionKey: 'setup.categoryDescriptions.administration',
    icon: Users,
    items: [
      {
        name: 'Users',
        path: '/users',
        description: 'Manage user accounts and permissions',
        icon: User,
        permission: 'MANAGE_USERS',
      },
      {
        name: 'Profiles',
        path: '/profiles',
        description: 'Configure profile-based permissions',
        icon: IdCard,
        permission: 'MANAGE_USERS',
      },
      {
        name: 'Delegated Admins',
        path: '/delegated-admins',
        description: 'Let selected users manage a limited set of users',
        icon: ShieldCheck,
        permission: 'MANAGE_DELEGATED_ADMINS',
      },
      {
        name: 'OIDC Providers',
        path: '/oidc-providers',
        description: 'Configure identity providers',
        icon: KeyRound,
        permission: 'MANAGE_CONNECTED_APPS',
      },
    ],
  },
  {
    key: 'security',
    titleKey: 'setup.categories.security',
    descriptionKey: 'setup.categoryDescriptions.security',
    icon: ShieldCheck,
    items: [
      {
        name: 'Password Policy',
        path: '/password-policy',
        description: 'Configure password complexity, lockout, and expiration rules',
        icon: Lock,
        permission: 'MANAGE_USERS',
      },
      {
        name: 'MFA Policy',
        path: '/mfa-policy',
        description: 'Configure multi-factor authentication requirements and enrollment',
        icon: Shield,
        permission: 'MANAGE_USERS',
      },
      {
        name: 'Login History',
        path: '/login-history',
        description: 'View user login activity',
        icon: LogIn,
        permission: 'MANAGE_USERS',
      },
      {
        name: 'Security Audit',
        path: '/security-audit',
        description: 'Review security event trail',
        icon: ShieldAlert,
        permission: 'MANAGE_USERS',
      },
      {
        name: 'Network Access',
        path: '/network-access',
        description: 'Restrict data access to allowed IP ranges',
        icon: Network,
        permission: 'MANAGE_TENANTS',
      },
      {
        name: 'Audit Trail',
        path: '/audit-trail',
        description: 'View configuration change history',
        icon: History,
        permission: 'VIEW_SETUP',
      },
    ],
  },
  {
    key: 'automation',
    titleKey: 'setup.categories.automation',
    descriptionKey: 'setup.categoryDescriptions.automation',
    icon: Zap,
    items: [
      {
        name: 'Approval Processes',
        path: '/approvals',
        description: 'Configure approval workflows',
        icon: CheckCircle,
        permission: 'MANAGE_APPROVALS',
      },
      {
        name: 'Flows',
        path: '/flows',
        description: 'Build visual process flows',
        icon: Workflow,
        permission: 'MANAGE_WORKFLOWS',
      },
      {
        name: 'Scheduled Jobs',
        path: '/scheduled-jobs',
        description: 'Manage scheduled tasks',
        icon: Clock,
        permission: 'MANAGE_WORKFLOWS',
      },
      {
        name: 'AI Agents',
        path: '/ai-agents',
        description: 'Create and run governed AI agents',
        icon: Bot,
        permission: 'CUSTOMIZE_APPLICATION',
      },
    ],
  },
  {
    key: 'integration',
    titleKey: 'setup.categories.integration',
    descriptionKey: 'setup.categoryDescriptions.integration',
    icon: LinkIcon,
    items: [
      {
        name: 'Connected Apps',
        path: '/connected-apps',
        description: 'Manage OAuth connected apps',
        icon: AppWindow,
        permission: 'MANAGE_CONNECTED_APPS',
      },
      {
        name: 'Credentials',
        path: '/credentials',
        description: 'Reusable secrets for outbound APIs and email',
        icon: KeyRound,
        permission: 'VIEW_CREDENTIALS',
      },
      {
        name: 'API Specs',
        path: '/api-specs',
        description: 'OpenAPI spec library used by the flow builder',
        icon: FileJson,
        permission: 'VIEW_API_SPECS',
      },
      {
        name: 'Webhooks',
        path: '/webhooks',
        description: 'Configure outbound webhooks',
        icon: Webhook,
        permission: 'MANAGE_CONNECTED_APPS',
      },
      {
        name: 'Email Templates',
        path: '/email-templates',
        description: 'Design email templates',
        icon: Mail,
        permission: 'MANAGE_EMAIL_TEMPLATES',
      },
      {
        name: 'Support Mailboxes',
        path: '/mailboxes',
        description: 'Shared inboxes, membership, and SLA policy',
        icon: Inbox,
        permission: 'MANAGE_SUPPORT_MAILBOX',
      },
      {
        name: 'Campaigns',
        path: '/campaigns',
        description: 'Compose and send email campaigns',
        icon: Megaphone,
        permission: 'MANAGE_CAMPAIGNS',
      },
      {
        name: 'Scripts',
        path: '/scripts',
        description: 'Manage server-side scripts',
        icon: Code2,
        permission: 'MANAGE_CONNECTED_APPS',
      },
      {
        name: 'Modules',
        path: '/modules',
        description: 'Install and manage runtime modules',
        icon: Package,
        permission: 'MANAGE_CONNECTED_APPS',
      },
      {
        name: 'Telehealth Retention',
        path: '/telehealth-settings',
        description: 'Encounter-record archival, retention, and legal holds',
        icon: Archive,
        permission: 'VIEW_SETUP',
      },
    ],
  },
  {
    key: 'uiCustomization',
    titleKey: 'setup.categories.uiCustomization',
    descriptionKey: 'setup.categoryDescriptions.uiCustomization',
    icon: Palette,
    items: [
      {
        name: 'Pages',
        path: '/pages',
        description: 'Build custom UI pages',
        icon: FileText,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Menus',
        path: '/menus',
        description: 'Configure navigation menus',
        icon: Menu,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Plugins',
        path: '/plugins',
        description: 'Manage installed plugins',
        icon: Puzzle,
        permission: 'CUSTOMIZE_APPLICATION',
      },
    ],
  },
  {
    key: 'analytics',
    titleKey: 'setup.categories.analytics',
    descriptionKey: 'setup.categoryDescriptions.analytics',
    icon: BarChart3,
    items: [
      {
        name: 'Analytics',
        path: '/analytics',
        description: 'View dashboards and reports powered by Superset',
        icon: BarChart3,
        permission: 'MANAGE_REPORTS',
      },
    ],
  },
  {
    key: 'platform',
    titleKey: 'setup.categories.platform',
    descriptionKey: 'setup.categoryDescriptions.platform',
    icon: Settings,
    items: [
      {
        name: 'Packages',
        path: '/packages',
        description: 'Import/export configuration packages',
        icon: PackageOpen,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Migrations',
        path: '/migrations',
        description: 'View database migration history',
        icon: ArrowUpFromLine,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Environments',
        path: '/environments',
        description: 'Manage sandboxes and promote metadata between environments',
        icon: GitBranch,
        permission: 'MANAGE_SANDBOXES',
      },
      {
        name: 'Governor Limits',
        path: '/governor-limits',
        description: 'Monitor usage limits',
        icon: Gauge,
        permission: 'VIEW_SETUP',
      },
      {
        name: 'Monitoring',
        path: '/monitoring',
        description: 'View metrics, request logs, errors, and system performance',
        icon: Activity,
        permission: 'VIEW_SETUP',
      },
      {
        name: 'Tenants',
        path: '/tenants',
        description: 'Platform-level tenant management',
        icon: Building2,
        permission: 'MANAGE_TENANTS',
      },
      {
        name: 'Search Index',
        path: '/search-settings',
        description: 'Manage search index and rebuild data',
        icon: SearchCheck,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Bulk Jobs',
        path: '/bulk-jobs',
        description: 'Monitor bulk data operations',
        icon: ListChecks,
        permission: 'MANAGE_DATA',
      },
      {
        name: 'Translations',
        path: '/translations',
        description: 'Override UI text per locale for this tenant',
        icon: Languages,
        permission: 'CUSTOMIZE_APPLICATION',
      },
      {
        name: 'Deduplicate',
        path: '/dedup',
        description: 'Find and merge duplicate records',
        icon: CopyCheck,
        permission: 'MANAGE_DATA',
      },
    ],
  },
]

const GROUP_OF: Record<string, GroupKey> = {
  administration: 'core',
  dataModel: 'core',
  security: 'core',
  automation: 'automation',
  integration: 'automation',
  uiCustomization: 'automation',
  analytics: 'platform',
  platform: 'platform',
}

const GROUP_ORDER: GroupKey[] = ['core', 'automation', 'platform']
const FILTER_ORDER: (GroupKey | 'all')[] = ['all', 'core', 'automation', 'platform']

const STAT_ICONS = {
  collections: Database,
  users: Users,
  dashboards: BarChart3,
} as const

function extractCount(data: unknown): number {
  if (data == null) return 0
  if (typeof data === 'object' && !Array.isArray(data)) {
    const paginated = data as PaginatedResponse
    if (typeof paginated.totalElements === 'number') {
      return paginated.totalElements
    }
    if (Array.isArray(paginated.content)) {
      return paginated.content.length
    }
  }
  if (Array.isArray(data)) {
    return data.length
  }
  return 0
}

function sanitizeTestPath(path: string): string {
  return path.replace(/\//g, '').replace(/-/g, '')
}

// Module-level minute-tick store: getSnapshot must be stable between ticks,
// otherwise useSyncExternalStore re-renders every call and React bails with
// "Maximum update depth exceeded" (#185).
let cachedNow = 0
const tickListeners = new Set<() => void>()
let tickIntervalId: number | null = null

function ensureTicker(): void {
  if (tickIntervalId !== null) return
  cachedNow = Date.now()
  tickIntervalId = window.setInterval(() => {
    cachedNow = Date.now()
    tickListeners.forEach((listener) => listener())
  }, 60_000)
}

function subscribeMinute(callback: () => void): () => void {
  ensureTicker()
  tickListeners.add(callback)
  // ensureTicker just set cachedNow above; notify the subscriber so React
  // re-reads the snapshot (transitioning from the 0 it captured during render).
  queueMicrotask(callback)
  return () => {
    tickListeners.delete(callback)
    if (tickListeners.size === 0 && tickIntervalId !== null) {
      window.clearInterval(tickIntervalId)
      tickIntervalId = null
    }
  }
}

function getNowSnapshot(): number {
  return cachedNow
}

function getNowServerSnapshot(): number {
  return 0
}

function useNow(): number {
  return useSyncExternalStore(subscribeMinute, getNowSnapshot, getNowServerSnapshot)
}

function formatRelative(
  t: ReturnType<typeof useI18n>['t'],
  visitedAt: number,
  now: number
): string {
  const diff = Math.max(0, now - visitedAt)
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 1) return t('setup.relativeTime.now')
  if (minutes < 60) return t('setup.relativeTime.minutes', { count: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t('setup.relativeTime.hours', { count: hours })
  if (hours < 48) return t('setup.relativeTime.yesterday')
  const days = Math.floor(hours / 24)
  return t('setup.relativeTime.days', { count: days })
}

export function SetupHomePage({
  testId = 'setup-home-page',
}: SetupHomePageProps): React.ReactElement {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const { tenantSlug } = useTenant()
  const { config } = useConfig()
  const { hasPermission } = useSystemPermissions()
  const [searchQuery, setSearchQuery] = useState('')
  const [activeFilter, setActiveFilter] = useState<GroupKey | 'all'>('all')
  const { pinned, isPinned, toggle: togglePin, unpin } = usePinnedSetup(tenantSlug)
  const { recent, recordVisit } = useRecentSetup(tenantSlug)
  const now = useNow()

  const { summaries: collectionSummaries } = useCollectionSummaries()

  const { data: usersData } = useQuery({
    queryKey: ['setup-stats-users'],
    queryFn: () => keltaClient.admin.users.list(undefined, undefined, 0, 1),
    staleTime: 300000,
  })

  const { data: analyticsData } = useQuery({
    queryKey: ['setup-stats-analytics'],
    queryFn: () => keltaClient.admin.superset.listDashboards().catch(() => []),
    staleTime: 300000,
  })

  const stats = useMemo(
    () => [
      {
        key: 'collections' as const,
        label: t('setup.stats.collections'),
        value: collectionSummaries.length,
      },
      {
        key: 'users' as const,
        label: t('setup.stats.users'),
        value: extractCount(usersData),
      },
      {
        key: 'dashboards' as const,
        label: t('setup.stats.dashboards'),
        value: analyticsData?.length ?? 0,
      },
    ],
    [collectionSummaries, usersData, analyticsData, t]
  )

  // Permission-filtered categories (applied once — drives counts + everything else).
  const permissionFiltered = useMemo(() => {
    return CATEGORIES.map((category) => ({
      ...category,
      items: category.items.filter((item) => !item.permission || hasPermission(item.permission)),
    })).filter((category) => category.items.length > 0)
  }, [hasPermission])

  // Flat item index (for pinned/recent lookups + search).
  const itemIndex = useMemo(() => {
    const map = new Map<string, { item: SetupItem; categoryKey: string }>()
    for (const category of permissionFiltered) {
      for (const item of category.items) {
        map.set(item.path, { item, categoryKey: category.key })
      }
    }
    return map
  }, [permissionFiltered])

  // Filter counts (per group, post-permission).
  const filterCounts = useMemo(() => {
    const counts: Record<GroupKey | 'all', number> = {
      all: 0,
      core: 0,
      automation: 0,
      platform: 0,
    }
    for (const category of permissionFiltered) {
      const group = GROUP_OF[category.key]
      counts.all += category.items.length
      if (group) counts[group] += category.items.length
    }
    return counts
  }, [permissionFiltered])

  // Apply group filter + search filter.
  const visibleCategories = useMemo(() => {
    const query = searchQuery.toLowerCase().trim()
    const groupFiltered =
      activeFilter === 'all'
        ? permissionFiltered
        : permissionFiltered.filter((c) => GROUP_OF[c.key] === activeFilter)

    if (!query) return groupFiltered

    return groupFiltered
      .map((category) => ({
        ...category,
        items: category.items.filter(
          (item) =>
            item.name.toLowerCase().includes(query) ||
            item.description.toLowerCase().includes(query)
        ),
      }))
      .filter((category) => category.items.length > 0)
  }, [permissionFiltered, activeFilter, searchQuery])

  // Group visible categories into sections.
  const visibleGroups = useMemo(() => {
    const order = activeFilter === 'all' ? GROUP_ORDER : [activeFilter as GroupKey]
    return order
      .map((groupKey) => {
        const categories = visibleCategories.filter((c) => GROUP_OF[c.key] === groupKey)
        const itemCount = categories.reduce((acc, c) => acc + c.items.length, 0)
        return { groupKey, categories, itemCount }
      })
      .filter((g) => g.categories.length > 0)
  }, [visibleCategories, activeFilter])

  const handleSearchChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchQuery(e.target.value)
  }, [])

  const handleClearSearch = useCallback(() => {
    setSearchQuery('')
  }, [])

  const tenantName = config?.branding.applicationName ?? tenantSlug

  const pinnedItems = useMemo(
    () =>
      pinned
        .map((path) => itemIndex.get(path))
        .filter((entry): entry is { item: SetupItem; categoryKey: string } => entry != null),
    [pinned, itemIndex]
  )

  const recentItems = useMemo(() => {
    return recent
      .map((entry) => {
        const indexed = itemIndex.get(entry.path)
        if (!indexed) return null
        return {
          item: indexed.item,
          categoryKey: indexed.categoryKey,
          visitedAt: entry.visitedAt,
          relative: now > 0 ? formatRelative(t, entry.visitedAt, now) : '',
        }
      })
      .filter((e): e is NonNullable<typeof e> => e != null)
  }, [recent, itemIndex, t, now])

  return (
    <div
      className="mx-auto max-w-[1400px] space-y-6 p-8 max-[767px]:space-y-4 max-[767px]:p-4"
      data-testid={testId}
    >
      {/* Hero card */}
      <section className="relative overflow-hidden rounded-xl border border-border bg-card">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 opacity-70 [background:radial-gradient(circle_at_top_left,hsl(var(--primary)/0.12),transparent_60%),radial-gradient(circle_at_bottom_right,hsl(var(--primary)/0.08),transparent_55%)]"
        />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 opacity-[0.08] [background-image:linear-gradient(hsl(var(--foreground))_1px,transparent_1px),linear-gradient(90deg,hsl(var(--foreground))_1px,transparent_1px)] [background-size:40px_40px]"
        />
        <div className="relative grid grid-cols-[1fr_auto] gap-10 p-8 max-[1023px]:grid-cols-1 max-[1023px]:gap-6">
          <div className="flex min-w-0 flex-col gap-4">
            <div className="inline-flex w-fit items-center gap-2 rounded-full border border-border bg-background/60 px-3 py-1 text-xs font-medium text-muted-foreground backdrop-blur">
              <span className="h-1.5 w-1.5 rounded-full bg-primary" aria-hidden="true" />
              {t('setup.breadcrumbLabel')} · <span className="text-foreground">{tenantName}</span>
            </div>
            <h1 className="m-0 text-[2.5rem] font-bold leading-tight tracking-tight text-foreground max-[767px]:text-[1.75rem]">
              {t('setup.title')}
            </h1>
            <p className="m-0 max-w-xl text-sm text-muted-foreground">{t('setup.subtitle')}</p>
            <div className="relative mt-2 flex w-full max-w-xl items-center">
              <Search
                className="pointer-events-none absolute left-3.5 h-4 w-4 text-muted-foreground"
                aria-hidden="true"
              />
              <input
                type="text"
                className={cn(
                  'h-11 w-full rounded-lg border border-border bg-background/80 pl-10 pr-20 text-sm text-foreground',
                  'placeholder:text-muted-foreground',
                  'outline-none transition-[border-color,box-shadow] duration-150',
                  'focus:border-primary focus:shadow-[0_0_0_3px_hsl(var(--primary)/0.18)]'
                )}
                placeholder={t('setup.searchPlaceholder')}
                value={searchQuery}
                onChange={handleSearchChange}
                data-testid="setup-search-input"
                aria-label={t('setup.searchPlaceholder')}
              />
              {searchQuery ? (
                <button
                  type="button"
                  className={cn(
                    'absolute right-2 flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground',
                    'cursor-pointer transition-colors duration-150',
                    'hover:bg-muted hover:text-foreground',
                    'focus:outline-2 focus:outline-offset-2 focus:outline-primary'
                  )}
                  onClick={handleClearSearch}
                  data-testid="setup-search-clear"
                  aria-label={t('common.clear')}
                >
                  <X className="h-4 w-4" />
                </button>
              ) : (
                <kbd
                  aria-hidden="true"
                  className="absolute right-3 inline-flex h-6 select-none items-center rounded border border-border bg-muted px-1.5 font-mono text-[0.6875rem] font-medium text-muted-foreground"
                >
                  {t('setup.shortcutHint')}
                </kbd>
              )}
            </div>
          </div>
          <div
            data-testid="setup-stats"
            className="grid w-[280px] grid-cols-1 content-start gap-3 max-[1023px]:w-full max-[1023px]:grid-cols-3 max-[767px]:grid-cols-1"
          >
            {stats.map((stat) => {
              const Icon = STAT_ICONS[stat.key]
              return (
                <div
                  key={stat.key}
                  className="flex items-center gap-3 rounded-lg border border-border bg-background/60 px-4 py-3 backdrop-blur"
                >
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                    <Icon className="h-[18px] w-[18px]" />
                  </div>
                  <div className="flex flex-col">
                    <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
                      {stat.label}
                    </span>
                    <span className="text-[1.5rem] font-bold leading-tight text-foreground">
                      {stat.value}
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      </section>

      {/* Pinned + Recently visited rows */}
      <div className="grid grid-cols-2 gap-4 max-[1023px]:grid-cols-1">
        <ShortcutRow
          testId="setup-pinned"
          icon={<Pin className="h-3.5 w-3.5" />}
          label={t('setup.pinned')}
          emptyLabel={t('setup.emptyPinned')}
          empty={pinnedItems.length === 0}
        >
          {pinnedItems.map(({ item }) => (
            <Chip
              key={item.path}
              to={`/${tenantSlug}${item.path}`}
              icon={item.icon}
              label={item.name}
              onVisit={() => recordVisit(item.path)}
              onRemove={() => unpin(item.path)}
              removeLabel={t('setup.removePin')}
              testId={`setup-pinned-${sanitizeTestPath(item.path)}`}
            />
          ))}
        </ShortcutRow>
        <ShortcutRow
          testId="setup-recent"
          icon={<Clock className="h-3.5 w-3.5" />}
          label={t('setup.recent')}
          emptyLabel={t('setup.emptyRecent')}
          empty={recentItems.length === 0}
        >
          {recentItems.map(({ item, relative }) => (
            <Chip
              key={item.path}
              to={`/${tenantSlug}${item.path}`}
              icon={item.icon}
              label={item.name}
              meta={relative}
              onVisit={() => recordVisit(item.path)}
              testId={`setup-recent-${sanitizeTestPath(item.path)}`}
            />
          ))}
        </ShortcutRow>
      </div>

      {/* Filter tabs */}
      <Tabs value={activeFilter} onValueChange={(v) => setActiveFilter(v as GroupKey | 'all')}>
        <TabsList variant="line" className="h-9">
          {FILTER_ORDER.map((key) => (
            <TabsTrigger key={key} value={key} data-testid={`setup-filter-${key}`} className="px-3">
              {t(`setup.filters.${key}`)}
              <span
                className={cn(
                  'ml-1.5 inline-flex h-5 min-w-[1.25rem] items-center justify-center rounded-md px-1.5 text-[0.6875rem] font-semibold',
                  activeFilter === key
                    ? 'bg-primary/15 text-primary'
                    : 'bg-muted text-muted-foreground'
                )}
              >
                {filterCounts[key]}
              </span>
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {/* Sections */}
      {visibleGroups.length === 0 ? (
        <div
          className="flex items-center justify-center rounded-xl border border-dashed border-border bg-card p-12"
          data-testid="setup-no-results"
        >
          <p className="m-0 text-sm text-muted-foreground">{t('common.noResults')}</p>
        </div>
      ) : (
        visibleGroups.map(({ groupKey, categories, itemCount }) => (
          <section key={groupKey} data-testid={`setup-group-${groupKey}`} className="space-y-4">
            <div className="flex items-end justify-between border-b border-border pb-2">
              <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.12em] text-muted-foreground">
                {t(`setup.groups.${groupKey}`)}
              </span>
              <span className="text-xs text-muted-foreground">
                {t('setup.groupMeta', { sections: categories.length, items: itemCount })}
              </span>
            </div>
            <div className="grid grid-cols-3 gap-4 max-[1279px]:grid-cols-2 max-[767px]:grid-cols-1">
              {categories.map((category) => (
                <CategoryCard
                  key={category.key}
                  category={category}
                  tenantSlug={tenantSlug}
                  isPinned={isPinned}
                  onTogglePin={togglePin}
                  onVisit={recordVisit}
                  pinLabel={t('setup.addPin')}
                  unpinLabel={t('setup.removePin')}
                  titleText={t(category.titleKey)}
                  descriptionText={t(category.descriptionKey)}
                />
              ))}
            </div>
          </section>
        ))
      )}
    </div>
  )
}

interface ShortcutRowProps {
  testId: string
  icon: React.ReactNode
  label: string
  emptyLabel: string
  empty: boolean
  children: React.ReactNode
}

function ShortcutRow({
  testId,
  icon,
  label,
  emptyLabel,
  empty,
  children,
}: ShortcutRowProps): React.ReactElement {
  return (
    <section data-testid={testId} className="rounded-xl border border-border bg-card p-4">
      <div className="mb-3 flex items-center gap-2 text-muted-foreground">
        <span className="flex h-5 w-5 items-center justify-center" aria-hidden="true">
          {icon}
        </span>
        <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.12em]">{label}</span>
      </div>
      {empty ? (
        <p className="m-0 text-xs text-muted-foreground">{emptyLabel}</p>
      ) : (
        <div className="flex flex-wrap gap-2">{children}</div>
      )}
    </section>
  )
}

interface ChipProps {
  to: string
  icon: React.ComponentType<{ className?: string }>
  label: string
  meta?: string
  onVisit: () => void
  onRemove?: () => void
  removeLabel?: string
  testId: string
}

function Chip({
  to,
  icon: Icon,
  label,
  meta,
  onVisit,
  onRemove,
  removeLabel,
  testId,
}: ChipProps): React.ReactElement {
  return (
    <div className="group inline-flex items-stretch overflow-hidden rounded-md border border-border bg-background/60 transition-colors hover:border-primary/40 hover:bg-muted">
      <Link
        to={to}
        onClick={onVisit}
        data-testid={testId}
        className="flex items-center gap-2 px-2.5 py-1.5 text-sm text-foreground no-underline"
      >
        <Icon className="h-3.5 w-3.5 text-muted-foreground group-hover:text-primary" />
        <span className="font-medium">{label}</span>
        {meta && <span className="text-[0.6875rem] text-muted-foreground">{meta}</span>}
      </Link>
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          aria-label={removeLabel}
          className="hidden h-full items-center border-l border-border px-2 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive group-hover:flex focus-visible:flex"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      )}
    </div>
  )
}

interface CategoryCardProps {
  category: SetupCategory
  tenantSlug: string
  isPinned: (path: string) => boolean
  onTogglePin: (path: string) => void
  onVisit: (path: string) => void
  pinLabel: string
  unpinLabel: string
  titleText: string
  descriptionText: string
}

function CategoryCard({
  category,
  tenantSlug,
  isPinned,
  onTogglePin,
  onVisit,
  pinLabel,
  unpinLabel,
  titleText,
  descriptionText,
}: CategoryCardProps): React.ReactElement {
  const Icon = category.icon
  return (
    <div
      data-testid={`setup-category-${category.key}`}
      className="flex flex-col overflow-hidden rounded-xl border border-border bg-card transition-colors hover:border-primary/40"
    >
      <div className="flex items-start gap-3 p-5">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Icon className="h-5 w-5" />
        </div>
        <div className="flex flex-1 flex-col gap-0.5">
          <div className="flex items-center justify-between gap-2">
            <h2 className="m-0 text-[0.9375rem] font-semibold text-foreground">{titleText}</h2>
            <span className="inline-flex h-5 min-w-[1.25rem] items-center justify-center rounded-md bg-muted px-1.5 text-[0.6875rem] font-semibold text-muted-foreground">
              {category.items.length}
            </span>
          </div>
          <p className="m-0 text-xs text-muted-foreground">{descriptionText}</p>
        </div>
      </div>
      <ul className="m-0 list-none border-t border-border p-0">
        {category.items.map((item) => {
          const ItemIcon = item.icon
          const pinned = isPinned(item.path)
          return (
            <li key={item.path} className="border-b border-border last:border-b-0">
              <div className="group/item relative flex items-stretch">
                <Link
                  to={`/${tenantSlug}${item.path}`}
                  onClick={() => onVisit(item.path)}
                  data-testid={`setup-item-${sanitizeTestPath(item.path)}`}
                  className={cn(
                    'flex flex-1 items-center gap-3 px-5 py-3 text-inherit no-underline',
                    'transition-colors duration-150',
                    'hover:bg-muted',
                    'focus:outline-2 focus:outline-offset-[-2px] focus:outline-primary'
                  )}
                >
                  <ItemIcon className="h-4 w-4 shrink-0 text-muted-foreground group-hover/item:text-primary" />
                  <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span className="text-sm font-medium text-foreground">{item.name}</span>
                    <span className="truncate text-xs text-muted-foreground">
                      {item.description}
                    </span>
                  </div>
                  <ChevronRight
                    className="h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform duration-150 group-hover/item:translate-x-0.5 group-hover/item:text-primary"
                    aria-hidden="true"
                  />
                </Link>
                <button
                  type="button"
                  onClick={(e) => {
                    e.preventDefault()
                    e.stopPropagation()
                    onTogglePin(item.path)
                  }}
                  data-testid={`setup-pin-${sanitizeTestPath(item.path)}`}
                  aria-label={pinned ? unpinLabel : pinLabel}
                  aria-pressed={pinned}
                  className={cn(
                    'absolute right-9 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-md',
                    'text-muted-foreground transition-opacity duration-150',
                    'hover:bg-background hover:text-primary',
                    'focus:outline-2 focus:outline-offset-1 focus:outline-primary',
                    pinned ? 'opacity-100 text-primary' : 'opacity-0 group-hover/item:opacity-100'
                  )}
                >
                  {pinned ? <PinOff className="h-3.5 w-3.5" /> : <Pin className="h-3.5 w-3.5" />}
                </button>
              </div>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
