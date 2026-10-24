import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { Clock, ArrowRight, Columns, Repeat } from 'lucide-react'
import { cn } from '@/lib/utils'
import { DebugStatusBadge, type DebugStatus } from './DebugStatusBadge'

type ControlNodeData = {
  label: string
  stateType: 'Wait' | 'Pass' | 'Parallel' | 'Map'
  waitDuration?: string
  branchCount?: number
  debugStatus?: DebugStatus
  debugDuration?: string | null
  debugError?: string | null
  debugBorderClass?: string
}

type ControlNodeType = Node<ControlNodeData, 'control'>

const iconMap = {
  Wait: Clock,
  Pass: ArrowRight,
  Parallel: Columns,
  Map: Repeat,
} as const

const styleMap = {
  Wait: {
    bg: 'bg-gray-50 dark:bg-gray-900',
    border: 'border-gray-300 dark:border-gray-600',
    selectedBorder: 'border-gray-500 ring-2 ring-gray-500/30',
    icon: 'text-gray-600 dark:text-gray-400',
    label: 'text-gray-900 dark:text-gray-100',
    detail: 'text-gray-500 dark:text-gray-400',
    handle: '!bg-gray-400',
  },
  Pass: {
    bg: 'bg-gray-50 dark:bg-gray-900',
    border: 'border-gray-300 dark:border-gray-600 border-dashed',
    selectedBorder: 'border-gray-500 ring-2 ring-gray-500/30',
    icon: 'text-gray-600 dark:text-gray-400',
    label: 'text-gray-900 dark:text-gray-100',
    detail: 'text-gray-500 dark:text-gray-400',
    handle: '!bg-gray-400',
  },
  Parallel: {
    bg: 'bg-purple-50 dark:bg-purple-950',
    border: 'border-purple-300 dark:border-purple-700',
    selectedBorder: 'border-purple-500 ring-2 ring-purple-500/30',
    icon: 'text-purple-600 dark:text-purple-400',
    label: 'text-purple-900 dark:text-purple-100',
    detail: 'text-purple-600 dark:text-purple-400',
    handle: '!bg-purple-400',
  },
  Map: {
    bg: 'bg-teal-50 dark:bg-teal-950',
    border: 'border-teal-300 dark:border-teal-700',
    selectedBorder: 'border-teal-500 ring-2 ring-teal-500/30',
    icon: 'text-teal-600 dark:text-teal-400',
    label: 'text-teal-900 dark:text-teal-100',
    detail: 'text-teal-600 dark:text-teal-400',
    handle: '!bg-teal-400',
  },
} as const

function ControlNodeComponent({ data, selected }: NodeProps<ControlNodeType>) {
  const { debugStatus, debugDuration, debugError, debugBorderClass } = data
  const isDebug = !!debugStatus || !!debugBorderClass
  const Icon = iconMap[data.stateType]
  const style = styleMap[data.stateType]

  const detail =
    data.stateType === 'Wait' && data.waitDuration
      ? data.waitDuration
      : data.stateType === 'Parallel' && data.branchCount !== undefined
        ? `${data.branchCount} branch${data.branchCount !== 1 ? 'es' : ''}`
        : data.stateType === 'Map'
          ? 'Iterator'
          : undefined

  return (
    <div
      className={cn(
        'relative flex min-w-[160px] flex-col rounded-lg border-2 shadow-sm transition-all',
        style.bg,
        !isDebug && (selected ? style.selectedBorder : style.border),
        isDebug && debugBorderClass
      )}
    >
      <Handle type="target" position={Position.Top} className={style.handle} />
      <div className="flex items-center gap-2 px-3 py-2">
        <Icon className={`h-4 w-4 shrink-0 ${style.icon}`} />
        <div className="min-w-0 flex-1">
          <div className={`truncate text-sm font-medium ${style.label}`}>{data.label}</div>
          {detail && <div className={`text-xs ${style.detail}`}>{detail}</div>}
        </div>
      </div>
      <Handle type="source" position={Position.Bottom} className={style.handle} />
      <DebugStatusBadge
        status={debugStatus ?? null}
        duration={debugDuration ?? null}
        error={debugError ?? null}
      />
    </div>
  )
}

export const ControlNode = memo(ControlNodeComponent)
