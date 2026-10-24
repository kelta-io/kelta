import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import type { ObservabilitySetting } from '@kelta/sdk'

export interface ObservabilitySettingsPageProps {
  className?: string
}

function findSetting(
  settings: ObservabilitySetting[] | undefined,
  key: string,
  fallback: string
): string {
  return settings?.find((s) => s.settingKey === key)?.settingValue ?? fallback
}

export function ObservabilitySettingsPage({ className }: ObservabilitySettingsPageProps) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const queryClient = useQueryClient()

  const [overrides, setOverrides] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)

  const { data, isLoading, error } = useQuery({
    queryKey: ['observability-settings'],
    queryFn: () => keltaClient.admin.observability.getSettings(),
  })

  const traceRetention =
    overrides.trace_retention_days ?? findSetting(data?.settings, 'trace_retention_days', '30')
  const logRetention =
    overrides.log_retention_days ?? findSetting(data?.settings, 'log_retention_days', '30')
  const auditRetention =
    overrides.audit_retention_days ?? findSetting(data?.settings, 'audit_retention_days', '90')

  const saveMutation = useMutation({
    mutationFn: (settings: {
      trace_retention_days: string
      log_retention_days: string
      audit_retention_days: string
    }) =>
      keltaClient.admin.observability.updateSettings({
        settings: Object.entries(settings).map(([key, value]) => ({
          settingKey: key,
          settingValue: value,
        })),
      }),
    onSuccess: () => {
      setOverrides({})
      queryClient.invalidateQueries({ queryKey: ['observability-settings'] })
      setSaved(true)
      setTimeout(() => setSaved(false), 3000)
    },
  })

  const handleSave = () => {
    saveMutation.mutate({
      trace_retention_days: traceRetention,
      log_retention_days: logRetention,
      audit_retention_days: auditRetention,
    })
  }

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('observabilitySettings.loadError')} />

  return (
    <div
      className={cn('mx-auto max-w-[800px]', className)}
      data-testid="observability-settings-page"
    >
      <div className="mb-4">
        <h2 className="m-0 text-lg font-semibold text-foreground">
          {t('observabilitySettings.title')}
        </h2>
        <p className="mt-2 text-sm text-muted-foreground">
          {t('observabilitySettings.description')}
        </p>
      </div>

      <div className="rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold text-foreground">
          {t('observabilitySettings.retention')}
        </h2>

        <div className="space-y-4">
          <div className="flex items-center gap-4">
            <label className="min-w-[200px] text-sm font-medium text-foreground">
              {t('observabilitySettings.traceRetention')}
            </label>
            <input
              data-testid="obs-settings-trace-retention"
              type="number"
              min="1"
              max="365"
              className="w-24 rounded-md border border-border bg-background p-2 text-sm text-foreground"
              value={traceRetention}
              onChange={(e) =>
                setOverrides((prev) => ({ ...prev, trace_retention_days: e.target.value }))
              }
            />
            <span className="text-sm text-muted-foreground">{t('observabilitySettings.days')}</span>
          </div>

          <div className="flex items-center gap-4">
            <label className="min-w-[200px] text-sm font-medium text-foreground">
              {t('observabilitySettings.logRetention')}
            </label>
            <input
              data-testid="obs-settings-log-retention"
              type="number"
              min="1"
              max="365"
              className="w-24 rounded-md border border-border bg-background p-2 text-sm text-foreground"
              value={logRetention}
              onChange={(e) =>
                setOverrides((prev) => ({ ...prev, log_retention_days: e.target.value }))
              }
            />
            <span className="text-sm text-muted-foreground">{t('observabilitySettings.days')}</span>
          </div>

          <div className="flex items-center gap-4">
            <label className="min-w-[200px] text-sm font-medium text-foreground">
              {t('observabilitySettings.auditRetention')}
            </label>
            <input
              data-testid="obs-settings-audit-retention"
              type="number"
              min="1"
              max="365"
              className="w-24 rounded-md border border-border bg-background p-2 text-sm text-foreground"
              value={auditRetention}
              onChange={(e) =>
                setOverrides((prev) => ({ ...prev, audit_retention_days: e.target.value }))
              }
            />
            <span className="text-sm text-muted-foreground">{t('observabilitySettings.days')}</span>
          </div>
        </div>

        <div className="mt-6 flex items-center gap-4">
          <button
            data-testid="obs-settings-save"
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
            onClick={handleSave}
            disabled={saveMutation.isPending}
          >
            {saveMutation.isPending ? t('common.saving') : t('common.save')}
          </button>
          {saved && (
            <span
              data-testid="obs-settings-success"
              className="text-sm text-emerald-600 dark:text-emerald-400"
            >
              {t('observabilitySettings.saved')}
            </span>
          )}
        </div>
      </div>
    </div>
  )
}
