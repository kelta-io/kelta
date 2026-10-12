import React, { useState } from 'react'
import { cn } from '@/lib/utils'
import { CheckCircle2, XCircle, Loader2, SkipForward, X } from 'lucide-react'
import { formatDuration } from '@/utils/formatDuration'

interface StepLog {
  id: string
  stateId: string
  stateName: string | null
  stateType: string
  status: string
  inputSnapshot: Record<string, unknown> | null
  outputSnapshot: Record<string, unknown> | null
  errorMessage: string | null
  errorCode: string | null
  attemptNumber: number
  durationMs: number | null
  startedAt: string | null
  completedAt: string | null
}

interface StepDetailPanelProps {
  step: StepLog | null
  onClose: () => void
}

type Tab = 'input' | 'output' | 'error' | 'raw'

const statusIcons: Record<string, React.ReactNode> = {
  SUCCEEDED: <CheckCircle2 className="h-4 w-4 text-emerald-600" />,
  FAILED: <XCircle className="h-4 w-4 text-red-600" />,
  RUNNING: <Loader2 className="h-4 w-4 animate-spin text-blue-600" />,
  SKIPPED: <SkipForward className="h-4 w-4 text-gray-400" />,
}

function JsonViewer({ data }: { data: unknown }) {
  if (data == null) {
    return <p className="text-sm italic text-muted-foreground">No data available</p>
  }
  return (
    <pre className="max-h-[300px] overflow-auto rounded border border-border bg-muted p-3 font-mono text-xs text-foreground">
      {JSON.stringify(data, null, 2)}
    </pre>
  )
}

export function StepDetailPanel({ step, onClose }: StepDetailPanelProps) {
  const [activeTab, setActiveTab] = useState<Tab>('input')

  if (!step) {
    return (
      <div className="flex w-80 shrink-0 items-center justify-center border-l border-border bg-card p-4">
        <p className="text-sm text-muted-foreground">Select a step to view details</p>
      </div>
    )
  }

  const tabs: { key: Tab; label: string; show: boolean }[] = [
    { key: 'input', label: 'Input', show: true },
    { key: 'output', label: 'Output', show: true },
    { key: 'error', label: 'Error', show: !!step.errorMessage },
    { key: 'raw', label: 'Raw', show: true },
  ]

  return (
    <div className="flex w-80 shrink-0 flex-col overflow-hidden border-l border-border bg-card">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <div className="flex items-center gap-2">
          {statusIcons[step.status] || null}
          <div>
            <div className="text-sm font-medium text-foreground">
              {step.stateName || step.stateId}
            </div>
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <span>{step.stateType}</span>
              <span>&middot;</span>
              <span>{formatDuration(step.durationMs)}</span>
              {step.attemptNumber > 1 && (
                <>
                  <span>&middot;</span>
                  <span>Attempt {step.attemptNumber}</span>
                </>
              )}
            </div>
          </div>
        </div>
        <button
          onClick={onClose}
          className="rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          aria-label="Close step detail"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border">
        {tabs
          .filter((t) => t.show)
          .map((tab) => (
            <button
              key={tab.key}
              className={cn(
                'flex-1 border-b-2 px-3 py-2 text-xs font-medium transition-colors',
                activeTab === tab.key
                  ? 'border-primary text-primary'
                  : 'border-transparent text-muted-foreground hover:text-foreground'
              )}
              onClick={() => setActiveTab(tab.key)}
            >
              {tab.label}
            </button>
          ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto p-4">
        {activeTab === 'input' && <JsonViewer data={step.inputSnapshot} />}
        {activeTab === 'output' && <JsonViewer data={step.outputSnapshot} />}
        {activeTab === 'error' && (
          <div className="space-y-3">
            {step.errorCode && (
              <div>
                <div className="mb-1 text-xs font-medium text-muted-foreground">Error Code</div>
                <code className="rounded bg-red-50 px-2 py-1 text-xs font-medium text-red-700 dark:bg-red-950 dark:text-red-300">
                  {step.errorCode}
                </code>
              </div>
            )}
            {step.errorMessage && (
              <div>
                <div className="mb-1 text-xs font-medium text-muted-foreground">Error Message</div>
                <pre className="max-h-[200px] overflow-auto rounded border border-red-200 bg-red-50 p-3 font-mono text-xs text-red-800 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
                  {step.errorMessage}
                </pre>
              </div>
            )}
          </div>
        )}
        {activeTab === 'raw' && <JsonViewer data={step} />}
      </div>
    </div>
  )
}
