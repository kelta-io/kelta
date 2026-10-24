import React, { useMemo, useState } from 'react'
import { ChevronRight, Variable } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

interface VariableNode {
  /** JSONPath-style path users will see, e.g. "$.record.data.name" */
  path: string
  /** Human-readable label shown next to the path */
  label?: string
  /** Optional type hint shown as a small chip (string, number, ...) */
  type?: string
}

export interface VariablePickerProps {
  /** Available variables (flat list of paths). Group by the first segment for display. */
  variables: VariableNode[]
  /** Called with the inserted token when the user picks a variable. */
  onPick: (token: string) => void
  /** Override the trigger button content / icon. */
  trigger?: React.ReactNode
  /** When true, the picker emits raw paths instead of `${...}` tokens. */
  raw?: boolean
}

/**
 * Popover that lists every available {@code ${$.path}} accessible from the
 * current flow state. Used by the payload mapper, JSON body editors, and any
 * other surface that wants quick variable insertion.
 */
export function VariablePicker({
  variables,
  onPick,
  trigger,
  raw,
}: VariablePickerProps): React.ReactElement {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')

  const filtered = useMemo(() => {
    if (!filter) return variables
    const q = filter.toLowerCase()
    return variables.filter(
      (v) => v.path.toLowerCase().includes(q) || (v.label?.toLowerCase().includes(q) ?? false)
    )
  }, [variables, filter])

  const grouped = useMemo(() => groupByRoot(filtered), [filtered])

  const handlePick = (path: string) => {
    onPick(raw ? path : `\${${path}}`)
    setOpen(false)
    setFilter('')
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        {trigger ?? (
          <Button size="sm" variant="outline" type="button" title="Insert variable">
            <Variable className="h-3.5 w-3.5" />
          </Button>
        )}
      </PopoverTrigger>
      <PopoverContent className="w-[320px] p-2" align="end">
        <div className="space-y-2">
          <Input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Search variables…"
            autoFocus
            className="h-8 text-xs"
          />
          <div className="max-h-72 overflow-y-auto space-y-3">
            {Object.entries(grouped).map(([root, items]) => (
              <div key={root}>
                <p className="px-1 text-[10px] uppercase tracking-wider text-muted-foreground">
                  {root}
                </p>
                <ul className="mt-1 rounded-md border divide-y">
                  {items.map((v) => (
                    <li key={v.path}>
                      <button
                        type="button"
                        onClick={() => handlePick(v.path)}
                        className={cn(
                          'flex w-full items-center gap-2 px-2 py-1.5 text-left text-xs',
                          'hover:bg-muted/60'
                        )}
                      >
                        <span className="font-mono">{v.path}</span>
                        {v.type && (
                          <span className="ml-auto text-[10px] text-muted-foreground">
                            {v.type}
                          </span>
                        )}
                        {v.label && <span className="text-muted-foreground">— {v.label}</span>}
                        <ChevronRight className="h-3 w-3 opacity-40" />
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
            {filtered.length === 0 && (
              <p className="px-2 py-3 text-xs text-muted-foreground">No variables match.</p>
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

function groupByRoot(variables: VariableNode[]): Record<string, VariableNode[]> {
  const groups: Record<string, VariableNode[]> = {}
  for (const v of variables) {
    const root = v.path.split('.').slice(0, 2).join('.')
    if (!groups[root]) groups[root] = []
    groups[root].push(v)
  }
  return groups
}
