/**
 * PluginsPage Component
 *
 * Displays a list of installed plugins with their status, allows enabling/disabling
 * plugins, and provides a settings interface for plugins that support configuration.
 *
 * Requirements:
 * - 12.6: Provide a plugin configuration interface for managing plugin settings
 */

import React, { useState, useCallback, useMemo } from 'react'
import { usePlugins } from '../../context/PluginContext'
import { useI18n } from '../../context/I18nContext'
import { LoadingSpinner } from '../../components'
import type { LoadedPlugin, PluginStatus } from '../../types/plugin'
import { cn } from '@/lib/utils'

/**
 * Plugin settings interface for plugins that provide configuration
 */
export interface PluginSettings {
  pluginId: string
  SettingsComponent?: React.ComponentType<PluginSettingsProps>
  values?: Record<string, unknown>
}

interface PluginSettingsProps {
  values: Record<string, unknown>
  onChange: (values: Record<string, unknown>) => void
  isSaving?: boolean
}

export interface PluginsPageProps {
  testId?: string
}

function getStatusText(status: PluginStatus, t: (key: string) => string): string {
  switch (status) {
    case 'loaded':
      return t('plugins.status.loaded')
    case 'loading':
      return t('plugins.status.loading')
    case 'error':
      return t('plugins.status.error')
    case 'pending':
      return t('plugins.status.pending')
    default:
      return status
  }
}

function getStatusDotColor(status: PluginStatus): string {
  switch (status) {
    case 'loaded':
      return 'bg-emerald-500'
    case 'loading':
      return 'bg-blue-500'
    case 'error':
      return 'bg-red-500'
    case 'pending':
      return 'bg-amber-500'
    default:
      return 'bg-gray-500'
  }
}

interface PluginCardProps {
  loadedPlugin: LoadedPlugin
  isSelected: boolean
  isEnabled: boolean
  onSelect: () => void
  onToggleEnabled: () => void
  onViewSettings: () => void
  hasSettings: boolean
}

function PluginCard({
  loadedPlugin,
  isSelected,
  isEnabled,
  onSelect,
  onToggleEnabled,
  onViewSettings,
  hasSettings,
}: PluginCardProps): React.ReactElement {
  const { t } = useI18n()
  const { plugin, status, error } = loadedPlugin

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault()
        onSelect()
      }
    },
    [onSelect]
  )

  const handleToggleClick = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation()
      onToggleEnabled()
    },
    [onToggleEnabled]
  )

  const handleSettingsClick = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation()
      onViewSettings()
    },
    [onViewSettings]
  )

  return (
    <div
      className={cn(
        'cursor-pointer rounded-lg border bg-card p-4 transition-all hover:shadow-md',
        isSelected ? 'border-primary ring-1 ring-primary' : 'border-border'
      )}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
      tabIndex={0}
      role="button"
      aria-pressed={isSelected}
      aria-label={`${plugin.name} plugin`}
      data-testid={`plugin-card-${plugin.id}`}
    >
      <div className="mb-2 flex items-start justify-between">
        <div className="flex items-center gap-2">
          <h3 className="m-0 text-base font-semibold text-foreground">{plugin.name}</h3>
          <span className="text-xs text-muted-foreground">v{plugin.version}</span>
        </div>
        <span
          className="flex items-center gap-1.5 text-xs text-muted-foreground"
          data-testid={`plugin-status-${plugin.id}`}
        >
          <span
            className={cn('inline-block h-2 w-2 rounded-full', getStatusDotColor(status))}
            aria-hidden="true"
          />
          {getStatusText(status, t)}
        </span>
      </div>

      <div className="mb-3">
        {error ? (
          <p className="text-sm text-destructive">
            {t('plugins.errorMessage')}: {error}
          </p>
        ) : (
          <p className="text-sm text-muted-foreground">
            {t('plugins.pluginId')}: {plugin.id}
          </p>
        )}
      </div>

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">
            {isEnabled ? t('plugins.enabled') : t('plugins.disabled')}
          </span>
          <button
            type="button"
            className={cn(
              'relative inline-flex h-5 w-9 items-center rounded-full transition-colors',
              isEnabled ? 'bg-primary' : 'bg-gray-300 dark:bg-gray-600',
              status === 'loading' && 'cursor-not-allowed opacity-50'
            )}
            onClick={handleToggleClick}
            disabled={status === 'loading'}
            aria-label={isEnabled ? t('plugins.disable') : t('plugins.enable')}
            aria-pressed={isEnabled}
            data-testid={`plugin-toggle-${plugin.id}`}
          >
            <span
              className={cn(
                'inline-block h-4 w-4 rounded-full bg-white shadow transition-transform',
                isEnabled ? 'translate-x-[18px]' : 'translate-x-0.5'
              )}
            />
          </button>
        </div>

        <div>
          {hasSettings && (
            <button
              type="button"
              className="rounded-md border border-border px-3 py-1 text-xs font-medium text-primary hover:bg-muted disabled:opacity-50"
              onClick={handleSettingsClick}
              disabled={status !== 'loaded'}
              aria-label={`${t('plugins.settings')} ${plugin.name}`}
              data-testid={`plugin-settings-${plugin.id}`}
            >
              {t('plugins.settings')}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

interface PluginDetailsPanelProps {
  loadedPlugin: LoadedPlugin
  registeredFieldRenderers: string[]
  registeredPageComponents: string[]
  onClose: () => void
}

function PluginDetailsPanel({
  loadedPlugin,
  registeredFieldRenderers,
  registeredPageComponents,
  onClose,
}: PluginDetailsPanelProps): React.ReactElement {
  const { t } = useI18n()
  const { plugin, status, error } = loadedPlugin

  return (
    <aside
      className="w-80 shrink-0 rounded-lg border border-border bg-card p-4"
      aria-label={`${plugin.name} details`}
      data-testid="plugin-details-panel"
    >
      <div className="mb-4 flex items-center justify-between">
        <h2 className="m-0 text-lg font-semibold text-foreground">{plugin.name}</h2>
        <button
          type="button"
          className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
          onClick={onClose}
          aria-label={t('common.close')}
          data-testid="plugin-details-close"
        >
          x
        </button>
      </div>

      <div className="mb-4 space-y-2">
        <h3 className="text-sm font-semibold text-foreground">{t('plugins.information')}</h3>
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">{t('plugins.pluginId')}:</span>
          <span className="text-foreground">{plugin.id}</span>
        </div>
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">{t('plugins.version')}:</span>
          <span className="text-foreground">{plugin.version}</span>
        </div>
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">{t('plugins.status.label')}:</span>
          <span className="text-foreground">{getStatusText(status, t)}</span>
        </div>
        {error && (
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">{t('plugins.errorMessage')}:</span>
            <span className="text-destructive">{error}</span>
          </div>
        )}
      </div>

      <div className="mb-4 space-y-2">
        <h3 className="text-sm font-semibold text-foreground">
          {t('plugins.registeredComponents')}
        </h3>

        <div className="text-sm">
          <span className="text-muted-foreground">{t('plugins.fieldRenderers')}:</span>
          <div className="mt-1">
            {registeredFieldRenderers.length > 0 ? (
              <div className="flex flex-wrap gap-1">
                {registeredFieldRenderers.map((type) => (
                  <span
                    key={type}
                    className="rounded bg-muted px-2 py-0.5 text-xs font-medium text-foreground"
                  >
                    {type}
                  </span>
                ))}
              </div>
            ) : (
              <span className="text-xs text-muted-foreground italic">{t('common.none')}</span>
            )}
          </div>
        </div>

        <div className="text-sm">
          <span className="text-muted-foreground">{t('plugins.pageComponents')}:</span>
          <div className="mt-1">
            {registeredPageComponents.length > 0 ? (
              <div className="flex flex-wrap gap-1">
                {registeredPageComponents.map((name) => (
                  <span
                    key={name}
                    className="rounded bg-muted px-2 py-0.5 text-xs font-medium text-foreground"
                  >
                    {name}
                  </span>
                ))}
              </div>
            ) : (
              <span className="text-xs text-muted-foreground italic">{t('common.none')}</span>
            )}
          </div>
        </div>
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold text-foreground">{t('plugins.settings')}</h3>
        <p className="text-xs text-muted-foreground italic">{t('plugins.noSettingsAvailable')}</p>
      </div>
    </aside>
  )
}

export function PluginsPage({ testId = 'plugins-page' }: PluginsPageProps): React.ReactElement {
  const { t } = useI18n()
  const { plugins, isLoading, errors, fieldRenderers, pageComponents } = usePlugins()

  const [enabledPlugins, setEnabledPlugins] = useState<Set<string>>(new Set())

  React.useEffect(() => {
    const loadedPluginIds = plugins.filter((p) => p.status === 'loaded').map((p) => p.plugin.id)
    setEnabledPlugins(new Set(loadedPluginIds))
  }, [plugins])

  const [selectedPluginId, setSelectedPluginId] = useState<string | null>(null)

  const selectedPlugin = useMemo(() => {
    if (!selectedPluginId) return null
    return plugins.find((p) => p.plugin.id === selectedPluginId) ?? null
  }, [plugins, selectedPluginId])

  const registeredFieldRenderers = useMemo(() => {
    if (!selectedPlugin) return []
    const pluginFieldRenderers = selectedPlugin.plugin.fieldRenderers
    if (!pluginFieldRenderers) return []
    return Object.keys(pluginFieldRenderers)
  }, [selectedPlugin])

  const registeredPageComponents = useMemo(() => {
    if (!selectedPlugin) return []
    const pluginPageComponents = selectedPlugin.plugin.pageComponents
    if (!pluginPageComponents) return []
    return Object.keys(pluginPageComponents)
  }, [selectedPlugin])

  const handleSelectPlugin = useCallback((pluginId: string) => {
    setSelectedPluginId((prev) => (prev === pluginId ? null : pluginId))
  }, [])

  const handleToggleEnabled = useCallback((pluginId: string) => {
    setEnabledPlugins((prev) => {
      const next = new Set(prev)
      if (next.has(pluginId)) {
        next.delete(pluginId)
      } else {
        next.add(pluginId)
      }
      return next
    })
  }, [])

  const handleViewSettings = useCallback((pluginId: string) => {
    setSelectedPluginId(pluginId)
  }, [])

  const handleCloseDetails = useCallback(() => {
    setSelectedPluginId(null)
  }, [])

  const loadedCount = plugins.filter((p) => p.status === 'loaded').length
  const totalFieldRenderers = fieldRenderers.size
  const totalPageComponents = pageComponents.size

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      <header className="flex items-center justify-between">
        <h1 className="m-0 text-2xl font-semibold text-foreground">{t('navigation.plugins')}</h1>
        <div className="flex items-center gap-4 text-sm text-muted-foreground">
          <span data-testid="plugins-count">
            {t('plugins.loadedCount', {
              count: String(loadedCount),
              total: String(plugins.length),
            })}
          </span>
          {totalFieldRenderers > 0 && (
            <span data-testid="field-renderers-count">
              {t('plugins.fieldRenderersCount', { count: String(totalFieldRenderers) })}
            </span>
          )}
          {totalPageComponents > 0 && (
            <span data-testid="page-components-count">
              {t('plugins.pageComponentsCount', { count: String(totalPageComponents) })}
            </span>
          )}
        </div>
      </header>

      {errors.length > 0 && (
        <div
          className="rounded-lg border border-destructive bg-destructive/10 p-4"
          role="alert"
          data-testid="plugin-errors"
        >
          <h2 className="mb-2 text-sm font-semibold text-foreground">{t('plugins.loadErrors')}</h2>
          {errors.map((err) => (
            <div key={err.pluginId} className="flex items-center gap-2 text-sm">
              <span className="font-medium text-foreground">{err.pluginId}</span>
              <span className="text-destructive">{err.error}</span>
            </div>
          ))}
        </div>
      )}

      {plugins.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card p-16 text-center text-muted-foreground"
          data-testid="empty-state"
        >
          <p>{t('plugins.noPlugins')}</p>
        </div>
      ) : (
        <div className="flex gap-6">
          <div className="flex-1 grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-4">
            {plugins.map((loadedPlugin) => (
              <PluginCard
                key={loadedPlugin.plugin.id}
                loadedPlugin={loadedPlugin}
                isSelected={selectedPluginId === loadedPlugin.plugin.id}
                isEnabled={enabledPlugins.has(loadedPlugin.plugin.id)}
                onSelect={() => handleSelectPlugin(loadedPlugin.plugin.id)}
                onToggleEnabled={() => handleToggleEnabled(loadedPlugin.plugin.id)}
                onViewSettings={() => handleViewSettings(loadedPlugin.plugin.id)}
                hasSettings={false}
              />
            ))}
          </div>

          {selectedPlugin && (
            <PluginDetailsPanel
              loadedPlugin={selectedPlugin}
              registeredFieldRenderers={registeredFieldRenderers}
              registeredPageComponents={registeredPageComponents}
              onClose={handleCloseDetails}
            />
          )}
        </div>
      )}
    </div>
  )
}
