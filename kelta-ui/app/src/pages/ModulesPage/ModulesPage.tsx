import React, { useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { componentRegistry } from '../../services/componentRegistry'
import { Button } from '@/components/ui/button'
import { ExternalModuleInstall } from './ExternalModuleInstall'
import { ExternalModuleUpgradeDialog } from './ExternalModuleUpgradeDialog'
import { ExternalModuleUninstallDialog } from './ExternalModuleUninstallDialog'
import { ExternalModuleHealth } from './ExternalModuleHealth'
import {
  ArrowUpCircle,
  Package,
  Power,
  PowerOff,
  Trash2,
  Plus,
  Puzzle,
  ChevronDown,
  ChevronUp,
  CheckCircle2,
  XCircle,
  Loader2,
  AlertTriangle,
} from 'lucide-react'

interface ModuleAction {
  id: string
  tenantModuleId: string
  actionKey: string
  name: string
  category: string | null
  description: string | null
  configSchema: string | null
  inputSchema: string | null
  outputSchema: string | null
}

/** A row of `GET /api/modules`. Every module is an external HTTPS service installed by URL. */
interface TenantModule {
  id: string
  moduleId: string
  name: string
  version: string
  description: string | null
  status: string
  installedBy: string
  installedAt: string
  updatedAt: string
  /** Absent from rows a worker older than part 9e lists. */
  actions?: ModuleAction[]
  /** The module's base URL, the HTTPS service it was installed from. */
  baseUrl: string
  protocolVersion?: number | null
  /** SHA-256 of the module's pinned admin UI bundle, the one approved at install. */
  uiBundleSha256?: string | null
  /** Why the module is DEGRADED (its service is not answering) or FAILED (install did not finish). */
  lastError?: string | null
  lastErrorAt?: string | null
}

export interface ModulesPageProps {
  testId?: string
}

function statusBadge(status: string) {
  switch (status) {
    case 'ACTIVE':
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
          <CheckCircle2 size={12} />
          Active
        </span>
      )
    case 'DISABLED':
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
          <PowerOff size={12} />
          Disabled
        </span>
      )
    case 'INSTALLED':
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
          <Package size={12} />
          Installed
        </span>
      )
    case 'FAILED':
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800">
          <XCircle size={12} />
          Failed
        </span>
      )
    case 'DEGRADED':
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-800">
          <AlertTriangle size={12} />
          Degraded
        </span>
      )
    case 'INSTALLING':
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-800">
          <Loader2 size={12} className="animate-spin" />
          Installing
        </span>
      )
    case 'UNINSTALLING':
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-800">
          <Loader2 size={12} className="animate-spin" />
          Uninstalling
        </span>
      )
    default:
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
          {status}
        </span>
      )
  }
}

/** What a module's `lastError` means in its status. Only these statuses show the error. */
const LAST_ERROR_PREFIX: Readonly<Record<string, string>> = {
  DEGRADED: 'Not answering, calls are failing fast: ',
  FAILED: 'Install did not finish: ',
}

export function ModulesPage({ testId = 'modules-page' }: ModulesPageProps): React.ReactElement {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const [isInstallOpen, setIsInstallOpen] = useState(false)
  const [expandedModule, setExpandedModule] = useState<string | null>(null)
  const [moduleToUpgrade, setModuleToUpgrade] = useState<TenantModule | null>(null)
  const [moduleToUninstall, setModuleToUninstall] = useState<TenantModule | null>(null)

  const {
    data: modules,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['modules'],
    // getList, not get: the worker answers a JSON:API collection, which get leaves wrapped.
    queryFn: () => apiClient.getList<TenantModule>('/api/modules'),
  })

  const enableMutation = useMutation({
    mutationFn: (moduleId: string) => apiClient.post(`/api/modules/${moduleId}/enable`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['modules'] })
      toast.success('Module enabled')
    },
    onError: (err: Error) => {
      toast.error(err.message || 'Failed to enable module')
    },
  })

  const disableMutation = useMutation({
    mutationFn: (moduleId: string) => apiClient.post(`/api/modules/${moduleId}/disable`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['modules'] })
      toast.success('Module disabled')
    },
    onError: (err: Error) => {
      toast.error(err.message || 'Failed to disable module')
    },
  })

  if (isLoading) {
    return <LoadingSpinner />
  }

  if (error) {
    return <ErrorMessage error={error} onRetry={() => refetch()} />
  }

  const moduleList = modules || []

  // Plugin components registered in this browser session (global client-side registry)
  const componentGroups = [
    {
      key: 'field-renderers',
      label: 'Field Renderers',
      names: componentRegistry.listFieldRenderers(),
    },
    {
      key: 'page-components',
      label: 'Page Components',
      names: componentRegistry.listPageComponents(),
    },
    {
      key: 'quick-actions',
      label: 'Quick Actions',
      names: componentRegistry.listQuickActions(),
    },
    {
      key: 'column-renderers',
      label: 'Column Renderers',
      names: componentRegistry.listColumnRenderers(),
    },
  ]
  const totalPluginComponents = componentGroups.reduce((sum, group) => sum + group.names.length, 0)

  return (
    <div data-testid={testId} className="mx-auto max-w-5xl px-6 py-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">Modules</h1>
          <p className="mt-1 text-sm text-gray-500">
            Install and manage runtime modules that extend flow capabilities
          </p>
        </div>
        <Button
          onClick={() => setIsInstallOpen(true)}
          className="gap-2"
          data-testid="install-button"
        >
          <Plus size={16} />
          Install Module
        </Button>
      </div>

      {/* Install Module Sheet */}
      {isInstallOpen && (
        <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-lg font-medium text-gray-900">Install Module</h2>
          <ExternalModuleInstall onDone={() => setIsInstallOpen(false)} />
        </div>
      )}

      {/* Plugin Components */}
      <div
        data-testid="plugin-components-section"
        className="mb-6 rounded-lg border border-gray-200 bg-white p-6 shadow-sm"
      >
        <div className="mb-1 flex items-center gap-2">
          <Puzzle size={18} className="text-blue-600" />
          <h2 className="text-lg font-medium text-gray-900">Plugin Components</h2>
        </div>
        <p className="mb-4 text-sm text-gray-500">
          UI components registered by plugin frontend bundles in this browser session
        </p>
        {totalPluginComponents === 0 ? (
          <p data-testid="plugin-components-empty" className="text-sm text-gray-400">
            No plugin components registered — components register when a module&apos;s frontend
            bundle loads.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {componentGroups.map((group) => (
              <div
                key={group.key}
                data-testid={`plugin-components-${group.key}`}
                className="rounded-md bg-gray-50 p-4"
              >
                <div className="mb-2 flex items-center justify-between">
                  <h3 className="text-sm font-medium text-gray-700">{group.label}</h3>
                  <span className="rounded-full bg-gray-200 px-2 py-0.5 text-xs text-gray-600">
                    {group.names.length}
                  </span>
                </div>
                {group.names.length === 0 ? (
                  <p className="text-xs text-gray-400">None registered</p>
                ) : (
                  <div className="flex flex-wrap gap-1.5">
                    {group.names.map((name) => (
                      <code
                        key={name}
                        className="rounded bg-blue-50 px-1.5 py-0.5 text-xs font-medium text-blue-700"
                      >
                        {name}
                      </code>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Empty State */}
      {moduleList.length === 0 && !isInstallOpen && (
        <div className="rounded-lg border border-dashed border-gray-300 p-12 text-center">
          <Package size={48} className="mx-auto mb-4 text-gray-400" />
          <h3 className="text-lg font-medium text-gray-900">No modules installed</h3>
          <p className="mt-1 text-sm text-gray-500">
            Install a module to add new action handlers to your flows
          </p>
          <Button onClick={() => setIsInstallOpen(true)} className="mt-4 gap-2">
            <Plus size={16} />
            Install Module
          </Button>
        </div>
      )}

      {/* Module Cards */}
      {moduleList.length > 0 && (
        <div className="space-y-4" data-testid="module-cards">
          {moduleList.map((mod) => (
            <div
              key={mod.id}
              className="rounded-lg border border-gray-200 bg-white shadow-sm"
              data-testid={`module-card-${mod.moduleId}`}
            >
              {/* Module Header */}
              <div className="flex items-center justify-between px-6 py-4">
                <div className="flex items-center gap-4">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-50">
                    <Package size={20} className="text-blue-600" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="text-base font-medium text-gray-900">{mod.name}</h3>
                      <span className="text-xs text-gray-500">v{mod.version}</span>
                      {statusBadge(mod.status)}
                    </div>
                    {mod.description && (
                      <p className="mt-0.5 text-sm text-gray-500">{mod.description}</p>
                    )}
                    {mod.lastError && LAST_ERROR_PREFIX[mod.status] && (
                      <p
                        className="mt-0.5 text-xs text-red-700"
                        data-testid={`module-last-error-${mod.moduleId}`}
                      >
                        {LAST_ERROR_PREFIX[mod.status]}
                        {mod.lastError}
                      </p>
                    )}
                    <p className="mt-0.5 text-xs text-gray-400">
                      {(mod.actions ?? []).length} action
                      {(mod.actions ?? []).length !== 1 ? 's' : ''} &middot; Installed{' '}
                      {new Date(mod.installedAt).toLocaleDateString()}
                    </p>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setModuleToUpgrade(mod)}
                    className="gap-1"
                    data-testid={`module-check-upgrade-${mod.moduleId}`}
                  >
                    <ArrowUpCircle size={14} />
                    Upgrade
                  </Button>
                  {mod.status === 'ACTIVE' || mod.status === 'DEGRADED' ? (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => disableMutation.mutate(mod.moduleId)}
                      disabled={disableMutation.isPending}
                      className="gap-1"
                    >
                      <PowerOff size={14} />
                      Disable
                    </Button>
                  ) : (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => enableMutation.mutate(mod.moduleId)}
                      disabled={enableMutation.isPending}
                      className="gap-1"
                    >
                      <Power size={14} />
                      {mod.status === 'FAILED' ? 'Retry install' : 'Enable'}
                    </Button>
                  )}
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setModuleToUninstall(mod)}
                    aria-label={`Uninstall ${mod.name}`}
                    className="gap-1 text-red-600 hover:bg-red-50 hover:text-red-700"
                  >
                    <Trash2 size={14} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setExpandedModule(expandedModule === mod.id ? null : mod.id)}
                  >
                    {expandedModule === mod.id ? (
                      <ChevronUp size={16} />
                    ) : (
                      <ChevronDown size={16} />
                    )}
                  </Button>
                </div>
              </div>

              {/* Expanded Actions List */}
              {expandedModule === mod.id && (
                <div className="space-y-4 border-t border-gray-100 px-6 py-4">
                  <div className="space-y-3">
                    <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-xs">
                      <dt className="text-gray-500">Base URL</dt>
                      <dd className="break-all text-gray-900">{mod.baseUrl}</dd>
                      <dt className="text-gray-500">Admin UI bundle</dt>
                      <dd className="break-all text-gray-900">
                        {mod.uiBundleSha256 ? (
                          <>
                            pinned, SHA-256 <code>{mod.uiBundleSha256}</code>
                          </>
                        ) : (
                          'none'
                        )}
                      </dd>
                    </dl>
                    <ExternalModuleHealth moduleId={mod.moduleId} />
                  </div>
                  <div>
                    <h4 className="mb-3 text-sm font-medium text-gray-700">Action Handlers</h4>
                    {(mod.actions ?? []).length === 0 ? (
                      <p className="text-sm text-gray-400">No action handlers declared</p>
                    ) : (
                      <div className="space-y-2">
                        {(mod.actions ?? []).map((action) => (
                          <div
                            key={action.id}
                            className="flex items-center justify-between rounded-md bg-gray-50 px-4 py-2.5"
                          >
                            <div>
                              <div className="flex items-center gap-2">
                                <code className="rounded bg-blue-50 px-1.5 py-0.5 text-xs font-medium text-blue-700">
                                  {action.actionKey}
                                </code>
                                <span className="text-sm text-gray-900">{action.name}</span>
                              </div>
                              {action.description && (
                                <p className="mt-0.5 text-xs text-gray-500">{action.description}</p>
                              )}
                            </div>
                            {action.category && (
                              <span className="rounded-full bg-gray-200 px-2 py-0.5 text-xs text-gray-600">
                                {action.category}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {moduleToUpgrade && (
        <ExternalModuleUpgradeDialog
          moduleId={moduleToUpgrade.moduleId}
          name={moduleToUpgrade.name}
          baseUrl={moduleToUpgrade.baseUrl}
          onClose={() => setModuleToUpgrade(null)}
        />
      )}

      {moduleToUninstall && (
        <ExternalModuleUninstallDialog
          moduleId={moduleToUninstall.moduleId}
          name={moduleToUninstall.name}
          onClose={() => setModuleToUninstall(null)}
        />
      )}
    </div>
  )
}
