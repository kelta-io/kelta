/**
 * RecordDetailBody — the one shared section renderer for a record's field body.
 *
 * Renders layout-driven sections through `LayoutFieldSections` (which already
 * powers view + in-place inline edit via the FieldControl registry) when the
 * resolved page layout has sections; otherwise renders a variant-specific
 * `fallback` (end-user Highlights+Details, admin field grid). This folds the
 * "sections vs. fallback" branch that both `ObjectDetailPage` (end-user) and
 * `ResourceDetailPage` (admin) duplicated, so both stacks share one body.
 *
 * Part of the unified record experience — see
 * `.claude/docs/specs/unified-record/2-record-shell.md` /
 * `8-admin-convergence.md`.
 */

import React from 'react'
import { LayoutFieldSections } from '@/components/LayoutFieldSections/LayoutFieldSections'
import type { LayoutSectionDto } from '@/hooks/usePageLayout'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

export interface RecordDetailBodyProps {
  /** Resolved page-layout sections. When empty/undefined, `fallback` renders. */
  sections: LayoutSectionDto[] | undefined
  /** Collection schema fields (resolve placements). */
  schemaFields: FieldDefinition[]
  /** The record whose values are shown. */
  record: CollectionRecord
  /** Tenant slug for building reference links. */
  tenantSlug?: string
  /** Reference/lookup display map (id → label) for reference fields. */
  lookupDisplayMap?: Record<string, Record<string, string>>
  /** Field name → raw value→`{label, color}` for picklist/multi_picklist fields. */
  picklistDisplayMaps?: Record<string, Map<string, { label: string; color?: string }>>
  /** Prefix for persisting per-section collapse state. */
  persistKeyPrefix?: string
  /** Opt-in in-place editing (requires `onFieldCommit`). */
  editable?: boolean
  /** Persist a committed field value; rejects surface inline. */
  onFieldCommit?: (fieldName: string, value: unknown) => Promise<void>
  /** Field names to mark with a "Changed" badge (record-version detail view). */
  highlightedFields?: Set<string>
  /**
   * Rendered when the layout has no sections. Each variant supplies its own
   * fallback (end-user Highlights+Details, admin field grid), keeping the
   * layout-driven path shared.
   */
  fallback?: React.ReactNode
}

export function RecordDetailBody({
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
  fallback,
}: RecordDetailBodyProps): React.ReactElement {
  if (sections && sections.length > 0) {
    return (
      <LayoutFieldSections
        sections={sections}
        schemaFields={schemaFields}
        record={record}
        tenantSlug={tenantSlug}
        lookupDisplayMap={lookupDisplayMap}
        picklistDisplayMaps={picklistDisplayMaps}
        persistKeyPrefix={persistKeyPrefix}
        editable={editable}
        onFieldCommit={onFieldCommit}
        highlightedFields={highlightedFields}
      />
    )
  }
  return <>{fallback ?? null}</>
}

export default RecordDetailBody
