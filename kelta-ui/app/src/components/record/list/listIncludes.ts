/**
 * Helpers for resolving reference (lookup/master-detail) columns in the record list core.
 */

/** Field types that hold a foreign key to another collection. */
export const REFERENCE_FIELD_TYPES = new Set(['master_detail', 'lookup', 'reference'])

/**
 * Build the JSON:API `?include=` value for a list view from its reference fields.
 *
 * The worker resolves `include` by **field name** (e.g. `title`), not by the target collection name
 * (e.g. `titles`). Using target names matches no relationship, so no `included` resources come back
 * and reference cells fall back to raw FK ids. Returns the deduped field names, or `undefined` when
 * there are no reference fields.
 */
export function referenceIncludeParam(
  referenceFields: Array<{ name: string }>
): string | undefined {
  if (referenceFields.length === 0) return undefined
  return [...new Set(referenceFields.map((f) => f.name))].join(',')
}
