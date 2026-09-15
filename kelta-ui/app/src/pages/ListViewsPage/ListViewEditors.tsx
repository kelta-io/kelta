/**
 * Schema-driven editors for the List View form — replace the raw-JSON textareas so authors never
 * type field names or JSON:
 *  - {@link ColumnsEditor}  — pick + reorder which fields are columns.
 *  - {@link FilterEditor}   — rows of field / operator / value.
 *  - {@link SortEditor}     — reorderable rows of field + direction.
 *
 * All three are controlled (value + onChange) and pure-presentational; the parent owns persistence.
 */
/* eslint-disable react-refresh/only-export-components -- shared editor module: exports the operator
   list + types alongside the editor components (not an HMR component file). */
import React from 'react'
import { ArrowUp, ArrowDown, X, Plus } from 'lucide-react'

/** A field the user can choose from (subset of the collection schema). */
export interface EditorField {
  name: string
  label: string
}

export interface FilterRow {
  field: string
  op: string
  value: string
}

export interface SortRow {
  field: string
  direction: 'ASC' | 'DESC'
}

/** Filter operators. `value` is the stored op code; `valueless` ops (is empty / not empty) hide the input. */
export const FILTER_OPERATORS: Array<{ value: string; label: string; valueless?: boolean }> = [
  { value: 'eq', label: 'equals' },
  { value: 'neq', label: 'not equals' },
  { value: 'contains', label: 'contains' },
  { value: 'starts', label: 'starts with' },
  { value: 'ends', label: 'ends with' },
  { value: 'gt', label: 'greater than' },
  { value: 'gte', label: 'greater or equal' },
  { value: 'lt', label: 'less than' },
  { value: 'lte', label: 'less or equal' },
  { value: 'isnull', label: 'is empty', valueless: true },
  { value: 'notnull', label: 'is not empty', valueless: true },
]

const SELECT_CLASS =
  'rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary'
const INPUT_CLASS =
  'flex-1 rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary'
const ICON_BTN =
  'rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-30 disabled:hover:bg-transparent'
const ADD_BTN =
  'inline-flex items-center gap-1.5 rounded-md border border-dashed border-border px-3 py-1.5 text-sm text-muted-foreground hover:border-primary hover:text-foreground'

/** Move item at `i` by `delta` (±1), returning a new array (no-op at the ends). */
function move<T>(arr: T[], i: number, delta: number): T[] {
  const j = i + delta
  if (j < 0 || j >= arr.length) return arr
  const next = arr.slice()
  ;[next[i], next[j]] = [next[j], next[i]]
  return next
}

function labelFor(fields: EditorField[], name: string): string {
  return fields.find((f) => f.name === name)?.label ?? name
}

function NoCollection(): React.ReactElement {
  return <p className="text-xs text-muted-foreground">Select a collection first.</p>
}

/** A `<select>` of fields with a leading placeholder. */
function FieldSelect({
  fields,
  value,
  onChange,
  testId,
}: {
  fields: EditorField[]
  value: string
  onChange: (v: string) => void
  testId?: string
}): React.ReactElement {
  return (
    <select
      className={SELECT_CLASS}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      data-testid={testId}
    >
      <option value="">Select field…</option>
      {fields.map((f) => (
        <option key={f.name} value={f.name}>
          {f.label}
        </option>
      ))}
    </select>
  )
}

/** Pick which fields are columns + order them. Also used for kanban card fields. */
export function ColumnsEditor({
  fields,
  value,
  onChange,
  emptyHint = 'No columns selected — all fields shown.',
  idPrefix = 'listview',
}: {
  fields: EditorField[]
  value: string[]
  onChange: (next: string[]) => void
  /** Shown when nothing is picked; says what the empty selection means here. */
  emptyHint?: string
  /** Namespaces the test ids so two editors can coexist in one form. */
  idPrefix?: string
}): React.ReactElement {
  if (fields.length === 0) return <NoCollection />
  const available = fields.filter((f) => !value.includes(f.name))
  return (
    <div className="flex flex-col gap-2" data-testid={`${idPrefix}-columns-editor`}>
      {value.length === 0 ? (
        <p className="text-xs text-muted-foreground">{emptyHint}</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {value.map((name, i) => (
            <li
              key={name}
              className="flex items-center gap-1 rounded-md border border-border bg-background px-2 py-1"
              data-testid={`${idPrefix}-column-${name}`}
            >
              <span className="flex-1 text-sm text-foreground">{labelFor(fields, name)}</span>
              <button
                type="button"
                className={ICON_BTN}
                disabled={i === 0}
                onClick={() => onChange(move(value, i, -1))}
                aria-label={`Move ${name} up`}
              >
                <ArrowUp className="h-4 w-4" />
              </button>
              <button
                type="button"
                className={ICON_BTN}
                disabled={i === value.length - 1}
                onClick={() => onChange(move(value, i, 1))}
                aria-label={`Move ${name} down`}
              >
                <ArrowDown className="h-4 w-4" />
              </button>
              <button
                type="button"
                className={ICON_BTN}
                onClick={() => onChange(value.filter((c) => c !== name))}
                aria-label={`Remove ${name}`}
              >
                <X className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
      {available.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {available.map((f) => (
            <button
              key={f.name}
              type="button"
              className={ADD_BTN}
              onClick={() => onChange([...value, f.name])}
              data-testid={`${idPrefix}-add-column-${f.name}`}
            >
              <Plus className="h-3.5 w-3.5" />
              {f.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/** Rows of field / operator / value. */
export function FilterEditor({
  fields,
  value,
  onChange,
}: {
  fields: EditorField[]
  value: FilterRow[]
  onChange: (next: FilterRow[]) => void
}): React.ReactElement {
  if (fields.length === 0) return <NoCollection />
  const update = (i: number, patch: Partial<FilterRow>) =>
    onChange(value.map((row, j) => (j === i ? { ...row, ...patch } : row)))
  return (
    <div className="flex flex-col gap-2" data-testid="listview-filters-editor">
      {value.map((row, i) => {
        const valueless = FILTER_OPERATORS.find((o) => o.value === row.op)?.valueless
        return (
          <div key={i} className="flex items-center gap-1" data-testid={`listview-filter-row-${i}`}>
            <FieldSelect
              fields={fields}
              value={row.field}
              onChange={(v) => update(i, { field: v })}
              testId={`listview-filter-field-${i}`}
            />
            <select
              className={SELECT_CLASS}
              value={row.op}
              onChange={(e) => update(i, { op: e.target.value })}
              data-testid={`listview-filter-op-${i}`}
            >
              {FILTER_OPERATORS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
            {!valueless && (
              <input
                className={INPUT_CLASS}
                value={row.value}
                onChange={(e) => update(i, { value: e.target.value })}
                placeholder="Value"
                data-testid={`listview-filter-value-${i}`}
              />
            )}
            <button
              type="button"
              className={ICON_BTN}
              onClick={() => onChange(value.filter((_, j) => j !== i))}
              aria-label="Remove filter"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        )
      })}
      <button
        type="button"
        className={ADD_BTN}
        onClick={() => onChange([...value, { field: fields[0]?.name ?? '', op: 'eq', value: '' }])}
        data-testid="listview-add-filter"
      >
        <Plus className="h-3.5 w-3.5" />
        Add filter
      </button>
    </div>
  )
}

/** Reorderable rows of field + direction. */
export function SortEditor({
  fields,
  value,
  onChange,
}: {
  fields: EditorField[]
  value: SortRow[]
  onChange: (next: SortRow[]) => void
}): React.ReactElement {
  if (fields.length === 0) return <NoCollection />
  const update = (i: number, patch: Partial<SortRow>) =>
    onChange(value.map((row, j) => (j === i ? { ...row, ...patch } : row)))
  return (
    <div className="flex flex-col gap-2" data-testid="listview-sort-editor">
      {value.map((row, i) => (
        <div key={i} className="flex items-center gap-1" data-testid={`listview-sort-row-${i}`}>
          <FieldSelect
            fields={fields}
            value={row.field}
            onChange={(v) => update(i, { field: v })}
            testId={`listview-sort-field-${i}`}
          />
          <select
            className={SELECT_CLASS}
            value={row.direction}
            onChange={(e) => update(i, { direction: e.target.value as 'ASC' | 'DESC' })}
            data-testid={`listview-sort-direction-${i}`}
          >
            <option value="ASC">Ascending</option>
            <option value="DESC">Descending</option>
          </select>
          <button
            type="button"
            className={ICON_BTN}
            disabled={i === 0}
            onClick={() => onChange(move(value, i, -1))}
            aria-label="Move sort up"
          >
            <ArrowUp className="h-4 w-4" />
          </button>
          <button
            type="button"
            className={ICON_BTN}
            disabled={i === value.length - 1}
            onClick={() => onChange(move(value, i, 1))}
            aria-label="Move sort down"
          >
            <ArrowDown className="h-4 w-4" />
          </button>
          <button
            type="button"
            className={ICON_BTN}
            onClick={() => onChange(value.filter((_, j) => j !== i))}
            aria-label="Remove sort"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      ))}
      <button
        type="button"
        className={ADD_BTN}
        onClick={() => onChange([...value, { field: fields[0]?.name ?? '', direction: 'ASC' }])}
        data-testid="listview-add-sort"
      >
        <Plus className="h-3.5 w-3.5" />
        Add sort
      </button>
    </div>
  )
}
