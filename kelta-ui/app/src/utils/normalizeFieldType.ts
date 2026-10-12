import type { FieldType } from '@/types/collections'

/**
 * Backend canonical field types (uppercase, runtime-core `FieldType`) whose UI
 * type is not simply the lowercased name. Everything else lowercases
 * (`STRING` → `string`, `PICKLIST` → `picklist`, `MASTER_DETAIL` → `master_detail`).
 */
const BACKEND_TYPE_TO_UI: Record<string, FieldType> = {
  DOUBLE: 'number',
  INTEGER: 'number',
  LONG: 'number',
  JSON: 'json',
  ARRAY: 'json',
}

/** Legacy relationship types that the UI treats as `master_detail`. */
const LEGACY_RELATIONSHIP_TO_UI: Record<string, FieldType> = {
  REFERENCE: 'master_detail',
  LOOKUP: 'master_detail',
}

export interface NormalizeFieldTypeOptions {
  /**
   * Map the deprecated `REFERENCE` / `LOOKUP` relationship types to `master_detail`
   * (default `true`). Pass `false` to keep them as `reference` / `lookup` — the
   * collection designer does this so a legacy field shows the type it is stored as.
   */
  collapseRelationships?: boolean
}

/**
 * Normalize a backend field type (any case) to the UI {@link FieldType}.
 *
 * Unknown types are lowercased and passed through unchecked, matching what
 * every caller did before this was shared.
 */
export function normalizeFieldType(
  backendType: string,
  options: NormalizeFieldTypeOptions = {}
): FieldType {
  const { collapseRelationships = true } = options
  const upper = backendType.toUpperCase()
  if (upper in BACKEND_TYPE_TO_UI) {
    return BACKEND_TYPE_TO_UI[upper]
  }
  if (collapseRelationships && upper in LEGACY_RELATIONSHIP_TO_UI) {
    return LEGACY_RELATIONSHIP_TO_UI[upper]
  }
  return backendType.toLowerCase() as FieldType
}
