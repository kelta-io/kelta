import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { GitBranch } from 'lucide-react'
import { cn } from '@/lib/utils'
import { DebugStatusBadge, type DebugStatus } from './DebugStatusBadge'

type ChoiceNodeData = {
  label: string
  ruleCount?: number
  stateType: string
  debugStatus?: DebugStatus
  debugDuration?: string | null
  debugError?: string | null
  debugBorderClass?: string
}

type ChoiceNodeType = Node<ChoiceNodeData, 'choice'>

function ChoiceNodeComponent({ data, selected }: NodeProps<ChoiceNodeType>) {
  const { debugStatus, debugDuration, debugError, debugBorderClass } = data
  const isDebug = !!debugStatus || !!debugBorderClass

  return (
    <div
      className={cn(
        'relative flex min-w-[140px] flex-col items-center rounded-lg border-2 bg-amber-50 shadow-sm transition-all dark:bg-amber-950',
        !isDebug &&
          (selected
            ? 'border-amber-500 ring-2 ring-amber-500/30'
            : 'border-amber-300 dark:border-amber-700'),
        isDebug && debugBorderClass
      )}
    >
      <Handle type="target" position={Position.Top} className="!bg-amber-400" />
      <div className="flex items-center gap-2 px-3 py-2">
        <GitBranch className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
        <div className="min-w-0">
          <div className="truncate text-sm font-medium text-amber-900 dark:text-amber-100">
            {data.label}
          </div>
          {data.ruleCount !== undefined && (
            <div className="text-xs text-amber-600 dark:text-amber-400">
              {data.ruleCount} rule{data.ruleCount !== 1 ? 's' : ''}
            </div>
          )}
        </div>
      </div>
      <Handle type="source" position={Position.Bottom} className="!bg-amber-400" />
      <DebugStatusBadge
        status={debugStatus ?? null}
        duration={debugDuration ?? null}
        error={debugError ?? null}
      />
    </div>
  )
}

export const ChoiceNode = memo(ChoiceNodeComponent)
