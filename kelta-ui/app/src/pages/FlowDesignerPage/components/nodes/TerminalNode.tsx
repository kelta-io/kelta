import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { CheckCircle, XCircle } from 'lucide-react'
import { cn } from '@/lib/utils'
import { DebugStatusBadge, type DebugStatus } from './DebugStatusBadge'

type TerminalNodeData = {
  label: string
  stateType: 'Succeed' | 'Fail'
  error?: string
  cause?: string
  debugStatus?: DebugStatus
  debugDuration?: string | null
  debugError?: string | null
  debugBorderClass?: string
}

type TerminalNodeType = Node<TerminalNodeData, 'terminal'>

function TerminalNodeComponent({ data, selected }: NodeProps<TerminalNodeType>) {
  const { debugStatus, debugDuration, debugError, debugBorderClass } = data
  const isDebug = !!debugStatus || !!debugBorderClass
  const isSuccess = data.stateType === 'Succeed'
  const Icon = isSuccess ? CheckCircle : XCircle

  const naturalBorder = isSuccess
    ? selected
      ? 'border-green-500 bg-green-50 ring-2 ring-green-500/30 dark:bg-green-950'
      : 'border-green-400 bg-green-50 dark:border-green-600 dark:bg-green-950'
    : selected
      ? 'border-red-500 bg-red-50 ring-2 ring-red-500/30 dark:bg-red-950'
      : 'border-red-400 bg-red-50 dark:border-red-600 dark:bg-red-950'

  // In debug mode, keep the natural background but let debugBorderClass override the border/ring.
  const debugBackground = isSuccess ? 'bg-green-50 dark:bg-green-950' : 'bg-red-50 dark:bg-red-950'

  return (
    <div
      className={cn(
        'relative flex min-w-[120px] items-center gap-2 rounded-full border-2 px-4 py-2 shadow-sm transition-all',
        !isDebug && naturalBorder,
        isDebug && debugBackground,
        isDebug && debugBorderClass
      )}
    >
      <Handle
        type="target"
        position={Position.Top}
        className={isSuccess ? '!bg-green-400' : '!bg-red-400'}
      />
      <Icon
        className={`h-4 w-4 shrink-0 ${
          isSuccess ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
        }`}
      />
      <span
        className={`text-sm font-medium ${
          isSuccess ? 'text-green-900 dark:text-green-100' : 'text-red-900 dark:text-red-100'
        }`}
      >
        {data.label}
      </span>
      <DebugStatusBadge
        status={debugStatus ?? null}
        duration={debugDuration ?? null}
        error={debugError ?? null}
      />
    </div>
  )
}

export const TerminalNode = memo(TerminalNodeComponent)
