/**
 * @kelta/sdk - Type-safe TypeScript client for Kelta APIs
 *
 * This package provides a comprehensive client for interacting with Kelta services,
 * including resource discovery, CRUD operations, query building, and control plane
 * administration.
 */

// Client
export { KeltaClient } from './client/KeltaClient';
export type { KeltaClientConfig, TokenProvider, CacheConfig, RetryConfig } from './client/types';

// Resources
export { ResourceClient } from './resources/ResourceClient';
export type {
  ListOptions,
  GetOptions,
  ListResponse,
  SortCriteria,
  FilterExpression,
  PaginationMeta,
} from './resources/types';

// Query Builder
export { QueryBuilder } from './query/QueryBuilder';

// Admin Client
export { AdminClient } from './admin/AdminClient';
export type {
  CollectionDefinition,
  FieldDefinition,
  FieldType,
  FieldTypeConfig,
  Role,
  Policy,
  OIDCProvider,
  UIConfig,
  UIPage,
  UIMenu,
  PackageData,
  ExportOptions,
  ImportResult,
  Migration,
  MigrationResult,
  MigrationRun,
  MigrationPlan,
  Page,
  Tenant,
  CreateTenantRequest,
  UpdateTenantRequest,
  GovernorLimits,
  GovernorLimitsStatus,
  PlatformUser,
  CreatePlatformUserRequest,
  UpdatePlatformUserRequest,
  LoginHistoryEntry,
  Profile,
  CreateProfileRequest,
  UpdateProfileRequest,
  GlobalPicklist,
  PicklistValue,
  PicklistDependency,
  CreateGlobalPicklistRequest,
  PicklistValueRequest,
  SetDependencyRequest,
  RelationshipInfo,
  CollectionRelationships,
  CollectionValidationRule,
  CreateCollectionValidationRuleRequest,
  CollectionValidationError,
  RecordType,
  CreateRecordTypeRequest,
  RecordTypePicklistOverride,
  SetPicklistOverrideRequest,
  FieldHistoryEntry,
  SetupAuditTrailEntry,
  PageLayout,
  LayoutSection,
  LayoutFieldPlacement,
  LayoutRelatedList,
  VisibilityRule,
  CreatePageLayoutRequest,
  CreateLayoutSectionRequest,
  CreateFieldPlacementRequest,
  CreateRelatedListRequest,
  LayoutAssignment,
  LayoutAssignmentRequest,
  LayoutRule,
  LayoutRuleKind,
  LayoutRuleEvent,
  LayoutRuleEnforce,
  LayoutRuleTransformType,
  ComputeLayoutRule,
  ValidateLayoutRule,
  DefaultLayoutRule,
  TransformLayoutRule,
  ScriptLayoutRule,
  CreateLayoutRuleRequest,
  UpdateLayoutRuleRequest,
  ListView,
  ListViewType,
  ListViewTypeConfig,
  CreateListViewRequest,
  Report,
  CreateReportRequest,
  UserDashboard,
  CreateDashboardRequest,
  EmailTemplate,
  CreateEmailTemplateRequest,
  EmailLog,
  Campaign,
  CampaignStatus,
  CampaignFilterCondition,
  CreateCampaignRequest,
  CampaignStats,
  CampaignRecipient,
  EmailSuppression,
  WorkflowRule,
  CreateWorkflowRuleRequest,
  ApprovalProcess,
  ApprovalInstance,
  CreateApprovalProcessRequest,
  JsonObjectField,
  FlowDefinition,
  FlowExecution,
  CreateFlowRequest,
  ScheduledJob,
  JobExecutionLog,
  CreateScheduledJobRequest,
  Script,
  ScriptExecutionLog,
  CreateScriptRequest,
  SvixPortalResponse,
  SupersetGuestTokenResponse,
  SupersetDashboard,
  SupersetDataset,
  WebhookUrlResponse,
  ConnectedApp,
  ConnectedAppCreatedResponse,
  CreateConnectedAppRequest,
  BulkJob,
  BulkJobResult,
  CreateBulkJobRequest,
  ObjectPermissionRequest,
  FieldPermissionRequest,
  SystemPermissionRequest,
  MetricsQueryParams,
  MetricsQueryResult,
  MetricsTimeSeries,
  MetricsDataPoint,
  MetricsSummary,
  ObservabilitySetting,
  ObservabilitySettingsResponse,
  UpdateObservabilitySettingsRequest,
  SearchIndexStats,
  SearchIndexCollectionStats,
  SearchReindexResult,
  PersonalAccessToken,
  PersonalAccessTokenCreated,
  CreatePersonalAccessTokenRequest,
  CredentialRecord,
  CredentialTypeDescriptor,
  CredentialTemplateDescriptor,
  CredentialTestResultPayload,
  ApiSpecSummary,
  ApiOperationSummary,
  ApiOperationDetail,
  ImportApiSpecRequest,
  ImportApiSpecResponse,
  ApiSpecValidateResult,
  DelegatedNamedRef,
  DelegatedAdminSummary,
  DelegatedAdminScope,
  SaveDelegatedAdminScopeRequest,
  DelegatedCreateUserRequest,
  DelegatedUpdateUserRequest,
} from './admin/types';

// Authentication
export { TokenManager } from './auth/TokenManager';

// Errors
export {
  KeltaError,
  ValidationError,
  AuthenticationError,
  AuthorizationError,
  NotFoundError,
  ServerError,
  NetworkError,
} from './errors';

// Validation Schemas
export {
  ResourceMetadataSchema,
  ListResponseSchema,
  ErrorResponseSchema,
} from './validation/schemas';

// Types
export type { ResourceMetadata, AuthzConfig, ValidationRule, User } from './types';

// CLI - Type Generation
export {
  generateTypesFromUrl,
  generateTypesFromSpec,
  parseCollections,
  validateOpenAPISpec,
  generateTypes,
} from './cli';
export type {
  OpenAPISpec,
  ParsedCollection,
  ParsedField,
  TypeGenerationOptions,
  TypeGenerationResult,
} from './cli';
