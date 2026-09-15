/**
 * AppHomePage
 *
 * End-user home/dashboard page. Shows a personalized welcome message,
 * dynamic quick actions based on available collections, recent items
 * with relative timestamps, and starred favorites.
 *
 * Features:
 * - Time-of-day greeting with user's first name
 * - Quick action buttons for each configured collection
 * - Recent items with collection badge and relative time
 * - Favorites with type indicators (record vs collection)
 * - Empty states with helpful guidance
 */

import React, { useMemo } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Plus, Clock, Star, ArrowRight, Database, Search } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import { useAuth } from '@/context/AuthContext'
import { useAppContext } from '@/context/AppContext'
import { useConfig } from '@/context/ConfigContext'
import { CustomPage } from '../CustomPage/CustomPage'
import { resolveHomePageSlug } from './homePage'
import { parseResourcePath } from '@/shells/EndUserShell/navTabs'
import type { MenuConfig, MenuItemConfig } from '@/types/config'

/**
 * Format a timestamp into a human-readable relative time string.
 */
function formatRelativeTime(timestamp: number): string {
  const now = Date.now()
  const diffMs = now - timestamp
  const diffSeconds = Math.floor(diffMs / 1000)
  const diffMinutes = Math.floor(diffSeconds / 60)
  const diffHours = Math.floor(diffMinutes / 60)
  const diffDays = Math.floor(diffHours / 24)

  if (diffSeconds < 60) return 'just now'
  if (diffMinutes < 60) return `${diffMinutes}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  if (diffDays === 1) return 'yesterday'
  if (diffDays < 7) return `${diffDays}d ago`
  if (diffDays < 30) return `${Math.floor(diffDays / 7)}w ago`
  return new Date(timestamp).toLocaleDateString()
}

/**
 * Extract collection tabs from menu config for quick actions.
 */
function getCollectionTabs(
  config: { menus?: MenuConfig[] } | null
): Array<{ collectionName: string; label: string }> {
  if (!config?.menus) return []
  const tabs: Array<{ collectionName: string; label: string }> = []
  const visit = (items: MenuItemConfig[] | undefined) => {
    for (const item of items ?? []) {
      const parsed = item.path ? parseResourcePath(item.path) : null
      if (parsed) {
        const { collectionName } = parsed
        tabs.push({
          collectionName,
          label: item.label || collectionName.charAt(0).toUpperCase() + collectionName.slice(1),
        })
      }
      // Submenu groups: collections may nest one level down.
      if (item.children?.length) visit(item.children)
    }
  }
  for (const menu of config.menus) {
    visit(menu.items)
  }
  return tabs
}

export function AppHomePage(): React.ReactElement {
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { recentItems, favorites } = useAppContext()
  const { config } = useConfig()

  const basePath = `/${tenantSlug}/app`

  // Determine greeting based on time of day
  const hour = new Date().getHours()
  let greeting = 'Good morning'
  if (hour >= 12 && hour < 17) greeting = 'Good afternoon'
  if (hour >= 17) greeting = 'Good evening'

  const firstName = user?.name?.split(' ')[0] || 'there'

  // Extract collection tabs from menu config
  const collectionTabs = useMemo(() => getCollectionTabs(config), [config])

  // A page-builder page can override this default home (config.isHomePage). When one is configured,
  // render it in place of the default content — the URL stays /app/home.
  const homePageSlug = useMemo(
    () => resolveHomePageSlug((config as { pages?: unknown } | null)?.pages),
    [config]
  )
  if (homePageSlug) {
    return <CustomPage slug={homePageSlug} />
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      {/* Welcome header */}
      <div className="space-y-1">
        <h1 className="text-[26px] font-bold tracking-[-0.01em] text-foreground">
          {greeting}, {firstName}
        </h1>
        <p className="text-base text-muted-foreground">
          Here&apos;s an overview of your recent activity.
        </p>
      </div>

      {/* Quick Actions */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">Quick actions</CardTitle>
          <CardDescription>Create new records or navigate to collections</CardDescription>
        </CardHeader>
        <CardContent>
          {collectionTabs.length === 0 ? (
            <div className="flex flex-col items-center gap-2 py-4 text-center">
              <Database className="h-8 w-8 text-muted-foreground/50" />
              <p className="text-sm text-muted-foreground">
                Quick actions will appear here once collections are configured.
              </p>
            </div>
          ) : (
            <div className="flex flex-wrap gap-2">
              {collectionTabs.map((tab) => (
                <Button
                  key={tab.collectionName}
                  variant="outline"
                  size="sm"
                  onClick={() => navigate(`${basePath}/o/${tab.collectionName}/new`)}
                >
                  <Plus className="mr-1.5 h-3.5 w-3.5" />
                  New {tab.label}
                </Button>
              ))}
              <Separator orientation="vertical" className="mx-1 h-8" />
              <Button
                variant="ghost"
                size="sm"
                className="text-muted-foreground"
                onClick={() => navigate(`${basePath}/search`)}
              >
                <Search className="mr-1.5 h-3.5 w-3.5" />
                Search
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-6 md:grid-cols-2">
        {/* Recent Items */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <Clock className="h-4 w-4 text-muted-foreground" />
              Recent items
            </CardTitle>
          </CardHeader>
          <CardContent>
            {recentItems.length === 0 ? (
              <div className="flex flex-col items-center gap-2 py-6 text-center">
                <Clock className="h-8 w-8 text-muted-foreground/30" />
                <p className="text-sm text-muted-foreground">
                  No recent items yet. Records you view will appear here.
                </p>
              </div>
            ) : (
              <div className="space-y-1">
                {recentItems.slice(0, 8).map((item) => (
                  <button
                    key={`${item.collectionName}-${item.id}`}
                    onClick={() => navigate(`${basePath}/o/${item.collectionName}/${item.id}`)}
                    className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-left text-sm transition-colors hover:bg-accent"
                  >
                    <span className="flex-1 truncate font-medium text-foreground">
                      {item.label}
                    </span>
                    <Badge variant="secondary" className="text-[10px] font-normal">
                      {item.collectionName}
                    </Badge>
                    <span className="whitespace-nowrap text-xs text-muted-foreground">
                      {formatRelativeTime(item.timestamp)}
                    </span>
                    <ArrowRight className="h-3 w-3 flex-shrink-0 text-muted-foreground" />
                  </button>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        {/* Favorites */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <Star className="h-4 w-4 text-muted-foreground" />
              Favorites
            </CardTitle>
          </CardHeader>
          <CardContent>
            {favorites.length === 0 ? (
              <div className="flex flex-col items-center gap-2 py-6 text-center">
                <Star className="h-8 w-8 text-muted-foreground/30" />
                <p className="text-sm text-muted-foreground">
                  No favorites yet. Star records or collections to access them quickly.
                </p>
              </div>
            ) : (
              <div className="space-y-1">
                {favorites.slice(0, 8).map((fav) => (
                  <button
                    key={fav.key}
                    onClick={() => {
                      if (fav.type === 'record' && fav.recordId) {
                        navigate(`${basePath}/o/${fav.collectionName}/${fav.recordId}`)
                      } else {
                        navigate(`${basePath}/o/${fav.collectionName}`)
                      }
                    }}
                    className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-left text-sm transition-colors hover:bg-accent"
                  >
                    <Star className="h-3.5 w-3.5 flex-shrink-0 fill-yellow-400 text-yellow-400" />
                    <span className="flex-1 truncate font-medium text-foreground">{fav.label}</span>
                    <Badge variant="outline" className="text-[10px] font-normal">
                      {fav.type}
                    </Badge>
                    <ArrowRight className="h-3 w-3 flex-shrink-0 text-muted-foreground" />
                  </button>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Collection Overview */}
      {collectionTabs.length > 0 && (
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <Database className="h-4 w-4 text-muted-foreground" />
              Collections
            </CardTitle>
            <CardDescription>Browse your data collections</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
              {collectionTabs.map((tab) => (
                <Button
                  key={tab.collectionName}
                  variant="outline"
                  className="h-auto justify-start px-3 py-3"
                  onClick={() => navigate(`${basePath}/o/${tab.collectionName}`)}
                >
                  <div className="flex flex-col items-start gap-0.5">
                    <span className="text-sm font-medium">{tab.label}</span>
                    <span className="text-[10px] text-muted-foreground">{tab.collectionName}</span>
                  </div>
                </Button>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
