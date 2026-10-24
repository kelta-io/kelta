/**
 * SecurityEditor Components
 *
 * Reusable components for managing security profiles and permission sets.
 * Used in Profile detail/edit pages, Permission Set pages, and user administration.
 */

// System Permission Checklist
export { SystemPermissionChecklist } from './SystemPermissionChecklist'

// Object Permission Matrix
export { ObjectPermissionMatrix } from './ObjectPermissionMatrix'
export type { ObjectPermission } from './ObjectPermissionMatrix'

// Field Permission Editor
export { FieldPermissionEditor } from './FieldPermissionEditor'
export type { CollectionRef, FieldRef, FieldPermission } from './FieldPermissionEditor'

// Custom Policy Editor
export { CustomPolicyEditor } from './CustomPolicyEditor'

// Policy Test Panel
export { PolicyTestPanel } from './PolicyTestPanel'
