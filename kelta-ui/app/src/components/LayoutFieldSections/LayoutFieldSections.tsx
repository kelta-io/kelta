/**
 * LayoutFieldSections Component
 *
 * Renders record fields organized by a page layout's sections.
 * Each section becomes a collapsible FieldSection with the configured
 * column count, field placements, and label overrides.
 *
 * Falls through gracefully when layout fields reference schema fields
 * that no longer exist (silently skipped).
 */

import React, { useMemo } from 'react'
import { FieldSection } from '@/components/detail'
import type { FieldSectionRenderContext } from '@/components/detail'
import { FieldRenderer } from '@/components/FieldRenderer'
import { InlineFieldValue } from '@/components/record/InlineFieldValue'
import { sectionAnchorId } from './sectionNavItems'
import { useI18n } from '@/context/I18nContext'
import type { LayoutSectionDto, LayoutFieldPlacementDto } from '@/hooks/usePageLayout'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

/** Placement-level overrides keyed by field name (read-only / required from the layout). */
type PlacementOverrides = Map<string, { readOnly?: boolean; required?: boolean }>

export interface LayoutFieldSectionsProps {
  /** Sections from the resolved page layout */
  sections: LayoutSectionDto[]
  /** Full collection schema fields for type/reference info */
  schemaFields: FieldDefinition[]
  /** Record data */
  record: CollectionRecord
  /** Tenant slug for building lookup links */
  tenantSlug?: string
  /** Lookup display map: { fieldName: { recordId: displayLabel } } */
  lookupDisplayMap?: Record<string, Record<string, string>>
  /**
   * Field name → raw value→`{label, color}` (see `usePicklistDisplayMap`) for picklist/
   * multi_picklist fields. Badges show the authored label; the stored value is unaffected.
   */
  picklistDisplayMaps?: Record<string, Map<string, { label: string; color?: string }>>
  /**
   * Prefix for persisting per-section open state. When set, each section's
   * collapsed state survives navigation/reload via localStorage. Usually
   * the collection name.
   */
  persistKeyPrefix?: string
  /**
   * Opt-in in-place editing. When true AND `onFieldCommit` is provided, each field renders as a
   * click-to-edit `InlineFieldValue` (honoring per-placement read-only). When false/omitted the
   * behavior is unchanged (read-only `FieldRenderer`), so existing callers are untouched.
   */
  editable?: boolean
  /** Persist a single committed field value (partial PATCH). Rejects surface inline. */
  onFieldCommit?: (fieldName: string, value: unknown) => Promise<void>
  /**
   * Field names to mark with a "Changed" badge (record-version detail view).
   * Read-only rendering only — ignored when inline editing is active.
   */
  highlightedFields?: Set<string>
}

/**
 * Maps layout field placements to FieldDefinition objects that FieldSection expects.
 * Applies label overrides from the layout and preserves schema metadata (type, reference info).
 *
 * Fields are interleaved by column so that CSS grid auto-placement (left-to-right,
 * top-to-bottom) puts each field in the correct designer-assigned column.
 */
function mapPlacementsToFields(
  placements: LayoutFieldPlacementDto[],
  schemaFieldsByName: Map<string, FieldDefinition>,
  schemaFieldsById: Map<string, FieldDefinition>,
  columns: number
): FieldDefinition[] {
  // Group placements by column, sorted within each column by sortOrder
  const columnGroups: LayoutFieldPlacementDto[][] = Array.from({ length: columns }, () => [])
  for (const p of placements) {
    const col = Math.min(p.columnNumber, columns - 1)
    columnGroups[col].push(p)
  }
  for (const group of columnGroups) {
    group.sort((a, b) => a.sortOrder - b.sortOrder)
  }

  // Interleave row-by-row across columns for CSS grid auto-placement
  const interleaved: LayoutFieldPlacementDto[] = []
  const maxRows = Math.max(...columnGroups.map((g) => g.length), 0)
  for (let row = 0; row < maxRows; row++) {
    for (let col = 0; col < columns; col++) {
      if (row < columnGroups[col].length) {
        interleaved.push(columnGroups[col][row])
      }
    }
  }

  return interleaved
    .map<FieldDefinition | null>((placement) => {
      // Try to find the schema field by ID first, then by name
      const schemaField =
        schemaFieldsById.get(placement.fieldId) || schemaFieldsByName.get(placement.fieldName)
      if (!schemaField) return null // Field no longer exists in schema; skip silently

      return {
        ...schemaField,
        // Apply label override from layout if set
        displayName: placement.labelOverride || schemaField.displayName || schemaField.name,
      }
    })
    .filter((f): f is FieldDefinition => f !== null)
}

/**
 * Amber pill marking a field that changed in the viewed record version
 * (PromotionWizard ChangeActionLabel styling).
 */
export function ChangedBadge(): React.ReactElement {
  const { t } = useI18n()
  return (
    <span
      className="absolute right-0 top-0 inline-flex items-center rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-amber-700 dark:text-amber-400"
      data-testid="version-changed-badge"
    >
      {t('history.changedBadge')}
    </span>
  )
}

/** Collect per-placement read-only / required overrides across all sections, keyed by field name. */
function buildPlacementOverrides(sections: LayoutSectionDto[]): PlacementOverrides {
  const overrides: PlacementOverrides = new Map()
  for (const section of sections) {
    for (const p of section.fields) {
      overrides.set(p.fieldName, {
        readOnly: p.readOnlyOnLayout,
        required: p.requiredOnLayout,
      })
    }
  }
  return overrides
}

export function LayoutFieldSections({
  sections,
  schemaFields,
  record,
  tenantSlug,
  lookupDisplayMap,
  picklistDisplayMaps,
  persistKeyPrefix,
  editable,
  onFieldCommit,
  highlightedFields,
}: LayoutFieldSectionsProps): React.ReactElement {
  const inlineEditing = !!editable && !!onFieldCommit
  const placementOverrides = useMemo(() => buildPlacementOverrides(sections), [sections])
  // Fields the server masked for this viewer (`meta.maskedFields`, surfaced by flattenResource).
  const maskedFieldSet = useMemo(() => {
    const raw = (record as { __maskedFields?: unknown }).__maskedFields
    return new Set(Array.isArray(raw) ? (raw as string[]) : [])
  }, [record])
  // Build lookup maps once for efficient field resolution
  const { schemaFieldsByName, schemaFieldsById } = useMemo(() => {
    const byName = new Map<string, FieldDefinition>()
    const byId = new Map<string, FieldDefinition>()
    for (const field of schemaFields) {
      byName.set(field.name, field)
      byId.set(field.id, field)
    }
    return { schemaFieldsByName: byName, schemaFieldsById: byId }
  }, [schemaFields])

  // Sort sections by sortOrder
  const sortedSections = useMemo(
    () => [...sections].sort((a, b) => a.sortOrder - b.sortOrder),
    [sections]
  )

  return (
    <>
      {sortedSections.map((section) => {
        const fields = mapPlacementsToFields(
          section.fields,
          schemaFieldsByName,
          schemaFieldsById,
          section.columns || 2
        )

        if (fields.length === 0) return null

        return (
          <div key={section.id} id={sectionAnchorId(section.id)} className="scroll-mt-20">
            <FieldSection<FieldDefinition>
              title={section.heading || 'Details'}
              fields={fields}
              record={record}
              lookupDisplayMap={lookupDisplayMap}
              defaultCollapsed={section.collapsed}
              columns={(section.columns as 1 | 2 | 3 | 4) || 2}
              persistKey={persistKeyPrefix ? `${persistKeyPrefix}.${section.id}` : undefined}
              renderField={({
                field,
                value,
                displayLabel,
              }: FieldSectionRenderContext<FieldDefinition>) =>
                inlineEditing ? (
                  <InlineFieldValue
                    field={field}
                    value={value}
                    displayLabel={displayLabel}
                    tenantSlug={tenantSlug}
                    picklistDisplayMap={picklistDisplayMaps?.[field.name]}
                    editable
                    readOnly={placementOverrides.get(field.name)?.readOnly}
                    required={placementOverrides.get(field.name)?.required}
                    masked={maskedFieldSet.has(field.name)}
                    onCommit={onFieldCommit}
                  />
                ) : highlightedFields?.has(field.name) ? (
                  <div className="relative" data-testid="version-changed-field">
                    <FieldRenderer
                      type={field.type}
                      value={value}
                      fieldName={field.name}
                      displayName={field.displayName || field.name}
                      tenantSlug={tenantSlug}
                      targetCollection={field.referenceTarget}
                      displayLabel={displayLabel}
                      picklistDisplayMap={picklistDisplayMaps?.[field.name]}
                      truncate={false}
                    />
                    <ChangedBadge />
                  </div>
                ) : (
                  <FieldRenderer
                    type={field.type}
                    value={value}
                    fieldName={field.name}
                    displayName={field.displayName || field.name}
                    tenantSlug={tenantSlug}
                    targetCollection={field.referenceTarget}
                    displayLabel={displayLabel}
                    picklistDisplayMap={picklistDisplayMaps?.[field.name]}
                    truncate={false}
                  />
                )
              }
            />
          </div>
        )
      })}
    </>
  )
}
