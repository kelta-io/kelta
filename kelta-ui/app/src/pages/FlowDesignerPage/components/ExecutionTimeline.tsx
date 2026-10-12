import { cn } from '@/lib/utils'
import { formatDuration } from '@/utils/formatDuration'

interface StepData {
  id: string
  stateId: string
  stateName: string | null
  stateType: string
  status: string
  durationMs: number | null
}

interface ExecutionTimelineProps {
  steps: StepData[]
  selectedStepId: string | null
  onStepSelect: (stateId: string) => void
}

const statusColors: Record<string, string> = {
  SUCCEEDED: 'bg-emerald-500',
  FAILED: 'bg-red-500',
  RUNNING: 'bg-blue-500 animate-pulse',
  SKIPPED: 'bg-gray-300',
}

export function ExecutionTimeline({ steps, selectedStepId, onStepSelect }: ExecutionTimelineProps) {
  const totalDuration = steps.reduce((sum, s) => sum + (s.durationMs || 0), 0)

  return (
    <div className="border-t border-border bg-card px-4 py-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-medium text-muted-foreground">
          Execution Timeline ({steps.length} steps, {formatDuration(totalDuration)})
        </span>
      </div>
      <div className="flex h-8 gap-0.5 overflow-hidden rounded">
        {steps.map((step) => {
          const widthPercent =
            totalDuration > 0 && step.durationMs
              ? Math.max((step.durationMs / totalDuration) * 100, 3)
              : 100 / steps.length
          return (
            <button
              key={step.id}
              className={cn(
                'relative h-full transition-opacity hover:opacity-80',
                statusColors[step.status] || 'bg-gray-400',
                selectedStepId === step.stateId && 'ring-2 ring-foreground ring-offset-1'
              )}
              style={{ width: `${widthPercent}%`, minWidth: '12px' }}
              onClick={() => onStepSelect(step.stateId)}
              title={`${step.stateName || step.stateId} (${step.status}) — ${formatDuration(step.durationMs)}`}
            />
          )
        })}
      </div>
    </div>
  )
}
