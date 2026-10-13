import React, { useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { FieldLabel, StatusBadge } from '@/components/kelta'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Bot, Zap, Settings } from 'lucide-react'
import { cn } from '@/lib/utils'

interface AiConfig {
  model: string
  maxTokens: string
  temperature: string
  aiTokensPerMonth: string
  aiEnabled: string
}

interface AiUsageHistoryEntry {
  inputTokens: number
  outputTokens: number
  requestCount: number
}

interface AiUsage {
  currentMonthUsage: number
  tokenLimit: number
  history: Record<string, AiUsageHistoryEntry>
}

const AVAILABLE_MODELS = [
  { value: 'claude-sonnet-4-20250514', label: 'Claude Sonnet 4' },
  { value: 'claude-opus-4-20250514', label: 'Claude Opus 4' },
  { value: 'claude-haiku-4-20250514', label: 'Claude Haiku 4' },
]

function formatTokenCount(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`
  if (count >= 1_000) return `${(count / 1_000).toFixed(0)}K`
  return String(count)
}

export interface AiSettingsPageProps {
  className?: string
}

export function AiSettingsPage({ className }: AiSettingsPageProps): React.ReactElement {
  const { keltaClient } = useApi()
  const { hasPermission } = useSystemPermissions()
  const queryClient = useQueryClient()

  const canManage = hasPermission('MANAGE_TENANTS')
  const [editConfig, setEditConfig] = useState<AiConfig | null>(null)

  const {
    data: config,
    isLoading: configLoading,
    error: configError,
  } = useQuery({
    queryKey: ['ai-config'],
    queryFn: () => keltaClient.admin.ai.config.get(),
  })

  const { data: usage, isLoading: usageLoading } = useQuery<AiUsage>({
    queryKey: ['ai-usage'],
    queryFn: () => keltaClient.admin.ai.usage() as Promise<AiUsage>,
    refetchInterval: 60000,
  })

  const saveMutation = useMutation({
    mutationFn: (updates: Partial<AiConfig>) => keltaClient.admin.ai.config.update(updates),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ai-config'] })
      queryClient.invalidateQueries({ queryKey: ['ai-usage'] })
      toast.success('AI settings saved successfully')
      setEditConfig(null)
    },
    onError: () => {
      toast.error('Failed to save AI settings')
    },
  })

  if (configLoading) return <LoadingSpinner />
  if (configError) return <ErrorMessage error="Failed to load AI configuration" />

  const currentConfig = editConfig ?? config
  const isEditing = editConfig !== null

  return (
    <div className={cn('space-y-6', className)}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Bot className="h-6 w-6 text-primary" />
          <div>
            <h1 className="text-[26px] font-bold tracking-[-0.01em]">AI settings</h1>
            <p className="text-sm text-muted-foreground">
              Configure AI assistant model, limits, and monitoring.
            </p>
          </div>
        </div>
        {canManage && !isEditing && (
          <Button onClick={() => setEditConfig(config ?? null)}>
            <Settings className="mr-2 h-4 w-4" />
            Edit settings
          </Button>
        )}
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        {/* Model Configuration */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Model configuration</CardTitle>
            <CardDescription>Select the AI model and parameters.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-2">
              <FieldLabel>Model</FieldLabel>
              {isEditing ? (
                <Select
                  value={currentConfig?.model}
                  onValueChange={(v) =>
                    setEditConfig((prev) => (prev ? { ...prev, model: v } : null))
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {AVAILABLE_MODELS.map((m) => (
                      <SelectItem key={m.value} value={m.value}>
                        {m.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <p className="text-sm font-medium">
                  {AVAILABLE_MODELS.find((m) => m.value === currentConfig?.model)?.label ??
                    currentConfig?.model}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <FieldLabel>Max tokens</FieldLabel>
              {isEditing ? (
                <Input
                  type="number"
                  value={currentConfig?.maxTokens}
                  onChange={(e) =>
                    setEditConfig((prev) => (prev ? { ...prev, maxTokens: e.target.value } : null))
                  }
                />
              ) : (
                <p className="text-sm font-medium">{currentConfig?.maxTokens}</p>
              )}
            </div>

            <div className="space-y-2">
              <FieldLabel>Temperature</FieldLabel>
              {isEditing ? (
                <Input
                  type="number"
                  step="0.1"
                  min="0"
                  max="1"
                  value={currentConfig?.temperature}
                  onChange={(e) =>
                    setEditConfig((prev) =>
                      prev ? { ...prev, temperature: e.target.value } : null
                    )
                  }
                />
              ) : (
                <p className="text-sm font-medium">{currentConfig?.temperature}</p>
              )}
            </div>
          </CardContent>
        </Card>

        {/* Token Limits */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Token limits</CardTitle>
            <CardDescription>Configure monthly token usage limits.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between">
              <FieldLabel className="mb-0">AI enabled</FieldLabel>
              {isEditing ? (
                <Switch
                  checked={currentConfig?.aiEnabled === 'true'}
                  onCheckedChange={(checked) =>
                    setEditConfig((prev) => (prev ? { ...prev, aiEnabled: String(checked) } : null))
                  }
                />
              ) : currentConfig?.aiEnabled === 'true' ? (
                <StatusBadge variant="active" label="Enabled" />
              ) : (
                <StatusBadge variant="inactive" label="Disabled" />
              )}
            </div>

            <div className="space-y-2">
              <FieldLabel>Monthly token limit</FieldLabel>
              {isEditing ? (
                <Input
                  type="number"
                  value={currentConfig?.aiTokensPerMonth}
                  onChange={(e) =>
                    setEditConfig((prev) =>
                      prev ? { ...prev, aiTokensPerMonth: e.target.value } : null
                    )
                  }
                />
              ) : (
                <p className="text-sm font-medium">
                  {formatTokenCount(Number(currentConfig?.aiTokensPerMonth ?? 0))} tokens/month
                </p>
              )}
            </div>
          </CardContent>
        </Card>

        {/* Usage Dashboard */}
        <Card className="md:col-span-2">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Zap className="h-4 w-4" />
              Current month usage
            </CardTitle>
          </CardHeader>
          <CardContent>
            {usageLoading ? (
              <LoadingSpinner />
            ) : usage ? (
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <span className="text-sm text-muted-foreground">Tokens used</span>
                  <span className="font-mono text-lg font-semibold tabular-nums">
                    {formatTokenCount(usage.currentMonthUsage)} /{' '}
                    {formatTokenCount(usage.tokenLimit)}
                  </span>
                </div>
                {/* Progress bar */}
                <div className="h-3 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className={cn(
                      'h-full rounded-full transition-all',
                      usage.currentMonthUsage / usage.tokenLimit >= 1
                        ? 'bg-red-500'
                        : usage.currentMonthUsage / usage.tokenLimit >= 0.8
                          ? 'bg-yellow-500'
                          : 'bg-primary'
                    )}
                    style={{
                      width: `${Math.min((usage.currentMonthUsage / usage.tokenLimit) * 100, 100)}%`,
                    }}
                  />
                </div>

                {/* Monthly history */}
                {Object.keys(usage.history).length > 0 && (
                  <div className="mt-4">
                    <h4 className="mb-2 text-sm font-medium">Monthly history</h4>
                    <div className="grid grid-cols-3 gap-2 text-xs">
                      <div className="kelta-field-label !mb-0">Month</div>
                      <div className="kelta-field-label !mb-0">Tokens</div>
                      <div className="kelta-field-label !mb-0">Requests</div>
                      {Object.entries(usage.history).map(([month, data]) => (
                        <React.Fragment key={month}>
                          <div>{month}</div>
                          <div className="font-mono tabular-nums">
                            {formatTokenCount(data.inputTokens + data.outputTokens)}
                          </div>
                          <div className="font-mono tabular-nums">{data.requestCount}</div>
                        </React.Fragment>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">No usage data available</p>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Edit actions */}
      {isEditing && (
        <div className="flex gap-2 justify-end">
          <Button variant="outline" onClick={() => setEditConfig(null)}>
            Cancel
          </Button>
          <Button
            onClick={() => editConfig && saveMutation.mutate(editConfig)}
            disabled={saveMutation.isPending}
          >
            {saveMutation.isPending ? 'Saving...' : 'Save changes'}
          </Button>
        </div>
      )}
    </div>
  )
}
