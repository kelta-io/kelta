/**
 * Built-in edit controls + the assembled `CONTROLS` map for all 23 field types.
 *
 * Read views delegate to `FieldRenderer` (via `FieldControlView`); this file supplies the missing
 * edit + inline editors — including reference/lookup/master_detail, multi_picklist, json,
 * rich_text and geolocation, which had no edit path before. Server-computed types
 * (formula/rollup_summary/auto_number) expose a read-only editor and never round-trip; encrypted
 * has a write-only editor used by the record form only (not inline/bulk editable).
 *
 * Every `Edit` honors the record form's DOM contract via `FieldEditProps`: `id`, `testId`,
 * `aria-invalid`/`aria-describedby`, `placeholder`, `ctx.readOnly` (disabled) and `ctx.required`.
 */
/* eslint-disable react-refresh/only-export-components -- field-control module: many internal edit components + the CONTROLS map */
import React, { useState } from 'react'
import type { FieldType } from '@/hooks/useCollectionSchema'
import { LookupSelect } from '@/components/LookupSelect'
import { MultiPicklistSelect, normalizeMultiPicklistValue } from '@/components/MultiPicklistSelect'
import { RichTextEditor } from '@/components/RichTextEditor'
import type { FieldControl, FieldEditProps } from './types'
import {
  FieldControlView,
  emptyToNull,
  makeInlineEdit,
  makeReadOnlyInline,
  requiredError,
} from './shared'

const INPUT_CLASS =
  'w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:border-primary disabled:cursor-not-allowed disabled:opacity-60'

function toInputString(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

/** date/datetime need the value trimmed to the input's expected shape. */
function toDateInputString(value: unknown, kind: 'date' | 'datetime'): string {
  if (typeof value !== 'string') return toInputString(value)
  if (kind === 'date') return value.length >= 10 ? value.substring(0, 10) : value
  return value.includes('T') ? value.substring(0, 16) : value
}

// --- edit controls -----------------------------------------------------------

/**
 * The DOM attributes every simple (single-element) edit control carries: id, test id, accessible
 * name, and the error/required a11y state the record form wires (`aria-invalid` +
 * `aria-describedby` → its `field-error-<name>` alert).
 */
function controlAttrs(props: FieldEditProps) {
  const { id, testId, ctx } = props
  return {
    id,
    'data-testid': testId,
    'aria-label': ctx.displayName ?? ctx.fieldName,
    'aria-invalid': props['aria-invalid'],
    'aria-describedby': props['aria-describedby'],
    'aria-required': ctx.required || undefined,
  }
}

function TextEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, onBlur, type, placeholder } = props
  const htmlType =
    type === 'email' ? 'email' : type === 'phone' ? 'tel' : type === 'url' ? 'url' : 'text'
  return (
    <input
      {...controlAttrs(props)}
      type={htmlType}
      className={INPUT_CLASS}
      value={toInputString(value)}
      disabled={ctx.readOnly}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)}
      onBlur={onBlur}
    />
  )
}

/**
 * number / currency / percent. Emits a `number` (or `''` when cleared) so consumers that persist
 * the raw value — the record form — send a JSON number; `coerce` maps `''` → `null` for the
 * inline/bulk surfaces. Currency and percent step in hundredths.
 */
function NumberEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, onBlur, type, placeholder } = props
  const step = type === 'currency' || type === 'percent' ? '0.01' : undefined
  return (
    <input
      {...controlAttrs(props)}
      type="number"
      step={step}
      className={INPUT_CLASS}
      value={toInputString(value)}
      disabled={ctx.readOnly}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
      onBlur={onBlur}
    />
  )
}

function BooleanEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, onBlur } = props
  return (
    <input
      {...controlAttrs(props)}
      type="checkbox"
      className="h-4 w-4 accent-primary"
      checked={Boolean(value)}
      disabled={ctx.readOnly}
      onChange={(e) => onChange(e.target.checked)}
      onBlur={onBlur}
    />
  )
}

function makeDateEdit(kind: 'date' | 'datetime') {
  return function DateEdit(props: FieldEditProps): React.ReactElement {
    const { value, ctx, onChange, onBlur } = props
    return (
      <input
        {...controlAttrs(props)}
        type={kind === 'date' ? 'date' : 'datetime-local'}
        className={INPUT_CLASS}
        value={toDateInputString(value, kind)}
        disabled={ctx.readOnly}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
      />
    )
  }
}

function PicklistEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, onBlur, placeholder } = props
  return (
    <select
      {...controlAttrs(props)}
      className={INPUT_CLASS}
      value={value === null || value === undefined ? '' : String(value)}
      disabled={ctx.readOnly}
      onChange={(e) => onChange(e.target.value)}
      onBlur={onBlur}
    >
      <option value="">{placeholder ?? ''}</option>
      {(ctx.enumValues ?? []).map((opt) => (
        <option key={opt} value={opt}>
          {ctx.picklistDisplayMap?.get(opt)?.label || opt}
        </option>
      ))}
    </select>
  )
}

function MultiPicklistEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, id, placeholder } = props
  return (
    <MultiPicklistSelect
      id={id}
      name={ctx.fieldName ?? ''}
      value={normalizeMultiPicklistValue(value)}
      options={ctx.enumValues ?? []}
      onChange={(vals) => onChange(vals)}
      placeholder={placeholder}
      required={ctx.required}
      disabled={ctx.readOnly}
      data-testid={props.testId}
      aria-invalid={props['aria-invalid']}
      aria-describedby={props['aria-describedby']}
    />
  )
}

function ReferenceEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, id, placeholder } = props
  return (
    <LookupSelect
      id={id}
      name={ctx.fieldName ?? ''}
      value={value === null || value === undefined ? '' : String(value)}
      options={ctx.referenceOptions ?? []}
      onChange={(v) => onChange(v)}
      placeholder={placeholder}
      required={ctx.required}
      disabled={ctx.readOnly}
      error={!!props['aria-invalid']}
      aria-describedby={props['aria-describedby']}
      data-testid={props.testId ?? (ctx.fieldName ? `field-${ctx.fieldName}-lookup` : undefined)}
    />
  )
}

function RichTextEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, id, placeholder } = props
  const html = typeof value === 'string' ? value : ''
  if (ctx.readOnly) {
    return <FieldControlView type="rich_text" value={html} ctx={ctx} truncate={false} />
  }
  return (
    <RichTextEditor
      id={id}
      value={html}
      onChange={(next) => onChange(next)}
      placeholder={placeholder ?? ''}
      testId={props.testId ?? (ctx.fieldName ? `field-${ctx.fieldName}-richtext` : undefined)}
      aria-label={ctx.displayName ?? ctx.fieldName}
      aria-invalid={props['aria-invalid']}
      aria-describedby={props['aria-describedby']}
      aria-required={ctx.required}
    />
  )
}

function JsonEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, onBlur, placeholder } = props
  const text =
    typeof value === 'string' ? value : value == null ? '' : JSON.stringify(value, null, 2)
  return (
    <textarea
      {...controlAttrs(props)}
      className={`${INPUT_CLASS} font-mono text-xs`}
      rows={6}
      value={text}
      disabled={ctx.readOnly}
      placeholder={placeholder ?? '{}'}
      onChange={(e) => onChange(e.target.value)}
      onBlur={onBlur}
    />
  )
}

function readGeo(value: unknown): { lat: string; lng: string } {
  if (value && typeof value === 'object') {
    const g = value as Record<string, unknown>
    const lat = g.latitude ?? g.lat
    const lng = g.longitude ?? g.lng ?? g.lon
    return { lat: lat == null ? '' : String(lat), lng: lng == null ? '' : String(lng) }
  }
  return { lat: '', lng: '' }
}

function GeolocationEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, onBlur, id } = props
  const { lat, lng } = readGeo(value)
  const emit = (nextLat: string, nextLng: string): void => {
    if (nextLat === '' && nextLng === '') {
      onChange(null)
      return
    }
    onChange({
      latitude: nextLat === '' ? null : Number(nextLat),
      longitude: nextLng === '' ? null : Number(nextLng),
    })
  }
  const a11y = {
    'aria-invalid': props['aria-invalid'],
    'aria-describedby': props['aria-describedby'],
    'aria-required': ctx.required || undefined,
  }
  return (
    <div className="flex gap-2">
      <input
        id={id}
        data-testid={props.testId}
        type="number"
        step="any"
        className={INPUT_CLASS}
        placeholder="Latitude"
        value={lat}
        disabled={ctx.readOnly}
        onChange={(e) => emit(e.target.value, lng)}
        onBlur={onBlur}
        aria-label={`${ctx.displayName ?? ctx.fieldName} latitude`}
        {...a11y}
      />
      <input
        id={id ? `${id}-longitude` : undefined}
        data-testid={props.testId ? `${props.testId}-longitude` : undefined}
        type="number"
        step="any"
        className={INPUT_CLASS}
        placeholder="Longitude"
        value={lng}
        disabled={ctx.readOnly}
        onChange={(e) => emit(lat, e.target.value)}
        onBlur={onBlur}
        aria-label={`${ctx.displayName ?? ctx.fieldName} longitude`}
        {...a11y}
      />
    </div>
  )
}

/**
 * encrypted — a WRITE-ONLY secret input (product decision). It never displays a value it was not
 * typed in this mount: a stored (or masked) value passed in by the consumer stays hidden, so the
 * editor starts empty and shows only what the user enters. Honors `readOnly` (disabled).
 *
 * The record form additionally never loads the stored value into its state and only sends the
 * field when the user typed one (`formData.ts`). Inline/bulk surfaces don't offer encrypted at all
 * (`editable: false`, `coerce` → omit), so the full record form is its only writer.
 */
function EncryptedEdit(props: FieldEditProps): React.ReactElement {
  const { value, ctx, onChange, onBlur, placeholder } = props
  const [typed, setTyped] = useState(false)
  return (
    <input
      {...controlAttrs(props)}
      type="password"
      autoComplete="new-password"
      className={INPUT_CLASS}
      value={typed ? toInputString(value) : ''}
      disabled={ctx.readOnly}
      placeholder={placeholder}
      onChange={(e) => {
        setTyped(true)
        onChange(e.target.value)
      }}
      onBlur={onBlur}
    />
  )
}

function ComputedEdit({ value, ctx, type, id, testId }: FieldEditProps): React.ReactElement {
  return (
    <div className="opacity-70" id={id} data-testid={testId}>
      <FieldControlView type={type} value={value} ctx={ctx} />
    </div>
  )
}

// --- coerce / validate helpers ----------------------------------------------

function coerceNumber(raw: unknown): unknown {
  if (raw === '' || raw === null || raw === undefined) return null
  const n = Number(raw)
  return Number.isNaN(n) ? raw : n
}

function coerceJson(raw: unknown): unknown {
  if (raw === '' || raw === null || raw === undefined) return null
  if (typeof raw !== 'string') return raw
  try {
    return JSON.parse(raw)
  } catch {
    return raw // validate flags it; keep the raw text so the user can fix it
  }
}

function validateNumber(value: unknown, ctx: import('./types').FieldControlContext): string | null {
  const req = requiredError(value, ctx)
  if (req) return req
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number') return Number.isNaN(value) ? 'Must be a number' : null
  return Number.isNaN(Number(value)) ? 'Must be a number' : null
}

function validateEnum(single: boolean) {
  return function (value: unknown, ctx: import('./types').FieldControlContext): string | null {
    const req = requiredError(value, ctx)
    if (req) return req
    if (value === null || value === undefined || value === '') return null
    if (!ctx.enumValues || ctx.enumValues.length === 0) return null
    const values = single ? [value] : normalizeMultiPicklistValue(value)
    const bad = values.find((v) => !ctx.enumValues!.includes(String(v)))
    return bad === undefined ? null : `"${String(bad)}" is not an allowed value`
  }
}

// --- control factories -------------------------------------------------------

function makeControl(
  Edit: React.ComponentType<FieldEditProps>,
  opts: Partial<Omit<FieldControl, 'View' | 'Edit' | 'InlineEdit'>> = {}
): FieldControl {
  const coerce = opts.coerce ?? emptyToNull
  const control: FieldControl = {
    View: FieldControlView,
    Edit,
    InlineEdit: makeInlineEdit(Edit, coerce),
    coerce,
    validate: opts.validate ?? requiredError,
    editable: opts.editable ?? true,
  }
  return control
}

function makeComputedControl(
  Edit: React.ComponentType<FieldEditProps> = ComputedEdit
): FieldControl {
  return {
    View: FieldControlView,
    Edit,
    InlineEdit: makeReadOnlyInline(FieldControlView),
    coerce: () => undefined, // never POST/PATCH a server-computed value
    validate: () => null,
    editable: false,
  }
}

const textControl = makeControl(TextEdit)
const numberControl = makeControl(NumberEdit, { coerce: coerceNumber, validate: validateNumber })
const dateControl = makeControl(makeDateEdit('date'))
const datetimeControl = makeControl(makeDateEdit('datetime'))
const contactControl = makeControl(TextEdit)
const referenceControl = makeControl(ReferenceEdit)
const computedControl = makeComputedControl()

/** The built-in registry map. Keyed by UI `FieldType`. */
export const CONTROLS: Record<FieldType, FieldControl> = {
  string: textControl,
  external_id: textControl,
  number: numberControl,
  currency: makeControl(NumberEdit, { coerce: coerceNumber, validate: validateNumber }),
  percent: makeControl(NumberEdit, { coerce: coerceNumber, validate: validateNumber }),
  boolean: makeControl(BooleanEdit, { coerce: (raw) => Boolean(raw), validate: () => null }),
  date: dateControl,
  datetime: datetimeControl,
  email: contactControl,
  phone: contactControl,
  url: contactControl,
  picklist: makeControl(PicklistEdit, { validate: validateEnum(true) }),
  multi_picklist: makeControl(MultiPicklistEdit, {
    coerce: (raw) => normalizeMultiPicklistValue(raw),
    validate: validateEnum(false),
  }),
  reference: referenceControl,
  lookup: referenceControl,
  master_detail: referenceControl,
  rich_text: makeControl(RichTextEdit),
  json: makeControl(JsonEdit, { coerce: coerceJson, validate: validateJson }),
  geolocation: makeControl(GeolocationEdit, { coerce: emptyToNull, validate: validateGeo }),
  auto_number: computedControl,
  formula: computedControl,
  rollup_summary: computedControl,
  // Write-only: editable only through the full record form's `Edit`; never inline/bulk, never
  // round-tripped by `coerce` (see `EncryptedEdit`).
  encrypted: makeComputedControl(EncryptedEdit),
}

function validateJson(value: unknown, ctx: import('./types').FieldControlContext): string | null {
  const req = requiredError(value, ctx)
  if (req) return req
  if (typeof value !== 'string' || value === '') return null
  try {
    JSON.parse(value)
    return null
  } catch {
    return 'Invalid JSON'
  }
}

function validateGeo(value: unknown, ctx: import('./types').FieldControlContext): string | null {
  const req = requiredError(value, ctx)
  if (req) return req
  if (value === null || value === undefined) return null
  if (typeof value !== 'object') return 'Invalid location'
  const g = value as Record<string, unknown>
  const lat = g.latitude ?? g.lat
  const lng = g.longitude ?? g.lng ?? g.lon
  if (lat != null && (Number(lat) < -90 || Number(lat) > 90))
    return 'Latitude must be between -90 and 90'
  if (lng != null && (Number(lng) < -180 || Number(lng) > 180))
    return 'Longitude must be between -180 and 180'
  return null
}
