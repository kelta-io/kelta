/**
 * Field-name / field-type sets shared by the record form core (`RecordForm`,
 * `FormField`, `useRecordFormModel`, `useRecordFormState`). Kept in their own
 * module so the component files only export components (react-refresh).
 */
import type { FieldType } from '@/hooks/useCollectionSchema'

/** System fields excluded from forms */
export const SYSTEM_FIELDS = new Set([
  'id',
  'createdAt',
  'updatedAt',
  'createdBy',
  'updatedBy',
  'created_at',
  'updated_at',
  'created_by',
  'updated_by',
])

/** Read-only field types that should not be editable */
export const READ_ONLY_TYPES: Set<FieldType> = new Set(['auto_number', 'formula', 'rollup_summary'])

/** Reference field types that need lookup dropdowns */
export const REFERENCE_TYPES: Set<FieldType> = new Set(['master_detail', 'lookup', 'reference'])
