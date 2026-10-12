import React, { useState, useCallback, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { useApi } from '../../context/ApiContext'
import { getTenantSlug } from '../../context/TenantContext'
import { ConfirmDialog } from '../../components'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import { Button } from '@/components/ui/button'
import type { PersonalAccessToken } from '@kelta/sdk'

const tokenSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 200, 'Name must be 200 characters or fewer'),
  /** Days until expiry, as the select's string value. */
  expiresInDays: z.string(),
})

type TokenFormData = z.infer<typeof tokenSchema>

const EXPIRY_OPTIONS = [30, 60, 90, 180, 365].map((days) => ({
  value: String(days),
  label: `${days} days`,
}))

export interface ApiTokensPageProps {
  testId?: string
}

export function ApiTokensPage({
  testId = 'api-tokens-page',
}: ApiTokensPageProps): React.ReactElement {
  const { keltaClient } = useApi()
  const [createdToken, setCreatedToken] = useState<string | null>(null)

  const {
    data: tokens,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['personal-tokens'],
    queryFn: () => keltaClient.admin.personalTokens.list(),
  })

  const tokenList: PersonalAccessToken[] = tokens ?? []

  // Tokens are create + revoke only: there is no edit, and "delete" is a revoke.
  const crud = useAdminCrud<PersonalAccessToken, TokenFormData>({
    queryKey: ['personal-tokens'],
    create: (data) =>
      keltaClient.admin.personalTokens.create({
        name: data.name,
        expiresInDays: parseInt(data.expiresInDays, 10),
      }),
    remove: (token) => keltaClient.admin.personalTokens.revoke(token.id),
    messages: {
      created: 'Token created successfully',
      updated: 'Token updated successfully',
      deleted: 'Token revoked',
      error: 'An error occurred',
      createError: 'Failed to create token',
      deleteError: 'Failed to revoke token',
    },
    // The raw token is returned exactly once: reveal it now.
    onCreated: (result) => setCreatedToken((result as { token: string }).token),
  })

  const columns = useMemo<AdminColumn<PersonalAccessToken>[]>(
    () => [
      { id: 'name', header: 'Name', cellClassName: 'font-medium text-foreground' },
      {
        id: 'tokenPrefix',
        header: 'Token',
        cell: (token) => (
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-muted-foreground">
            {token.tokenPrefix}...
          </code>
        ),
      },
      {
        id: 'expiresAt',
        header: 'Expires',
        cell: (token) => new Date(token.expiresAt).toLocaleDateString(),
        cellClassName: 'text-muted-foreground',
      },
      {
        id: 'lastUsedAt',
        header: 'Last Used',
        cell: (token) =>
          token.lastUsedAt ? new Date(token.lastUsedAt).toLocaleDateString() : 'Never',
        cellClassName: 'text-muted-foreground',
      },
      {
        id: 'createdAt',
        header: 'Created',
        cell: (token) => new Date(token.createdAt).toLocaleDateString(),
        cellClassName: 'text-muted-foreground',
      },
    ],
    []
  )

  return (
    <AdminCrudPage
      testId={testId}
      title="API Tokens"
      description="Create personal access tokens for programmatic API access."
      actions={
        <Button onClick={crud.openCreate} data-testid="create-token-button">
          Create Token
        </Button>
      }
      isLoading={isLoading}
      error={error}
      loadErrorMessage="Failed to load tokens. Please try again."
      onRetry={() => refetch()}
      isEmpty={tokenList.length === 0}
      emptyMessage="No API tokens yet. Create one to get started."
      list={
        <AdminDataTable
          tableId="personal-tokens"
          testId="tokens-table"
          ariaLabel="API Tokens"
          columns={columns}
          rows={tokenList}
          rowKey={(token) => token.id}
          renderActions={(token) => (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="border-destructive/30 text-destructive hover:bg-destructive/10"
              onClick={() => crud.requestDelete(token)}
              disabled={crud.deleteMutation.isPending}
              data-testid={`revoke-token-${token.id}`}
            >
              Revoke
            </Button>
          )}
        />
      }
    >
      <CrudFormDialog<TokenFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title="Create API Token"
        schema={tokenSchema}
        defaultValues={{ name: '', expiresInDays: '90' }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="create-token"
        submitTestId="create-token-submit"
        submitLabel="Create Token"
        submittingLabel="Creating..."
        className="sm:max-w-[500px]"
      >
        <CrudTextField<TokenFormData>
          name="name"
          id="token-name"
          label="Token Name"
          required
          placeholder="e.g. CI/CD Pipeline"
          testId="token-name-input"
        />
        <CrudSelectField<TokenFormData>
          name="expiresInDays"
          id="token-expiry"
          label="Expiration"
          options={EXPIRY_OPTIONS}
          testId="token-expiry-select"
        />
      </CrudFormDialog>

      {createdToken && (
        <TokenCreatedDialog token={createdToken} onClose={() => setCreatedToken(null)} />
      )}

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Revoke Token"
        message={`Are you sure you want to revoke "${crud.deleteTarget?.name}"? This action cannot be undone. Any applications using this token will lose access.`}
        confirmLabel="Revoke"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}

/**
 * Resolve the MCP server base URL for use-with-claude-code commands.
 * Mirrors the API base URL convention: VITE_API_BASE_URL when set
 * (production), otherwise the current page origin (local dev).
 */
// Vite's react-refresh requires page modules to export only components.
// Re-export non-component helpers below from this file so callers' import
// paths stay stable, but mark them so the lint doesn't flag fast-refresh.
// eslint-disable-next-line react-refresh/only-export-components
export function resolveMcpBaseUrl(): string {
  const apiBase = import.meta.env.VITE_API_BASE_URL || ''
  if (apiBase) return apiBase
  if (typeof window !== 'undefined' && window.location?.origin) {
    return window.location.origin
  }
  return ''
}

// eslint-disable-next-line react-refresh/only-export-components
export function buildMcpAddCommand(
  profile: 'user' | 'admin',
  mcpBaseUrl: string,
  tenantSlug: string,
  token: string
): string {
  // Slug binds the URL to a tenant (matches the platform-wide /{slug}/...
  // convention). Different PAT in a different tenant → different URL.
  const url = `${mcpBaseUrl}/${tenantSlug}/mcp/${profile}`
  // Single-quoted shell strings keep the token opaque to the shell.
  return `claude mcp add kelta-${profile} --transport http --url ${url} --header 'Authorization: Bearer ${token}'`
}

function CopyableCommandBlock({
  label,
  command,
  testId,
}: {
  label: string
  command: string
  testId: string
}): React.ReactElement {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(command).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }, [command])

  return (
    <div data-testid={testId}>
      <div className="mb-1 text-xs font-medium text-muted-foreground">{label}</div>
      <div className="flex items-start gap-2">
        <code
          className="flex-1 rounded bg-muted px-2 py-1.5 font-mono text-xs text-foreground break-all"
          data-testid={`${testId}-value`}
        >
          {command}
        </code>
        <button
          type="button"
          className="shrink-0 rounded-md border border-border bg-secondary px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
          onClick={handleCopy}
          data-testid={`${testId}-copy`}
        >
          {copied ? 'Copied!' : 'Copy'}
        </button>
      </div>
    </div>
  )
}

function TokenCreatedDialog({
  token,
  onClose,
}: {
  token: string
  onClose: () => void
}): React.ReactElement {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(token).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }, [token])

  const mcpBaseUrl = resolveMcpBaseUrl()
  const tenantSlug = getTenantSlug() || '<your-tenant-slug>'
  const userCommand = buildMcpAddCommand('user', mcpBaseUrl, tenantSlug, token)
  const adminCommand = buildMcpAddCommand('admin', mcpBaseUrl, tenantSlug, token)

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
      role="presentation"
      data-testid="token-created-dialog-overlay"
    >
      <div
        className="w-full max-w-[640px] rounded-lg bg-card shadow-xl"
        role="dialog"
        aria-modal="true"
        data-testid="token-created-dialog-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 className="m-0 text-xl font-semibold text-foreground">Token created</h2>
          <button
            type="button"
            className="rounded p-2 text-2xl leading-none text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onClose}
            aria-label="Close"
          >
            &times;
          </button>
        </div>
        <div className="p-6 space-y-5">
          <div className="rounded-lg border border-amber-300 bg-amber-50 p-4 dark:border-amber-700 dark:bg-amber-950">
            <p className="mb-3 text-sm font-medium text-amber-800 dark:text-amber-300">
              Copy this token now. It will not be shown again.
            </p>
            <div className="flex items-center gap-2">
              <code
                className="flex-1 rounded bg-muted px-2 py-1.5 font-mono text-xs text-foreground break-all"
                data-testid="created-token-value"
              >
                {token}
              </code>
              <button
                type="button"
                className="shrink-0 rounded-md border border-border bg-secondary px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
                onClick={handleCopy}
                data-testid="copy-token-button"
              >
                {copied ? 'Copied!' : 'Copy'}
              </button>
            </div>
          </div>

          <div
            className="rounded-lg border border-border bg-card p-4 space-y-3"
            data-testid="claude-code-panel"
          >
            <div>
              <h3 className="text-sm font-semibold text-foreground">Use with Claude Code</h3>
              <p className="mt-1 text-xs text-muted-foreground">
                Run one or both of these in a terminal to register Kelta as an MCP server. The{' '}
                <code className="font-mono">/mcp/user</code> endpoint exposes data-plane tools
                (CRUD, flows, approvals); <code className="font-mono">/mcp/admin</code> exposes
                control-plane tools (collection / layout / flow definition).
              </p>
            </div>
            <CopyableCommandBlock
              label="Data plane (CRUD, flows, approvals)"
              command={userCommand}
              testId="mcp-add-user"
            />
            <CopyableCommandBlock
              label="Control plane (schema, layouts, flows definition)"
              command={adminCommand}
              testId="mcp-add-admin"
            />
          </div>

          <div className="flex justify-end">
            <button
              type="button"
              className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
              onClick={onClose}
              data-testid="token-created-done"
            >
              Done
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
