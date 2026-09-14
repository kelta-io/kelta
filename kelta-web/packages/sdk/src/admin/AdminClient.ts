import type { AxiosInstance } from 'axios';
import type {
  CollectionDefinition,
  ApiOperationDetail,
  ApiOperationSummary,
  ApiSpecSummary,
  ApiSpecValidateResult,
  CredentialTemplateDescriptor,
  CredentialTestResultPayload,
  CredentialTypeDescriptor,
  ImportApiSpecRequest,
  ImportApiSpecResponse,
  FieldDefinition,
  Role,
  Policy,
  OIDCProvider,
  UIConfig,
  PackageData,
  ExportOptions,
  PlatformUser,
  CreatePlatformUserRequest,
  UpdatePlatformUserRequest,
  PortalInviteRequest,
  PortalInviteResponse,
  DelegatedAdminSummary,
  DelegatedAdminScope,
  SaveDelegatedAdminScopeRequest,
  DelegatedCreateUserRequest,
  DelegatedUpdateUserRequest,
  LoginHistoryEntry,
  ImportResult,
  Migration,
  UIPage,
  UIMenu,
  MigrationPlan,
  MigrationRun,
  Tenant,
  CreateTenantRequest,
  UpdateTenantRequest,
  GovernorLimits,
  Page,
  Profile,
  CreateProfileRequest,
  UpdateProfileRequest,
  ObjectPermissionRequest,
  FieldPermissionRequest,
  SystemPermissionRequest,
  OrgWideDefault,
  SetOwdRequest,
  SharingRule,
  CreateSharingRuleRequest,
  UpdateSharingRuleRequest,
  RecordShare,
  UserGroup,
  CreateUserGroupRequest,
  RoleHierarchyNode,
  SetupAuditTrailEntry,
  GovernorLimitsStatus,
  GlobalPicklist,
  PicklistValue,
  PicklistDependency,
  CreateGlobalPicklistRequest,
  PicklistValueRequest,
  SetDependencyRequest,
  CollectionRelationships,
  CollectionValidationRule,
  CreateCollectionValidationRuleRequest,
  CollectionValidationError,
  RecordType,
  CreateRecordTypeRequest,
  RecordTypePicklistOverride,
  SetPicklistOverrideRequest,
  FieldHistoryEntry,
  PageLayout,
  CreatePageLayoutRequest,
  LayoutAssignment,
  LayoutAssignmentRequest,
  LayoutRule,
  CreateLayoutRuleRequest,
  UpdateLayoutRuleRequest,
  ListView,
  CreateListViewRequest,
  Report,
  ReportFolder,
  CreateReportRequest,
  UserDashboard,
  CreateDashboardRequest,
  ExportRequest,
  EmailTemplate,
  EmailLog,
  CreateEmailTemplateRequest,
  Campaign,
  CampaignStats,
  CampaignRecipient,
  EmailSuppression,
  CreateCampaignRequest,
  WorkflowRule,
  WorkflowExecutionLog,
  CreateWorkflowRuleRequest,
  ApprovalProcess,
  ApprovalInstance,
  CreateApprovalProcessRequest,
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
  ConnectedAppToken,
  CreateConnectedAppRequest,
  BulkJob,
  BulkJobResult,
  CreateBulkJobRequest,
  CompositeRequest,
  CompositeResponse,
  MetricsQueryParams,
  MetricsQueryResult,
  MetricsSummary,
  EndpointPerformance,
  ErrorGroup,
  LatencyPercentiles,
  RequestLogSearchParams,
  RequestLogSearchResult,
  RequestLogDetail,
  LogSearchParams,
  LogSearchResult,
  AuditSearchParams,
  AuditSearchResult,
  ObservabilitySettingsResponse,
  UpdateObservabilitySettingsRequest,
  SearchIndexStats,
  SearchReindexResult,
  PersonalAccessToken,
  PersonalAccessTokenCreated,
  CreatePersonalAccessTokenRequest,
  AiChatRequest,
  AiChatResponse,
  AiConversationSummary,
  AiConversationDetail,
  AiTokenUsage,
  AiConfig,
  AiApplyResult,
  EmailSettings,
  EmailSettingsUpdate,
  EmailTemplateEntry,
} from './types';
import {
  toJsonApiBody,
  unwrapJsonApiResource,
  unwrapJsonApiList,
  unwrapJsonApiMenusWithItems,
  extractMetadata,
  buildJsonApiParams,
} from './jsonapi-helpers';
import { evaluateFilter } from './filterEval';

function specificity(a: LayoutAssignment, profileId?: string, recordTypeId?: string): number {
  let score = 0;
  if (profileId && a.profileId === profileId) score += 2;
  if (recordTypeId && a.recordTypeId === recordTypeId) score += 1;
  return score;
}

/**
 * Default theme and branding constants for bootstrap.
 * These provide fallback values when the tenant has no custom theme.
 */
const DEFAULT_THEME = {
  primaryColor: '#1976d2',
  secondaryColor: '#dc004e',
  fontFamily: 'Inter, system-ui, -apple-system, sans-serif',
  borderRadius: '8px',
};

const DEFAULT_BRANDING = {
  logoUrl: '',
  applicationName: 'Kelta',
  faviconUrl: '',
};

/**
 * Convert JSON:API metadata into a Spring-style Page<T> response.
 */
function toPage<T>(items: T[], metadata: unknown): Page<T> {
  const meta = metadata as Record<string, number> | undefined;
  return {
    content: items,
    totalElements: meta?.totalCount ?? items.length,
    totalPages: meta?.totalPages ?? 1,
    size: meta?.pageSize ?? items.length,
    number: meta?.currentPage ?? 0,
  };
}

/**
 * Admin client for platform operations.
 *
 * All endpoints route through the worker's DynamicCollectionRouter
 * via `/api/{collection}` (JSON:API format). The control plane has
 * been removed; all system collections are served by the worker.
 */
export class AdminClient {
  constructor(private readonly axios: AxiosInstance) {}

  // ---------------------------------------------------------------------------
  // Collections
  // ---------------------------------------------------------------------------

  readonly collections = {
    list: async (): Promise<CollectionDefinition[]> => {
      const response = await this.axios.get('/api/collections', {
        params: { 'page[size]': 1000 },
      });
      return unwrapJsonApiList<CollectionDefinition>(response.data);
    },

    get: async (id: string): Promise<CollectionDefinition> => {
      const response = await this.axios.get(`/api/collections/${id}`);
      return unwrapJsonApiResource<CollectionDefinition>(response.data);
    },

    create: async (definition: CollectionDefinition): Promise<CollectionDefinition> => {
      const body = toJsonApiBody('collections', definition as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/collections', body);
      return unwrapJsonApiResource<CollectionDefinition>(response.data);
    },

    update: async (id: string, definition: CollectionDefinition): Promise<CollectionDefinition> => {
      const body = toJsonApiBody(
        'collections',
        definition as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/collections/${id}`, body);
      return unwrapJsonApiResource<CollectionDefinition>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/collections/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  readonly fields = {
    add: async (collectionId: string, field: FieldDefinition): Promise<FieldDefinition> => {
      const body = toJsonApiBody('fields', {
        ...(field as unknown as Record<string, unknown>),
        collectionId,
      });
      const response = await this.axios.post('/api/fields', body);
      return unwrapJsonApiResource<FieldDefinition>(response.data);
    },

    update: async (
      _collectionId: string,
      fieldId: string,
      field: FieldDefinition
    ): Promise<FieldDefinition> => {
      const body = toJsonApiBody('fields', field as unknown as Record<string, unknown>, fieldId);
      const response = await this.axios.patch(`/api/fields/${fieldId}`, body);
      return unwrapJsonApiResource<FieldDefinition>(response.data);
    },

    delete: async (_collectionId: string, fieldId: string): Promise<void> => {
      await this.axios.delete(`/api/fields/${fieldId}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Authorization (roles & policies)
  // ---------------------------------------------------------------------------

  readonly authz = {
    listRoles: async (): Promise<Role[]> => {
      const response = await this.axios.get('/api/roles');
      return unwrapJsonApiList<Role>(response.data);
    },

    createRole: async (role: Role): Promise<Role> => {
      const body = toJsonApiBody('roles', role as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/roles', body);
      return unwrapJsonApiResource<Role>(response.data);
    },

    updateRole: async (id: string, role: Role): Promise<Role> => {
      const body = toJsonApiBody('roles', role as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/roles/${id}`, body);
      return unwrapJsonApiResource<Role>(response.data);
    },

    deleteRole: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/roles/${id}`);
    },

    listPolicies: async (): Promise<Policy[]> => {
      const response = await this.axios.get('/api/policies');
      return unwrapJsonApiList<Policy>(response.data);
    },

    createPolicy: async (policy: Policy): Promise<Policy> => {
      const body = toJsonApiBody('policies', policy as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/policies', body);
      return unwrapJsonApiResource<Policy>(response.data);
    },

    updatePolicy: async (id: string, policy: Policy): Promise<Policy> => {
      const body = toJsonApiBody('policies', policy as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/policies/${id}`, body);
      return unwrapJsonApiResource<Policy>(response.data);
    },

    deletePolicy: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/policies/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // OIDC providers (already on /api/)
  // ---------------------------------------------------------------------------

  readonly oidc = {
    list: async (): Promise<OIDCProvider[]> => {
      const response = await this.axios.get('/api/oidc-providers');
      return unwrapJsonApiList<OIDCProvider>(response.data);
    },

    get: async (id: string): Promise<OIDCProvider> => {
      const response = await this.axios.get(`/api/oidc-providers/${id}`);
      return unwrapJsonApiResource<OIDCProvider>(response.data);
    },

    create: async (provider: OIDCProvider): Promise<OIDCProvider> => {
      const body = toJsonApiBody('oidc-providers', provider as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/oidc-providers', body);
      return unwrapJsonApiResource<OIDCProvider>(response.data);
    },

    update: async (id: string, provider: OIDCProvider): Promise<OIDCProvider> => {
      const body = toJsonApiBody(
        'oidc-providers',
        provider as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/oidc-providers/${id}`, body);
      return unwrapJsonApiResource<OIDCProvider>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/oidc-providers/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // UI configuration (bootstrap, pages, menus)
  // ---------------------------------------------------------------------------

  readonly ui = {
    /**
     * Compose bootstrap configuration from individual JSON:API endpoints.
     * Replaces the old single /control/ui-bootstrap endpoint.
     */
    getBootstrap: async (): Promise<UIConfig> => {
      const [pagesRes, menusRes, providersRes] = await Promise.all([
        this.axios.get('/api/ui-pages'),
        this.axios.get('/api/ui-menus?include=ui-menu-items'),
        this.axios.get('/api/oidc-providers'),
      ]);
      return {
        pages: unwrapJsonApiList(pagesRes.data),
        menus: unwrapJsonApiMenusWithItems(menusRes.data),
        oidcProviders: unwrapJsonApiList(providersRes.data),
        theme: DEFAULT_THEME,
        branding: DEFAULT_BRANDING,
      } as UIConfig;
    },

    listPages: async (): Promise<UIPage[]> => {
      const response = await this.axios.get('/api/ui-pages');
      return unwrapJsonApiList<UIPage>(response.data);
    },

    createPage: async (page: UIPage): Promise<UIPage> => {
      const body = toJsonApiBody('ui-pages', page as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/ui-pages', body);
      return unwrapJsonApiResource<UIPage>(response.data);
    },

    updatePage: async (id: string, page: UIPage): Promise<UIPage> => {
      const body = toJsonApiBody('ui-pages', page as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/ui-pages/${id}`, body);
      return unwrapJsonApiResource<UIPage>(response.data);
    },

    listMenus: async (): Promise<UIMenu[]> => {
      const response = await this.axios.get('/api/ui-menus?include=ui-menu-items');
      return unwrapJsonApiMenusWithItems<UIMenu>(response.data);
    },

    updateMenu: async (id: string, menu: UIMenu): Promise<UIMenu> => {
      const body = toJsonApiBody('ui-menus', menu as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/ui-menus/${id}`, body);
      return unwrapJsonApiResource<UIMenu>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Packages — graceful degradation (needs server-side logic)
  // ---------------------------------------------------------------------------

  readonly packages = {
    export: (_options: ExportOptions): Promise<PackageData> => {
      throw new Error('Package export is temporarily unavailable');
    },

    import: (_packageData: PackageData): Promise<ImportResult> => {
      throw new Error('Package import is temporarily unavailable');
    },
  };

  // ---------------------------------------------------------------------------
  // Tenants
  // ---------------------------------------------------------------------------

  readonly tenants = {
    list: async (page = 0, size = 20): Promise<Page<Tenant>> => {
      const qs = buildJsonApiParams({ page, size });
      const response = await this.axios.get(`/api/tenants${qs}`);
      const items = unwrapJsonApiList<Tenant>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },

    get: async (id: string): Promise<Tenant> => {
      const response = await this.axios.get(`/api/tenants/${id}`);
      return unwrapJsonApiResource<Tenant>(response.data);
    },

    create: async (request: CreateTenantRequest): Promise<Tenant> => {
      const body = toJsonApiBody('tenants', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/tenants', body);
      return unwrapJsonApiResource<Tenant>(response.data);
    },

    update: async (id: string, request: UpdateTenantRequest): Promise<Tenant> => {
      const body = toJsonApiBody('tenants', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/tenants/${id}`, body);
      return unwrapJsonApiResource<Tenant>(response.data);
    },

    suspend: async (id: string): Promise<void> => {
      const body = toJsonApiBody('tenants', { status: 'SUSPENDED' }, id);
      await this.axios.patch(`/api/tenants/${id}`, body);
    },

    activate: async (id: string): Promise<void> => {
      const body = toJsonApiBody('tenants', { status: 'ACTIVE' }, id);
      await this.axios.patch(`/api/tenants/${id}`, body);
    },

    getLimits: async (id: string): Promise<GovernorLimits> => {
      // Governor limits are per-tenant; fetch from governor-limits collection
      const response = await this.axios.get(
        `/api/governor-limits?filter[tenantId][eq]=${encodeURIComponent(id)}`
      );
      const items = unwrapJsonApiList<GovernorLimits>(response.data);
      return items[0] ?? ({} as GovernorLimits);
    },
  };

  // ---------------------------------------------------------------------------
  // Users
  // ---------------------------------------------------------------------------

  readonly users = {
    list: async (
      filter?: string,
      status?: string,
      page = 0,
      size = 20
    ): Promise<Page<PlatformUser>> => {
      const params: Record<string, string> = {};
      if (filter) params['filter[search][contains]'] = filter;
      if (status) params['filter[status][eq]'] = status;
      const qs = buildJsonApiParams({ page, size, filters: params });
      const response = await this.axios.get(`/api/users${qs}`);
      const items = unwrapJsonApiList<PlatformUser>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },

    get: async (id: string): Promise<PlatformUser> => {
      const response = await this.axios.get(`/api/users/${id}`);
      return unwrapJsonApiResource<PlatformUser>(response.data);
    },

    create: async (request: CreatePlatformUserRequest): Promise<PlatformUser> => {
      const body = toJsonApiBody('users', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/users', body);
      return unwrapJsonApiResource<PlatformUser>(response.data);
    },

    update: async (id: string, request: UpdatePlatformUserRequest): Promise<PlatformUser> => {
      const body = toJsonApiBody('users', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/users/${id}`, body);
      return unwrapJsonApiResource<PlatformUser>(response.data);
    },

    deactivate: async (id: string): Promise<void> => {
      const body = toJsonApiBody('users', { status: 'INACTIVE' }, id);
      await this.axios.patch(`/api/users/${id}`, body);
    },

    activate: async (id: string): Promise<void> => {
      const body = toJsonApiBody('users', { status: 'ACTIVE' }, id);
      await this.axios.patch(`/api/users/${id}`, body);
    },

    /**
     * Creates (or re-invites) an external portal user. The worker forces
     * user_type=PORTAL + the Portal User profile and emails a single-use
     * magic sign-in link; portal users have no password.
     */
    invitePortal: async (request: PortalInviteRequest): Promise<PortalInviteResponse> => {
      const response = await this.axios.post('/api/admin/users/portal-invite', request);
      return response.data as PortalInviteResponse;
    },

    getLoginHistory: async (id: string, page = 0, size = 20): Promise<Page<LoginHistoryEntry>> => {
      const qs = buildJsonApiParams({
        page,
        size,
        filters: { 'filter[userId][eq]': id },
      });
      const response = await this.axios.get(`/api/login-history${qs}`);
      const items = unwrapJsonApiList<LoginHistoryEntry>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },

    resetPassword: async (userId: string): Promise<{ status: string; userId: string }> => {
      const response = await this.axios.post(`/api/admin/users/${userId}/reset-password`);
      return response.data as { status: string; userId: string };
    },

    tokens: {
      /**
       * Admin-on-behalf-of PAT mint (`MANAGE_USERS`) — same shape as {@link AdminClient.personalTokens}'s
       * self-service create, but mints for `userId` instead of the caller.
       */
      create: async (
        userId: string,
        request: CreatePersonalAccessTokenRequest
      ): Promise<PersonalAccessTokenCreated> => {
        const response = await this.axios.post(`/api/admin/users/${userId}/tokens`, request);
        return response.data as PersonalAccessTokenCreated;
      },
    },
  };

  // ---------------------------------------------------------------------------
  // Delegated administration (scoped user management for non-admin delegates)
  // ---------------------------------------------------------------------------

  readonly delegated = {
    /** The caller's effective delegated-admin summary. Fast {delegated:false} for non-delegates. */
    me: async (): Promise<DelegatedAdminSummary> => {
      const response = await this.axios.get('/api/admin/delegated/me');
      return response.data as DelegatedAdminSummary;
    },

    users: {
      list: async (page = 1, limit = 50): Promise<PlatformUser[]> => {
        const response = await this.axios.get(
          `/api/admin/delegated/users?page=${page}&limit=${limit}`
        );
        return unwrapJsonApiList<PlatformUser>(response.data);
      },

      create: async (request: DelegatedCreateUserRequest): Promise<PlatformUser> => {
        const body = toJsonApiBody('users', request as unknown as Record<string, unknown>);
        const response = await this.axios.post('/api/admin/delegated/users', body);
        return unwrapJsonApiResource<PlatformUser>(response.data);
      },

      update: async (id: string, request: DelegatedUpdateUserRequest): Promise<PlatformUser> => {
        const body = toJsonApiBody('users', request as unknown as Record<string, unknown>, id);
        const response = await this.axios.patch(`/api/admin/delegated/users/${id}`, body);
        return unwrapJsonApiResource<PlatformUser>(response.data);
      },

      invite: async (id: string): Promise<{ status: string }> => {
        const response = await this.axios.post(`/api/admin/delegated/users/${id}/invite`);
        return response.data as { status: string };
      },

      resetPassword: async (id: string): Promise<{ status: string; userId: string }> => {
        const response = await this.axios.post(`/api/admin/delegated/users/${id}/reset-password`);
        return response.data as { status: string; userId: string };
      },
    },
  };

  // ---------------------------------------------------------------------------
  // Delegated-admin scopes (MANAGE_DELEGATED_ADMINS)
  // ---------------------------------------------------------------------------

  readonly delegatedAdminScopes = {
    list: async (page = 1, limit = 50): Promise<DelegatedAdminScope[]> => {
      const response = await this.axios.get(
        `/api/admin/delegated-admin-scopes?page=${page}&limit=${limit}`
      );
      return unwrapJsonApiList<DelegatedAdminScope>(response.data);
    },

    get: async (id: string): Promise<DelegatedAdminScope> => {
      const response = await this.axios.get(`/api/admin/delegated-admin-scopes/${id}`);
      return unwrapJsonApiResource<DelegatedAdminScope>(response.data);
    },

    create: async (request: SaveDelegatedAdminScopeRequest): Promise<DelegatedAdminScope> => {
      const body = toJsonApiBody(
        'delegated-admin-scopes',
        request as unknown as Record<string, unknown>
      );
      const response = await this.axios.post('/api/admin/delegated-admin-scopes', body);
      return unwrapJsonApiResource<DelegatedAdminScope>(response.data);
    },

    update: async (
      id: string,
      request: SaveDelegatedAdminScopeRequest
    ): Promise<DelegatedAdminScope> => {
      const body = toJsonApiBody(
        'delegated-admin-scopes',
        request as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/admin/delegated-admin-scopes/${id}`, body);
      return unwrapJsonApiResource<DelegatedAdminScope>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/admin/delegated-admin-scopes/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Picklists (global already on /api/, field-level migrated)
  // ---------------------------------------------------------------------------

  readonly picklists = {
    listGlobal: async (_tenantId?: string): Promise<GlobalPicklist[]> => {
      const response = await this.axios.get('/api/global-picklists?page[size]=200');
      return unwrapJsonApiList<GlobalPicklist>(response.data);
    },

    createGlobal: async (
      request: CreateGlobalPicklistRequest,
      _tenantId?: string
    ): Promise<GlobalPicklist> => {
      const body = toJsonApiBody('global-picklists', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/global-picklists', body);
      return unwrapJsonApiResource<GlobalPicklist>(response.data);
    },

    getGlobal: async (id: string): Promise<GlobalPicklist> => {
      const response = await this.axios.get(`/api/global-picklists/${id}`);
      return unwrapJsonApiResource<GlobalPicklist>(response.data);
    },

    updateGlobal: async (
      id: string,
      request: Partial<CreateGlobalPicklistRequest>
    ): Promise<GlobalPicklist> => {
      const body = toJsonApiBody(
        'global-picklists',
        request as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/global-picklists/${id}`, body);
      return unwrapJsonApiResource<GlobalPicklist>(response.data);
    },

    deleteGlobal: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/global-picklists/${id}`);
    },

    getGlobalValues: async (id: string): Promise<PicklistValue[]> => {
      const response = await this.axios.get(`/api/global-picklists/${id}/picklist-values`);
      return unwrapJsonApiList<PicklistValue>(response.data);
    },

    setGlobalValues: async (
      id: string,
      values: PicklistValueRequest[]
    ): Promise<PicklistValue[]> => {
      // Replace all values: delete existing, then create new ones via sub-resource
      const existingResponse = await this.axios.get(`/api/global-picklists/${id}/picklist-values`);
      const existing = unwrapJsonApiList<PicklistValue>(existingResponse.data);
      await Promise.all(
        existing.map((v) =>
          this.axios.delete(`/api/global-picklists/${id}/picklist-values/${v.id}`)
        )
      );
      const created = await Promise.all(
        values.map((v) => {
          const body = toJsonApiBody('picklist-values', v as unknown as Record<string, unknown>);
          return this.axios.post(`/api/global-picklists/${id}/picklist-values`, body);
        })
      );
      return created.map((r) => unwrapJsonApiResource<PicklistValue>(r.data));
    },

    getFieldValues: async (fieldId: string): Promise<PicklistValue[]> => {
      const response = await this.axios.get(
        `/api/picklist-values?filter[picklistSourceId][eq]=${encodeURIComponent(fieldId)}&filter[picklistSourceType][eq]=FIELD`
      );
      return unwrapJsonApiList<PicklistValue>(response.data);
    },

    setFieldValues: async (
      fieldId: string,
      values: PicklistValueRequest[]
    ): Promise<PicklistValue[]> => {
      // Delete existing field values, then create new ones
      const existingResponse = await this.axios.get(
        `/api/picklist-values?filter[picklistSourceId][eq]=${encodeURIComponent(fieldId)}&filter[picklistSourceType][eq]=FIELD`
      );
      const existing = unwrapJsonApiList<PicklistValue>(existingResponse.data);
      await Promise.all(existing.map((v) => this.axios.delete(`/api/picklist-values/${v.id}`)));
      const created = await Promise.all(
        values.map((v) => {
          const body = toJsonApiBody('picklist-values', {
            ...(v as unknown as Record<string, unknown>),
            picklistSourceId: fieldId,
            picklistSourceType: 'FIELD',
          });
          return this.axios.post('/api/picklist-values', body);
        })
      );
      return created.map((r) => unwrapJsonApiResource<PicklistValue>(r.data));
    },

    getDependencies: async (fieldId: string): Promise<PicklistDependency[]> => {
      const response = await this.axios.get(
        `/api/picklist-dependencies?filter[controllingFieldId][eq]=${encodeURIComponent(fieldId)}`
      );
      return unwrapJsonApiList<PicklistDependency>(response.data);
    },

    setDependency: async (request: SetDependencyRequest): Promise<PicklistDependency> => {
      const body = toJsonApiBody(
        'picklist-dependencies',
        request as unknown as Record<string, unknown>
      );
      const response = await this.axios.post('/api/picklist-dependencies', body);
      return unwrapJsonApiResource<PicklistDependency>(response.data);
    },

    removeDependency: async (
      controllingFieldId: string,
      dependentFieldId: string
    ): Promise<void> => {
      // Find the dependency record first, then delete
      const response = await this.axios.get(
        `/api/picklist-dependencies?filter[controllingFieldId][eq]=${encodeURIComponent(controllingFieldId)}&filter[dependentFieldId][eq]=${encodeURIComponent(dependentFieldId)}`
      );
      const deps = unwrapJsonApiList<PicklistDependency>(response.data);
      if (deps.length > 0) {
        await this.axios.delete(`/api/picklist-dependencies/${deps[0].id}`);
      }
    },
  };

  // ---------------------------------------------------------------------------
  // Relationships
  // ---------------------------------------------------------------------------

  readonly relationships = {
    getForCollection: async (collectionId: string): Promise<CollectionRelationships> => {
      // Fetch reference-type fields for the collection to derive relationships
      const response = await this.axios.get(
        `/api/fields?filter[collectionId][eq]=${encodeURIComponent(collectionId)}&filter[type][eq]=REFERENCE`
      );
      const fields = unwrapJsonApiList<FieldDefinition>(response.data);
      return { fields } as unknown as CollectionRelationships;
    },
  };

  // ---------------------------------------------------------------------------
  // Validation rules
  // ---------------------------------------------------------------------------

  readonly validationRules = {
    list: async (collectionId: string): Promise<CollectionValidationRule[]> => {
      const response = await this.axios.get(
        `/api/validation-rules?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      return unwrapJsonApiList<CollectionValidationRule>(response.data);
    },

    create: async (
      collectionId: string,
      request: CreateCollectionValidationRuleRequest
    ): Promise<CollectionValidationRule> => {
      const body = toJsonApiBody('validation-rules', {
        ...(request as unknown as Record<string, unknown>),
        collectionId,
      });
      const response = await this.axios.post('/api/validation-rules', body);
      return unwrapJsonApiResource<CollectionValidationRule>(response.data);
    },

    get: async (_collectionId: string, ruleId: string): Promise<CollectionValidationRule> => {
      const response = await this.axios.get(`/api/validation-rules/${ruleId}`);
      return unwrapJsonApiResource<CollectionValidationRule>(response.data);
    },

    update: async (
      _collectionId: string,
      ruleId: string,
      request: Partial<CreateCollectionValidationRuleRequest> & { active?: boolean }
    ): Promise<CollectionValidationRule> => {
      const body = toJsonApiBody(
        'validation-rules',
        request as unknown as Record<string, unknown>,
        ruleId
      );
      const response = await this.axios.patch(`/api/validation-rules/${ruleId}`, body);
      return unwrapJsonApiResource<CollectionValidationRule>(response.data);
    },

    delete: async (_collectionId: string, ruleId: string): Promise<void> => {
      await this.axios.delete(`/api/validation-rules/${ruleId}`);
    },

    activate: async (_collectionId: string, ruleId: string): Promise<void> => {
      const body = toJsonApiBody('validation-rules', { active: true }, ruleId);
      await this.axios.patch(`/api/validation-rules/${ruleId}`, body);
    },

    deactivate: async (_collectionId: string, ruleId: string): Promise<void> => {
      const body = toJsonApiBody('validation-rules', { active: false }, ruleId);
      await this.axios.patch(`/api/validation-rules/${ruleId}`, body);
    },

    test: (
      _collectionId: string,
      _testRecord: Record<string, unknown>
    ): Promise<CollectionValidationError[]> => {
      // Validation test requires server-side logic — temporarily unavailable
      return Promise.resolve([]);
    },
  };

  // ---------------------------------------------------------------------------
  // Record types
  // ---------------------------------------------------------------------------

  readonly recordTypes = {
    list: async (collectionId: string): Promise<RecordType[]> => {
      const response = await this.axios.get(
        `/api/record-types?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      return unwrapJsonApiList<RecordType>(response.data);
    },

    create: async (collectionId: string, request: CreateRecordTypeRequest): Promise<RecordType> => {
      const body = toJsonApiBody('record-types', {
        ...(request as unknown as Record<string, unknown>),
        collectionId,
      });
      const response = await this.axios.post('/api/record-types', body);
      return unwrapJsonApiResource<RecordType>(response.data);
    },

    get: async (_collectionId: string, recordTypeId: string): Promise<RecordType> => {
      const response = await this.axios.get(`/api/record-types/${recordTypeId}`);
      return unwrapJsonApiResource<RecordType>(response.data);
    },

    update: async (
      _collectionId: string,
      recordTypeId: string,
      request: Partial<CreateRecordTypeRequest> & { active?: boolean }
    ): Promise<RecordType> => {
      const body = toJsonApiBody(
        'record-types',
        request as unknown as Record<string, unknown>,
        recordTypeId
      );
      const response = await this.axios.patch(`/api/record-types/${recordTypeId}`, body);
      return unwrapJsonApiResource<RecordType>(response.data);
    },

    delete: async (_collectionId: string, recordTypeId: string): Promise<void> => {
      await this.axios.delete(`/api/record-types/${recordTypeId}`);
    },

    getPicklistOverrides: async (
      _collectionId: string,
      recordTypeId: string
    ): Promise<RecordTypePicklistOverride[]> => {
      const response = await this.axios.get(
        `/api/record-type-picklists?filter[recordTypeId][eq]=${encodeURIComponent(recordTypeId)}`
      );
      return unwrapJsonApiList<RecordTypePicklistOverride>(response.data);
    },

    setPicklistOverride: async (
      _collectionId: string,
      recordTypeId: string,
      fieldId: string,
      request: SetPicklistOverrideRequest
    ): Promise<RecordTypePicklistOverride> => {
      const body = toJsonApiBody('record-type-picklists', {
        ...(request as unknown as Record<string, unknown>),
        recordTypeId,
        fieldId,
      });
      const response = await this.axios.post('/api/record-type-picklists', body);
      return unwrapJsonApiResource<RecordTypePicklistOverride>(response.data);
    },

    removePicklistOverride: async (
      _collectionId: string,
      _recordTypeId: string,
      picklistOverrideId: string
    ): Promise<void> => {
      await this.axios.delete(`/api/record-type-picklists/${picklistOverrideId}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Field history
  // ---------------------------------------------------------------------------

  readonly fieldHistory = {
    getRecordHistory: async (
      collectionId: string,
      recordId: string,
      page?: number,
      size?: number
    ): Promise<Page<FieldHistoryEntry>> => {
      const qs = buildJsonApiParams({
        page,
        size,
        filters: {
          'filter[collectionId][eq]': collectionId,
          'filter[recordId][eq]': recordId,
        },
      });
      const response = await this.axios.get(`/api/field-history${qs}`);
      const items = unwrapJsonApiList<FieldHistoryEntry>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },

    getFieldHistory: async (
      collectionId: string,
      recordId: string,
      fieldName: string,
      page?: number,
      size?: number
    ): Promise<Page<FieldHistoryEntry>> => {
      const qs = buildJsonApiParams({
        page,
        size,
        filters: {
          'filter[collectionId][eq]': collectionId,
          'filter[recordId][eq]': recordId,
          'filter[fieldName][eq]': fieldName,
        },
      });
      const response = await this.axios.get(`/api/field-history${qs}`);
      const items = unwrapJsonApiList<FieldHistoryEntry>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },

    getFieldHistoryAcrossRecords: async (
      collectionId: string,
      fieldName: string,
      page?: number,
      size?: number
    ): Promise<Page<FieldHistoryEntry>> => {
      const qs = buildJsonApiParams({
        page,
        size,
        filters: {
          'filter[collectionId][eq]': collectionId,
          'filter[fieldName][eq]': fieldName,
        },
      });
      const response = await this.axios.get(`/api/field-history${qs}`);
      const items = unwrapJsonApiList<FieldHistoryEntry>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },

    getUserHistory: async (
      userId: string,
      page?: number,
      size?: number
    ): Promise<Page<FieldHistoryEntry>> => {
      const qs = buildJsonApiParams({
        page,
        size,
        filters: { 'filter[userId][eq]': userId },
      });
      const response = await this.axios.get(`/api/field-history${qs}`);
      const items = unwrapJsonApiList<FieldHistoryEntry>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },
  };

  // ---------------------------------------------------------------------------
  // Migrations
  // ---------------------------------------------------------------------------

  readonly migrations = {
    plan: (_collectionId: string, _targetSchema: CollectionDefinition): Promise<MigrationPlan> => {
      throw new Error('Migration planning is temporarily unavailable');
    },

    listRuns: async (): Promise<Migration[]> => {
      const response = await this.axios.get('/api/migration-runs');
      return unwrapJsonApiList<Migration>(response.data);
    },

    getRun: async (id: string): Promise<MigrationRun> => {
      const response = await this.axios.get(`/api/migration-runs/${id}`);
      return unwrapJsonApiResource<MigrationRun>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Profiles
  // ---------------------------------------------------------------------------

  readonly profiles = {
    list: async (): Promise<Profile[]> => {
      const response = await this.axios.get('/api/profiles');
      return unwrapJsonApiList<Profile>(response.data);
    },

    get: async (id: string): Promise<Profile> => {
      const response = await this.axios.get(`/api/profiles/${id}`);
      return unwrapJsonApiResource<Profile>(response.data);
    },

    create: async (request: CreateProfileRequest): Promise<Profile> => {
      const body = toJsonApiBody('profiles', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/profiles', body);
      return unwrapJsonApiResource<Profile>(response.data);
    },

    update: async (id: string, request: UpdateProfileRequest): Promise<Profile> => {
      const body = toJsonApiBody('profiles', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/profiles/${id}`, body);
      return unwrapJsonApiResource<Profile>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/profiles/${id}`);
    },

    setObjectPermissions: async (
      id: string,
      collectionId: string,
      perms: ObjectPermissionRequest
    ): Promise<void> => {
      const body = toJsonApiBody('profile-object-permissions', {
        ...(perms as unknown as Record<string, unknown>),
        profileId: id,
        collectionId,
      });
      await this.axios.post('/api/profile-object-permissions', body);
    },

    setFieldPermissions: async (id: string, perms: FieldPermissionRequest[]): Promise<void> => {
      await Promise.all(
        perms.map((p) => {
          const body = toJsonApiBody('profile-field-permissions', {
            ...(p as unknown as Record<string, unknown>),
            profileId: id,
          });
          return this.axios.post('/api/profile-field-permissions', body);
        })
      );
    },

    setSystemPermissions: async (id: string, perms: SystemPermissionRequest[]): Promise<void> => {
      await Promise.all(
        perms.map((p) => {
          const body = toJsonApiBody('profile-system-permissions', {
            ...(p as unknown as Record<string, unknown>),
            profileId: id,
          });
          return this.axios.post('/api/profile-system-permissions', body);
        })
      );
    },
  };

  // ---------------------------------------------------------------------------
  // Sharing (OWD, rules, record shares)
  // ---------------------------------------------------------------------------

  readonly sharing = {
    getOwd: async (collectionId: string): Promise<OrgWideDefault> => {
      const response = await this.axios.get(
        `/api/org-wide-defaults?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      const items = unwrapJsonApiList<OrgWideDefault>(response.data);
      return items[0] ?? ({} as OrgWideDefault);
    },

    setOwd: async (collectionId: string, request: SetOwdRequest): Promise<OrgWideDefault> => {
      // Try to find existing OWD, then update or create
      const existing = await this.axios.get(
        `/api/org-wide-defaults?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      const items = unwrapJsonApiList<OrgWideDefault>(existing.data);
      if (items.length > 0) {
        const body = toJsonApiBody(
          'org-wide-defaults',
          request as unknown as Record<string, unknown>,
          items[0].id
        );
        const response = await this.axios.patch(`/api/org-wide-defaults/${items[0].id}`, body);
        return unwrapJsonApiResource<OrgWideDefault>(response.data);
      }
      const body = toJsonApiBody('org-wide-defaults', {
        ...(request as unknown as Record<string, unknown>),
        collectionId,
      });
      const response = await this.axios.post('/api/org-wide-defaults', body);
      return unwrapJsonApiResource<OrgWideDefault>(response.data);
    },

    listOwds: async (): Promise<OrgWideDefault[]> => {
      const response = await this.axios.get('/api/org-wide-defaults');
      return unwrapJsonApiList<OrgWideDefault>(response.data);
    },

    listRules: async (collectionId: string): Promise<SharingRule[]> => {
      const response = await this.axios.get(
        `/api/sharing-rules?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      return unwrapJsonApiList<SharingRule>(response.data);
    },

    createRule: async (
      _collectionId: string,
      request: CreateSharingRuleRequest
    ): Promise<SharingRule> => {
      const body = toJsonApiBody('sharing-rules', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/sharing-rules', body);
      return unwrapJsonApiResource<SharingRule>(response.data);
    },

    updateRule: async (ruleId: string, request: UpdateSharingRuleRequest): Promise<SharingRule> => {
      const body = toJsonApiBody(
        'sharing-rules',
        request as unknown as Record<string, unknown>,
        ruleId
      );
      const response = await this.axios.patch(`/api/sharing-rules/${ruleId}`, body);
      return unwrapJsonApiResource<SharingRule>(response.data);
    },

    deleteRule: async (ruleId: string): Promise<void> => {
      await this.axios.delete(`/api/sharing-rules/${ruleId}`);
    },

    listRecordShares: async (collectionId: string, recordId: string): Promise<RecordShare[]> => {
      const response = await this.axios.get(
        `/api/record-shares?filter[collectionId][eq]=${encodeURIComponent(collectionId)}&filter[recordId][eq]=${encodeURIComponent(recordId)}`
      );
      return unwrapJsonApiList<RecordShare>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // User groups
  // ---------------------------------------------------------------------------

  readonly groups = {
    list: async (): Promise<UserGroup[]> => {
      const response = await this.axios.get('/api/user-groups');
      return unwrapJsonApiList<UserGroup>(response.data);
    },

    get: async (id: string): Promise<UserGroup> => {
      const response = await this.axios.get(`/api/user-groups/${id}`);
      return unwrapJsonApiResource<UserGroup>(response.data);
    },

    create: async (request: CreateUserGroupRequest): Promise<UserGroup> => {
      const body = toJsonApiBody('user-groups', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/user-groups', body);
      return unwrapJsonApiResource<UserGroup>(response.data);
    },

    updateMembers: async (id: string, memberIds: string[]): Promise<UserGroup> => {
      // Update group by patching members
      const body = toJsonApiBody('user-groups', { memberIds }, id);
      const response = await this.axios.patch(`/api/user-groups/${id}`, body);
      return unwrapJsonApiResource<UserGroup>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/user-groups/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Audit
  // ---------------------------------------------------------------------------

  readonly audit = {
    list: async (params?: {
      section?: string;
      entityType?: string;
      userId?: string;
      from?: string;
      to?: string;
      page?: number;
      size?: number;
    }): Promise<Page<SetupAuditTrailEntry>> => {
      const filters: Record<string, string> = {};
      if (params?.section) filters['filter[section][eq]'] = params.section;
      if (params?.entityType) filters['filter[entityType][eq]'] = params.entityType;
      if (params?.userId) filters['filter[userId][eq]'] = params.userId;
      if (params?.from) filters['filter[createdAt][gte]'] = params.from;
      if (params?.to) filters['filter[createdAt][lte]'] = params.to;
      const qs = buildJsonApiParams({
        page: params?.page,
        size: params?.size,
        filters,
      });
      const response = await this.axios.get(`/api/setup-audit-entries${qs}`);
      const items = unwrapJsonApiList<SetupAuditTrailEntry>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },

    getEntityHistory: async (
      entityType: string,
      entityId: string,
      page?: number,
      size?: number
    ): Promise<Page<SetupAuditTrailEntry>> => {
      const qs = buildJsonApiParams({
        page,
        size,
        filters: {
          'filter[entityType][eq]': entityType,
          'filter[entityId][eq]': entityId,
        },
      });
      const response = await this.axios.get(`/api/setup-audit-entries${qs}`);
      const items = unwrapJsonApiList<SetupAuditTrailEntry>(response.data);
      const meta = extractMetadata(response.data);
      return toPage(items, meta);
    },
  };

  // ---------------------------------------------------------------------------
  // Governor limits
  // ---------------------------------------------------------------------------

  readonly governorLimits = {
    getStatus: async (): Promise<GovernorLimitsStatus> => {
      const response = await this.axios.get('/api/governor-limits');
      return unwrapJsonApiResource<GovernorLimitsStatus>(response.data);
    },

    /**
     * Update the tenant's tier (edition). Server validates the value against
     * the allowed CHECK constraint set: FREE / PROFESSIONAL / ENTERPRISE / UNLIMITED.
     * Returns the refreshed status (with new tier defaults already merged in).
     */
    updateTier: async (
      tier: 'FREE' | 'PROFESSIONAL' | 'ENTERPRISE' | 'UNLIMITED'
    ): Promise<GovernorLimitsStatus> => {
      const response = await this.axios.put('/api/governor-limits/tier', { tier });
      return unwrapJsonApiResource<GovernorLimitsStatus>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Role hierarchy
  // ---------------------------------------------------------------------------

  readonly roleHierarchy = {
    get: async (): Promise<RoleHierarchyNode[]> => {
      const response = await this.axios.get('/api/roles?sort=hierarchyLevel');
      return unwrapJsonApiList<RoleHierarchyNode>(response.data);
    },

    setParent: async (roleId: string, parentRoleId: string | null): Promise<RoleHierarchyNode> => {
      const body = toJsonApiBody('roles', { parentRoleId }, roleId);
      const response = await this.axios.patch(`/api/roles/${roleId}`, body);
      return unwrapJsonApiResource<RoleHierarchyNode>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Page layouts (already on /api/)
  // ---------------------------------------------------------------------------

  readonly layouts = {
    list: async (collectionId: string): Promise<PageLayout[]> => {
      const response = await this.axios.get(
        `/api/page-layouts?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      return unwrapJsonApiList<PageLayout>(response.data);
    },

    get: async (id: string): Promise<PageLayout> => {
      const response = await this.axios.get(`/api/page-layouts/${id}`);
      return unwrapJsonApiResource<PageLayout>(response.data);
    },

    create: async (_tenantId: string, request: CreatePageLayoutRequest): Promise<PageLayout> => {
      const body = toJsonApiBody('page-layouts', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/page-layouts', body);
      return unwrapJsonApiResource<PageLayout>(response.data);
    },

    update: async (id: string, request: Partial<CreatePageLayoutRequest>): Promise<PageLayout> => {
      const body = toJsonApiBody('page-layouts', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/page-layouts/${id}`, body);
      return unwrapJsonApiResource<PageLayout>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/page-layouts/${id}`);
    },

    listAssignments: async (collectionId: string): Promise<LayoutAssignment[]> => {
      const response = await this.axios.get(
        `/api/layout-assignments?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      return unwrapJsonApiList<LayoutAssignment>(response.data);
    },

    assign: async (request: LayoutAssignmentRequest): Promise<LayoutAssignment> => {
      const body = toJsonApiBody(
        'layout-assignments',
        request as unknown as Record<string, unknown>
      );
      const response = await this.axios.post('/api/layout-assignments', body);
      return unwrapJsonApiResource<LayoutAssignment>(response.data);
    },

    /**
     * Resolve layout for a collection by fetching assignments and picking the match.
     *
     * Walks assignments in ascending evaluationOrder. For each candidate that
     * matches the requested profile/recordType, evaluates the condition (if any)
     * against the provided record. Returns the first conditional match; falls
     * back to the first unconditional candidate. Within identical
     * evaluationOrder, profile+recordType > profile-only > unconditional acts
     * as the tiebreaker, preserving legacy resolution semantics.
     */
    resolve: async (
      collectionId: string,
      profileId?: string,
      recordTypeId?: string,
      record?: Record<string, unknown>
    ): Promise<PageLayout> => {
      const assignResponse = await this.axios.get(
        `/api/layout-assignments?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      const assignments = unwrapJsonApiList<LayoutAssignment>(assignResponse.data);

      const candidates = assignments
        .filter(
          (a) =>
            (!a.profileId || !profileId || a.profileId === profileId) &&
            (!a.recordTypeId || !recordTypeId || a.recordTypeId === recordTypeId)
        )
        .sort((a, b) => {
          const ao = a.evaluationOrder ?? 100;
          const bo = b.evaluationOrder ?? 100;
          if (ao !== bo) return ao - bo;
          return specificity(b, profileId, recordTypeId) - specificity(a, profileId, recordTypeId);
        });

      let fallback: LayoutAssignment | undefined;
      let match: LayoutAssignment | undefined;
      for (const c of candidates) {
        if (!c.condition) {
          if (!fallback) fallback = c;
          continue;
        }
        if (record && evaluateFilter(c.condition, record)) {
          match = c;
          break;
        }
      }

      const chosen = match ?? fallback;
      if (!chosen?.layoutId) {
        return {} as PageLayout;
      }

      const layoutResponse = await this.axios.get(`/api/page-layouts/${chosen.layoutId}`);
      return unwrapJsonApiResource<PageLayout>(layoutResponse.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Layout rules (per-layout client-side rules: compute/validate/default/transform)
  // ---------------------------------------------------------------------------

  readonly layoutRules = {
    list: async (layoutId: string): Promise<LayoutRule[]> => {
      const response = await this.axios.get(
        `/api/layout-rules?filter[layoutId][eq]=${encodeURIComponent(layoutId)}&sort=sortOrder`
      );
      return unwrapJsonApiList<LayoutRule>(response.data);
    },

    get: async (id: string): Promise<LayoutRule> => {
      const response = await this.axios.get(`/api/layout-rules/${id}`);
      return unwrapJsonApiResource<LayoutRule>(response.data);
    },

    create: async (request: CreateLayoutRuleRequest): Promise<LayoutRule> => {
      const body = toJsonApiBody('layout-rules', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/layout-rules', body);
      return unwrapJsonApiResource<LayoutRule>(response.data);
    },

    update: async (id: string, request: UpdateLayoutRuleRequest): Promise<LayoutRule> => {
      const body = toJsonApiBody('layout-rules', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/layout-rules/${id}`, body);
      return unwrapJsonApiResource<LayoutRule>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/layout-rules/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // List views (already on /api/)
  // ---------------------------------------------------------------------------

  readonly listViews = {
    list: async (
      _tenantId: string,
      collectionId: string,
      _userId?: string
    ): Promise<ListView[]> => {
      const response = await this.axios.get(
        `/api/list-views?filter[collectionId][eq]=${encodeURIComponent(collectionId)}`
      );
      return unwrapJsonApiList<ListView>(response.data);
    },

    get: async (id: string): Promise<ListView> => {
      const response = await this.axios.get(`/api/list-views/${id}`);
      return unwrapJsonApiResource<ListView>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateListViewRequest
    ): Promise<ListView> => {
      const body = toJsonApiBody('list-views', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/list-views', body);
      return unwrapJsonApiResource<ListView>(response.data);
    },

    update: async (id: string, request: Partial<CreateListViewRequest>): Promise<ListView> => {
      const body = toJsonApiBody('list-views', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/list-views/${id}`, body);
      return unwrapJsonApiResource<ListView>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/list-views/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Reports (already on /api/)
  // ---------------------------------------------------------------------------

  readonly reports = {
    list: async (_tenantId?: string, _userId?: string): Promise<Report[]> => {
      const response = await this.axios.get('/api/reports');
      return unwrapJsonApiList<Report>(response.data);
    },

    get: async (id: string): Promise<Report> => {
      const response = await this.axios.get(`/api/reports/${id}`);
      return unwrapJsonApiResource<Report>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateReportRequest
    ): Promise<Report> => {
      const body = toJsonApiBody('reports', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/reports', body);
      return unwrapJsonApiResource<Report>(response.data);
    },

    update: async (id: string, request: Partial<CreateReportRequest>): Promise<Report> => {
      const body = toJsonApiBody('reports', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/reports/${id}`, body);
      return unwrapJsonApiResource<Report>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/reports/${id}`);
    },

    listFolders: async (_tenantId?: string): Promise<ReportFolder[]> => {
      const response = await this.axios.get('/api/report-folders');
      return unwrapJsonApiList<ReportFolder>(response.data);
    },

    createFolder: async (
      _tenantId: string,
      _userId: string,
      name: string,
      accessLevel?: string
    ): Promise<ReportFolder> => {
      const attrs: Record<string, unknown> = { name };
      if (accessLevel) attrs.accessLevel = accessLevel;
      const body = toJsonApiBody('report-folders', attrs);
      const response = await this.axios.post('/api/report-folders', body);
      return unwrapJsonApiResource<ReportFolder>(response.data);
    },

    deleteFolder: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/report-folders/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Dashboards (already on /api/)
  // ---------------------------------------------------------------------------

  readonly dashboards = {
    list: async (_tenantId?: string, _userId?: string): Promise<UserDashboard[]> => {
      const response = await this.axios.get('/api/dashboards');
      return unwrapJsonApiList<UserDashboard>(response.data);
    },

    get: async (id: string): Promise<UserDashboard> => {
      const response = await this.axios.get(`/api/dashboards/${id}`);
      return unwrapJsonApiResource<UserDashboard>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateDashboardRequest
    ): Promise<UserDashboard> => {
      const body = toJsonApiBody('dashboards', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/dashboards', body);
      return unwrapJsonApiResource<UserDashboard>(response.data);
    },

    update: async (
      id: string,
      request: Partial<CreateDashboardRequest>
    ): Promise<UserDashboard> => {
      const body = toJsonApiBody('dashboards', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/dashboards/${id}`, body);
      return unwrapJsonApiResource<UserDashboard>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/dashboards/${id}`);
    },
  };

  // ---------------------------------------------------------------------------
  // Data export — graceful degradation
  // ---------------------------------------------------------------------------

  readonly dataExport = {
    exportCsv: (_request: ExportRequest): Promise<Blob> => {
      throw new Error('CSV export is temporarily unavailable');
    },

    exportXlsx: (_request: ExportRequest): Promise<Blob> => {
      throw new Error('XLSX export is temporarily unavailable');
    },
  };

  // ---------------------------------------------------------------------------
  // Email templates (already on /api/)
  // ---------------------------------------------------------------------------

  readonly emailTemplates = {
    list: async (_tenantId?: string): Promise<EmailTemplate[]> => {
      const response = await this.axios.get('/api/email-templates');
      return unwrapJsonApiList<EmailTemplate>(response.data);
    },

    get: async (id: string): Promise<EmailTemplate> => {
      const response = await this.axios.get(`/api/email-templates/${id}`);
      return unwrapJsonApiResource<EmailTemplate>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateEmailTemplateRequest
    ): Promise<EmailTemplate> => {
      const body = toJsonApiBody('email-templates', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/email-templates', body);
      return unwrapJsonApiResource<EmailTemplate>(response.data);
    },

    update: async (
      id: string,
      request: Partial<CreateEmailTemplateRequest>
    ): Promise<EmailTemplate> => {
      const body = toJsonApiBody(
        'email-templates',
        request as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/email-templates/${id}`, body);
      return unwrapJsonApiResource<EmailTemplate>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/email-templates/${id}`);
    },

    listLogs: async (_tenantId?: string, _status?: string): Promise<EmailLog[]> => {
      const response = await this.axios.get('/api/email-logs');
      return unwrapJsonApiList<EmailLog>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Email campaigns (admin API)
  // ---------------------------------------------------------------------------

  readonly campaigns = {
    list: async (limit?: number, offset?: number): Promise<Campaign[]> => {
      const params: Record<string, number> = {};
      if (limit !== undefined) params.limit = limit;
      if (offset !== undefined) params.offset = offset;
      const response = await this.axios.get('/api/admin/campaigns', { params });
      return unwrapJsonApiList<Campaign>(response.data);
    },

    get: async (id: string): Promise<Campaign> => {
      const response = await this.axios.get(`/api/admin/campaigns/${id}`);
      return unwrapJsonApiResource<Campaign>(response.data);
    },

    create: async (request: CreateCampaignRequest): Promise<Campaign> => {
      const body = toJsonApiBody('campaigns', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/admin/campaigns', body);
      return unwrapJsonApiResource<Campaign>(response.data);
    },

    update: async (id: string, request: Partial<CreateCampaignRequest>): Promise<Campaign> => {
      const body = toJsonApiBody('campaigns', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/admin/campaigns/${id}`, body);
      return unwrapJsonApiResource<Campaign>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/admin/campaigns/${id}`);
    },

    send: async (id: string): Promise<{ status: string }> => {
      const response = await this.axios.post(`/api/admin/campaigns/${id}/send`);
      return response.data as { status: string };
    },

    schedule: async (id: string, scheduledAt: string): Promise<{ status: string }> => {
      const response = await this.axios.post(`/api/admin/campaigns/${id}/schedule`, {
        scheduledAt,
      });
      return response.data as { status: string };
    },

    cancel: async (id: string): Promise<{ status: string }> => {
      const response = await this.axios.post(`/api/admin/campaigns/${id}/cancel`);
      return response.data as { status: string };
    },

    stats: async (id: string): Promise<CampaignStats> => {
      const response = await this.axios.get(`/api/admin/campaigns/${id}/stats`);
      return (response.data as { data: CampaignStats }).data;
    },

    recipients: async (
      id: string,
      limit?: number,
      offset?: number
    ): Promise<CampaignRecipient[]> => {
      const params: Record<string, number> = {};
      if (limit !== undefined) params.limit = limit;
      if (offset !== undefined) params.offset = offset;
      const response = await this.axios.get(`/api/admin/campaigns/${id}/recipients`, { params });
      return unwrapJsonApiList<CampaignRecipient>(response.data);
    },

    test: async (id: string, email: string): Promise<void> => {
      await this.axios.post(`/api/admin/campaigns/${id}/test`, { email });
    },

    listSuppressions: async (limit?: number, offset?: number): Promise<EmailSuppression[]> => {
      const params: Record<string, number> = {};
      if (limit !== undefined) params.limit = limit;
      if (offset !== undefined) params.offset = offset;
      const response = await this.axios.get('/api/admin/campaigns/suppressions', { params });
      return unwrapJsonApiList<EmailSuppression>(response.data);
    },

    addSuppression: async (email: string, reason?: string): Promise<EmailSuppression> => {
      const response = await this.axios.post('/api/admin/campaigns/suppressions', {
        email,
        ...(reason !== undefined ? { reason } : {}),
      });
      return unwrapJsonApiResource<EmailSuppression>(response.data);
    },

    removeSuppression: async (email: string): Promise<void> => {
      await this.axios.delete('/api/admin/campaigns/suppressions', {
        params: { email },
      });
    },
  };

  // ---------------------------------------------------------------------------
  // Workflow rules (already on /api/)
  // ---------------------------------------------------------------------------

  readonly workflowRules = {
    list: async (_tenantId?: string): Promise<WorkflowRule[]> => {
      const response = await this.axios.get('/api/workflow-rules');
      return unwrapJsonApiList<WorkflowRule>(response.data);
    },

    get: async (id: string): Promise<WorkflowRule> => {
      const response = await this.axios.get(`/api/workflow-rules/${id}`);
      return unwrapJsonApiResource<WorkflowRule>(response.data);
    },

    create: async (
      _tenantId: string,
      request: CreateWorkflowRuleRequest
    ): Promise<WorkflowRule> => {
      const body = toJsonApiBody('workflow-rules', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/workflow-rules', body);
      return unwrapJsonApiResource<WorkflowRule>(response.data);
    },

    update: async (
      id: string,
      request: Partial<CreateWorkflowRuleRequest>
    ): Promise<WorkflowRule> => {
      const body = toJsonApiBody(
        'workflow-rules',
        request as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/workflow-rules/${id}`, body);
      return unwrapJsonApiResource<WorkflowRule>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/workflow-rules/${id}`);
    },

    listLogs: async (_tenantId?: string): Promise<WorkflowExecutionLog[]> => {
      const response = await this.axios.get('/api/workflow-execution-logs');
      return unwrapJsonApiList<WorkflowExecutionLog>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Approval processes (already on /api/)
  // ---------------------------------------------------------------------------

  readonly approvals = {
    listProcesses: async (_tenantId?: string): Promise<ApprovalProcess[]> => {
      const response = await this.axios.get('/api/approval-processes');
      return unwrapJsonApiList<ApprovalProcess>(response.data);
    },

    getProcess: async (id: string): Promise<ApprovalProcess> => {
      const response = await this.axios.get(`/api/approval-processes/${id}`);
      return unwrapJsonApiResource<ApprovalProcess>(response.data);
    },

    createProcess: async (
      _tenantId: string,
      request: CreateApprovalProcessRequest
    ): Promise<ApprovalProcess> => {
      const body = toJsonApiBody(
        'approval-processes',
        request as unknown as Record<string, unknown>
      );
      const response = await this.axios.post('/api/approval-processes', body);
      return unwrapJsonApiResource<ApprovalProcess>(response.data);
    },

    updateProcess: async (
      id: string,
      request: Partial<CreateApprovalProcessRequest>
    ): Promise<ApprovalProcess> => {
      const body = toJsonApiBody(
        'approval-processes',
        request as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/approval-processes/${id}`, body);
      return unwrapJsonApiResource<ApprovalProcess>(response.data);
    },

    deleteProcess: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/approval-processes/${id}`);
    },

    listInstances: async (_tenantId: string): Promise<ApprovalInstance[]> => {
      const response = await this.axios.get('/api/approval-instances');
      return unwrapJsonApiList<ApprovalInstance>(response.data);
    },

    getPendingForUser: async (userId: string): Promise<ApprovalInstance[]> => {
      const response = await this.axios.get(
        `/api/approval-instances?filter[status][eq]=PENDING&filter[assignedTo][eq]=${encodeURIComponent(userId)}`
      );
      return unwrapJsonApiList<ApprovalInstance>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Flows (already on /api/)
  // ---------------------------------------------------------------------------

  readonly flows = {
    list: async (_tenantId?: string): Promise<FlowDefinition[]> => {
      const response = await this.axios.get('/api/flows');
      return unwrapJsonApiList<FlowDefinition>(response.data);
    },

    get: async (id: string): Promise<FlowDefinition> => {
      const response = await this.axios.get(`/api/flows/${id}`);
      return unwrapJsonApiResource<FlowDefinition>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateFlowRequest
    ): Promise<FlowDefinition> => {
      const body = toJsonApiBody('flows', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/flows', body);
      return unwrapJsonApiResource<FlowDefinition>(response.data);
    },

    update: async (id: string, request: Partial<CreateFlowRequest>): Promise<FlowDefinition> => {
      const body = toJsonApiBody('flows', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/flows/${id}`, body);
      return unwrapJsonApiResource<FlowDefinition>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/flows/${id}`);
    },

    listExecutions: async (_tenantId?: string): Promise<FlowExecution[]> => {
      const response = await this.axios.get('/api/flow-executions');
      return unwrapJsonApiList<FlowExecution>(response.data);
    },

    getExecution: async (executionId: string): Promise<FlowExecution> => {
      const response = await this.axios.get(`/api/flow-executions/${executionId}`);
      return unwrapJsonApiResource<FlowExecution>(response.data);
    },

    getWebhookUrl: async (flowId: string): Promise<WebhookUrlResponse> => {
      const response = await this.axios.get(`/api/flows/${flowId}/webhook-url`);
      return unwrapJsonApiResource<WebhookUrlResponse>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Scheduled jobs (already on /api/)
  // ---------------------------------------------------------------------------

  readonly scheduledJobs = {
    list: async (_tenantId?: string): Promise<ScheduledJob[]> => {
      const response = await this.axios.get('/api/scheduled-jobs');
      return unwrapJsonApiList<ScheduledJob>(response.data);
    },

    get: async (id: string): Promise<ScheduledJob> => {
      const response = await this.axios.get(`/api/scheduled-jobs/${id}`);
      return unwrapJsonApiResource<ScheduledJob>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateScheduledJobRequest
    ): Promise<ScheduledJob> => {
      const body = toJsonApiBody('scheduled-jobs', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/scheduled-jobs', body);
      return unwrapJsonApiResource<ScheduledJob>(response.data);
    },

    update: async (
      id: string,
      request: Partial<CreateScheduledJobRequest>
    ): Promise<ScheduledJob> => {
      const body = toJsonApiBody(
        'scheduled-jobs',
        request as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/scheduled-jobs/${id}`, body);
      return unwrapJsonApiResource<ScheduledJob>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/scheduled-jobs/${id}`);
    },

    listLogs: async (id: string): Promise<JobExecutionLog[]> => {
      const response = await this.axios.get(`/api/scheduled-jobs/${id}/job-execution-logs`);
      return unwrapJsonApiList<JobExecutionLog>(response.data);
    },

    pause: async (id: string): Promise<void> => {
      await this.axios.post(`/api/scheduled-jobs/${id}/pause`);
    },

    resume: async (id: string): Promise<void> => {
      await this.axios.post(`/api/scheduled-jobs/${id}/resume`);
    },

    execute: async (id: string): Promise<void> => {
      await this.axios.post(`/api/scheduled-jobs/${id}/execute`);
    },
  };

  // ---------------------------------------------------------------------------
  // Scripts (already on /api/)
  // ---------------------------------------------------------------------------

  readonly scripts = {
    list: async (_tenantId?: string): Promise<Script[]> => {
      const response = await this.axios.get('/api/scripts');
      return unwrapJsonApiList<Script>(response.data);
    },

    get: async (id: string): Promise<Script> => {
      const response = await this.axios.get(`/api/scripts/${id}`);
      return unwrapJsonApiResource<Script>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateScriptRequest
    ): Promise<Script> => {
      const body = toJsonApiBody('scripts', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/scripts', body);
      return unwrapJsonApiResource<Script>(response.data);
    },

    update: async (id: string, request: Partial<CreateScriptRequest>): Promise<Script> => {
      const body = toJsonApiBody('scripts', request as unknown as Record<string, unknown>, id);
      const response = await this.axios.patch(`/api/scripts/${id}`, body);
      return unwrapJsonApiResource<Script>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/scripts/${id}`);
    },

    listLogs: async (_tenantId?: string): Promise<ScriptExecutionLog[]> => {
      const response = await this.axios.get('/api/script-execution-logs');
      return unwrapJsonApiList<ScriptExecutionLog>(response.data);
    },

    listLogsByScript: async (id: string): Promise<ScriptExecutionLog[]> => {
      const response = await this.axios.get(`/api/scripts/${id}/script-execution-logs`);
      return unwrapJsonApiList<ScriptExecutionLog>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Svix Webhook Portal
  // ---------------------------------------------------------------------------

  readonly svix = {
    getPortalAccess: async (): Promise<SvixPortalResponse> => {
      const response = await this.axios.get<SvixPortalResponse>('/api/svix/portal');
      return response.data;
    },
  };

  // ---------------------------------------------------------------------------
  // Superset Analytics
  // ---------------------------------------------------------------------------

  readonly superset = {
    getGuestToken: async (dashboardId: string): Promise<SupersetGuestTokenResponse> => {
      const response = await this.axios.post<SupersetGuestTokenResponse>(
        '/api/superset/guest-token',
        { dashboardId }
      );
      return response.data;
    },

    listDashboards: async (): Promise<SupersetDashboard[]> => {
      const response = await this.axios.get<SupersetDashboard[]>('/api/superset/dashboards');
      return response.data;
    },

    listDatasets: async (): Promise<SupersetDataset[]> => {
      const response = await this.axios.get<SupersetDataset[]>('/api/superset/datasets');
      return response.data;
    },

    syncDatasets: async (): Promise<void> => {
      await this.axios.post('/api/superset/datasets/sync');
    },
  };

  // ---------------------------------------------------------------------------
  // Connected apps (already on /api/)
  // ---------------------------------------------------------------------------

  readonly connectedApps = {
    list: async (_tenantId?: string): Promise<ConnectedApp[]> => {
      const response = await this.axios.get('/api/connected-apps');
      return unwrapJsonApiList<ConnectedApp>(response.data);
    },

    get: async (id: string): Promise<ConnectedApp> => {
      const response = await this.axios.get(`/api/connected-apps/${id}`);
      return unwrapJsonApiResource<ConnectedApp>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateConnectedAppRequest
    ): Promise<ConnectedAppCreatedResponse> => {
      const body = toJsonApiBody('connected-apps', request as unknown as Record<string, unknown>);
      const response = await this.axios.post('/api/connected-apps', body);
      return unwrapJsonApiResource<ConnectedAppCreatedResponse>(response.data);
    },

    update: async (
      id: string,
      request: Partial<CreateConnectedAppRequest>
    ): Promise<ConnectedApp> => {
      const body = toJsonApiBody(
        'connected-apps',
        request as unknown as Record<string, unknown>,
        id
      );
      const response = await this.axios.patch(`/api/connected-apps/${id}`, body);
      return unwrapJsonApiResource<ConnectedApp>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/connected-apps/${id}`);
    },

    rotateSecret: (_id: string): Promise<ConnectedAppCreatedResponse> => {
      throw new Error('Secret rotation is temporarily unavailable');
    },

    listTokens: async (id: string): Promise<ConnectedAppToken[]> => {
      const response = await this.axios.get(`/api/connected-apps/${id}/connected-app-tokens`);
      return unwrapJsonApiList<ConnectedAppToken>(response.data);
    },

    generateToken: async (id: string): Promise<Record<string, string>> => {
      const response = await this.axios.post(`/api/connected-apps/${id}/tokens`);
      return response.data as Record<string, string>;
    },

    revokeToken: async (appId: string, tokenId: string): Promise<void> => {
      await this.axios.delete(`/api/connected-apps/${appId}/tokens/${tokenId}`);
    },

    listAudit: async (id: string): Promise<Record<string, unknown>[]> => {
      const response = await this.axios.get(`/api/connected-apps/${id}/audit`);
      return (response.data as { data: Record<string, unknown>[] }).data ?? [];
    },
  };

  // ---------------------------------------------------------------------------
  // Credentials (tenant-managed, encrypted at rest)
  // ---------------------------------------------------------------------------

  readonly credentials = {
    list: async (): Promise<Record<string, unknown>[]> => {
      const response = await this.axios.get('/api/credentials');
      return unwrapJsonApiList<Record<string, unknown>>(response.data);
    },

    get: async (id: string): Promise<Record<string, unknown>> => {
      const response = await this.axios.get(`/api/credentials/${id}`);
      return unwrapJsonApiResource<Record<string, unknown>>(response.data);
    },

    create: async (record: Record<string, unknown>): Promise<Record<string, unknown>> => {
      const body = toJsonApiBody('credentials', record);
      const response = await this.axios.post('/api/credentials', body);
      return unwrapJsonApiResource<Record<string, unknown>>(response.data);
    },

    update: async (
      id: string,
      record: Record<string, unknown>
    ): Promise<Record<string, unknown>> => {
      const body = toJsonApiBody('credentials', record, id);
      const response = await this.axios.patch(`/api/credentials/${id}`, body);
      return unwrapJsonApiResource<Record<string, unknown>>(response.data);
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/credentials/${id}`);
    },

    types: async (): Promise<CredentialTypeDescriptor[]> => {
      const response = await this.axios.get('/api/credentials/types');
      return (response.data as { data: CredentialTypeDescriptor[] }).data ?? [];
    },

    templates: async (): Promise<CredentialTemplateDescriptor[]> => {
      const response = await this.axios.get('/api/credentials/templates');
      return (response.data as { data: CredentialTemplateDescriptor[] }).data ?? [];
    },

    test: async (id: string): Promise<CredentialTestResultPayload> => {
      const response = await this.axios.post(`/api/credentials/${id}/test`);
      return response.data as CredentialTestResultPayload;
    },

    testInline: async (
      type: string,
      data: Record<string, unknown>
    ): Promise<CredentialTestResultPayload> => {
      const response = await this.axios.post('/api/credentials/test', { type, data });
      return response.data as CredentialTestResultPayload;
    },

    beginOAuth: async (
      id: string
    ): Promise<{ authUrl: string; state: string; expiresInSeconds: number }> => {
      const response = await this.axios.post(`/api/credentials/${id}/oauth/authorize-url`);
      return response.data as { authUrl: string; state: string; expiresInSeconds: number };
    },

    completeOAuth: async (
      state: string,
      code: string,
      redirectUri?: string
    ): Promise<{ ok: boolean; expiresAt?: string; scope?: string }> => {
      const response = await this.axios.post('/api/credentials/oauth/complete', {
        state,
        code,
        redirectUri,
      });
      return response.data as { ok: boolean; expiresAt?: string; scope?: string };
    },
  };

  // ---------------------------------------------------------------------------
  // OpenAPI Spec Library (PR 3)
  // ---------------------------------------------------------------------------

  readonly apiSpecs = {
    list: async (): Promise<ApiSpecSummary[]> => {
      const response = await this.axios.get('/api/api-specs/library');
      return (response.data as { data: ApiSpecSummary[] }).data ?? [];
    },

    get: async (id: string): Promise<ApiSpecSummary> => {
      const response = await this.axios.get(`/api/api-specs/${id}/details`);
      return response.data as ApiSpecSummary;
    },

    raw: async (id: string): Promise<string> => {
      const response = await this.axios.get(`/api/api-specs/${id}/raw`, {
        responseType: 'text',
        transformResponse: (v: unknown) => (typeof v === 'string' ? v : JSON.stringify(v)),
      });
      return String(response.data ?? '');
    },

    importSpec: async (request: ImportApiSpecRequest): Promise<ImportApiSpecResponse> => {
      const response = await this.axios.post('/api/api-specs/import', request);
      return response.data as ImportApiSpecResponse;
    },

    validate: async (request: {
      raw?: string;
      sourceUrl?: string;
    }): Promise<ApiSpecValidateResult> => {
      const response = await this.axios.post('/api/api-specs/validate', request);
      return response.data as ApiSpecValidateResult;
    },

    delete: async (id: string): Promise<void> => {
      await this.axios.delete(`/api/api-specs/${id}`);
    },

    listOperations: async (specId: string): Promise<ApiOperationSummary[]> => {
      const response = await this.axios.get(`/api/api-specs/${specId}/operations`);
      return (response.data as { data: ApiOperationSummary[] }).data ?? [];
    },

    getOperation: async (specId: string, opId: string): Promise<ApiOperationDetail> => {
      const response = await this.axios.get(`/api/api-specs/${specId}/operations/${opId}`);
      return response.data as ApiOperationDetail;
    },

    searchOperations: async (params: {
      q?: string;
      method?: string;
      specId?: string;
      limit?: number;
    }): Promise<ApiOperationSummary[]> => {
      const response = await this.axios.get('/api/api-operations/search', { params });
      return (response.data as { data: ApiOperationSummary[] }).data ?? [];
    },
  };

  // ---------------------------------------------------------------------------
  // Password Policy (admin API)
  // ---------------------------------------------------------------------------

  readonly passwordPolicy = {
    get: async (): Promise<Record<string, unknown>> => {
      const response = await this.axios.get('/api/admin/password-policy');
      return (response.data as { data: Record<string, unknown> }).data;
    },

    update: async (policy: Record<string, unknown>): Promise<void> => {
      await this.axios.put('/api/admin/password-policy', policy);
    },

    unlockUser: async (userId: string): Promise<void> => {
      await this.axios.post(`/api/admin/password-policy/users/${userId}/unlock`);
    },
  };

  // ---------------------------------------------------------------------------
  // Tenant email settings (admin API)
  // ---------------------------------------------------------------------------

  readonly emailSettings = {
    get: async (): Promise<EmailSettings> => {
      const response = await this.axios.get('/api/admin/tenant/email-settings');
      return (response.data as { data: EmailSettings }).data;
    },

    update: async (update: EmailSettingsUpdate): Promise<void> => {
      await this.axios.put('/api/admin/tenant/email-settings', update);
    },

    testSend: async (to: string): Promise<void> => {
      await this.axios.post('/api/admin/tenant/email-settings/test', { to });
    },
  };

  readonly emailTemplateOverrides = {
    list: async (): Promise<EmailTemplateEntry[]> => {
      const response = await this.axios.get('/api/admin/email-templates');
      return (response.data as { data: EmailTemplateEntry[] }).data;
    },

    override: async (templateKey: string): Promise<void> => {
      await this.axios.post(`/api/admin/email-templates/${templateKey}/override`);
    },

    revert: async (templateKey: string): Promise<void> => {
      await this.axios.delete(`/api/admin/email-templates/${templateKey}/override`);
    },

    inviteUser: async (userId: string): Promise<void> => {
      await this.axios.post(`/api/admin/users/${userId}/invite`);
    },
  };

  // ---------------------------------------------------------------------------
  // Bulk jobs (already on /api/)
  // ---------------------------------------------------------------------------

  readonly bulkJobs = {
    list: async (_tenantId?: string): Promise<BulkJob[]> => {
      const response = await this.axios.get('/api/bulk-jobs');
      return unwrapJsonApiList<BulkJob>(response.data);
    },

    get: async (id: string): Promise<BulkJob> => {
      const response = await this.axios.get(`/api/bulk-jobs/${id}`);
      return unwrapJsonApiResource<BulkJob>(response.data);
    },

    create: async (
      _tenantId: string,
      _userId: string,
      request: CreateBulkJobRequest
    ): Promise<BulkJob> => {
      // The worker's BulkOperationsController reads plain top-level keys
      // (collectionId, operation, records, ...), not a JSON:API envelope.
      const response = await this.axios.post('/api/bulk-jobs', request);
      return unwrapJsonApiResource<BulkJob>(response.data);
    },

    abort: async (id: string): Promise<BulkJob> => {
      const body = toJsonApiBody('bulk-jobs', { status: 'ABORTED' }, id);
      const response = await this.axios.patch(`/api/bulk-jobs/${id}`, body);
      return unwrapJsonApiResource<BulkJob>(response.data);
    },

    getResults: async (id: string): Promise<BulkJobResult[]> => {
      const response = await this.axios.get(`/api/bulk-jobs/${id}/bulk-job-results`);
      return unwrapJsonApiList<BulkJobResult>(response.data);
    },

    getErrors: async (id: string): Promise<BulkJobResult[]> => {
      const response = await this.axios.get(
        `/api/bulk-jobs/${id}/bulk-job-results?filter[status][eq]=ERROR`
      );
      return unwrapJsonApiList<BulkJobResult>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Composite API — graceful degradation
  // ---------------------------------------------------------------------------

  readonly composite = {
    execute: (_tenantId: string, _request: CompositeRequest): Promise<CompositeResponse> => {
      throw new Error('Composite API is temporarily unavailable');
    },
  };

  // ---------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------

  readonly metrics = {
    query: async (params: MetricsQueryParams): Promise<MetricsQueryResult> => {
      const qs = new URLSearchParams();
      qs.set('metric', params.metric);
      qs.set('start', params.start);
      qs.set('end', params.end);
      if (params.step) qs.set('step', params.step);
      if (params.route) qs.set('route', params.route);
      const response = await this.axios.get(`/api/metrics/query?${qs.toString()}`);
      return unwrapJsonApiResource<MetricsQueryResult>(response.data);
    },

    summary: async (): Promise<MetricsSummary> => {
      const response = await this.axios.get('/api/metrics/summary');
      return unwrapJsonApiResource<MetricsSummary>(response.data);
    },

    endpoints: async (limit = 20): Promise<{ endpoints: EndpointPerformance[] }> => {
      const response = await this.axios.get(`/api/metrics/endpoints?limit=${limit}`);
      return unwrapJsonApiResource<{ endpoints: EndpointPerformance[] }>(response.data);
    },

    errors: async (limit = 20): Promise<{ errors: ErrorGroup[] }> => {
      const response = await this.axios.get(`/api/metrics/errors?limit=${limit}`);
      return unwrapJsonApiResource<{ errors: ErrorGroup[] }>(response.data);
    },

    latency: async (start?: string, end?: string): Promise<LatencyPercentiles> => {
      const qs = new URLSearchParams();
      if (start) qs.set('start', start);
      if (end) qs.set('end', end);
      const response = await this.axios.get(`/api/metrics/latency?${qs.toString()}`);
      return unwrapJsonApiResource<LatencyPercentiles>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Observability
  // ---------------------------------------------------------------------------

  readonly observability = {
    searchRequestLogs: async (params: RequestLogSearchParams): Promise<RequestLogSearchResult> => {
      const qs = new URLSearchParams();
      if (params.method) qs.set('method', params.method);
      if (params.status) qs.set('status', params.status);
      if (params.path) qs.set('path', params.path);
      if (params.traceId) qs.set('traceId', params.traceId);
      if (params.userId) qs.set('userId', params.userId);
      if (params.start) qs.set('start', params.start);
      if (params.end) qs.set('end', params.end);
      if (params.page !== undefined) qs.set('page', String(params.page));
      if (params.size !== undefined) qs.set('size', String(params.size));
      const response = await this.axios.get(
        `/api/admin/observability/request-logs?${qs.toString()}`
      );
      return unwrapJsonApiResource<RequestLogSearchResult>(response.data);
    },

    getRequestLog: async (traceId: string): Promise<RequestLogDetail> => {
      const response = await this.axios.get(`/api/admin/observability/request-logs/${traceId}`);
      return unwrapJsonApiResource<RequestLogDetail>(response.data);
    },

    searchLogs: async (params: LogSearchParams): Promise<LogSearchResult> => {
      const qs = new URLSearchParams();
      if (params.query) qs.set('query', params.query);
      if (params.level) qs.set('level', params.level);
      if (params.service) qs.set('service', params.service);
      if (params.traceId) qs.set('traceId', params.traceId);
      if (params.start) qs.set('start', params.start);
      if (params.end) qs.set('end', params.end);
      if (params.page !== undefined) qs.set('page', String(params.page));
      if (params.size !== undefined) qs.set('size', String(params.size));
      const response = await this.axios.get(`/api/admin/observability/logs?${qs.toString()}`);
      return unwrapJsonApiResource<LogSearchResult>(response.data);
    },

    searchAudit: async (params: AuditSearchParams): Promise<AuditSearchResult> => {
      const qs = new URLSearchParams();
      if (params.auditType) qs.set('auditType', params.auditType);
      if (params.action) qs.set('action', params.action);
      if (params.userId) qs.set('userId', params.userId);
      if (params.start) qs.set('start', params.start);
      if (params.end) qs.set('end', params.end);
      if (params.page !== undefined) qs.set('page', String(params.page));
      if (params.size !== undefined) qs.set('size', String(params.size));
      const response = await this.axios.get(`/api/admin/observability/audit?${qs.toString()}`);
      return unwrapJsonApiResource<AuditSearchResult>(response.data);
    },

    getSettings: async (): Promise<ObservabilitySettingsResponse> => {
      const response = await this.axios.get('/api/admin/observability-settings');
      return unwrapJsonApiResource<ObservabilitySettingsResponse>(response.data);
    },

    updateSettings: async (
      request: UpdateObservabilitySettingsRequest
    ): Promise<ObservabilitySettingsResponse> => {
      const response = await this.axios.put('/api/admin/observability-settings', request);
      return unwrapJsonApiResource<ObservabilitySettingsResponse>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // Search Reindex
  // ---------------------------------------------------------------------------

  readonly searchReindex = {
    getStats: async (): Promise<SearchIndexStats> => {
      const response = await this.axios.get('/api/admin/search-reindex');
      return unwrapJsonApiResource<SearchIndexStats>(response.data);
    },

    reindex: async (collectionName?: string): Promise<SearchReindexResult> => {
      const body = collectionName ? { collectionName } : {};
      const response = await this.axios.post('/api/admin/search-reindex', body);
      return unwrapJsonApiResource<SearchReindexResult>(response.data);
    },
  };

  // ---------------------------------------------------------------------------
  // MFA Management
  // ---------------------------------------------------------------------------

  readonly mfa = {
    getUserStatus: async (
      userId: string
    ): Promise<{
      userId: string;
      mfaEnabled: boolean;
      enrolled: boolean;
      enrolledAt: string | null;
      remainingRecoveryCodes: number;
    }> => {
      const response = await this.axios.get(`/api/admin/mfa/users/${userId}/status`);
      return response.data as {
        userId: string;
        mfaEnabled: boolean;
        enrolled: boolean;
        enrolledAt: string | null;
        remainingRecoveryCodes: number;
      };
    },

    resetUser: async (userId: string): Promise<{ status: string; userId: string }> => {
      const response = await this.axios.post(`/api/admin/mfa/users/${userId}/reset`);
      return response.data as { status: string; userId: string };
    },

    getPolicy: async (): Promise<{ mfaRequired: boolean }> => {
      const response = await this.axios.get('/api/admin/mfa/policy');
      return response.data as { mfaRequired: boolean };
    },

    updatePolicy: async (policy: { mfaRequired: boolean }): Promise<{ status: string }> => {
      const response = await this.axios.put('/api/admin/mfa/policy', policy);
      return response.data as { status: string };
    },
  };

  // ---------------------------------------------------------------------------
  // Personal Access Tokens
  // ---------------------------------------------------------------------------

  readonly personalTokens = {
    list: async (): Promise<PersonalAccessToken[]> => {
      const response = await this.axios.get('/api/me/tokens');
      return (response.data as { data: PersonalAccessToken[] }).data;
    },

    create: async (
      request: CreatePersonalAccessTokenRequest
    ): Promise<PersonalAccessTokenCreated> => {
      const response = await this.axios.post('/api/me/tokens', request);
      return response.data as PersonalAccessTokenCreated;
    },

    revoke: async (tokenId: string): Promise<void> => {
      await this.axios.delete(`/api/me/tokens/${tokenId}`);
    },
  };

  // ---------------------------------------------------------------------------
  // AI Chat
  // ---------------------------------------------------------------------------

  readonly ai = {
    chat: async (request: AiChatRequest): Promise<AiChatResponse> => {
      const response = await this.axios.post('/api/ai/chat', request);
      return response.data as AiChatResponse;
    },

    chatStream: (request: AiChatRequest): ReadableStream => {
      const url = `${this.axios.defaults.baseURL ?? ''}/api/ai/chat/stream`;
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      };

      // Forward auth headers from axios defaults
      const authHeader = this.axios.defaults.headers?.common?.Authorization;
      if (authHeader) {
        headers['Authorization'] = String(authHeader);
      }

      // Forward tenant headers
      const tenantId = this.axios.defaults.headers?.common?.['X-Tenant-ID'];
      if (tenantId) headers['X-Tenant-ID'] = String(tenantId);
      const userId = this.axios.defaults.headers?.common?.['X-User-Id'];
      if (userId) headers['X-User-Id'] = String(userId);

      const fetchPromise = fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(request),
      });

      return new ReadableStream({
        async start(controller) {
          try {
            const response = await fetchPromise;
            if (!response.ok || !response.body) {
              controller.error(new Error(`AI chat stream failed: ${response.status}`));
              return;
            }
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            let done = false;
            while (!done) {
              const result = await reader.read();
              done = result.done;
              const value = result.value;
              if (done) break;

              buffer += decoder.decode(value, { stream: true });
              const lines = buffer.split('\n');
              buffer = lines.pop() ?? '';

              for (const line of lines) {
                if (line.trim()) {
                  controller.enqueue(line);
                }
              }
            }
            controller.close();
          } catch (err) {
            controller.error(err);
          }
        },
      });
    },

    conversations: async (): Promise<AiConversationSummary[]> => {
      const response = await this.axios.get('/api/ai/conversations');
      return (response.data as { data: AiConversationSummary[] }).data;
    },

    conversation: async (id: string): Promise<AiConversationDetail> => {
      const response = await this.axios.get(`/api/ai/conversations/${id}`);
      return (response.data as { data: AiConversationDetail }).data;
    },

    applyProposal: async (proposalId: string): Promise<AiApplyResult> => {
      const response = await this.axios.post(`/api/ai/proposals/${proposalId}/apply`);
      return response.data as AiApplyResult;
    },

    usage: async (): Promise<AiTokenUsage> => {
      const response = await this.axios.get('/api/ai/usage');
      return (response.data as { data: AiTokenUsage }).data;
    },

    config: {
      get: async (): Promise<AiConfig> => {
        const response = await this.axios.get('/api/ai/config');
        return (response.data as { data: AiConfig }).data;
      },

      update: async (config: Partial<AiConfig>): Promise<AiConfig> => {
        const response = await this.axios.put('/api/ai/config', config);
        return (response.data as { data: AiConfig }).data;
      },
    },
  };
}
