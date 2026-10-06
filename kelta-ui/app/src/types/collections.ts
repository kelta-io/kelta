/**
 * Collection Types
 *
 * Types related to collections, fields, and validation rules.
 */

/**
 * Collection definition
 */
export interface Collection {
  id: string
  name: string
  displayName: string
  description?: string
  active: boolean
  currentVersion: number
  /** Collection-level record versioning — every record change writes a record_version snapshot */
  trackHistory?: boolean
  /** Stamp request-origin geolocation into created_geo/updated_geo on HTTP writes */
  captureGeo?: boolean
  fields?: FieldDefinition[]
  createdAt: string
  updatedAt: string
}

/**
 * Field definition within a collection
 */
export interface FieldDefinition {
  id: string
  collectionId?: string
  name: string
  displayName?: string
  type: FieldType
  required: boolean
  unique: boolean
  indexed: boolean
  defaultValue?: string
  referenceTarget?: string
  fieldTypeConfig?: string | Record<string, unknown>
  order: number
  active?: boolean
  description?: string
  constraints?: string
  relationshipType?: 'MASTER_DETAIL'
  relationshipName?: string
  cascadeDelete?: boolean
  referenceCollectionId?: string
  trackHistory?: boolean
  createdAt?: string
  updatedAt?: string
}

/**
 * Supported field types.
 * 'reference' and 'lookup' are deprecated — all relationship fields are now 'master_detail'.
 * They remain in the union for backward compatibility with pre-migration data.
 */
export type FieldType =
  | 'string'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'json'
  | 'reference' // @deprecated — use 'master_detail'
  | 'picklist'
  | 'multi_picklist'
  | 'currency'
  | 'percent'
  | 'auto_number'
  | 'phone'
  | 'email'
  | 'url'
  | 'rich_text'
  | 'encrypted'
  | 'external_id'
  | 'geolocation'
  | 'lookup' // @deprecated — use 'master_detail'
  | 'master_detail'
  | 'formula'
  | 'rollup_summary'

/**
 * Validation rule for a field
 */
export interface ValidationRule {
  type: 'min' | 'max' | 'pattern' | 'email' | 'url' | 'custom'
  value?: unknown
  message?: string
}

/**
 * Collection version for history tracking
 */
export interface CollectionVersion {
  id: string
  version: number
  schema: string
  createdAt: string
}

/**
 * Collection-level validation rule using formula evaluation
 */
export interface CollectionValidationRule {
  id: string
  collectionId: string
  name: string
  description?: string
  active: boolean
  errorConditionFormula: string
  errorMessage: string
  errorField?: string
  evaluateOn: 'CREATE' | 'UPDATE' | 'CREATE_AND_UPDATE'
  createdAt: string
  updatedAt: string
}

/**
 * Record type definition
 */
export interface RecordType {
  id: string
  collectionId: string
  name: string
  description?: string
  active: boolean
  isDefault: boolean
  createdAt: string
  updatedAt: string
}

/**
 * Picklist override for a record type
 */
export interface RecordTypePicklistOverride {
  id: string
  fieldId: string
  fieldName: string
  availableValues: string
  defaultValue?: string
}

/**
 * Field history entry
 */
export interface FieldHistoryEntry {
  id: string
  collectionId: string
  recordId: string
  fieldName: string
  oldValue: unknown
  newValue: unknown
  changedBy: string
  changedAt: string
  changeSource: 'UI' | 'API' | 'WORKFLOW' | 'SYSTEM' | 'IMPORT'
}

/**
 * Picklist value within a global or field-level picklist
 */
export interface PicklistValue {
  id?: string
  value: string
  label: string
  isDefault: boolean
  isActive: boolean
  sortOrder: number
  color?: string
  description?: string
}

/**
 * Dependency between two picklist fields in the same collection
 */
export interface PicklistDependency {
  id: string
  controllingFieldId: string
  dependentFieldId: string
  mapping: Record<string, string[]>
}

/**
 * Setup audit trail entry
 */
export interface SetupAuditTrailEntry {
  id: string
  userId: string | null
  action: 'CREATED' | 'UPDATED' | 'DELETED' | 'ACTIVATED' | 'DEACTIVATED'
  section: string
  entityType: string
  entityId?: string
  entityName?: string
  oldValue?: string
  newValue?: string
  timestamp: string
}
