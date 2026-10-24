/**
 * RecordVersionDetail
 *
 * Renders one record version's snapshot through the CURRENT page layout
 * (read-only), marking each field changed in that version with an amber
 * "Changed" badge. Falls back to a flat field grid when the layout has no
 * sections. Historical lookup values not present in the live page's
 * lookupDisplayMap render as their raw id.
 */

import React, { useMemo } from 'react'
import { RecordDetailBody } from '@/components/record/RecordDetailBody'
import { FieldRenderer } from '@/components/FieldRenderer'
import { ChangedBadge } from '@/components/LayoutFieldSections/LayoutFieldSections'
import type { LayoutSectionDto } from '@/hooks/usePageLayout'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import { snapshotToRecord } from './useRecordVersions'
import type { RecordVersion } from './useRecordVersions'

export interface RecordVersionDetailProps {
  version: RecordVersion
  /** Resolved page-layout sections of the CURRENT layout (may be empty). */
  sections: LayoutSectionDto[] | undefined
  /** Collection schema fields. */
  schemaFields: FieldDefinition[]
  tenantSlug?: string
  /** Reference/lookup display map from the live record page. */
  lookupDisplayMap?: Record<string, Record<string, string>>
}

/** System/audit keys hidden from the fallback flat grid. */
const SYSTEM_KEYS = new Set([
  'id',
  'recordTypeId',
  'record_type_id',
  'createdAt',
  'created_at',
  'createdBy',
  'created_by',
  'updatedAt',
  'updated_at',
  'updatedBy',
  'updated_by',
  '__maskedFields',
])

export function RecordVersionDetail({
  version,
  sections,
  schemaFields,
  tenantSlug,
  lookupDisplayMap,
}: RecordVersionDetailProps): React.ReactElement {
  const record = useMemo(() => snapshotToRecord(version), [version])
  const highlightedFields = useMemo(() => new Set(version.changedFields), [version])

  // Flat grid fallback for collections without layout sections: every schema
  // field (snapshot value or —), changed ones badged.
  const fallback = (
    <div
      className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-4 max-md:grid-cols-1"
      data-testid="version-detail-fallback"
    >
      {schemaFields
        .filter((field) => !SYSTEM_KEYS.has(field.name))
        .map((field) =>
          highlightedFields.has(field.name) ? (
            <div key={field.name} className="relative" data-testid="version-changed-field">
              <FieldRenderer
                type={field.type}
                value={record[field.name]}
                fieldName={field.name}
                displayName={field.displayName || field.name}
                tenantSlug={tenantSlug}
                targetCollection={field.referenceTarget}
                truncate={false}
              />
              <ChangedBadge />
            </div>
          ) : (
            <FieldRenderer
              key={field.name}
              type={field.type}
              value={record[field.name]}
              fieldName={field.name}
              displayName={field.displayName || field.name}
              tenantSlug={tenantSlug}
              targetCollection={field.referenceTarget}
              truncate={false}
            />
          )
        )}
    </div>
  )

  return (
    <div data-testid="record-version-detail">
      <RecordDetailBody
        sections={sections}
        schemaFields={schemaFields}
        record={record}
        tenantSlug={tenantSlug}
        lookupDisplayMap={lookupDisplayMap}
        editable={false}
        highlightedFields={highlightedFields}
        fallback={fallback}
      />
    </div>
  )
}
