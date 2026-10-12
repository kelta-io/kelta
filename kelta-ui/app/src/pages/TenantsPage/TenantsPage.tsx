/**
 * TenantsPage Component
 *
 * Platform admin page for managing tenants. Provides CRUD operations,
 * status management (suspend/activate), and displays tenant metadata.
 * Built on the shared AdminCrud building block (create/edit only — tenants
 * are suspended, never deleted, from this page).
 *
 * Requirements:
 * - A13: Tenant Administration UI
 */

import React, { useState, useCallback, useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import type { CreateTenantRequest, UpdateTenantRequest } from '@kelta/sdk'
import { useToast, ConfirmDialog } from '../../components'
import { cn } from '@/lib/utils'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudNumberField,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import { BootstrapTokenDialog } from './BootstrapTokenDialog'

/**
 * Tenant interface matching the API response
 */
export interface Tenant {
  id: string
  slug: string
  name: string
  edition: string
  status: string
  settings?: string
  limits?: string
  createdAt: string
  updatedAt: string
}

/**
 * Governor limits for a tenant
 */
interface GovernorLimitsData {
  apiCallsPerDay: number
  storageGb: number
  maxUsers: number
  maxCollections: number
  maxFieldsPerCollection: number
  maxWorkflows: number
  maxReports: number
}

const DEFAULT_GOVERNOR_LIMITS: GovernorLimitsData = {
  apiCallsPerDay: 100_000,
  storageGb: 10,
  maxUsers: 100,
  maxCollections: 200,
  maxFieldsPerCollection: 500,
  maxWorkflows: 50,
  maxReports: 200,
}

/**
 * Parse governor limits from the tenant's limits JSON string.
 * The API stores limits as a JSONB column with snake_case keys.
 */
function parseLimits(limitsJson?: string): GovernorLimitsData {
  if (!limitsJson || limitsJson === '{}') {
    return { ...DEFAULT_GOVERNOR_LIMITS }
  }
  try {
    const parsed = JSON.parse(limitsJson)
    return {
      apiCallsPerDay: parsed.api_calls_per_day ?? DEFAULT_GOVERNOR_LIMITS.apiCallsPerDay,
      storageGb: parsed.storage_gb ?? DEFAULT_GOVERNOR_LIMITS.storageGb,
      maxUsers: parsed.max_users ?? DEFAULT_GOVERNOR_LIMITS.maxUsers,
      maxCollections: parsed.max_collections ?? DEFAULT_GOVERNOR_LIMITS.maxCollections,
      maxFieldsPerCollection:
        parsed.max_fields_per_collection ?? DEFAULT_GOVERNOR_LIMITS.maxFieldsPerCollection,
      maxWorkflows: parsed.max_workflows ?? DEFAULT_GOVERNOR_LIMITS.maxWorkflows,
      maxReports: parsed.max_reports ?? DEFAULT_GOVERNOR_LIMITS.maxReports,
    }
  } catch {
    return { ...DEFAULT_GOVERNOR_LIMITS }
  }
}

/**
 * Props for TenantsPage component
 */
export interface TenantsPageProps {
  testId?: string
}

const VALID_EDITIONS = ['FREE', 'PROFESSIONAL', 'ENTERPRISE', 'UNLIMITED']

const EDITION_OPTIONS = VALID_EDITIONS.map((edition) => ({ value: edition, label: edition }))

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/

/**
 * Slug and admin email are only entered (and validated) on create; an edit
 * accepts whatever the tenant already has.
 */
function buildTenantSchema(isEditing: boolean) {
  return z.object({
    slug: z.string().superRefine((value, ctx) => {
      if (isEditing) return
      if (!value.trim()) {
        ctx.addIssue({ code: 'custom', message: 'Tenant slug is required' })
      } else if (value.length > 50) {
        ctx.addIssue({ code: 'custom', message: 'Slug must be 50 characters or less' })
      } else if (!/^[a-z][a-z0-9-]*$/.test(value)) {
        ctx.addIssue({
          code: 'custom',
          message:
            'Slug must be lowercase, start with a letter, and contain only letters, numbers, and hyphens',
        })
      }
    }),
    name: z.string().superRefine((value, ctx) => {
      if (!value.trim()) {
        ctx.addIssue({ code: 'custom', message: 'Tenant name is required' })
      } else if (value.length > 100) {
        ctx.addIssue({ code: 'custom', message: 'Name must be 100 characters or less' })
      }
    }),
    edition: z.string().refine((value) => VALID_EDITIONS.includes(value), 'Invalid edition'),
    adminEmail: z
      .string()
      .refine(
        (value) => isEditing || !value.trim() || EMAIL_PATTERN.test(value.trim()),
        'Enter a valid email address'
      ),
    limits: z.object({
      apiCallsPerDay: z.number(),
      storageGb: z.number(),
      maxUsers: z.number(),
      maxCollections: z.number(),
      maxFieldsPerCollection: z.number(),
      maxWorkflows: z.number(),
      maxReports: z.number(),
    }),
  })
}

type TenantFormData = z.infer<ReturnType<typeof buildTenantSchema>>

/** Limit field descriptor for rendering */
interface LimitField {
  key: keyof GovernorLimitsData
  label: string
  min: number
  max: number
}

const LIMIT_FIELDS: LimitField[] = [
  { key: 'apiCallsPerDay', label: 'API Calls Per Day', min: 0, max: 10_000_000 },
  { key: 'storageGb', label: 'Storage (GB)', min: 1, max: 10_000 },
  { key: 'maxUsers', label: 'Max Users', min: 1, max: 100_000 },
  { key: 'maxCollections', label: 'Max Collections', min: 1, max: 10_000 },
  { key: 'maxFieldsPerCollection', label: 'Max Fields Per Collection', min: 1, max: 10_000 },
  { key: 'maxWorkflows', label: 'Max Workflows', min: 0, max: 10_000 },
  { key: 'maxReports', label: 'Max Reports', min: 0, max: 10_000 },
]

/** An empty limit input means 0; anything that is not a number is ignored. */
function parseLimitInput(raw: string): number | null {
  const value = raw === '' ? 0 : parseInt(raw, 10)
  return Number.isNaN(value) ? null : value
}

/**
 * Status badge component
 */
function StatusBadge({ status }: { status: string }): React.ReactElement {
  const colorMap: Record<string, string> = {
    ACTIVE: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-300',
    SUSPENDED: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300',
    PROVISIONING: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300',
  }
  const defaultColor = 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'

  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
        colorMap[status] ?? defaultColor
      )}
    >
      {status}
    </span>
  )
}

const ACTION_BUTTON =
  'rounded border border-border px-2 py-1 text-xs font-medium text-primary hover:border-primary hover:bg-muted'

/**
 * TenantsPage Component
 *
 * Main page for managing tenants in the Kelta Platform Admin UI.
 */
export function TenantsPage({ testId = 'tenants-page' }: TenantsPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()
  const { showToast } = useToast()

  const [suspendDialogOpen, setSuspendDialogOpen] = useState(false)
  const [activateDialogOpen, setActivateDialogOpen] = useState(false)
  const [targetTenant, setTargetTenant] = useState<Tenant | null>(null)
  const [inviteTenant, setInviteTenant] = useState<Tenant | null>(null)
  const [inviteEmail, setInviteEmail] = useState('')
  const [bootstrapTenant, setBootstrapTenant] = useState<Tenant | null>(null)

  // Fetch tenants
  const {
    data: tenantsPage,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['tenants'],
    queryFn: () => keltaClient.admin.tenants.list(0, 100),
  })

  const tenants = (tenantsPage?.content ?? []) as Tenant[]

  // Create / update — tenants are never deleted from this page.
  const crud = useAdminCrud<Tenant, TenantFormData>({
    queryKey: ['tenants'],
    create: (data) =>
      keltaClient.admin.tenants.create({
        slug: data.slug,
        name: data.name,
        edition: data.edition,
        limits: data.limits,
        ...(data.adminEmail.trim() ? { adminEmail: data.adminEmail.trim() } : {}),
      } as CreateTenantRequest),
    update: (tenant, data) =>
      keltaClient.admin.tenants.update(tenant.id, {
        name: data.name,
        edition: data.edition,
        limits: data.limits,
      } as UpdateTenantRequest),
    messages: {
      created: 'Tenant created successfully.',
      updated: 'Tenant updated successfully.',
      deleted: '',
      error: 'Failed to save tenant.',
      createError: 'Failed to create tenant.',
      updateError: 'Failed to update tenant.',
    },
  })

  const editing = crud.editing
  const isEditing = !!editing
  const schema = useMemo(() => buildTenantSchema(isEditing), [isEditing])

  // Suspend mutation
  const suspendMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.tenants.suspend(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tenants'] })
      showToast('Tenant suspended.', 'success')
      setSuspendDialogOpen(false)
      setTargetTenant(null)
    },
    onError: (error: Error) => {
      showToast(error.message || 'Failed to suspend tenant.', 'error')
    },
  })

  // Activate mutation
  const activateMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.tenants.activate(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tenants'] })
      showToast('Tenant activated.', 'success')
      setActivateDialogOpen(false)
      setTargetTenant(null)
    },
    onError: (error: Error) => {
      showToast(error.message || 'Failed to activate tenant.', 'error')
    },
  })

  // Invite the tenant's seeded admin — provisioned tenants have no usable admin password
  const inviteMutation = useMutation({
    mutationFn: ({ id, email }: { id: string; email: string }) =>
      keltaClient.admin.tenants.inviteAdmin(id, email),
    onSuccess: (result) => {
      showToast(`Invite sent to ${result.email}.`, 'success')
      setInviteTenant(null)
      setInviteEmail('')
    },
    onError: (error: Error) => {
      showToast(error.message || 'Failed to send the admin invite.', 'error')
    },
  })

  const handleInviteClick = useCallback((tenant: Tenant) => {
    setInviteTenant(tenant)
    setInviteEmail('')
  }, [])

  const handleInviteClose = useCallback(() => {
    setInviteTenant(null)
    setInviteEmail('')
  }, [])

  const handleInviteSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      if (inviteTenant && inviteEmail.trim()) {
        inviteMutation.mutate({ id: inviteTenant.id, email: inviteEmail.trim() })
      }
    },
    [inviteTenant, inviteEmail, inviteMutation]
  )

  const handleSuspendClick = useCallback((tenant: Tenant) => {
    setTargetTenant(tenant)
    setSuspendDialogOpen(true)
  }, [])

  const handleActivateClick = useCallback((tenant: Tenant) => {
    setTargetTenant(tenant)
    setActivateDialogOpen(true)
  }, [])

  const handleSuspendConfirm = useCallback(() => {
    if (targetTenant) {
      suspendMutation.mutate(targetTenant.id)
    }
  }, [targetTenant, suspendMutation])

  const handleActivateConfirm = useCallback(() => {
    if (targetTenant) {
      activateMutation.mutate(targetTenant.id)
    }
  }, [targetTenant, activateMutation])

  const columns = useMemo<AdminColumn<Tenant>[]>(
    () => [
      { id: 'slug', header: 'Slug', cellClassName: 'font-medium font-mono text-foreground' },
      { id: 'name', header: 'Name', cellClassName: 'text-foreground' },
      { id: 'edition', header: 'Edition', cellClassName: 'text-foreground' },
      { id: 'status', header: 'Status', cell: (tenant) => <StatusBadge status={tenant.status} /> },
      {
        id: 'createdAt',
        header: 'Created',
        cell: (tenant) =>
          formatDate(new Date(tenant.createdAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          }),
        cellClassName: 'whitespace-nowrap text-muted-foreground',
      },
    ],
    [formatDate]
  )

  return (
    <AdminCrudPage
      testId={testId}
      title="Tenants"
      actions={
        <button
          type="button"
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={crud.openCreate}
          aria-label="Create Tenant"
          data-testid="create-tenant-button"
        >
          Create Tenant
        </button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={tenants.length === 0}
      emptyMessage="No tenants found."
      list={
        <AdminDataTable
          tableId="tenants"
          testId="tenants-table"
          ariaLabel="Tenants"
          actionsHeader="Actions"
          columns={columns}
          rows={tenants}
          rowKey={(tenant) => tenant.id}
          rowTestId={(_, index) => `tenant-row-${index}`}
          renderActions={(tenant, index) => (
            <div className="flex justify-end gap-2 whitespace-nowrap">
              <button
                type="button"
                className={ACTION_BUTTON}
                onClick={() => crud.openEdit(tenant)}
                aria-label={`Edit ${tenant.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </button>
              <button
                type="button"
                className={ACTION_BUTTON}
                onClick={() => handleInviteClick(tenant)}
                aria-label={`Invite admin for ${tenant.name}`}
                data-testid={`invite-admin-button-${index}`}
              >
                Invite admin
              </button>
              <button
                type="button"
                className={ACTION_BUTTON}
                onClick={() => setBootstrapTenant(tenant)}
                aria-label={`Bootstrap token for ${tenant.name}`}
                data-testid={`bootstrap-token-button-${index}`}
              >
                Bootstrap token
              </button>
              {tenant.status === 'ACTIVE' ? (
                <button
                  type="button"
                  className="rounded border border-border px-2 py-1 text-xs font-medium text-destructive hover:border-destructive hover:bg-destructive/10"
                  onClick={() => handleSuspendClick(tenant)}
                  aria-label={`Suspend ${tenant.name}`}
                  data-testid={`suspend-button-${index}`}
                >
                  Suspend
                </button>
              ) : tenant.status === 'SUSPENDED' ? (
                <button
                  type="button"
                  className="rounded border border-emerald-300 px-2 py-1 text-xs font-medium text-emerald-600 hover:border-emerald-600 hover:bg-emerald-50 dark:border-emerald-700 dark:text-emerald-400 dark:hover:border-emerald-400 dark:hover:bg-emerald-950"
                  onClick={() => handleActivateClick(tenant)}
                  aria-label={`Activate ${tenant.name}`}
                  data-testid={`activate-button-${index}`}
                >
                  Activate
                </button>
              ) : null}
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<TenantFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={isEditing ? 'Edit Tenant' : 'Create Tenant'}
        schema={schema}
        defaultValues={{
          slug: editing?.slug ?? '',
          name: editing?.name ?? '',
          edition: editing?.edition ?? 'PROFESSIONAL',
          limits: parseLimits(editing?.limits),
          adminEmail: '',
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="tenant"
        className="sm:max-w-[600px]"
      >
        {!isEditing && (
          <CrudTextField<TenantFormData>
            name="slug"
            id="tenant-slug"
            label="Slug"
            required
            placeholder="e.g., acme-corp"
            testId="tenant-slug-input"
          />
        )}
        <CrudTextField<TenantFormData>
          name="name"
          id="tenant-name"
          label="Name"
          required
          placeholder="e.g., Acme Corporation"
          testId="tenant-name-input"
        />
        <CrudSelectField<TenantFormData>
          name="edition"
          id="tenant-edition"
          label="Edition"
          options={EDITION_OPTIONS}
          testId="tenant-edition-select"
        />
        {!isEditing && (
          <CrudTextField<TenantFormData>
            name="adminEmail"
            id="tenant-admin-email"
            label="Admin email"
            type="email"
            placeholder="owner@example.com"
            hint="The tenant admin has no password until claimed. Enter an email to send the invite now, or invite later from this page."
            testId="tenant-admin-email-input"
          />
        )}
        <fieldset
          className="m-0 rounded-md border border-border p-4"
          data-testid="governor-limits-section"
        >
          <legend className="px-1 text-sm font-semibold text-foreground">Governor Limits</legend>
          <div className="grid grid-cols-2 gap-4">
            {LIMIT_FIELDS.map((field) => (
              <CrudNumberField<TenantFormData>
                key={field.key}
                name={`limits.${field.key}`}
                id={`limit-${field.key}`}
                label={field.label}
                min={field.min}
                max={field.max}
                parse={parseLimitInput}
                testId={`limit-${field.key}-input`}
              />
            ))}
          </div>
        </fieldset>
      </CrudFormDialog>

      {inviteTenant && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={(e) => e.target === e.currentTarget && handleInviteClose()}
          onKeyDown={(e) => e.key === 'Escape' && handleInviteClose()}
          role="presentation"
        >
          <div
            className="w-full max-w-[480px] rounded-lg bg-card p-6 shadow-xl"
            role="dialog"
            aria-modal="true"
            aria-labelledby="invite-admin-title"
            data-testid="invite-admin-dialog"
          >
            <h2 id="invite-admin-title" className="mb-2 text-xl font-semibold">
              Invite admin — {inviteTenant.name}
            </h2>
            <p className="mb-4 text-sm text-muted-foreground">
              The tenant&apos;s System Administrator gets this email and an invite to set a
              password.
            </p>
            <form onSubmit={handleInviteSubmit}>
              <label className="mb-1 block text-sm font-medium" htmlFor="invite-admin-email">
                Email
              </label>
              <input
                id="invite-admin-email"
                type="email"
                required
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                data-testid="invite-admin-email-input"
              />
              <div className="mt-6 flex justify-end gap-2">
                <button
                  type="button"
                  className="cursor-pointer rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
                  onClick={handleInviteClose}
                  data-testid="invite-admin-cancel"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                  disabled={inviteMutation.isPending || !inviteEmail.trim()}
                  data-testid="invite-admin-submit"
                >
                  {inviteMutation.isPending ? 'Sending...' : 'Send invite'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {bootstrapTenant && (
        <BootstrapTokenDialog tenant={bootstrapTenant} onClose={() => setBootstrapTenant(null)} />
      )}

      <ConfirmDialog
        open={suspendDialogOpen}
        title="Suspend Tenant"
        message={`Are you sure you want to suspend "${targetTenant?.name}"? Users will not be able to access this tenant's data.`}
        confirmLabel="Suspend"
        cancelLabel="Cancel"
        onConfirm={handleSuspendConfirm}
        onCancel={() => {
          setSuspendDialogOpen(false)
          setTargetTenant(null)
        }}
        variant="danger"
      />

      <ConfirmDialog
        open={activateDialogOpen}
        title="Activate Tenant"
        message={`Are you sure you want to activate "${targetTenant?.name}"?`}
        confirmLabel="Activate"
        cancelLabel="Cancel"
        onConfirm={handleActivateConfirm}
        onCancel={() => {
          setActivateDialogOpen(false)
          setTargetTenant(null)
        }}
      />
    </AdminCrudPage>
  )
}

export default TenantsPage
