/**
 * Record-type picklist overrides — the one client definition of where they live and how their
 * `availableValues` is read, shared by the writer (`RecordTypePicklistEditor`) and the reader
 * (the record form core, `useRecordFormModel`) so the two cannot drift apart again.
 *
 * Storage is the `record-type-picklists` system collection (`record_type_picklist`: one row per
 * record type × picklist field, unique on that pair). `availableValues` is a JSONB array of
 * allowed values — the worker's `RecordTypeEnforcementHook` parses it as a JSON string array —
 * so writers send a real array, never a JSON-encoded string.
 */

/** Collection path of the record-type picklist overrides. */
export const RECORD_TYPE_PICKLISTS_PATH = '/api/record-type-picklists'

/**
 * List URL for one record type's overrides. At most one row per picklist field, so a page of 200
 * (the HTTP maximum) covers every field; the default page of 20 would silently drop overrides on
 * a collection with more picklist fields than that.
 */
export function recordTypePicklistsUrl(recordTypeId: string): string {
  return `${RECORD_TYPE_PICKLISTS_PATH}?filter[recordTypeId][eq]=${encodeURIComponent(recordTypeId)}&page[size]=200`
}

/**
 * The values an override allows: a JSON array of strings, or (legacy rows) its string encoding.
 * Anything else is unusable → undefined, which both reader and editor treat as "the field's
 * full picklist".
 */
export function parseAvailableValues(raw: unknown): string[] | undefined {
  let parsed = raw
  if (typeof raw === 'string') {
    try {
      parsed = JSON.parse(raw)
    } catch {
      return undefined
    }
  }
  return Array.isArray(parsed) && parsed.every((v) => typeof v === 'string') ? parsed : undefined
}
