import {
  ArrowLeft,
  Save,
  Code,
  CheckCircle,
  Play,
  Upload,
  Sparkles,
  LayoutGrid,
} from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'

interface FlowToolbarProps {
  flowName: string
  flowType: string
  isActive: boolean
  isDirty: boolean
  isSaving: boolean
  showJson: boolean
  publishedVersion?: number | null
  currentVersion?: number | null
  onSave: () => void
  onToggleJson: () => void
  onActivate?: () => void
  onTest?: () => void
  onPublish?: () => void
  onGenerate?: () => void
  onAutoLayout?: () => void
}

const flowTypeLabels: Record<string, string> = {
  RECORD_TRIGGERED: 'Record-Triggered',
  SCHEDULED: 'Scheduled',
  AUTOLAUNCHED: 'API / Manual',
  NATS_TRIGGERED: 'NATS',
  SCREEN: 'Screen',
}

export function FlowToolbar({
  flowName,
  flowType,
  isActive,
  isDirty,
  isSaving,
  showJson,
  publishedVersion,
  currentVersion,
  onSave,
  onToggleJson,
  onActivate,
  onTest,
  onPublish,
  onGenerate,
  onAutoLayout,
}: FlowToolbarProps) {
  const navigate = useNavigate()

  return (
    <div
      role="toolbar"
      aria-label="Flow actions"
      className="flex h-12 shrink-0 items-center justify-between border-b border-border bg-card px-4"
    >
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('../..', { relative: 'path' })}
          className="rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          aria-label="Back to flows"
        >
          <ArrowLeft className="h-4 w-4" />
        </button>
        <div className="flex items-center gap-2">
          <h1 className="text-sm font-semibold text-foreground">{flowName || 'Untitled Flow'}</h1>
          <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
            {flowTypeLabels[flowType] || flowType}
          </span>
          {isActive && (
            <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-semibold text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300">
              Active
            </span>
          )}
          {publishedVersion != null && (
            <span className="rounded border border-border px-1.5 py-0.5 text-[10px] font-mono text-muted-foreground">
              v{publishedVersion}
              {currentVersion != null && currentVersion > publishedVersion && ' (draft)'}
            </span>
          )}
          {isDirty && (
            <span className="h-2 w-2 rounded-full bg-amber-500" title="Unsaved changes" />
          )}
        </div>
      </div>

      <div className="flex items-center gap-2">
        {onGenerate && (
          <Button variant="outline" size="sm" onClick={onGenerate}>
            <Sparkles className="mr-1 h-3.5 w-3.5" />
            Generate
          </Button>
        )}
        <Button
          variant="outline"
          size="sm"
          onClick={onToggleJson}
          className={showJson ? 'bg-muted' : ''}
        >
          <Code className="mr-1 h-3.5 w-3.5" />
          JSON
        </Button>
        {onAutoLayout && (
          <Button
            variant="outline"
            size="sm"
            onClick={onAutoLayout}
            title="Rearrange steps top-to-bottom and untangle connections"
          >
            <LayoutGrid className="mr-1 h-3.5 w-3.5" />
            Auto layout
          </Button>
        )}
        {onTest && (
          <Button variant="outline" size="sm" onClick={onTest}>
            <Play className="mr-1 h-3.5 w-3.5" />
            Test
          </Button>
        )}
        <Button variant="outline" size="sm" onClick={onSave} disabled={isSaving || !isDirty}>
          <Save className="mr-1 h-3.5 w-3.5" />
          {isSaving ? 'Saving...' : 'Save'}
        </Button>
        {onPublish && (
          <Button variant="outline" size="sm" onClick={onPublish} disabled={isDirty}>
            <Upload className="mr-1 h-3.5 w-3.5" />
            Publish
          </Button>
        )}
        {onActivate && !isActive && (
          <Button size="sm" onClick={onActivate}>
            <CheckCircle className="mr-1 h-3.5 w-3.5" />
            Activate
          </Button>
        )}
      </div>
    </div>
  )
}
