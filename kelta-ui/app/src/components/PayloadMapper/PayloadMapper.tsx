import React, { useEffect, useMemo, useState } from 'react'
import { Code2, Eye, FormInput } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Card, CardContent } from '@/components/ui/card'
import { FieldLabel } from '@/components/kelta'
import { VariablePicker } from '@/components/VariablePicker/VariablePicker'
import { cn } from '@/lib/utils'
import {
  type BindingKind,
  type MappingMap,
  type SourceVariable,
  type TargetBinding,
  type TargetField,
} from './types'
import { deserializeMapping, serializeBindings } from './mappingSerializer'

export interface PayloadMapperProps {
  /** Schema describing the fields the consumer wants the mapper to fill. */
  targetSchema: TargetField[]
  /** Available variables from flow state — drives the variable picker tree. */
  sourceVariables: SourceVariable[]
  /** Current value (the JSON template shape the worker expects). */
  value: Record<string, unknown> | null | undefined
  /** Called with the new template when bindings change. */
  onChange: (next: Record<string, unknown>) => void
  /** Optional: render a header (e.g., the page/sheet title) above the editor. */
  header?: React.ReactNode
  /** Optional: render a footer (e.g., Save / Cancel buttons) below. */
  footer?: React.ReactNode
}

type View = 'visual' | 'code' | 'preview'

/**
 * Reusable visual mapper. Each row in the target schema gets a binding
 * (constant / variable / expression) that the consumer can edit inline.
 * The component serializes to the JSON template shape consumed by the
 * platform's PayloadMapperService, so consumers can persist whatever
 * {@link PayloadMapperProps#onChange} returns directly.
 */
export function PayloadMapper({
  targetSchema,
  sourceVariables,
  value,
  onChange,
  header,
  footer,
}: PayloadMapperProps): React.ReactElement {
  const targetPaths = useMemo(() => targetSchema.map((t) => t.path), [targetSchema])
  const [bindings, setBindings] = useState<MappingMap>(() =>
    deserializeMapping(value ?? {}, targetPaths)
  )

  // When the consumer hands us a new value (e.g., loaded from the server),
  // reset our local bindings.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- existing sync-from-prop/storage effect; refactor tracked as follow-up to PLT-854
    setBindings(deserializeMapping(value ?? {}, targetPaths))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(value), JSON.stringify(targetPaths)])

  const updateBinding = (path: string, next: TargetBinding) => {
    setBindings((prev) => {
      const updated = { ...prev, [path]: next }
      onChange(serializeBindings(updated))
      return updated
    })
  }

  const [view, setView] = useState<View>('visual')

  return (
    <div className="space-y-4">
      {header}

      <div className="flex items-center gap-2">
        <ViewTab
          active={view === 'visual'}
          onClick={() => setView('visual')}
          icon={<FormInput className="h-3.5 w-3.5" />}
        >
          Visual
        </ViewTab>
        <ViewTab
          active={view === 'code'}
          onClick={() => setView('code')}
          icon={<Code2 className="h-3.5 w-3.5" />}
        >
          Code
        </ViewTab>
        <ViewTab
          active={view === 'preview'}
          onClick={() => setView('preview')}
          icon={<Eye className="h-3.5 w-3.5" />}
        >
          Preview
        </ViewTab>
      </div>

      {view === 'visual' && (
        <div className="space-y-2">
          {targetSchema.map((field) => (
            <BindingRow
              key={field.path}
              field={field}
              binding={bindings[field.path] ?? { kind: 'unset', value: '' }}
              variables={sourceVariables}
              onChange={(next) => updateBinding(field.path, next)}
            />
          ))}
        </div>
      )}

      {view === 'code' && (
        <CodeView
          template={serializeBindings(bindings)}
          onChange={(parsed) => {
            setBindings(deserializeMapping(parsed, targetPaths))
            onChange(parsed)
          }}
        />
      )}

      {view === 'preview' && (
        <PreviewView template={serializeBindings(bindings)} variables={sourceVariables} />
      )}

      {footer}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Visual view — one row per target field
// ---------------------------------------------------------------------------

interface BindingRowProps {
  field: TargetField
  binding: TargetBinding
  variables: SourceVariable[]
  onChange: (next: TargetBinding) => void
}

function BindingRow({ field, binding, variables, onChange }: BindingRowProps) {
  const setKind = (kind: BindingKind) => {
    onChange({ kind, value: binding.value })
  }

  return (
    <Card className="bg-background">
      <CardContent className="p-3 space-y-2">
        <div className="flex items-baseline justify-between gap-2">
          <div className="min-w-0">
            <FieldLabel>
              {field.label}
              {field.required ? <span className="text-red-500"> *</span> : null}
            </FieldLabel>
            <p className="text-xs text-muted-foreground font-mono truncate">
              {field.path}
              {field.type ? ` · ${field.type}` : ''}
            </p>
            {field.description && (
              <p className="text-xs text-muted-foreground">{field.description}</p>
            )}
          </div>
          <Select value={binding.kind} onValueChange={(v) => setKind(v as BindingKind)}>
            <SelectTrigger className="h-7 w-32 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="unset">Unset</SelectItem>
              <SelectItem value="constant">Constant</SelectItem>
              <SelectItem value="variable">Variable</SelectItem>
              <SelectItem value="expression">Expression</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <BindingEditor binding={binding} onChange={onChange} variables={variables} />
      </CardContent>
    </Card>
  )
}

interface BindingEditorProps {
  binding: TargetBinding
  variables: SourceVariable[]
  onChange: (next: TargetBinding) => void
}

function BindingEditor({ binding, variables, onChange }: BindingEditorProps) {
  if (binding.kind === 'unset') {
    return (
      <p className="text-xs text-muted-foreground italic">
        No value bound — the field will be omitted from the payload.
      </p>
    )
  }

  if (binding.kind === 'constant') {
    return (
      <Input
        value={binding.value}
        onChange={(e) => onChange({ ...binding, value: e.target.value })}
        placeholder="Literal value"
        className="font-mono text-xs"
      />
    )
  }

  if (binding.kind === 'variable') {
    return (
      <div className="flex items-center gap-2">
        <Input
          value={binding.value}
          onChange={(e) => onChange({ ...binding, value: e.target.value })}
          placeholder="$.input.fieldName"
          className="font-mono text-xs flex-1"
        />
        <VariablePicker
          variables={variables}
          raw
          onPick={(token) => onChange({ ...binding, value: token })}
        />
      </div>
    )
  }

  // expression
  return (
    <Textarea
      rows={3}
      value={binding.value}
      onChange={(e) => onChange({ ...binding, value: e.target.value })}
      placeholder="$sum(items.price)"
      className="font-mono text-xs"
    />
  )
}

// ---------------------------------------------------------------------------
// Code view — raw JSON editor
// ---------------------------------------------------------------------------

function CodeView({
  template,
  onChange,
}: {
  template: Record<string, unknown>
  onChange: (parsed: Record<string, unknown>) => void
}) {
  const [text, setText] = useState(() => JSON.stringify(template, null, 2))
  const [error, setError] = useState<string | null>(null)

  // Resync editor text when parent passes a new template shape.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setText(JSON.stringify(template, null, 2))
  }, [JSON.stringify(template)])

  return (
    <div className="space-y-2">
      <Textarea
        rows={14}
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          try {
            const parsed = JSON.parse(e.target.value)
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
              setError(null)
              onChange(parsed as Record<string, unknown>)
            } else {
              setError('Mapping must be a JSON object at the top level')
            }
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Invalid JSON')
          }
        }}
        className="font-mono text-xs"
      />
      {error && <p className="text-xs text-red-600">{error}</p>}
      <p className="text-xs text-muted-foreground">
        Strings starting with <code>=</code> are evaluated as JSONata; everything else flows through{' '}
        <code>${'${$.path}'}</code> substitution.
      </p>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Preview view — best-effort client-side resolution against synthetic data
// ---------------------------------------------------------------------------

function PreviewView({
  template,
  variables,
}: {
  template: Record<string, unknown>
  variables: SourceVariable[]
}) {
  const sample = useMemo(() => buildSampleState(variables), [variables])
  const [stateJson, setStateJson] = useState(() => JSON.stringify(sample, null, 2))
  const resolved = useMemo(() => {
    try {
      const state = JSON.parse(stateJson)
      return resolveTemplate(template, state)
    } catch {
      return null
    }
  }, [stateJson, template])

  return (
    <div className="grid gap-3 md:grid-cols-2">
      <div className="space-y-1">
        <FieldLabel>Sample state</FieldLabel>
        <Textarea
          rows={14}
          value={stateJson}
          onChange={(e) => setStateJson(e.target.value)}
          className="font-mono text-xs"
        />
      </div>
      <div className="space-y-1">
        <FieldLabel>Resolved payload</FieldLabel>
        <pre className="rounded-md border bg-muted/30 p-3 text-xs font-mono whitespace-pre-wrap">
          {resolved ? JSON.stringify(resolved, null, 2) : '— invalid sample state —'}
        </pre>
        <p className="text-xs text-muted-foreground">
          Preview resolves <code>${'${$.path}'}</code> tokens client-side. JSONata expressions are
          not evaluated here — they're only run by the worker.
        </p>
      </div>
    </div>
  )
}

function buildSampleState(variables: SourceVariable[]): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const v of variables) {
    setSamplePath(out, v.path, sampleValueFor(v))
  }
  return out
}

function sampleValueFor(v: SourceVariable): unknown {
  if (v.type === 'number' || v.type === 'integer') return 42
  if (v.type === 'boolean') return true
  if (v.type === 'array') return ['…']
  if (v.type === 'object') return {}
  return v.label ?? `<${v.path}>`
}

function setSamplePath(target: Record<string, unknown>, path: string, value: unknown) {
  const segments = path
    .replace(/^\$\.?/, '')
    .split('.')
    .filter(Boolean)
  if (segments.length === 0) return
  let cursor: Record<string, unknown> = target
  for (let i = 0; i < segments.length - 1; i++) {
    const seg = segments[i]
    if (typeof cursor[seg] !== 'object' || cursor[seg] === null) {
      cursor[seg] = {}
    }
    cursor = cursor[seg] as Record<string, unknown>
  }
  cursor[segments[segments.length - 1]] = value
}

function resolveTemplate(template: unknown, state: Record<string, unknown>): unknown {
  if (template === null || template === undefined) return template
  if (typeof template === 'string') return resolveString(template, state)
  if (Array.isArray(template)) return template.map((item) => resolveTemplate(item, state))
  if (typeof template === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(template)) {
      out[k] = resolveTemplate(v, state)
    }
    return out
  }
  return template
}

const TOKEN = /\$\{\$\.([^}]+)\}/g

function resolveString(template: string, state: Record<string, unknown>): unknown {
  // Whole-string token returns the raw value (preserves type)
  const wholeMatch = template.match(/^\$\{\$\.([^}]+)\}$/)
  if (wholeMatch) {
    return readPath(state, wholeMatch[1])
  }
  // Otherwise interpolate inline
  return template.replace(TOKEN, (_match, path: string) => {
    const value = readPath(state, path)
    return value === undefined ? '' : String(value)
  })
}

function readPath(state: Record<string, unknown>, path: string): unknown {
  const segments = path.split('.')
  let cursor: unknown = state
  for (const seg of segments) {
    if (cursor == null || typeof cursor !== 'object') return undefined
    cursor = (cursor as Record<string, unknown>)[seg]
  }
  return cursor
}

// ---------------------------------------------------------------------------
// Tab pill
// ---------------------------------------------------------------------------

function ViewTab({
  active,
  onClick,
  icon,
  children,
}: {
  active: boolean
  onClick: () => void
  icon: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <Button
      type="button"
      variant={active ? 'default' : 'outline'}
      size="sm"
      onClick={onClick}
      className={cn('gap-1.5')}
    >
      {icon}
      {children}
    </Button>
  )
}
