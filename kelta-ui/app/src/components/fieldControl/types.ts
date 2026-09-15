/**
 * FieldControl registry — unified view + edit + inline contract (Slice 1).
 *
 * A single `FieldType`-keyed source of truth so "every field is editable" is a registry
 * guarantee rather than per-page code. Merges what was split across `FieldRenderer` (display)
 * and `formFieldRenderers`/`InlineEditCell` (edit). See
 * `.claude/docs/specs/unified-record/1-field-control-registry.md`.
 */
import type React from 'react'
import type { FieldType } from '@/hooks/useCollectionSchema'
import type { LookupOption } from '@/components/LookupSelect'

/**
 * Everything a control needs beyond the raw value: reference/picklist options, link context,
 * layout overrides. Supplied by the consuming page (Slices 2–4); the registry stays presentational.
 */
export interface FieldControlContext {
  /** Tenant slug for building reference links. */
  tenantSlug?: string
  /** Target collection name for reference/lookup/master_detail links. */
  targetCollection?: string
  /** Resolved display label for a reference value. */
  displayLabel?: string
  /** Field API name (accessibility + input ids). */
  fieldName?: string
  /** Human display name (accessibility). */
  displayName?: string
  /** Allowed values for picklist / multi_picklist. */
  enumValues?: string[]
  /** Raw value → authored `{label, color}` for picklist / multi_picklist (see `usePicklistDisplayMap`). */
  picklistDisplayMap?: Map<string, { label: string; color?: string }>
  /** Fetched options for reference/lookup/master_detail edit controls. */
  referenceOptions?: LookupOption[]
  /** Layout/schema read-only override — renders View or a disabled Edit. */
  readOnly?: boolean
  /** Layout/schema required override — drives `validate`. */
  required?: boolean
}

export interface FieldViewProps {
  type: FieldType
  value: unknown
  ctx: FieldControlContext
  truncate?: boolean
  className?: string
}

export interface FieldEditProps {
  type: FieldType
  value: unknown
  ctx: FieldControlContext
  /** Fires with the raw control value on every change (coerce at submit). */
  onChange: (next: unknown) => void
  onBlur?: () => void
  /** Per-field validation message to surface. */
  error?: string
  /** Input element id. */
  id?: string
}

export interface FieldInlineProps extends FieldEditProps {
  /** Commit the (coerced) value — Enter/blur in a grid/detail cell. */
  onCommit: (next: unknown) => void
  /** Abandon the edit — Esc. */
  onCancel: () => void
}

/**
 * One control per field type. `View`/`Edit`/`InlineEdit` are components; `coerce` maps a raw
 * control value to the API payload shape; `validate` mirrors server constraints (the server
 * remains authoritative). `editable` is false for server-computed types.
 */
export interface FieldControl {
  View: React.ComponentType<FieldViewProps>
  Edit: React.ComponentType<FieldEditProps>
  InlineEdit: React.ComponentType<FieldInlineProps>
  /**
   * Map a raw editor value to the value POSTed/PATCHed. Returns `undefined` to signal "omit this
   * field from the payload" (server-computed types never round-trip).
   */
  coerce: (raw: unknown) => unknown
  /** Return an error message, or null when valid. */
  validate: (value: unknown, ctx: FieldControlContext) => string | null
  /** Whether the type accepts user edits at all. */
  editable: boolean
}

/** A control registration may override any subset; unspecified members fall back to the string control. */
export type PartialFieldControl = Partial<FieldControl>
