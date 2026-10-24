/**
 * OIDCProvidersPage Component
 *
 * Displays a list of all OIDC providers with status indicators and provides
 * add, edit, delete, and test connection functionality. Built on the shared
 * AdminCrud building block.
 *
 * Requirements:
 * - 6.1: Display a list of all OIDC providers with status (active/inactive)
 * - 6.2: Add new OIDC provider action
 * - 6.7: Delete OIDC provider with confirmation dialog
 * - 6.8: Test connection functionality to verify provider configuration
 * - 6.9: Display provider status (connected/disconnected)
 */

import React, { useState, useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation } from '@tanstack/react-query'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog } from '../../components'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * OIDC Provider interface matching the API response
 */
export interface OIDCProvider {
  id: string
  name: string
  issuer: string
  clientId: string
  scopes: string[]
  active: boolean
  createdAt: string
  updatedAt: string
  // OIDC Discovery endpoint overrides
  jwksUri?: string
  authorizationUri?: string
  tokenUri?: string
  userinfoUri?: string
  endSessionUri?: string
  discoveryStatus?: string
  // Claim mapping fields
  rolesClaim?: string
  rolesMapping?: string
  emailClaim?: string
  usernameClaim?: string
  nameClaim?: string
  groupsClaim?: string
  groupsProfileMapping?: string
}

/**
 * Test connection result
 */
interface TestConnectionResult {
  success: boolean
  message: string
}

/**
 * Props for OIDCProvidersPage component
 */
export interface OIDCProvidersPageProps {
  /** Optional test ID for testing */
  testId?: string
}

const isBlank = (v: string): boolean => v.trim().length === 0

function parses(parse: (v: string) => unknown) {
  return (v: string): boolean => {
    if (isBlank(v)) return true
    try {
      parse(v)
      return true
    } catch {
      return false
    }
  }
}

/**
 * OIDC provider form validation. Every form field is in the schema so none is
 * stripped from the submitted values, and refinements never transform them. The
 * client secret is required only when creating: on edit a blank secret is sent
 * unchanged and means "keep the existing secret".
 */
function buildProviderSchema(t: (key: string) => string, isEditing: boolean) {
  const claimPath = z.string().max(200, t('oidc.validation.claimPathTooLong'))
  return z.object({
    name: z
      .string()
      .refine((v) => !isBlank(v), t('oidc.validation.nameRequired'))
      .refine((v) => v.length <= 100, t('oidc.validation.nameTooLong')),
    issuer: z
      .string()
      .refine((v) => !isBlank(v), t('oidc.validation.issuerRequired'))
      .refine(
        parses((v) => new URL(v)),
        t('oidc.validation.issuerInvalid')
      ),
    clientId: z
      .string()
      .refine((v) => !isBlank(v), t('oidc.validation.clientIdRequired'))
      .refine((v) => v.length <= 255, t('oidc.validation.clientIdTooLong')),
    clientSecret: z
      .string()
      .refine((v) => isEditing || !isBlank(v), t('oidc.validation.clientSecretRequired')),
    scopes: z.string().refine((v) => !isBlank(v), t('oidc.validation.scopesRequired')),
    // OIDC Discovery endpoint overrides
    jwksUri: z.string(),
    authorizationUri: z.string(),
    tokenUri: z.string(),
    userinfoUri: z.string(),
    endSessionUri: z.string(),
    // Claim mapping fields
    rolesClaim: claimPath,
    rolesMapping: z
      .string()
      .refine(parses(JSON.parse), t('oidc.validation.rolesMappingInvalidJson')),
    emailClaim: claimPath,
    usernameClaim: claimPath,
    nameClaim: claimPath,
    groupsClaim: z.string(),
    groupsProfileMapping: z.string(),
  })
}

/**
 * Form data for creating/editing an OIDC provider
 */
type OIDCProviderFormData = z.infer<ReturnType<typeof buildProviderSchema>>

function providerFormDefaults(provider: OIDCProvider | undefined): OIDCProviderFormData {
  return {
    name: provider?.name ?? '',
    issuer: provider?.issuer ?? '',
    clientId: provider?.clientId ?? '',
    clientSecret: '',
    scopes: provider?.scopes?.join(', ') ?? 'openid, profile, email, roles',
    // OIDC Discovery endpoint overrides
    jwksUri: provider?.jwksUri ?? '',
    authorizationUri: provider?.authorizationUri ?? '',
    tokenUri: provider?.tokenUri ?? '',
    userinfoUri: provider?.userinfoUri ?? '',
    endSessionUri: provider?.endSessionUri ?? '',
    // Claim mapping fields
    rolesClaim: provider?.rolesClaim ?? '',
    rolesMapping: provider?.rolesMapping ?? '',
    emailClaim: provider?.emailClaim ?? '',
    usernameClaim: provider?.usernameClaim ?? '',
    nameClaim: provider?.nameClaim ?? '',
    groupsClaim: provider?.groupsClaim ?? '',
    groupsProfileMapping: provider?.groupsProfileMapping ?? '',
  }
}

/** Create/update request body: every form field, with scopes split into a list. */
function toProviderPayload(data: OIDCProviderFormData): OIDCProvider {
  const payload: Record<string, unknown> = {
    ...data,
    scopes: data.scopes
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),
  }
  if (data.rolesClaim.trim()) payload.rolesClaim = data.rolesClaim.trim()
  if (data.rolesMapping.trim()) payload.rolesMapping = data.rolesMapping.trim()
  if (data.emailClaim.trim()) payload.emailClaim = data.emailClaim.trim()
  if (data.usernameClaim.trim()) payload.usernameClaim = data.usernameClaim.trim()
  if (data.nameClaim.trim()) payload.nameClaim = data.nameClaim.trim()
  return payload as unknown as OIDCProvider
}

/** OIDC Discovery endpoint override fields, all optional URLs. */
const ENDPOINT_OVERRIDES = [
  { name: 'jwksUri', id: 'oidc-jwks-uri', label: 'JWKS URI' },
  { name: 'authorizationUri', id: 'oidc-authorization-uri', label: 'Authorization URI' },
  { name: 'tokenUri', id: 'oidc-token-uri', label: 'Token URI' },
  { name: 'userinfoUri', id: 'oidc-userinfo-uri', label: 'UserInfo URI' },
  { name: 'endSessionUri', id: 'oidc-end-session-uri', label: 'End Session URI' },
] as const

/**
 * Status Badge Component
 */
interface StatusBadgeProps {
  active: boolean
}

function StatusBadge({ active }: StatusBadgeProps): React.ReactElement {
  const { t } = useI18n()
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium',
        active
          ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-300'
          : 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300'
      )}
      data-testid="status-badge"
    >
      {active ? t('collections.active') : t('collections.inactive')}
    </span>
  )
}

/**
 * OIDCProvidersPage Component
 *
 * Main page for managing OIDC providers in the Kelta Admin UI.
 * Provides listing and CRUD operations for OIDC providers.
 */
export function OIDCProvidersPage({
  testId = 'oidc-providers-page',
}: OIDCProvidersPageProps): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { keltaClient } = useApi()

  // Test connection state
  const [testingProviderId, setTestingProviderId] = useState<string | null>(null)

  // Fetch OIDC providers query
  const {
    data: providers = [],
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['oidc-providers'],
    queryFn: () => keltaClient.admin.oidc.list(),
  })
  const providerList = providers as OIDCProvider[]

  const item = t('navigation.oidcProviders')
  const crud = useAdminCrud<OIDCProvider, OIDCProviderFormData>({
    queryKey: ['oidc-providers'],
    create: (data) => keltaClient.admin.oidc.create(toProviderPayload(data)),
    update: (provider, data) => keltaClient.admin.oidc.update(provider.id, toProviderPayload(data)),
    remove: (provider) => keltaClient.admin.oidc.delete(provider.id),
    messages: {
      created: t('success.created', { item }),
      updated: t('success.updated', { item }),
      deleted: t('success.deleted', { item }),
      error: t('errors.generic'),
    },
  })

  const editing = crud.editing
  const isEditing = editing !== undefined
  const schema = useMemo(() => buildProviderSchema(t, isEditing), [t, isEditing])

  // Test connection mutation
  // Note: The test connection action endpoint is temporarily unavailable
  // after the control-plane to worker API migration. Graceful degradation.
  const testConnectionMutation = useMutation({
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    mutationFn: async (_id: string): Promise<TestConnectionResult> => {
      return { success: false, message: 'Test connection is temporarily unavailable' }
    },
    onSuccess: (result) => {
      if (result.success) {
        toast.success(t('oidc.connectionSuccess'))
      } else {
        toast.error(`${t('oidc.connectionFailed')}: ${result.message}`)
      }
      setTestingProviderId(null)
    },
    onError: (error: Error) => {
      toast.error(`${t('oidc.connectionFailed')}: ${error.message}`)
      setTestingProviderId(null)
    },
  })

  // Handle test connection
  const handleTestConnection = useCallback(
    (provider: OIDCProvider) => {
      setTestingProviderId(provider.id)
      testConnectionMutation.mutate(provider.id)
    },
    [testConnectionMutation]
  )

  const columns = useMemo<AdminColumn<OIDCProvider>[]>(
    () => [
      {
        id: 'name',
        header: t('oidc.providerName'),
        cellClassName: 'font-medium text-foreground',
      },
      {
        id: 'issuer',
        header: t('oidc.issuer'),
        cell: (provider) => (
          <span
            className="block overflow-hidden text-ellipsis whitespace-nowrap text-xs text-muted-foreground"
            title={provider.issuer}
          >
            {provider.issuer}
          </span>
        ),
        cellClassName: 'max-w-[300px]',
      },
      {
        id: 'clientId',
        header: t('oidc.clientId'),
        cell: (provider) => (
          <code className="inline-block max-w-full overflow-hidden text-ellipsis whitespace-nowrap rounded bg-muted px-2 py-1 font-mono text-xs text-foreground">
            {provider.clientId}
          </code>
        ),
        cellClassName: 'max-w-[200px]',
      },
      {
        id: 'active',
        header: t('collections.status'),
        cell: (provider) => <StatusBadge active={provider.active} />,
      },
      {
        id: 'createdAt',
        header: t('collections.created'),
        cell: (provider) =>
          formatDate(new Date(provider.createdAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          }),
        cellClassName: 'text-muted-foreground',
      },
    ],
    [t, formatDate]
  )

  return (
    <AdminCrudPage
      testId={testId}
      title={t('oidc.title')}
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label={t('oidc.addProvider')}
          data-testid="add-provider-button"
        >
          {t('oidc.addProvider')}
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={providerList.length === 0}
      emptyMessage={t('common.noResults')}
      list={
        <AdminDataTable
          tableId="oidc-providers"
          testId="providers-table"
          ariaLabel={t('oidc.title')}
          actionsHeader={t('common.actions')}
          columns={columns}
          rows={providerList}
          rowKey={(provider) => provider.id}
          rowTestId={(_, index) => `provider-row-${index}`}
          renderActions={(provider, index) => (
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => handleTestConnection(provider)}
                disabled={testingProviderId === provider.id}
                aria-label={`${t('oidc.testConnection')} ${provider.name}`}
                data-testid={`test-button-${index}`}
              >
                {testingProviderId === provider.id ? t('common.loading') : t('oidc.testConnection')}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(provider)}
                aria-label={`${t('common.edit')} ${provider.name}`}
                data-testid={`edit-button-${index}`}
              >
                {t('common.edit')}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(provider)}
                aria-label={`${t('common.delete')} ${provider.name}`}
                data-testid={`delete-button-${index}`}
              >
                {t('common.delete')}
              </Button>
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<OIDCProviderFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={isEditing ? t('oidc.editProvider') : t('oidc.addProvider')}
        schema={schema}
        defaultValues={providerFormDefaults(editing)}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="oidc"
        submittingLabel={t('common.loading')}
        className="sm:max-w-[600px]"
      >
        <CrudTextField<OIDCProviderFormData>
          name="name"
          id="oidc-name"
          label={t('oidc.providerName')}
          required
          placeholder={t('oidc.namePlaceholder')}
          testId="oidc-name-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="issuer"
          id="oidc-issuer"
          type="url"
          label={t('oidc.issuer')}
          required
          placeholder={t('oidc.issuerPlaceholder')}
          hint={t('oidc.issuerHint')}
          testId="oidc-issuer-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="clientId"
          id="oidc-client-id"
          label={t('oidc.clientId')}
          required
          placeholder={t('oidc.clientIdPlaceholder')}
          testId="oidc-client-id-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="clientSecret"
          id="oidc-client-secret"
          type="password"
          label={t('oidc.clientSecret')}
          required={!isEditing}
          placeholder={
            isEditing ? t('oidc.clientSecretPlaceholderEdit') : t('oidc.clientSecretPlaceholder')
          }
          hint={isEditing ? t('oidc.clientSecretHintEdit') : t('oidc.clientSecretHint')}
          testId="oidc-client-secret-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="scopes"
          id="oidc-scopes"
          label={t('oidc.scopes')}
          required
          placeholder={t('oidc.scopesPlaceholder')}
          hint={t('oidc.scopesHint')}
          testId="oidc-scopes-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="rolesClaim"
          id="oidc-roles-claim"
          label={t('oidc.rolesClaim')}
          placeholder="roles, realm_access.roles, groups"
          hint={t('oidc.rolesClaimHint')}
          testId="oidc-roles-claim-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="rolesMapping"
          id="oidc-roles-mapping"
          label={t('oidc.rolesMapping')}
          multiline
          rows={4}
          placeholder='{"external-admin": "ADMIN", "external-user": "USER"}'
          hint={t('oidc.rolesMappingHint')}
          testId="oidc-roles-mapping-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="emailClaim"
          id="oidc-email-claim"
          label={t('oidc.emailClaim')}
          placeholder="email (default)"
          hint={t('oidc.emailClaimHint')}
          testId="oidc-email-claim-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="usernameClaim"
          id="oidc-username-claim"
          label={t('oidc.usernameClaim')}
          placeholder="preferred_username (default)"
          hint={t('oidc.usernameClaimHint')}
          testId="oidc-username-claim-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="nameClaim"
          id="oidc-name-claim"
          label={t('oidc.nameClaim')}
          placeholder="name (default)"
          hint={t('oidc.nameClaimHint')}
          testId="oidc-name-claim-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="groupsClaim"
          id="oidc-groups-claim"
          label="Groups Claim"
          placeholder="groups (default)"
          hint="Path to groups claim in the JWT token (e.g., groups, memberOf)"
          testId="oidc-groups-claim-input"
        />
        <CrudTextField<OIDCProviderFormData>
          name="groupsProfileMapping"
          id="oidc-groups-profile-mapping"
          label="Groups to Profile Mapping"
          multiline
          rows={4}
          placeholder='{"admin-group": "System Administrator", "user-group": "Standard User"}'
          hint="JSON mapping of OIDC group names to internal profile names. Users without a match require manual profile assignment."
          testId="oidc-groups-profile-mapping-input"
        />

        {/* OIDC Discovery Endpoint Overrides */}
        <div className="mt-2 flex flex-col gap-3 border-t border-border pt-4">
          <div>
            <h3 className="text-sm font-semibold text-foreground">Endpoint overrides</h3>
            <p className="mt-1 text-xs text-muted-foreground">
              Leave blank to auto-discover from the issuer URL. Override when Discovery is
              unavailable.
            </p>
          </div>
          {ENDPOINT_OVERRIDES.map((endpoint) => (
            <CrudTextField<OIDCProviderFormData>
              key={endpoint.name}
              name={endpoint.name}
              id={endpoint.id}
              type="url"
              label={endpoint.label}
              placeholder="Auto-discovered from issuer"
              testId={`${endpoint.id}-input`}
            />
          ))}
        </div>
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title={t('oidc.deleteProvider')}
        message={t('oidc.confirmDelete')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}
