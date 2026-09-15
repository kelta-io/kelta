/**
 * Admin/Control Plane types
 */

import type { AuthzConfig, ValidationRule } from '../types';

/**
 * Collection definition for admin operations
 */
export interface CollectionDefinition {
  id?: string;
  name: string;
  displayName: string;
  description?: string;
  active?: boolean;
  systemCollection?: boolean;
  currentVersion?: number;
  fields?: FieldDefinition[];
  authz?: AuthzConfig;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Supported field types
 */
export type FieldType =
  | 'string'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'json'
  | 'reference'
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
  | 'lookup'
  | 'master_detail'
  | 'formula'
  | 'rollup_summary';

/**
 * Type-specific configuration for fields
 */
export interface FieldTypeConfig {
  /** Picklist: global picklist ID */
  globalPicklistId?: string;
  /** AutoNumber: prefix string */
  prefix?: string;
  /** AutoNumber: zero-padding width */
  padding?: number;
  /** Currency: decimal precision (0-6) */
  precision?: number;
  /** Currency: default ISO 4217 code */
  defaultCurrencyCode?: string;
  /** Formula: expression string */
  expression?: string;
  /** Formula: return type */
  returnType?: string;
  /** RollupSummary: child collection name */
  childCollection?: string;
  /** RollupSummary: aggregate function */
  aggregateFunction?: 'COUNT' | 'SUM' | 'MIN' | 'MAX' | 'AVG';
  /** RollupSummary: field to aggregate */
  aggregateField?: string;
  /** Geolocation: latitude */
  latitude?: number;
  /** Geolocation: longitude */
  longitude?: number;
  /** Lookup/MasterDetail: target collection name */
  targetCollection?: string;
  /** Lookup/MasterDetail: human-readable relationship name */
  relationshipName?: string;
}

/**
 * Field definition
 */
export interface FieldDefinition {
  id?: string;
  collectionId?: string;
  name: string;
  displayName?: string;
  type: FieldType;
  required?: boolean;
  unique?: boolean;
  indexed?: boolean;
  defaultValue?: string;
  referenceTarget?: string;
  fieldTypeConfig?: FieldTypeConfig;
  order?: number;
  active?: boolean;
  description?: string;
  constraints?: string;
  /**
   * Per-field validation rules. Distinct from the schema-level
   * {@code validation} module — these run client-side before submit and
   * are also enforced server-side by the worker.
   */
  validation?: ValidationRule[];
  relationshipType?: 'LOOKUP' | 'MASTER_DETAIL';
  relationshipName?: string;
  cascadeDelete?: boolean;
  referenceCollectionId?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Relationship information for a field
 */
export interface RelationshipInfo {
  fieldId: string;
  fieldName: string;
  relationshipType: 'LOOKUP' | 'MASTER_DETAIL';
  relationshipName: string;
  targetCollectionId: string;
  targetCollectionName: string;
  cascadeDelete: boolean;
}

/**
 * All relationships for a collection
 */
export interface CollectionRelationships {
  collectionId: string;
  collectionName: string;
  outgoing: RelationshipInfo[];
  incoming: RelationshipInfo[];
}

/**
 * Role definition
 */
export interface Role {
  id?: string;
  name: string;
  description?: string;
  createdAt?: string;
}

/**
 * Policy definition
 */
export interface Policy {
  id?: string;
  name: string;
  description?: string;
  expression?: string;
  rules?: string;
  createdAt?: string;
}

/**
 * OIDC Provider configuration
 */
export interface OIDCProvider {
  id: string;
  name: string;
  issuer: string;
  clientId: string;
  clientSecret?: string;
  clientSecretEnc?: string;
  scopes: string[];
  active: boolean;
  // OIDC Discovery endpoint overrides (auto-discovered from issuer when null)
  jwksUri?: string;
  authorizationUri?: string;
  tokenUri?: string;
  userinfoUri?: string;
  endSessionUri?: string;
  discoveryStatus?: string;
  // Claim mappings
  rolesClaim?: string;
  rolesMapping?: string;
  emailClaim?: string;
  usernameClaim?: string;
  nameClaim?: string;
  groupsClaim?: string;
  groupsProfileMapping?: string;
}

/**
 * UI configuration
 */
export interface UIConfig {
  theme?: ThemeConfig;
  branding?: BrandingConfig;
  features?: Record<string, boolean>;
}

/**
 * Theme configuration
 */
export interface ThemeConfig {
  primaryColor?: string;
  secondaryColor?: string;
  fontFamily?: string;
}

/**
 * Branding configuration
 */
export interface BrandingConfig {
  logo?: string;
  title?: string;
  favicon?: string;
}

/**
 * Package data for import/export
 */
export interface PackageData {
  version: string;
  collections: CollectionDefinition[];
  roles: Role[];
  policies: Policy[];
  uiConfig?: UIConfig;
}

/**
 * Export options
 */
export interface ExportOptions {
  includeCollections?: string[];
  includeRoles?: boolean;
  includePolicies?: boolean;
  includeUIConfig?: boolean;
}

/**
 * Import result
 */
export interface ImportResult {
  success: boolean;
  imported: {
    collections: number;
    roles: number;
    policies: number;
  };
  errors?: string[];
}

/**
 * Migration definition
 */
export interface Migration {
  id: string;
  name: string;
  description?: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  createdAt: string;
  completedAt?: string;
}

/**
 * Migration result
 */
export interface MigrationResult {
  success: boolean;
  message: string;
  details?: unknown;
}

/**
 * UI Page definition
 */
export interface UIPage {
  id?: string;
  name: string;
  path: string;
  component?: string;
  layout?: string;
  metadata?: Record<string, unknown>;
}

/**
 * UI Menu definition
 */
export interface UIMenu {
  id?: string;
  name: string;
  items: UIMenuItem[];
  position?: string;
  /** Lucide icon name shown in the end-user app switcher (apps/nav v2). */
  icon?: string;
  /** The app selected when the user has no stored preference (apps/nav v2). */
  isDefault?: boolean;
  /** Inactive apps are hidden from the end-user shell (apps/nav v2). */
  active?: boolean;
  displayOrder?: number;
}

/**
 * UI Menu item
 */
export interface UIMenuItem {
  id?: string;
  label: string;
  path?: string;
  icon?: string;
  children?: UIMenuItem[];
  order?: number;
}

/**
 * Tenant definition
 */
export interface Tenant {
  id: string;
  slug: string;
  name: string;
  edition: 'FREE' | 'PROFESSIONAL' | 'ENTERPRISE' | 'UNLIMITED';
  status: 'PROVISIONING' | 'ACTIVE' | 'SUSPENDED' | 'DECOMMISSIONED';
  settings?: string;
  limits?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Request to create a tenant
 */
export interface CreateTenantRequest {
  slug: string;
  name: string;
  edition?: string;
  settings?: Record<string, unknown>;
  limits?: Partial<GovernorLimits>;
}

/**
 * Request to update a tenant
 */
export interface UpdateTenantRequest {
  name?: string;
  edition?: string;
  settings?: Record<string, unknown>;
  limits?: Partial<GovernorLimits>;
}

/**
 * Governor limits for a tenant
 */
export interface GovernorLimits {
  apiCallsPerDay: number;
  storageGb: number;
  maxUsers: number;
  maxCollections: number;
  maxFieldsPerCollection: number;
  maxWorkflows: number;
  maxReports: number;
}

/**
 * Paginated response
 */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Platform user
 */
export interface PlatformUser {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  username?: string;
  status: 'ACTIVE' | 'INACTIVE' | 'LOCKED' | 'PENDING_ACTIVATION';
  userType?: 'INTERNAL' | 'PORTAL';
  locale: string;
  timezone: string;
  profileId?: string;
  managerId?: string;
  lastLoginAt?: string;
  loginCount: number;
  mfaEnabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Request to create + invite an external portal user (magic-link login,
 * no password). The server forces user_type=PORTAL + the Portal User profile.
 */
export interface PortalInviteRequest {
  email: string;
  firstName?: string;
  lastName?: string;
}

/** Response from the portal-invite endpoint. */
export interface PortalInviteResponse {
  userId: string;
  status: 'INVITED' | 'REINVITED';
}

/**
 * Request to create a user
 */
export interface CreatePlatformUserRequest {
  email: string;
  firstName: string;
  lastName: string;
  username?: string;
  locale?: string;
  timezone?: string;
  profileId?: string;
}

/**
 * Request to update a user
 */
export interface UpdatePlatformUserRequest {
  firstName?: string;
  lastName?: string;
  username?: string;
  locale?: string;
  timezone?: string;
  managerId?: string;
  profileId?: string;
}

/**
 * A named reference (id + display name) inside a delegated-admin summary.
 */
export interface DelegatedNamedRef {
  id: string;
  name: string;
}

/**
 * The caller's effective delegated-administration summary
 * (GET /api/admin/delegated/me).
 */
export interface DelegatedAdminSummary {
  delegated: boolean;
  canCreateUsers: boolean;
  canDeactivateUsers: boolean;
  canResetPasswords: boolean;
  manageableProfiles: DelegatedNamedRef[];
}

/**
 * A delegated-admin scope definition (delegated-admin-scopes system collection).
 */
export interface DelegatedAdminScope {
  id: string;
  name: string;
  description?: string;
  active: boolean;
  delegatedUserIds: string[];
  manageableProfileIds: string[];
  canCreateUsers: boolean;
  canDeactivateUsers: boolean;
  canResetPasswords: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Create/update payload for a delegated-admin scope.
 */
export interface SaveDelegatedAdminScopeRequest {
  name?: string;
  description?: string;
  active?: boolean;
  delegatedUserIds?: string[];
  manageableProfileIds?: string[];
  canCreateUsers?: boolean;
  canDeactivateUsers?: boolean;
  canResetPasswords?: boolean;
}

/**
 * Create payload for a delegated user create (status is forced server-side).
 */
export interface DelegatedCreateUserRequest {
  email: string;
  firstName?: string;
  lastName?: string;
  username?: string;
  locale?: string;
  timezone?: string;
  profileId: string;
}

/**
 * Update payload for a delegated user update (whitelisted fields only).
 */
export interface DelegatedUpdateUserRequest {
  firstName?: string;
  lastName?: string;
  username?: string;
  locale?: string;
  timezone?: string;
  profileId?: string;
  status?: 'ACTIVE' | 'INACTIVE';
}

/**
 * Login history entry
 */
export interface LoginHistoryEntry {
  id: string;
  userId: string;
  loginTime: string;
  sourceIp: string;
  loginType: 'UI' | 'API' | 'OAUTH' | 'SERVICE_ACCOUNT';
  status: 'SUCCESS' | 'FAILED' | 'LOCKED_OUT';
  userAgent: string;
}

/**
 * Profile definition
 */
export interface Profile {
  id: string;
  name: string;
  description?: string;
  system: boolean;
  objectPermissions?: ObjectPermission[];
  fieldPermissions?: FieldPermissionEntry[];
  systemPermissions?: SystemPermissionEntry[];
  createdAt: string;
  updatedAt: string;
}

/**
 * Object permission (collection-level CRUD)
 */
export interface ObjectPermission {
  id?: string;
  collectionId: string;
  canCreate: boolean;
  canRead: boolean;
  canEdit: boolean;
  canDelete: boolean;
}

/**
 * Field permission entry
 */
export interface FieldPermissionEntry {
  id?: string;
  fieldId: string;
  visibility: 'VISIBLE' | 'READ_ONLY' | 'HIDDEN';
}

/**
 * System permission entry
 */
export interface SystemPermissionEntry {
  id?: string;
  permissionKey: string;
  granted: boolean;
}

/**
 * Request to create a profile
 */
export interface CreateProfileRequest {
  name: string;
  description?: string;
}

/**
 * Request to update a profile
 */
export interface UpdateProfileRequest {
  name?: string;
  description?: string;
}

/**
 * Request to set object permissions
 */
export interface ObjectPermissionRequest {
  canCreate: boolean;
  canRead: boolean;
  canEdit: boolean;
  canDelete: boolean;
}

/**
 * Request to set field permissions
 */
export interface FieldPermissionRequest {
  fieldId: string;
  visibility: 'VISIBLE' | 'READ_ONLY' | 'HIDDEN';
}

/**
 * Request to set system permissions
 */
export interface SystemPermissionRequest {
  permissionKey: string;
  granted: boolean;
}

/**
 * Organization-wide default
 */
export interface OrgWideDefault {
  id?: string;
  collectionId: string;
  internalAccess: 'PRIVATE' | 'PUBLIC_READ' | 'PUBLIC_READ_WRITE';
  externalAccess: 'PRIVATE' | 'PUBLIC_READ' | 'PUBLIC_READ_WRITE';
}

/**
 * Request to set OWD
 */
export interface SetOwdRequest {
  internalAccess: 'PRIVATE' | 'PUBLIC_READ' | 'PUBLIC_READ_WRITE';
  externalAccess?: 'PRIVATE' | 'PUBLIC_READ' | 'PUBLIC_READ_WRITE';
}

/**
 * Sharing rule
 */
export interface SharingRule {
  id: string;
  collectionId: string;
  name: string;
  ruleType: 'OWNER_BASED' | 'CRITERIA_BASED';
  sharedFrom?: string;
  sharedTo: string;
  sharedToType: 'ROLE' | 'GROUP' | 'QUEUE';
  accessLevel: 'READ' | 'READ_WRITE';
  criteria?: string;
  active: boolean;
}

/**
 * Request to create a sharing rule
 */
export interface CreateSharingRuleRequest {
  name: string;
  ruleType: 'OWNER_BASED' | 'CRITERIA_BASED';
  sharedFrom?: string;
  sharedTo: string;
  sharedToType: 'ROLE' | 'GROUP' | 'QUEUE';
  accessLevel: 'READ' | 'READ_WRITE';
  criteria?: string;
}

/**
 * Request to update a sharing rule
 */
export interface UpdateSharingRuleRequest {
  name?: string;
  sharedFrom?: string;
  sharedTo?: string;
  sharedToType?: 'ROLE' | 'GROUP' | 'QUEUE';
  accessLevel?: 'READ' | 'READ_WRITE';
  criteria?: string;
  active?: boolean;
}

/**
 * Record share
 */
export interface RecordShare {
  id: string;
  collectionId: string;
  recordId: string;
  sharedWithId: string;
  sharedWithType: 'USER' | 'GROUP' | 'ROLE';
  accessLevel: 'READ' | 'READ_WRITE';
  reason: 'MANUAL' | 'RULE' | 'TEAM' | 'TERRITORY';
  createdBy: string;
  createdAt: string;
}

/**
 * User group
 */
export interface UserGroup {
  id: string;
  name: string;
  description?: string;
  groupType: 'PUBLIC' | 'QUEUE';
  memberIds?: string[];
}

/**
 * Request to create a user group
 */
export interface CreateUserGroupRequest {
  name: string;
  description?: string;
  groupType?: 'PUBLIC' | 'QUEUE';
  memberIds?: string[];
}

/**
 * Role hierarchy node
 */
export interface RoleHierarchyNode {
  id: string;
  name: string;
  description?: string;
  parentRoleId?: string;
  hierarchyLevel: number;
  children?: RoleHierarchyNode[];
}

/**
 * Setup audit trail entry
 */
export interface SetupAuditTrailEntry {
  id: string;
  userId: string | null;
  action: 'CREATED' | 'UPDATED' | 'DELETED' | 'ACTIVATED' | 'DEACTIVATED';
  section: string;
  entityType: string;
  entityId?: string;
  entityName?: string;
  oldValue?: string;
  newValue?: string;
  timestamp: string;
}

/**
 * Governor limits status
 */
export interface GovernorLimitsStatus {
  /** Tenant tier; drives default quotas before per-tenant overrides apply. */
  tier: 'FREE' | 'PROFESSIONAL' | 'ENTERPRISE' | 'UNLIMITED';
  limits: GovernorLimits;
  apiCallsUsed: number;
  apiCallsLimit: number;
  usersUsed: number;
  usersLimit: number;
  collectionsUsed: number;
  collectionsLimit: number;
  storageUsedBytes: number;
  storageGbLimit: number;
  aiTokensUsed: number;
  aiTokensLimit: number;
  aiEnabled: boolean;
}

/**
 * Governor limits configuration
 */
export interface GovernorLimits {
  apiCallsPerDay: number;
  storageGb: number;
  maxUsers: number;
  maxCollections: number;
  maxFieldsPerCollection: number;
  maxWorkflows: number;
  maxReports: number;
}

/**
 * Migration plan result
 */
export interface MigrationPlan {
  steps: MigrationStep[];
  warnings?: string[];
  estimatedDuration?: number;
}

/**
 * Migration step
 */
export interface MigrationStep {
  type: string;
  description: string;
  sql?: string;
  reversible: boolean;
}

/**
 * Migration run details
 */
export interface MigrationRun {
  id: string;
  migrationId: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  startedAt?: string;
  completedAt?: string;
  error?: string;
  steps: MigrationStepResult[];
}

/**
 * Migration step result
 */
export interface MigrationStepResult {
  step: MigrationStep;
  status: 'pending' | 'running' | 'completed' | 'failed';
  error?: string;
}

/**
 * Global picklist definition
 */
export interface GlobalPicklist {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  sorted: boolean;
  restricted: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Picklist value
 */
export interface PicklistValue {
  id: string;
  value: string;
  label: string;
  isDefault: boolean;
  isActive: boolean;
  sortOrder: number;
  color?: string;
  description?: string;
}

/**
 * Picklist dependency mapping
 */
export interface PicklistDependency {
  id: string;
  controllingFieldId: string;
  dependentFieldId: string;
  mapping: Record<string, string[]>;
}

/**
 * Request to create a global picklist
 */
export interface CreateGlobalPicklistRequest {
  name: string;
  description?: string;
  sorted?: boolean;
  restricted?: boolean;
  values?: PicklistValueRequest[];
}

/**
 * Request to set picklist values
 */
export interface PicklistValueRequest {
  value: string;
  label: string;
  isDefault?: boolean;
  isActive?: boolean;
  sortOrder?: number;
  color?: string;
  description?: string;
}

/**
 * Request to set a picklist dependency
 */
export interface SetDependencyRequest {
  controllingFieldId: string;
  dependentFieldId: string;
  mapping: Record<string, string[]>;
}

// --- Validation Rules (Phase 2 Stream D) ---

/**
 * Collection-level validation rule using formula evaluation
 */
export interface CollectionValidationRule {
  id: string;
  collectionId: string;
  name: string;
  description?: string;
  active: boolean;
  errorConditionFormula: string;
  errorMessage: string;
  errorField?: string;
  evaluateOn: 'CREATE' | 'UPDATE' | 'CREATE_AND_UPDATE';
  enforceOnClient: boolean;
  severity: 'ERROR' | 'WARNING';
  createdAt: string;
  updatedAt: string;
}

/**
 * Request to create a collection validation rule
 */
export interface CreateCollectionValidationRuleRequest {
  name: string;
  description?: string;
  errorConditionFormula: string;
  errorMessage: string;
  errorField?: string;
  evaluateOn?: 'CREATE' | 'UPDATE' | 'CREATE_AND_UPDATE';
  enforceOnClient?: boolean;
  severity?: 'ERROR' | 'WARNING';
}

/**
 * Validation error returned when a record fails validation
 */
export interface CollectionValidationError {
  ruleName: string;
  errorMessage: string;
  errorField?: string;
}

// --- Record Types (Phase 2 Stream D) ---

/**
 * Record type definition
 */
export interface RecordType {
  id: string;
  collectionId: string;
  name: string;
  description?: string;
  active: boolean;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Request to create a record type
 */
export interface CreateRecordTypeRequest {
  name: string;
  description?: string;
  isDefault?: boolean;
}

/**
 * Picklist value override for a record type
 */
export interface RecordTypePicklistOverride {
  id: string;
  fieldId: string;
  fieldName: string;
  availableValues: string;
  defaultValue?: string;
}

/**
 * Request to set picklist override for a record type
 */
export interface SetPicklistOverrideRequest {
  availableValues: string[];
  defaultValue?: string;
}

// --- Field History (Phase 2 Stream E) ---

/**
 * Field history entry tracking a single field change
 */
export interface FieldHistoryEntry {
  id: string;
  collectionId: string;
  recordId: string;
  fieldName: string;
  oldValue: unknown;
  newValue: unknown;
  changedBy: string;
  changedAt: string;
  changeSource: 'UI' | 'API' | 'WORKFLOW' | 'SYSTEM' | 'IMPORT';
}

// --- Page Layouts (Phase 3 Stream A) ---

export interface PageLayout {
  id: string;
  tenantId: string;
  collectionId: string;
  name: string;
  description?: string;
  layoutType: 'DETAIL' | 'EDIT' | 'MINI' | 'LIST';
  isDefault: boolean;
  sections: LayoutSection[];
  relatedLists: LayoutRelatedList[];
  rules?: LayoutRule[];
  defaultFilter?: LayoutFilter | null;
  defaultSortField?: string;
  defaultSortDirection?: 'ASC' | 'DESC';
  defaultRowLimit?: number;
  createdAt: string;
  updatedAt: string;
}

export type LayoutFilterOperator =
  | 'equals'
  | 'not_equals'
  | 'contains'
  | 'starts_with'
  | 'ends_with'
  | 'gt'
  | 'lt'
  | 'gte'
  | 'lte'
  | 'is_null'
  | 'is_not_null';

export interface LayoutFilterClause {
  field: string;
  op: LayoutFilterOperator;
  value?: unknown;
}

export interface LayoutFilter {
  logic: 'AND' | 'OR';
  filters: LayoutFilterClause[];
}

export type LayoutRuleKind = 'compute' | 'validate' | 'default' | 'transform' | 'script';
export type LayoutRuleEvent = 'onChange' | 'onBlur' | 'onLoad' | 'onBeforeSave';
export type LayoutRuleEnforce = 'block' | 'warn';
export type LayoutRuleTransformType = 'upper' | 'lower' | 'trim' | 'titleCase';

interface BaseLayoutRule {
  id: string;
  tenantId: string;
  layoutId: string;
  name: string;
  description?: string;
  active: boolean;
  when: LayoutRuleEvent[];
  dependsOn?: string[];
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface ComputeLayoutRule extends BaseLayoutRule {
  kind: 'compute';
  target: string;
  formula: string;
}

export interface ValidateLayoutRule extends BaseLayoutRule {
  kind: 'validate';
  target?: string;
  formula: string;
  errorMessage: string;
  enforce: LayoutRuleEnforce;
}

export interface DefaultLayoutRule extends BaseLayoutRule {
  kind: 'default';
  target: string;
  formula: string;
  triggerFields?: string[];
}

export interface TransformLayoutRule extends BaseLayoutRule {
  kind: 'transform';
  target: string;
  transform:
    | { type: 'upper' | 'lower' | 'trim' | 'titleCase' }
    | { type: 'formula'; formula: string };
}

/**
 * SCRIPT rule — a general client event handler evaluating a sandboxed
 * `@kelta/formula` expression on its `when` events. The result is treated as a
 * validation message: a non-empty string surfaces on `target` (or the form
 * when `target` is omitted); a boolean `true` uses the static `message`; any
 * falsy result clears it. On `onBeforeSave` a non-empty message blocks the
 * submit (client-side UX only — the server record-event hook, slice 7, is
 * authoritative and cannot be bypassed via the API).
 */
export interface ScriptLayoutRule extends BaseLayoutRule {
  kind: 'script';
  target?: string;
  expression: string;
  message?: string;
}

export type LayoutRule =
  | ComputeLayoutRule
  | ValidateLayoutRule
  | DefaultLayoutRule
  | TransformLayoutRule
  | ScriptLayoutRule;

export interface CreateLayoutRuleRequest {
  layoutId: string;
  name: string;
  description?: string;
  kind: 'COMPUTE' | 'VALIDATE' | 'DEFAULT' | 'TRANSFORM' | 'SCRIPT';
  active?: boolean;
  whenEvents: LayoutRuleEvent[];
  targetField?: string;
  dependsOn?: string[];
  body: Record<string, unknown>;
  sortOrder: number;
}

export type UpdateLayoutRuleRequest = Partial<CreateLayoutRuleRequest>;

export interface LayoutSection {
  id: string;
  heading: string;
  columns: number;
  sortOrder: number;
  collapsed: boolean;
  style: 'DEFAULT' | 'COLLAPSIBLE' | 'CARD';
  sectionType: 'STANDARD' | 'HIGHLIGHTS_PANEL' | 'BLANK_SPACE';
  tabGroup?: string;
  tabLabel?: string;
  visibilityRule?: string;
  fields: LayoutFieldPlacement[];
}

export interface LayoutFieldPlacement {
  id: string;
  fieldId: string;
  fieldName?: string;
  fieldType?: string;
  fieldDisplayName?: string;
  columnNumber: number;
  columnSpan?: number;
  sortOrder: number;
  requiredOnLayout: boolean;
  readOnlyOnLayout: boolean;
  labelOverride?: string;
  helpTextOverride?: string;
  visibilityRule?: string;
}

export interface LayoutRelatedList {
  id: string;
  relatedCollectionId: string;
  relationshipField: string;
  displayColumns: string;
  sortField?: string;
  sortDirection: 'ASC' | 'DESC';
  rowLimit: number;
  sortOrder: number;
}

export interface CreatePageLayoutRequest {
  collectionId: string;
  name: string;
  description?: string;
  layoutType: string;
  isDefault?: boolean;
  sections?: CreateLayoutSectionRequest[];
  relatedLists?: CreateRelatedListRequest[];
  defaultFilter?: LayoutFilter | null;
  defaultSortField?: string;
  defaultSortDirection?: 'ASC' | 'DESC';
  defaultRowLimit?: number;
}

export interface CreateLayoutSectionRequest {
  heading: string;
  columns?: number;
  sortOrder: number;
  collapsed?: boolean;
  style?: string;
  sectionType?: string;
  tabGroup?: string;
  tabLabel?: string;
  visibilityRule?: string;
  fields?: CreateFieldPlacementRequest[];
}

export interface CreateFieldPlacementRequest {
  fieldId: string;
  columnNumber?: number;
  columnSpan?: number;
  sortOrder: number;
  requiredOnLayout?: boolean;
  readOnlyOnLayout?: boolean;
  labelOverride?: string;
  helpTextOverride?: string;
  visibilityRule?: string;
}

export interface CreateRelatedListRequest {
  relatedCollectionId: string;
  relationshipField: string;
  displayColumns?: string;
  sortField?: string;
  sortDirection?: string;
  rowLimit?: number;
  sortOrder: number;
}

export interface LayoutAssignment {
  id: string;
  collectionId: string;
  profileId?: string;
  recordTypeId?: string;
  layoutId: string;
  condition?: LayoutFilter | null;
  evaluationOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface LayoutAssignmentRequest {
  collectionId: string;
  profileId?: string;
  recordTypeId?: string;
  layoutId: string;
  condition?: LayoutFilter | null;
  evaluationOrder?: number;
}

/**
 * Visibility rule for conditional field/section display.
 * The rule is stored as a JSON string in the API but this type
 * represents the parsed structure for client-side evaluation.
 */
export interface VisibilityRule {
  fieldName: string;
  operator: 'EQUALS' | 'NOT_EQUALS' | 'CONTAINS' | 'IS_EMPTY' | 'IS_NOT_EMPTY';
  value?: string;
  logic?: 'AND' | 'OR';
  conditions?: VisibilityRule[];
}

// --- List Views (Phase 3 Stream B) ---

export interface ListView {
  id: string;
  tenantId: string;
  collectionId: string;
  name: string;
  columns: string;
  filters: string;
  filterLogic?: string;
  sortField?: string;
  sortDirection: 'ASC' | 'DESC';
  chartConfig?: string;
  visibility: 'PRIVATE' | 'PUBLIC' | 'GROUP';
  /** Renderer published with the view (V196); absent on rows written before it. */
  viewType?: ListViewType;
  /** Per-renderer settings, e.g. `{ kanban: { laneField, cardFields } }` (V196). */
  typeConfig?: ListViewTypeConfig | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export type ListViewType = 'TABLE' | 'KANBAN' | 'CALENDAR' | 'GALLERY';

export interface ListViewTypeConfig {
  kanban?: { laneField: string; cardFields?: string[] };
  calendar?: { dateField: string; endDateField?: string };
  gallery?: { imageField?: string; titleField?: string; cardFields?: string[] };
}

export interface CreateListViewRequest {
  collectionId: string;
  name: string;
  columns: string;
  filters?: string;
  filterLogic?: string;
  sortField?: string;
  sortDirection?: string;
  chartConfig?: string;
  visibility?: string;
  viewType?: ListViewType;
  typeConfig?: ListViewTypeConfig | null;
}

// --- Reports (Phase 3 Stream C) ---

export interface Report {
  id: string;
  name: string;
  description?: string;
  reportType: 'TABULAR' | 'SUMMARY' | 'MATRIX';
  primaryCollectionId: string;
  relatedJoins: string;
  columns: string;
  filters: string;
  filterLogic?: string;
  rowGroupings: string;
  columnGroupings: string;
  sortOrder: string;
  chartType?: string;
  chartConfig?: string;
  scope: 'MY_RECORDS' | 'ALL_RECORDS' | 'MY_TEAM_RECORDS';
  folderId?: string;
  accessLevel: 'PRIVATE' | 'PUBLIC' | 'HIDDEN';
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReportFolder {
  id: string;
  tenantId: string;
  name: string;
  accessLevel: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateReportRequest {
  name: string;
  description?: string;
  reportType: string;
  primaryCollectionId: string;
  relatedJoins?: string;
  columns: string;
  filters?: string;
  filterLogic?: string;
  rowGroupings?: string;
  columnGroupings?: string;
  sortOrder?: string;
  chartType?: string;
  chartConfig?: string;
  scope?: string;
  folderId?: string;
  accessLevel?: string;
}

// --- Dashboards (Phase 3 Stream D) ---

export interface UserDashboard {
  id: string;
  name: string;
  description?: string;
  folderId?: string;
  accessLevel: 'PRIVATE' | 'PUBLIC' | 'HIDDEN';
  dynamic: boolean;
  runningUserId?: string;
  columnCount: number;
  createdBy: string;
  components: DashboardComponent[];
  createdAt: string;
  updatedAt: string;
}

export interface DashboardComponent {
  id: string;
  reportId: string;
  /**
   * The four widget renderers the server supports, lowercase — the values
   * `dashboard-components.componentType` now declares as its enum, so anything
   * else is rejected with a 400. (`GAUGE` was never implemented.)
   */
  componentType: 'metric' | 'chart' | 'table' | 'recent';
  title?: string;
  columnPosition: number;
  rowPosition: number;
  columnSpan: number;
  rowSpan: number;
  config: string;
  sortOrder: number;
}

export interface CreateDashboardRequest {
  name: string;
  description?: string;
  folderId?: string;
  accessLevel?: string;
  dynamic?: boolean;
  runningUserId?: string;
  columnCount?: number;
  components?: CreateDashboardComponentRequest[];
}

export interface CreateDashboardComponentRequest {
  reportId: string;
  componentType: string;
  title?: string;
  columnPosition: number;
  rowPosition: number;
  columnSpan?: number;
  rowSpan?: number;
  config?: string;
  sortOrder: number;
}

// --- Data Export (Phase 3 Stream E) ---

export interface ExportRequest {
  filename?: string;
  columns: string[];
  rows: Record<string, unknown>[];
}

// --- Email Templates (Phase 4 Stream A) ---

export interface EmailTemplate {
  id: string;
  name: string;
  description?: string;
  subject: string;
  bodyHtml: string;
  bodyText?: string;
  relatedCollectionId?: string;
  folder?: string;
  active: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface EmailLog {
  id: string;
  templateId?: string;
  recipientEmail: string;
  subject: string;
  status: 'QUEUED' | 'SENT' | 'FAILED';
  source?: string;
  sourceId?: string;
  errorMessage?: string;
  sentAt?: string;
  createdAt: string;
}

export interface CreateEmailTemplateRequest {
  name: string;
  description?: string;
  subject: string;
  bodyHtml: string;
  bodyText?: string;
  relatedCollectionId?: string;
  folder?: string;
  active?: boolean;
}

// --- Email Campaigns ---

export type CampaignStatus =
  | 'DRAFT'
  | 'SCHEDULED'
  | 'QUEUED'
  | 'SENDING'
  | 'SENT'
  | 'FAILED'
  | 'CANCELLED';

/** A single filter condition applied to the target collection. */
export interface CampaignFilterCondition {
  field: string;
  op: string;
  value: unknown;
}

export interface Campaign {
  id: string;
  name: string;
  description?: string;
  subject: string;
  bodyHtml?: string;
  templateId?: string;
  targetCollection: string;
  recipientEmailField: string;
  filterJson?: CampaignFilterCondition[];
  listViewId?: string;
  fromName?: string;
  fromAddress?: string;
  status: CampaignStatus;
  scheduledAt?: string;
  totalRecipients?: number;
  sentCount?: number;
  failedCount?: number;
  openCount?: number;
  clickCount?: number;
  unsubscribeCount?: number;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  createdAt: string;
}

export interface CreateCampaignRequest {
  name: string;
  description?: string;
  subject: string;
  bodyHtml?: string;
  templateId?: string;
  targetCollection: string;
  recipientEmailField: string;
  filterJson?: CampaignFilterCondition[];
  listViewId?: string;
  fromName?: string;
  fromAddress?: string;
  status?: CampaignStatus;
  scheduledAt?: string;
}

export interface CampaignStats {
  status: CampaignStatus;
  totalRecipients: number;
  sent: number;
  failed: number;
  opens: number;
  clicks: number;
  unsubscribes: number;
  startedAt?: string;
  completedAt?: string;
}

export interface CampaignRecipient {
  id: string;
  campaignId?: string;
  recordId?: string;
  email: string;
  status: string;
  errorMessage?: string;
  sentAt?: string;
  openedAt?: string;
  clickedAt?: string;
}

export interface EmailSuppression {
  id: string;
  email: string;
  reason?: string;
  createdAt?: string;
}

// --- Workflow Rules (Phase 4 Stream B) ---

export interface WorkflowRule {
  id: string;
  collectionId: string;
  name: string;
  description?: string;
  active: boolean;
  triggerType: 'ON_CREATE' | 'ON_UPDATE' | 'ON_CREATE_OR_UPDATE' | 'ON_DELETE';
  filterFormula?: string;
  reEvaluateOnUpdate: boolean;
  executionOrder: number;
  actions: WorkflowAction[];
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowAction {
  id: string;
  actionType:
    | 'FIELD_UPDATE'
    | 'EMAIL_ALERT'
    | 'CREATE_RECORD'
    | 'INVOKE_SCRIPT'
    | 'OUTBOUND_MESSAGE'
    | 'CREATE_TASK'
    | 'PUBLISH_EVENT';
  executionOrder: number;
  config: string;
  active: boolean;
}

export interface WorkflowExecutionLog {
  id: string;
  workflowRuleId: string;
  recordId: string;
  triggerType: string;
  status: 'SUCCESS' | 'PARTIAL_FAILURE' | 'FAILURE';
  actionsExecuted: number;
  errorMessage?: string;
  executedAt: string;
  durationMs?: number;
}

export interface CreateWorkflowRuleRequest {
  collectionId: string;
  name: string;
  description?: string;
  active?: boolean;
  triggerType: string;
  filterFormula?: string;
  reEvaluateOnUpdate?: boolean;
  executionOrder?: number;
  actions?: CreateWorkflowActionRequest[];
}

export interface CreateWorkflowActionRequest {
  actionType: string;
  executionOrder?: number;
  config: string;
  active?: boolean;
}

// --- Approval Processes (Phase 4 Stream C) ---

export interface ApprovalProcess {
  id: string;
  collectionId: string;
  name: string;
  description?: string;
  active: boolean;
  entryCriteria?: string;
  recordEditability: 'LOCKED' | 'ADMIN_ONLY';
  initialSubmitterField?: string;
  onSubmitFieldUpdates: string;
  onApprovalFieldUpdates: string;
  onRejectionFieldUpdates: string;
  onRecallFieldUpdates: string;
  allowRecall: boolean;
  executionOrder: number;
  steps: ApprovalStep[];
  createdAt: string;
  updatedAt: string;
}

export interface ApprovalStep {
  id: string;
  stepNumber: number;
  name: string;
  description?: string;
  entryCriteria?: string;
  approverType: 'USER' | 'ROLE' | 'QUEUE' | 'MANAGER_HIERARCHY' | 'RELATED_USER';
  approverId?: string;
  approverField?: string;
  unanimityRequired: boolean;
  escalationTimeoutHours?: number;
  escalationAction?: string;
  onApproveAction: string;
  onRejectAction: string;
}

export interface ApprovalInstance {
  id: string;
  approvalProcessId: string;
  approvalProcessName: string;
  collectionId: string;
  recordId: string;
  submittedBy: string;
  currentStepNumber: number;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'RECALLED';
  submittedAt: string;
  completedAt?: string;
  stepInstances: ApprovalStepInstance[];
}

export interface ApprovalStepInstance {
  id: string;
  stepId: string;
  assignedTo: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'REASSIGNED';
  comments?: string;
  actedAt?: string;
}

export interface CreateApprovalProcessRequest {
  collectionId: string;
  name: string;
  description?: string;
  active?: boolean;
  entryCriteria?: string;
  recordEditability?: string;
  initialSubmitterField?: string;
  onSubmitFieldUpdates?: string;
  onApprovalFieldUpdates?: string;
  onRejectionFieldUpdates?: string;
  onRecallFieldUpdates?: string;
  allowRecall?: boolean;
  executionOrder?: number;
  steps?: CreateApprovalStepRequest[];
}

export interface CreateApprovalStepRequest {
  stepNumber: number;
  name: string;
  description?: string;
  entryCriteria?: string;
  approverType: string;
  approverId?: string;
  approverField?: string;
  unanimityRequired?: boolean;
  escalationTimeoutHours?: number;
  escalationAction?: string;
  onApproveAction?: string;
  onRejectAction?: string;
}

// --- Flow Engine (Phase 4 Stream D) ---

/**
 * A field backed by a Postgres `jsonb` column, so its wire shape is a real JSON
 * object — not a JSON string.
 *
 * Writing a *string* into one of these lands in the database as
 * `{"type":"jsonb","value":"<escaped json>"}`, which every reader then has to
 * defensively unwrap (see the `"jsonb"`/`"value"` unwrap in
 * `kelta-worker/.../listener/NatsTriggerFlowListener.java`). Send the object.
 *
 * `string` stays in the read/write unions for rows written before that was
 * understood; callers narrow on `typeof` before use.
 *
 * Deliberately `object` rather than `Record<string, unknown>`: callers pass
 * their own domain interfaces here (a flow state machine, a trigger config),
 * and TypeScript gives an implicit index signature to type aliases but not to
 * interfaces, so `Record<string, unknown>` would reject them.
 */
export type JsonObjectField = object;

export interface FlowDefinition {
  id: string;
  name: string;
  description?: string;
  flowType: 'RECORD_TRIGGERED' | 'SCHEDULED' | 'AUTOLAUNCHED' | 'SCREEN';
  active: boolean;
  version: number;
  triggerConfig?: string;
  definition: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface FlowExecution {
  id: string;
  flowId: string;
  flowName: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'WAITING' | 'CANCELLED';
  startedBy?: string;
  triggerRecordId?: string;
  variables: string;
  currentNodeId?: string;
  errorMessage?: string;
  startedAt: string;
  completedAt?: string;
}

export interface CreateFlowRequest {
  name: string;
  /** `null` clears the stored description; `undefined` leaves it untouched. */
  description?: string | null;
  flowType: string;
  active?: boolean;
  /** `jsonb` — send an object. See {@link JsonObjectField}. */
  triggerConfig?: JsonObjectField | string | null;
  /** `jsonb` — send an object. See {@link JsonObjectField}. */
  definition: JsonObjectField | string;
  /**
   * Audit identity stamped on records this flow writes when the execution has
   * no initiating user (cron/NATS/webhook starts). Falls back to the flow
   * owner when unset.
   */
  runAsUserId?: string | null;
}

// --- Scheduled Jobs (Phase 4 Stream E) ---

export interface ScheduledJob {
  id: string;
  name: string;
  description?: string;
  jobType: 'FLOW' | 'SCRIPT' | 'REPORT_EXPORT';
  jobReferenceId?: string;
  cronExpression: string;
  timezone: string;
  active: boolean;
  lastRunAt?: string;
  lastStatus?: string;
  nextRunAt?: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface JobExecutionLog {
  id: string;
  jobId: string;
  status: string;
  recordsProcessed: number;
  errorMessage?: string;
  startedAt: string;
  completedAt?: string;
  durationMs?: number;
}

export interface CreateScheduledJobRequest {
  name: string;
  description?: string;
  jobType: string;
  jobReferenceId?: string;
  cronExpression: string;
  timezone?: string;
  active?: boolean;
}

// --- Scripts (Phase 5 Stream A) ---

export interface Script {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  scriptType:
    | 'BEFORE_TRIGGER'
    | 'AFTER_TRIGGER'
    | 'SCHEDULED'
    | 'API_ENDPOINT'
    | 'VALIDATION'
    | 'EVENT_HANDLER'
    | 'EMAIL_HANDLER';
  language: string;
  sourceCode: string;
  active: boolean;
  version: number;
  createdBy: string;
  triggers: ScriptTrigger[];
  createdAt: string;
  updatedAt: string;
}

export interface ScriptTrigger {
  id: string;
  collectionId: string;
  triggerEvent: 'INSERT' | 'UPDATE' | 'DELETE';
  executionOrder: number;
  active: boolean;
}

export interface ScriptExecutionLog {
  id: string;
  tenantId: string;
  scriptId: string;
  status: 'SUCCESS' | 'FAILURE' | 'TIMEOUT' | 'GOVERNOR_LIMIT';
  triggerType?: string;
  recordId?: string;
  durationMs?: number;
  cpuMs?: number;
  queriesExecuted: number;
  dmlRows: number;
  callouts: number;
  errorMessage?: string;
  logOutput?: string;
  executedAt: string;
}

export interface CreateScriptRequest {
  name: string;
  description?: string;
  scriptType: string;
  language?: string;
  sourceCode: string;
  active?: boolean;
  triggers?: CreateScriptTriggerRequest[];
}

export interface CreateScriptTriggerRequest {
  collectionId: string;
  triggerEvent: string;
  executionOrder?: number;
  active?: boolean;
}

// --- Svix Webhook Portal ---

export interface SvixPortalResponse {
  token: string;
  appId: string;
  serverUrl: string;
}

// --- Superset ---

export interface SupersetGuestTokenResponse {
  token: string;
  supersetDomain: string;
}

export interface SupersetDashboard {
  id: number;
  dashboard_title: string;
  url: string;
  status: string;
  published: boolean;
  changed_on_utc: string;
  embedded_id?: string;
}

export interface SupersetDataset {
  id: number;
  table_name: string;
  database_name: string;
  changed_on_utc: string;
}

// --- Webhook URL ---

export interface WebhookUrlResponse {
  webhookUrl: string;
  flowId: string;
}

// --- Connected Apps (Phase 5 Stream C) ---

export interface ConnectedApp {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  clientId: string;
  redirectUris?: string;
  scopes?: string;
  ipRestrictions?: string;
  rateLimitPerHour: number;
  active: boolean;
  grantTypes?: string[];
  requirePkce?: boolean;
  consentRequired?: boolean;
  lastUsedAt?: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConnectedAppCreatedResponse extends ConnectedApp {
  clientSecret: string;
}

export interface ConnectedAppToken {
  id: string;
  connectedAppId: string;
  scopes: string;
  issuedAt: string;
  expiresAt: string;
  revoked: boolean;
}

export interface CreateConnectedAppRequest {
  name: string;
  description?: string;
  redirectUris?: string;
  scopes?: string;
  ipRestrictions?: string;
  rateLimitPerHour?: number;
  active?: boolean;
  /** JSON array string of enabled OAuth2 grants, e.g. `["client_credentials","authorization_code"]`. */
  grantTypes?: string;
  /** Require PKCE (public client, no secret) — authorization_code apps only. */
  requirePkce?: boolean;
  /** Prompt the user for consent on the authorization_code flow. */
  consentRequired?: boolean;
}

// --- Bulk Jobs (Phase 5 Stream D) ---

export interface BulkJob {
  id: string;
  tenantId: string;
  collectionId: string;
  operation: 'INSERT' | 'UPDATE' | 'UPSERT' | 'DELETE';
  status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'ABORTED';
  totalRecords: number;
  processedRecords: number;
  successRecords: number;
  errorRecords: number;
  externalIdField?: string;
  contentType: string;
  batchSize: number;
  createdBy: string;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface BulkJobResult {
  id: string;
  bulkJobId: string;
  recordIndex: number;
  recordId?: string;
  status: 'SUCCESS' | 'FAILURE';
  errorMessage?: string;
  createdAt: string;
}

export interface CreateBulkJobRequest {
  collectionId: string;
  operation: string;
  externalIdField?: string;
  batchSize?: number;
  records: Record<string, unknown>[];
}

export interface CompositeSubRequest {
  method: string;
  url: string;
  body?: Record<string, unknown>;
  referenceId: string;
}

export interface CompositeRequest {
  compositeRequest: CompositeSubRequest[];
}

export interface CompositeSubResponse {
  referenceId: string;
  httpStatusCode: number;
  body: unknown;
}

export interface CompositeResponse {
  compositeResponse: CompositeSubResponse[];
}

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

export interface MetricsQueryParams {
  metric: string;
  start: string;
  end: string;
  step?: string;
  route?: string;
}

export interface MetricsDataPoint {
  timestamp: number;
  value: number;
}

export interface MetricsTimeSeries {
  labels: Record<string, string>;
  dataPoints: MetricsDataPoint[];
}

export interface MetricsQueryResult {
  metric: string;
  start: string;
  end: string;
  step: string;
  series: MetricsTimeSeries[];
}

export interface MetricsSummary {
  totalRequests: number;
  errorRate: number;
  avgLatencyMs: number;
  activeRequests: number;
  authFailures?: number;
  rateLimited?: number;
}

// ─── Observability types ───────────────────────────────────────────

export interface RequestLogEntry {
  traceID: string;
  spanID: string;
  operationName: string;
  startTime: number;
  startTimeMillis: number;
  duration: number;
  tagMap: Record<string, string | number | boolean>;
  process?: {
    serviceName: string;
  };
  references?: Array<{
    refType: string;
    traceID: string;
    spanID: string;
  }>;
}

export interface RequestLogSearchParams {
  method?: string;
  status?: string;
  path?: string;
  traceId?: string;
  userId?: string;
  start?: string;
  end?: string;
  page?: number;
  size?: number;
}

export interface RequestLogSearchResult {
  hits: RequestLogEntry[];
  totalHits: number;
  page: number;
  size: number;
}

export interface RequestLogDetail {
  spans: RequestLogEntry[];
  traceId: string;
}

export interface LogEntry {
  '@timestamp': string;
  level: string;
  message: string;
  logger_name?: string;
  thread_name?: string;
  service?: string;
  traceId?: string;
  spanId?: string;
  tenantId?: string;
  tenantSlug?: string;
  userId?: string;
  userEmail?: string;
  correlationId?: string;
  stack_trace?: string;
}

export interface LogSearchParams {
  query?: string;
  level?: string;
  service?: string;
  traceId?: string;
  start?: string;
  end?: string;
  page?: number;
  size?: number;
}

export interface LogSearchResult {
  hits: LogEntry[];
  totalHits: number;
  page: number;
  size: number;
}

export interface AuditSearchParams {
  auditType?: string;
  action?: string;
  userId?: string;
  start?: string;
  end?: string;
  page?: number;
  size?: number;
}

export interface AuditSearchResult {
  hits: Record<string, unknown>[];
  totalHits: number;
  page: number;
  size: number;
}

export interface EndpointPerformance {
  endpoint: string;
  requestCount: number;
  p50: number;
  p95: number;
  p99: number;
  avgDuration: number;
}

export interface ErrorGroup {
  path: string;
  count: number;
  statusCodes: Record<string, number>;
}

export interface LatencyPercentiles {
  p50: number;
  p95: number;
  p99: number;
  avg: number;
}

export interface ObservabilitySetting {
  id: string;
  settingKey: string;
  settingValue: string;
}

export interface ObservabilitySettingsResponse {
  settings: ObservabilitySetting[];
}

export interface UpdateObservabilitySettingsRequest {
  settings: { settingKey: string; settingValue: string }[];
}

// --- Search Reindex ---

export interface SearchIndexCollectionStats {
  collectionId: string;
  collectionName: string;
  indexedRecords: number;
}

export interface SearchIndexStats {
  totalIndexed: number;
  collections: SearchIndexCollectionStats[];
}

export interface SearchReindexResult {
  status: string;
  collection: string;
  message: string;
}

// --- Personal Access Tokens ---

export interface PersonalAccessToken {
  id: string;
  name: string;
  tokenPrefix: string;
  scopes: string[];
  expiresAt: string;
  lastUsedAt: string | null;
  createdAt: string;
}

export interface PersonalAccessTokenCreated {
  token: string;
  name: string;
  tokenPrefix: string;
  scopes: string[];
  expiresAt: string;
}

export interface CreatePersonalAccessTokenRequest {
  name: string;
  scopes?: string[];
  expiresInDays?: number;
}

// ---------------------------------------------------------------------------
// AI Chat
// ---------------------------------------------------------------------------

export interface AiChatRequest {
  message: string;
  conversationId?: string | null;
  contextType?: string;
  contextId?: string;
}

export interface AiChatResponse {
  conversationId: string;
  content: string;
  proposals: AiProposal[];
  tokensUsed: { input: number; output: number };
}

export interface AiProposal {
  id: string;
  type: 'collection' | 'layout';
  status: 'pending' | 'applied' | 'dismissed';
  data: Record<string, unknown>;
  createdAt: string;
}

export interface AiConversationSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface AiConversationDetail {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messages: AiMessage[];
}

export interface AiMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  proposalJson: string | null;
  tokensInput: number;
  tokensOutput: number;
  createdAt: string;
}

export interface AiTokenUsage {
  currentMonthUsage: number;
  tokenLimit: number;
  aiEnabled: boolean;
  history: Record<string, { inputTokens: number; outputTokens: number; requestCount: number }>;
}

export interface AiConfig {
  model: string;
  maxTokens: string;
  temperature: string;
  aiTokensPerMonth: string;
  aiEnabled: string;
}

export interface AiApplyResult {
  data: Record<string, unknown>;
  status: string;
}

// ----------------------------------------------------------------------------
// Credentials (PR 1: vault foundation)
// ----------------------------------------------------------------------------

export interface CredentialTypeDescriptor {
  key: string;
  displayName: string;
  description: string;
  inputSchema: Record<string, unknown>;
  supportsOAuthRefresh: boolean;
}

export interface CredentialTemplateDescriptor {
  key: string;
  name: string;
  type: string;
  iconUrl?: string;
  defaults?: Record<string, unknown>;
}

export interface CredentialTestResultPayload {
  ok: boolean;
  message: string;
  details?: Record<string, unknown>;
}

export interface CredentialRecord {
  id: string;
  name: string;
  displayName?: string;
  description?: string;
  type: string;
  providerTemplate?: string;
  metadata?: Record<string, unknown>;
  active: boolean;
  lastTestAt?: string;
  lastTestStatus?: string;
  lastTestError?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ----------------------------------------------------------------------------
// OpenAPI Spec Library (PR 3)
// ----------------------------------------------------------------------------

export interface ApiSpecSummary {
  id: string;
  name: string;
  description?: string | null;
  specVersion: string;
  apiTitle?: string | null;
  apiVersion?: string | null;
  baseUrl?: string | null;
  servers?: unknown;
  securitySchemes?: unknown;
  sourceType: 'INLINE_JSON' | 'INLINE_YAML' | 'URL';
  sourceUrl?: string | null;
  revision: number;
  active: boolean;
  lastImportedAt?: string | null;
}

export interface ApiOperationSummary {
  id: string;
  specId: string;
  operationId?: string | null;
  syntheticOpId: string;
  httpMethod: string;
  pathTemplate: string;
  summary?: string | null;
  tags?: unknown;
  deprecated: boolean;
}

export interface ApiOperationDetail extends ApiOperationSummary {
  description?: string | null;
  parametersSchema?: unknown;
  requestBodySchema?: unknown;
  responseSchemas?: unknown;
  securityRequired?: unknown;
}

export interface ImportApiSpecRequest {
  name: string;
  description?: string;
  sourceType: 'INLINE_JSON' | 'INLINE_YAML' | 'URL';
  sourceUrl?: string;
  raw?: string;
  rawFormat?: 'json' | 'yaml';
}

export interface ImportApiSpecResponse {
  spec: ApiSpecSummary;
  diff: { added: number; changed: number; removed: number };
}

export interface ApiSpecValidateResult {
  ok: boolean;
  title?: string;
  version?: string;
  specVersion?: string;
  operations?: number;
  baseUrl?: string;
  error?: string;
}

// ---------------------------------------------------------------------------
// Tenant email settings
// ---------------------------------------------------------------------------

export interface EmailSettingsSmtp {
  host?: string;
  port?: number;
  useStartTls?: boolean;
  fromAddress?: string;
}

export interface EmailSettings {
  hasOverride: boolean;
  fromAddress?: string | null;
  fromName?: string | null;
  autoInviteOnCreate: boolean;
  smtp?: EmailSettingsSmtp;
}

export interface EmailSettingsUpdate {
  host?: string;
  port?: number;
  username?: string;
  password?: string;
  useStartTls?: boolean;
  fromAddress?: string | null;
  fromName?: string | null;
  autoInviteOnCreate?: boolean;
  /** When true, drops the credential FK and reverts the tenant to platform default SMTP. */
  clear?: boolean;
}

export interface EmailTemplateDetail {
  id: string;
  name: string;
  subject: string;
  bodyHtml: string;
  bodyText?: string | null;
  variablesSchema?: unknown;
}

export interface EmailTemplateEntry {
  templateKey: string;
  systemDefault?: EmailTemplateDetail;
  tenantOverride?: EmailTemplateDetail;
}
