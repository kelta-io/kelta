import { useState, useMemo } from 'react'
import { toast } from 'sonner'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetFooter } from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  RecordTriggerForm,
  ScheduledTriggerForm,
  AutolaunchedTriggerForm,
  NatsTriggerForm,
} from '@/components/flows'
import { useApi } from '@/context/ApiContext'
import { STEP_LOGGING_LEVELS, type Flow, type StepLogging, type TriggerConfig } from '../types'

/** Sentinel for "no run-as user configured" (radix Select forbids empty values). */
const RUN_AS_OWNER = '__owner__'

const STEP_LOGGING_LABELS: Record<StepLogging, string> = {
  FULL: 'Full — every step with input and output (default)',
  ERRORS_ONLY: 'Errors only — every step, snapshots for failures',
  NONE: 'None — only the failing step',
}

const MAX_RETENTION_DAYS = 3650

/** Empty input = the platform-wide window (null); otherwise a whole number of days in range. */
function parseRetentionDays(value: string): number | null | undefined {
  const trimmed = value.trim()
  if (trimmed === '') return null
  if (!/^\d+$/.test(trimmed)) return undefined
  const days = Number(trimmed)
  return days >= 1 && days <= MAX_RETENTION_DAYS ? days : undefined
}

interface TriggerEditSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  flow: Flow
}

function parseTriggerConfig(flow: Flow): Partial<TriggerConfig> {
  if (!flow.triggerConfig) return {}
  try {
    const raw = flow.triggerConfig
    if (typeof raw === 'string') return JSON.parse(raw) as Partial<TriggerConfig>
    if (typeof raw === 'object') return raw as Partial<TriggerConfig>
    return {}
  } catch {
    return {}
  }
}

export function TriggerEditSheet({ open, onOpenChange, flow }: TriggerEditSheetProps) {
  const { keltaClient } = useApi()
  const queryClient = useQueryClient()
  const initialConfig = useMemo(() => parseTriggerConfig(flow), [flow])
  const [config, setConfig] = useState<Partial<TriggerConfig>>({})
  const [runAsUserId, setRunAsUserId] = useState<string>(RUN_AS_OWNER)
  const [stepLogging, setStepLogging] = useState<StepLogging>('FULL')
  const [retentionDays, setRetentionDays] = useState('')
  const [initialized, setInitialized] = useState(false)

  // Reset config when sheet opens
  if (open && !initialized) {
    setConfig(initialConfig)
    setRunAsUserId(flow.runAsUserId ?? RUN_AS_OWNER)
    setStepLogging(flow.stepLogging ?? 'FULL')
    setRetentionDays(flow.retentionDays != null ? String(flow.retentionDays) : '')
    setInitialized(true)
  }
  if (!open && initialized) {
    setInitialized(false)
  }

  // Active users for the run-as picker (fetched only while the sheet is open)
  const { data: usersData } = useQuery({
    queryKey: ['flow-run-as-users'],
    queryFn: () => keltaClient.admin.users.list(undefined, 'ACTIVE', 0, 100),
    enabled: open,
  })
  const users = useMemo(() => {
    const content = (usersData as { content?: unknown } | undefined)?.content
    return Array.isArray(content)
      ? (content as Array<{ id: string; email?: string; firstName?: string; lastName?: string }>)
      : []
  }, [usersData])

  const parsedRetentionDays = parseRetentionDays(retentionDays)
  const retentionInvalid = parsedRetentionDays === undefined

  const saveMutation = useMutation({
    mutationFn: async () => {
      // Only send mutable fields; JSON-type fields must be objects (not strings)
      const definition = flow.definition
        ? typeof flow.definition === 'string'
          ? JSON.parse(flow.definition)
          : flow.definition
        : null
      await keltaClient.admin.flows.update(flow.id, {
        name: flow.name,
        description: flow.description,
        flowType: flow.flowType,
        active: flow.active,
        definition,
        triggerConfig: config,
        runAsUserId: runAsUserId === RUN_AS_OWNER ? null : runAsUserId,
        stepLogging,
        retentionDays: parsedRetentionDays ?? null,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['flow', flow.id] })
      toast.success('Trigger configuration saved')
      onOpenChange(false)
    },
    onError: (err: Error) => {
      toast.error(err.message || 'Failed to save trigger config')
    },
  })

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-[400px] sm:max-w-[400px]">
        <SheetHeader>
          <SheetTitle>Edit Trigger</SheetTitle>
        </SheetHeader>

        <div className="flex-1 space-y-6 overflow-y-auto px-4 py-4">
          <TriggerFormRouter
            flowType={flow.flowType}
            config={config}
            onChange={setConfig}
            flowId={flow.id}
          />

          <div className="space-y-1.5 border-t border-border pt-4">
            <Label htmlFor="flow-run-as-user">Run as user</Label>
            <Select value={runAsUserId} onValueChange={setRunAsUserId}>
              <SelectTrigger id="flow-run-as-user" data-testid="flow-run-as-select">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={RUN_AS_OWNER}>Flow owner (default)</SelectItem>
                {users.map((u) => (
                  <SelectItem key={u.id} value={u.id}>
                    {[u.firstName, u.lastName].filter(Boolean).join(' ') || u.email || u.id}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              Audit identity stamped on records this flow creates or updates when no user started
              the run (scheduled, NATS, or webhook triggers).
            </p>
          </div>

          <div className="space-y-1.5 border-t border-border pt-4">
            <Label htmlFor="flow-step-logging">Step logging</Label>
            <Select
              value={stepLogging}
              onValueChange={(value) => setStepLogging(value as StepLogging)}
            >
              <SelectTrigger id="flow-step-logging" data-testid="flow-step-logging-select">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {STEP_LOGGING_LEVELS.map((level) => (
                  <SelectItem key={level} value={level}>
                    {STEP_LOGGING_LABELS[level]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              Which step rows each run keeps. Use Errors only for high-frequency or high-volume
              flows; failed steps keep their full input and output at every level.
            </p>
          </div>

          <div className="space-y-1.5 border-t border-border pt-4">
            <Label htmlFor="flow-retention-days">Keep run history (days)</Label>
            <Input
              id="flow-retention-days"
              data-testid="flow-retention-days-input"
              type="number"
              inputMode="numeric"
              min={1}
              max={MAX_RETENTION_DAYS}
              step={1}
              placeholder="Platform default"
              value={retentionDays}
              onChange={(e) => setRetentionDays(e.target.value)}
              aria-invalid={retentionInvalid}
              aria-describedby="flow-retention-days-help"
            />
            <p id="flow-retention-days-help" className="text-xs text-muted-foreground">
              {retentionInvalid
                ? `Enter a whole number of days from 1 to ${MAX_RETENTION_DAYS}, or leave it empty.`
                : 'How long finished runs and their step logs are kept. Leave empty for the platform default (60 days unless your operator changed it).'}
            </p>
          </div>
        </div>

        <SheetFooter className="px-4 pb-4">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            onClick={() => saveMutation.mutate()}
            disabled={saveMutation.isPending || retentionInvalid}
          >
            {saveMutation.isPending ? 'Saving...' : 'Save'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

function TriggerFormRouter({
  flowType,
  config,
  onChange,
  flowId,
}: {
  flowType: string
  config: Partial<TriggerConfig>
  onChange: (config: Partial<TriggerConfig>) => void
  flowId?: string
}) {
  // The discriminated union of TriggerConfig means each form variant accepts
  // a specialized Partial; we trust the runtime flowType discriminant.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const c = config as any
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const onC = onChange as any
  switch (flowType) {
    case 'RECORD_TRIGGERED':
      return <RecordTriggerForm config={c} onChange={onC} />
    case 'SCHEDULED':
      return <ScheduledTriggerForm config={c} onChange={onC} />
    case 'AUTOLAUNCHED':
      return <AutolaunchedTriggerForm config={c} onChange={onC} flowId={flowId} />
    case 'NATS_TRIGGERED':
      return <NatsTriggerForm config={c} onChange={onC} />
    default:
      return <div className="text-sm text-muted-foreground">Unknown flow type: {flowType}</div>
  }
}
