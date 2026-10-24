import React, { useState, useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useFormContext, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog, LoadingSpinner } from '../../components'
import type { CreateConnectedAppRequest } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudNumberField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'

interface ConnectedApp {
  id: string
  name: string
  description: string | null
  clientId: string
  redirectUris: string[] | null
  ipRestrictions: string[] | null
  active: boolean
  rateLimitPerHour: number
  grantTypes: string[] | null
  requirePkce: boolean | null
  consentRequired: boolean | null
  lastUsedAt: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

interface ConnectedAppCreatedResponse {
  id: string
  name: string
  clientId: string
  clientSecret: string
  active: boolean
  rateLimitPerHour: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface ConnectedAppsPageProps {
  testId?: string
}

/**
 * Builds the `grant_types` JSON array string persisted for a connected app.
 * Never emits an empty set — falls back to client_credentials so an app always
 * has at least one usable grant.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function buildGrantTypesJson(
  allowClientCredentials: boolean,
  allowAuthorizationCode: boolean
): string {
  const grants: string[] = []
  if (allowClientCredentials) grants.push('client_credentials')
  if (allowAuthorizationCode) grants.push('authorization_code')
  if (grants.length === 0) grants.push('client_credentials')
  return JSON.stringify(grants)
}

const splitLines = (text: string): string[] =>
  text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)

const CIDR_PATTERN = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(\/\d{1,2})?$/

function isValidUrl(uri: string): boolean {
  try {
    new URL(uri)
    return true
  } catch {
    return false
  }
}

const connectedAppSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 100, 'Name must be 100 characters or fewer'),
  description: z.string().max(500, 'Description must be 500 characters or fewer'),
  scopes: z.string().superRefine((value, ctx) => {
    if (!value.trim()) return
    try {
      if (!Array.isArray(JSON.parse(value))) {
        ctx.addIssue({ code: 'custom', message: 'Scopes must be a JSON array (e.g. ["api"])' })
      }
    } catch {
      ctx.addIssue({ code: 'custom', message: 'Scopes must be valid JSON (e.g. ["api"])' })
    }
  }),
  redirectUris: z
    .string()
    .refine(
      (v) => splitLines(v).every(isValidUrl),
      'Each redirect URI must be a valid URL (e.g. https://example.com/callback)'
    ),
  ipRestrictions: z
    .string()
    .refine(
      (v) => splitLines(v).every((cidr) => CIDR_PATTERN.test(cidr)),
      'Each entry must be a valid IP or CIDR (e.g. 10.0.0.0/8)'
    ),
  rateLimitPerHour: z
    .number()
    .refine((v) => v >= 1 && v <= 1000000, 'Rate limit must be between 1 and 1,000,000'),
  active: z.boolean(),
  allowClientCredentials: z.boolean(),
  allowAuthorizationCode: z.boolean(),
  requirePkce: z.boolean(),
  consentRequired: z.boolean(),
})

type ConnectedAppFormData = z.infer<typeof connectedAppSchema>

const toJsonArray = (text: string): string | undefined => {
  const items = splitLines(text)
  return items.length > 0 ? JSON.stringify(items) : undefined
}

const toConnectedAppRequest = (data: ConnectedAppFormData): CreateConnectedAppRequest => ({
  name: data.name,
  description: data.description || undefined,
  scopes: data.scopes || undefined,
  redirectUris: toJsonArray(data.redirectUris),
  ipRestrictions: toJsonArray(data.ipRestrictions),
  rateLimitPerHour: data.rateLimitPerHour,
  active: data.active,
  grantTypes: buildGrantTypesJson(data.allowClientCredentials, data.allowAuthorizationCode),
  requirePkce: data.allowAuthorizationCode ? data.requirePkce : false,
  consentRequired: data.allowAuthorizationCode ? data.consentRequired : false,
})

/** OAuth grant-type checkboxes; PKCE / consent only apply to authorization code. */
function GrantTypesFieldset(): React.ReactElement {
  const { control } = useFormContext<ConnectedAppFormData>()
  const allowAuthorizationCode = useWatch({ control, name: 'allowAuthorizationCode' })
  return (
    <fieldset className="space-y-2 rounded-md border border-border p-3">
      <legend className="px-1 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
        OAuth grant types
      </legend>
      <p className="text-xs text-muted-foreground">
        Client credentials mints machine tokens from the app&apos;s secret. Authorization code lets
        the app sign a user in (redirect + consent) and act on their behalf.
      </p>
      <CrudCheckboxField<ConnectedAppFormData>
        name="allowClientCredentials"
        id="connected-app-grant-cc"
        label="Client credentials"
        testId="connected-app-grant-cc-input"
      />
      <CrudCheckboxField<ConnectedAppFormData>
        name="allowAuthorizationCode"
        id="connected-app-grant-ac"
        label="Authorization code"
        testId="connected-app-grant-ac-input"
      />
      {allowAuthorizationCode && (
        <div className="ml-6 space-y-2 border-l border-border pl-4">
          <CrudCheckboxField<ConnectedAppFormData>
            name="requirePkce"
            id="connected-app-pkce"
            label="Require PKCE (public client — no secret, e.g. an SPA/mobile app)"
            testId="connected-app-pkce-input"
          />
          <CrudCheckboxField<ConnectedAppFormData>
            name="consentRequired"
            id="connected-app-consent"
            label="Show a consent screen"
            testId="connected-app-consent-input"
          />
        </div>
      )}
    </fieldset>
  )
}

const ACTION_BUTTON =
  'rounded-md border border-border px-3 py-1.5 text-sm font-medium text-primary hover:border-primary hover:bg-muted'

interface CredentialsDialogProps {
  clientId: string
  clientSecret: string
  onClose: () => void
}

function CredentialsDialog({
  clientId,
  clientSecret,
  onClose,
}: CredentialsDialogProps): React.ReactElement {
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    },
    [onClose]
  )

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
      onKeyDown={handleKeyDown}
      data-testid="credentials-dialog-overlay"
      role="presentation"
    >
      <div
        className="w-full max-w-[700px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        role="dialog"
        aria-modal="true"
        aria-labelledby="credentials-dialog-title"
        data-testid="credentials-dialog-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="credentials-dialog-title" className="m-0 text-xl font-semibold text-foreground">
            Connected App Created
          </h2>
          <button
            type="button"
            className="rounded p-2 text-2xl leading-none text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onClose}
            aria-label="Close"
            data-testid="credentials-dialog-close"
          >
            &times;
          </button>
        </div>
        <div className="p-6">
          <div className="rounded-lg border border-amber-300 bg-amber-50 p-4 dark:border-amber-700 dark:bg-amber-950">
            <p className="mb-3 text-sm font-medium text-amber-800 dark:text-amber-300">
              Save these credentials now. The client secret will not be shown again.
            </p>
            <div className="mb-2 flex flex-col gap-1">
              <span className="text-xs font-medium text-muted-foreground">Client ID</span>
              <code
                className="rounded bg-muted px-2 py-1 font-mono text-sm text-foreground"
                data-testid="credentials-client-id"
              >
                {clientId}
              </code>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-xs font-medium text-muted-foreground">Client Secret</span>
              <code
                className="rounded bg-muted px-2 py-1 font-mono text-sm text-foreground"
                data-testid="credentials-client-secret"
              >
                {clientSecret}
              </code>
            </div>
          </div>
          <div className="mt-4 flex justify-end">
            <button
              type="button"
              className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
              onClick={onClose}
              data-testid="credentials-dialog-done"
            >
              Done
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

export function ConnectedAppsPage({
  testId = 'connected-apps-page',
}: ConnectedAppsPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()

  const [tokensAppId, setTokensAppId] = useState<string | null>(null)
  const [tokensAppName, setTokensAppName] = useState('')
  const [auditAppId, setAuditAppId] = useState<string | null>(null)
  const [auditAppName, setAuditAppName] = useState('')
  const [createdCredentials, setCreatedCredentials] = useState<{
    clientId: string
    clientSecret: string
  } | null>(null)

  const {
    data: connectedApps,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['connected-apps'],
    queryFn: () => keltaClient.admin.connectedApps.list(),
  })

  const appList: ConnectedApp[] = (connectedApps ?? []) as unknown as ConnectedApp[]

  const { data: tokens, isLoading: tokensLoading } = useQuery({
    queryKey: ['connected-app-tokens', tokensAppId],
    queryFn: () => keltaClient.admin.connectedApps.listTokens(tokensAppId!),
    enabled: !!tokensAppId,
  })

  const { data: auditEntries } = useQuery({
    queryKey: ['connected-app-audit', auditAppId],
    queryFn: () => keltaClient.admin.connectedApps.listAudit(auditAppId!),
    enabled: !!auditAppId,
  })

  const revokeTokenMutation = useMutation({
    mutationFn: ({ appId, tokenId }: { appId: string; tokenId: string }) =>
      keltaClient.admin.connectedApps.revokeToken(appId, tokenId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['connected-app-tokens', tokensAppId] })
      toast.success('Token revoked')
    },
    onError: (err: Error) => toast.error(err.message || 'Failed to revoke token'),
  })

  const crud = useAdminCrud<ConnectedApp, ConnectedAppFormData>({
    queryKey: ['connected-apps'],
    create: (data) => keltaClient.admin.connectedApps.create('', '', toConnectedAppRequest(data)),
    update: (app, data) =>
      keltaClient.admin.connectedApps.update(app.id, toConnectedAppRequest(data)),
    remove: (app) => keltaClient.admin.connectedApps.delete(app.id),
    messages: {
      created: 'Connected app created successfully',
      updated: 'Connected app updated successfully',
      deleted: 'Connected app deleted successfully',
      error: 'An error occurred',
    },
    // The client secret is only returned by create — reveal it once.
    onCreated: (result) => {
      const created = result as ConnectedAppCreatedResponse
      setCreatedCredentials({ clientId: created.clientId, clientSecret: created.clientSecret })
    },
  })

  const handleCredentialsClose = useCallback(() => {
    setCreatedCredentials(null)
  }, [])

  const columns = useMemo<AdminColumn<ConnectedApp>[]>(
    () => [
      { id: 'name', header: 'Name', cellClassName: 'text-foreground' },
      {
        id: 'clientId',
        header: 'Client ID',
        cell: (app) => (
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{app.clientId}</code>
        ),
      },
      {
        id: 'rateLimitPerHour',
        header: 'Rate Limit',
        cell: (app) => app.rateLimitPerHour.toLocaleString(),
        cellClassName: 'text-foreground',
      },
      {
        id: 'active',
        header: 'Active',
        cell: (app) => (
          <span
            className={cn(
              'inline-block rounded px-2 py-0.5 text-xs font-medium',
              app.active
                ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                : 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
            )}
          >
            {app.active ? 'Yes' : 'No'}
          </span>
        ),
      },
      {
        id: 'lastUsedAt',
        header: 'Last Used',
        cell: (app) =>
          app.lastUsedAt
            ? formatDate(new Date(app.lastUsedAt), {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
              })
            : 'Never',
        cellClassName: 'text-foreground',
      },
      {
        id: 'createdAt',
        header: 'Created',
        cell: (app) =>
          formatDate(new Date(app.createdAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          }),
        cellClassName: 'text-foreground',
      },
    ],
    [formatDate]
  )

  const editing = crud.editing

  return (
    <AdminCrudPage
      testId={testId}
      title="Connected apps"
      actions={
        <button
          type="button"
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={crud.openCreate}
          aria-label="Create Connected App"
          data-testid="add-connected-app-button"
        >
          Create Connected App
        </button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={appList.length === 0}
      emptyMessage="No connected apps found."
      list={
        <AdminDataTable
          tableId="connected-apps"
          testId="connected-apps-table"
          ariaLabel="Connected Apps"
          actionsHeader="Actions"
          columns={columns}
          rows={appList}
          rowKey={(app) => app.id}
          rowTestId={(_, index) => `connected-app-row-${index}`}
          renderActions={(app, index) => (
            <div className="flex justify-end gap-2">
              <button
                type="button"
                className={ACTION_BUTTON}
                onClick={() => {
                  setTokensAppId(app.id)
                  setTokensAppName(app.name)
                }}
                aria-label={`Manage tokens for ${app.name}`}
                data-testid={`tokens-button-${index}`}
              >
                Tokens
              </button>
              <button
                type="button"
                className={ACTION_BUTTON}
                onClick={() => {
                  setAuditAppId(app.id)
                  setAuditAppName(app.name)
                }}
                aria-label={`View audit trail for ${app.name}`}
                data-testid={`audit-button-${index}`}
              >
                Audit
              </button>
              <button
                type="button"
                className={ACTION_BUTTON}
                onClick={() => crud.openEdit(app)}
                aria-label={`Edit ${app.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </button>
              <button
                type="button"
                className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-destructive hover:border-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(app)}
                aria-label={`Delete ${app.name}`}
                data-testid={`delete-button-${index}`}
              >
                Delete
              </button>
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<ConnectedAppFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? 'Edit Connected App' : 'Create Connected App'}
        schema={connectedAppSchema}
        defaultValues={{
          name: editing?.name ?? '',
          description: editing?.description ?? '',
          scopes: '',
          redirectUris: editing?.redirectUris?.join('\n') ?? '',
          ipRestrictions: editing?.ipRestrictions?.join('\n') ?? '',
          rateLimitPerHour: editing?.rateLimitPerHour ?? 10000,
          active: editing?.active ?? true,
          // Default a brand-new app to client_credentials only (matches the DB default);
          // an existing app reflects its stored grant_types.
          allowClientCredentials: editing
            ? (editing.grantTypes?.includes('client_credentials') ?? false)
            : true,
          allowAuthorizationCode: editing?.grantTypes?.includes('authorization_code') ?? false,
          requirePkce: editing?.requirePkce ?? false,
          consentRequired: editing?.consentRequired ?? true,
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="connected-app"
        className="sm:max-w-[700px]"
      >
        <CrudTextField<ConnectedAppFormData>
          name="name"
          id="connected-app-name"
          label="Name"
          required
          placeholder="Enter connected app name"
          testId="connected-app-name-input"
        />
        <CrudTextField<ConnectedAppFormData>
          name="description"
          id="connected-app-description"
          label="Description"
          multiline
          placeholder="Enter connected app description"
          testId="connected-app-description-input"
        />
        {!editing && (
          <CrudTextField<ConnectedAppFormData>
            name="scopes"
            id="connected-app-scopes"
            label="Scopes"
            placeholder='["api"]'
            hint="JSON array of scope strings"
            testId="connected-app-scopes-input"
          />
        )}
        <CrudTextField<ConnectedAppFormData>
          name="redirectUris"
          id="connected-app-redirect-uris"
          label="Redirect URIs"
          multiline
          placeholder="https://example.com/callback"
          hint="One URI per line"
          testId="connected-app-redirect-uris-input"
        />
        <CrudTextField<ConnectedAppFormData>
          name="ipRestrictions"
          id="connected-app-ip-restrictions"
          label="IP Restrictions"
          multiline
          placeholder="10.0.0.0/8"
          hint="One CIDR block per line. Leave empty for no restrictions."
          testId="connected-app-ip-restrictions-input"
        />
        <CrudNumberField<ConnectedAppFormData>
          name="rateLimitPerHour"
          id="connected-app-rate-limit"
          label="Rate Limit (per hour)"
          min={1}
          max={1000000}
          parse={(raw) => parseInt(raw, 10) || 1}
          testId="connected-app-rate-limit-input"
        />
        <GrantTypesFieldset />
        <CrudCheckboxField<ConnectedAppFormData>
          name="active"
          id="connected-app-active"
          label="Active"
          testId="connected-app-active-input"
        />
      </CrudFormDialog>

      {createdCredentials && (
        <CredentialsDialog
          clientId={createdCredentials.clientId}
          clientSecret={createdCredentials.clientSecret}
          onClose={handleCredentialsClose}
        />
      )}

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Connected App"
        message="Are you sure you want to delete this connected app? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />

      {/* Token Management Modal */}
      {tokensAppId && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={(e) => e.target === e.currentTarget && setTokensAppId(null)}
          role="presentation"
          data-testid="tokens-modal-overlay"
        >
          <div
            className="w-full max-w-[700px] max-h-[80vh] overflow-y-auto rounded-lg bg-background shadow-xl"
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-center justify-between border-b border-border p-6">
              <h2 className="text-lg font-semibold text-foreground">Tokens — {tokensAppName}</h2>
              <button
                type="button"
                className="rounded p-2 text-2xl leading-none text-muted-foreground hover:bg-muted"
                onClick={() => setTokensAppId(null)}
                aria-label="Close"
              >
                &times;
              </button>
            </div>
            <div className="p-6 space-y-4">
              <p className="text-sm text-muted-foreground">
                Use <code>client_id</code> and <code>client_secret</code> with{' '}
                <code>grant_type=client_credentials</code> at the OAuth2 token endpoint to obtain
                access tokens.
              </p>
              {tokensLoading ? (
                <LoadingSpinner size="small" label="Loading tokens..." />
              ) : (
                <table className="w-full border-collapse text-sm">
                  <thead>
                    <tr className="bg-muted">
                      <th className="border-b border-border px-3 py-2 text-left text-xs font-semibold uppercase text-muted-foreground">
                        Scopes
                      </th>
                      <th className="border-b border-border px-3 py-2 text-left text-xs font-semibold uppercase text-muted-foreground">
                        Issued
                      </th>
                      <th className="border-b border-border px-3 py-2 text-left text-xs font-semibold uppercase text-muted-foreground">
                        Expires
                      </th>
                      <th className="border-b border-border px-3 py-2 text-left text-xs font-semibold uppercase text-muted-foreground">
                        Status
                      </th>
                      <th className="border-b border-border px-3 py-2 text-right text-xs font-semibold uppercase text-muted-foreground">
                        Actions
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {(
                      (tokens ?? []) as Array<{
                        id: string
                        scopes?: string
                        issuedAt?: string
                        expiresAt?: string
                        revoked?: boolean
                      }>
                    ).map((token) => (
                      <tr key={token.id} className="border-b border-border last:border-0">
                        <td className="px-3 py-2">{token.scopes || '-'}</td>
                        <td className="px-3 py-2">
                          {token.issuedAt
                            ? formatDate(new Date(token.issuedAt), {
                                month: 'short',
                                day: 'numeric',
                                hour: '2-digit',
                                minute: '2-digit',
                              })
                            : '-'}
                        </td>
                        <td className="px-3 py-2">
                          {token.expiresAt
                            ? formatDate(new Date(token.expiresAt), {
                                month: 'short',
                                day: 'numeric',
                                hour: '2-digit',
                                minute: '2-digit',
                              })
                            : '-'}
                        </td>
                        <td className="px-3 py-2">
                          <span
                            className={cn(
                              'inline-block rounded-full px-2 py-0.5 text-xs font-semibold',
                              token.revoked
                                ? 'bg-destructive/10 text-destructive'
                                : 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                            )}
                          >
                            {token.revoked ? 'Revoked' : 'Active'}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-right">
                          {!token.revoked && (
                            <button
                              type="button"
                              className="rounded-md border border-destructive/30 px-2 py-1 text-xs text-destructive hover:bg-destructive/10"
                              onClick={() =>
                                revokeTokenMutation.mutate({
                                  appId: tokensAppId,
                                  tokenId: token.id,
                                })
                              }
                              data-testid={`revoke-token-${token.id}`}
                            >
                              Revoke
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                    {(tokens ?? []).length === 0 && (
                      <tr>
                        <td colSpan={5} className="px-3 py-6 text-center text-muted-foreground">
                          No tokens found.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Audit Trail Modal */}
      {auditAppId && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={(e) => e.target === e.currentTarget && setAuditAppId(null)}
          role="presentation"
          data-testid="audit-modal-overlay"
        >
          <div
            className="w-full max-w-[700px] max-h-[80vh] overflow-y-auto rounded-lg bg-background shadow-xl"
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-center justify-between border-b border-border p-6">
              <h2 className="text-lg font-semibold text-foreground">
                Audit Trail — {auditAppName}
              </h2>
              <button
                type="button"
                className="rounded p-2 text-2xl leading-none text-muted-foreground hover:bg-muted"
                onClick={() => setAuditAppId(null)}
                aria-label="Close"
              >
                &times;
              </button>
            </div>
            <div className="p-6">
              <table className="w-full border-collapse text-sm">
                <thead>
                  <tr className="bg-muted">
                    <th className="border-b border-border px-3 py-2 text-left text-xs font-semibold uppercase text-muted-foreground">
                      Action
                    </th>
                    <th className="border-b border-border px-3 py-2 text-left text-xs font-semibold uppercase text-muted-foreground">
                      Date
                    </th>
                    <th className="border-b border-border px-3 py-2 text-left text-xs font-semibold uppercase text-muted-foreground">
                      Performed By
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {(
                    (auditEntries ?? []) as Array<{
                      action?: unknown
                      created_at?: unknown
                      performed_by?: unknown
                    }>
                  ).map((entry, i: number) => (
                    <tr key={i} className="border-b border-border last:border-0">
                      <td className="px-3 py-2">
                        <span className="inline-block rounded-full bg-muted px-2 py-0.5 text-xs font-semibold">
                          {String(entry.action)}
                        </span>
                      </td>
                      <td className="px-3 py-2">
                        {entry.created_at
                          ? formatDate(new Date(String(entry.created_at)), {
                              month: 'short',
                              day: 'numeric',
                              hour: '2-digit',
                              minute: '2-digit',
                            })
                          : '-'}
                      </td>
                      <td className="px-3 py-2">{String(entry.performed_by ?? '-')}</td>
                    </tr>
                  ))}
                  {(auditEntries ?? []).length === 0 && (
                    <tr>
                      <td colSpan={3} className="px-3 py-6 text-center text-muted-foreground">
                        No audit events.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </AdminCrudPage>
  )
}
