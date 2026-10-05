/**
 * TenantsPage Component
 *
 * Platform admin page for managing tenants. Provides CRUD operations,
 * status management (suspend/activate), and displays tenant metadata.
 *
 * Requirements:
 * - A13: Tenant Administration UI
 */

import React, { useState, useCallback, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import type { CreateTenantRequest, UpdateTenantRequest } from '@kelta/sdk'
import { useToast, ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'

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
 * Form data for creating/editing a tenant
 */
interface TenantFormData {
  slug: string
  name: string
  edition: string
  limits: GovernorLimitsData
  adminEmail: string
}

/**
 * Form validation errors
 */
interface FormErrors {
  slug?: string
  name?: string
  edition?: string
  adminEmail?: string
}

/**
 * Props for TenantsPage component
 */
export interface TenantsPageProps {
  testId?: string
}

const VALID_EDITIONS = ['FREE', 'PROFESSIONAL', 'ENTERPRISE', 'UNLIMITED']

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/

function validateForm(data: TenantFormData, isEditing: boolean): FormErrors {
  const errors: FormErrors = {}

  if (!isEditing) {
    if (!data.slug.trim()) {
      errors.slug = 'Tenant slug is required'
    } else if (data.slug.length > 50) {
      errors.slug = 'Slug must be 50 characters or less'
    } else if (!/^[a-z][a-z0-9-]*$/.test(data.slug)) {
      errors.slug =
        'Slug must be lowercase, start with a letter, and contain only letters, numbers, and hyphens'
    }
  }

  if (!data.name.trim()) {
    errors.name = 'Tenant name is required'
  } else if (data.name.length > 100) {
    errors.name = 'Name must be 100 characters or less'
  }

  if (!VALID_EDITIONS.includes(data.edition)) {
    errors.edition = 'Invalid edition'
  }

  if (!isEditing && data.adminEmail.trim() && !EMAIL_PATTERN.test(data.adminEmail.trim())) {
    errors.adminEmail = 'Enter a valid email address'
  }

  return errors
}

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

/**
 * TenantForm Component — Modal form for creating and editing tenants.
 */
interface TenantFormProps {
  tenant?: Tenant
  onSubmit: (data: TenantFormData) => void
  onCancel: () => void
  isSubmitting: boolean
}

function TenantForm({
  tenant,
  onSubmit,
  onCancel,
  isSubmitting,
}: TenantFormProps): React.ReactElement {
  const isEditing = !!tenant
  const [formData, setFormData] = useState<TenantFormData>({
    slug: tenant?.slug ?? '',
    name: tenant?.name ?? '',
    edition: tenant?.edition ?? 'PROFESSIONAL',
    limits: parseLimits(tenant?.limits),
    adminEmail: '',
  })
  const [errors, setErrors] = useState<FormErrors>({})
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const nameInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    nameInputRef.current?.focus()
  }, [])

  const handleChange = useCallback(
    (field: 'slug' | 'name' | 'edition' | 'adminEmail', value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }))
      if (errors[field]) {
        setErrors((prev) => ({ ...prev, [field]: undefined }))
      }
    },
    [errors]
  )

  const handleLimitChange = useCallback((key: keyof GovernorLimitsData, value: string) => {
    const numValue = value === '' ? 0 : parseInt(value, 10)
    if (!isNaN(numValue)) {
      setFormData((prev) => ({
        ...prev,
        limits: { ...prev.limits, [key]: numValue },
      }))
    }
  }, [])

  const handleBlur = useCallback(
    (field: 'slug' | 'name' | 'edition' | 'adminEmail') => {
      setTouched((prev) => ({ ...prev, [field]: true }))
      const validationErrors = validateForm(formData, isEditing)
      if (validationErrors[field]) {
        setErrors((prev) => ({ ...prev, [field]: validationErrors[field] }))
      }
    },
    [formData, isEditing]
  )

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      const validationErrors = validateForm(formData, isEditing)
      setErrors(validationErrors)
      setTouched({ slug: true, name: true, edition: true, adminEmail: true })
      if (Object.keys(validationErrors).length === 0) {
        onSubmit(formData)
      }
    },
    [formData, onSubmit, isEditing]
  )

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel()
      }
    },
    [onCancel]
  )

  const title = isEditing ? 'Edit Tenant' : 'Create Tenant'

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={handleKeyDown}
      data-testid="tenant-form-overlay"
      role="presentation"
    >
      <div
        className="w-full max-w-[600px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        role="dialog"
        aria-modal="true"
        aria-labelledby="tenant-form-title"
        data-testid="tenant-form-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="tenant-form-title" className="m-0 text-lg font-semibold text-foreground">
            {title}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onCancel}
            aria-label="Close"
            data-testid="tenant-form-close"
          >
            &times;
          </button>
        </div>
        <div className="p-6">
          <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
            {!isEditing && (
              <div className="flex flex-col gap-1">
                <label htmlFor="tenant-slug" className="text-sm font-medium text-foreground">
                  Slug
                  <span className="ml-0.5 text-destructive" aria-hidden="true">
                    *
                  </span>
                </label>
                <input
                  id="tenant-slug"
                  type="text"
                  className={cn(
                    'w-full rounded-md border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary',
                    touched.slug && errors.slug ? 'border-destructive' : 'border-border'
                  )}
                  value={formData.slug}
                  onChange={(e) => handleChange('slug', e.target.value)}
                  onBlur={() => handleBlur('slug')}
                  placeholder="e.g., acme-corp"
                  aria-required="true"
                  aria-invalid={touched.slug && !!errors.slug}
                  disabled={isSubmitting}
                  data-testid="tenant-slug-input"
                />
                {touched.slug && errors.slug && (
                  <span className="mt-1 block text-xs text-destructive" role="alert">
                    {errors.slug}
                  </span>
                )}
              </div>
            )}

            <div className="flex flex-col gap-1">
              <label htmlFor="tenant-name" className="text-sm font-medium text-foreground">
                Name
                <span className="ml-0.5 text-destructive" aria-hidden="true">
                  *
                </span>
              </label>
              <input
                ref={nameInputRef}
                id="tenant-name"
                type="text"
                className={cn(
                  'w-full rounded-md border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary',
                  touched.name && errors.name ? 'border-destructive' : 'border-border'
                )}
                value={formData.name}
                onChange={(e) => handleChange('name', e.target.value)}
                onBlur={() => handleBlur('name')}
                placeholder="e.g., Acme Corporation"
                aria-required="true"
                aria-invalid={touched.name && !!errors.name}
                disabled={isSubmitting}
                data-testid="tenant-name-input"
              />
              {touched.name && errors.name && (
                <span className="mt-1 block text-xs text-destructive" role="alert">
                  {errors.name}
                </span>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="tenant-edition" className="text-sm font-medium text-foreground">
                Edition
              </label>
              <select
                id="tenant-edition"
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                value={formData.edition}
                onChange={(e) => handleChange('edition', e.target.value)}
                disabled={isSubmitting}
                data-testid="tenant-edition-select"
              >
                {VALID_EDITIONS.map((edition) => (
                  <option key={edition} value={edition}>
                    {edition}
                  </option>
                ))}
              </select>
            </div>

            {!isEditing && (
              <div className="flex flex-col gap-1">
                <label htmlFor="tenant-admin-email" className="text-sm font-medium text-foreground">
                  Admin email
                </label>
                <input
                  id="tenant-admin-email"
                  type="email"
                  className={cn(
                    'w-full rounded-md border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary',
                    touched.adminEmail && errors.adminEmail ? 'border-destructive' : 'border-border'
                  )}
                  value={formData.adminEmail}
                  onChange={(e) => handleChange('adminEmail', e.target.value)}
                  onBlur={() => handleBlur('adminEmail')}
                  placeholder="owner@example.com"
                  aria-invalid={touched.adminEmail && !!errors.adminEmail}
                  aria-describedby="tenant-admin-email-hint"
                  disabled={isSubmitting}
                  data-testid="tenant-admin-email-input"
                />
                <span id="tenant-admin-email-hint" className="text-xs text-muted-foreground">
                  The tenant admin has no password until claimed. Enter an email to send the invite
                  now, or invite later from this page.
                </span>
                {touched.adminEmail && errors.adminEmail && (
                  <span className="mt-1 block text-xs text-destructive" role="alert">
                    {errors.adminEmail}
                  </span>
                )}
              </div>
            )}

            <fieldset
              className="m-0 rounded-md border border-border p-4"
              data-testid="governor-limits-section"
            >
              <legend className="px-1 text-sm font-semibold text-foreground">
                Governor Limits
              </legend>
              <div className="grid grid-cols-2 gap-4">
                {LIMIT_FIELDS.map((field) => (
                  <div key={field.key} className="flex flex-col gap-1">
                    <label
                      htmlFor={`limit-${field.key}`}
                      className="text-xs font-medium text-muted-foreground"
                    >
                      {field.label}
                    </label>
                    <input
                      id={`limit-${field.key}`}
                      type="number"
                      className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                      value={formData.limits[field.key]}
                      onChange={(e) => handleLimitChange(field.key, e.target.value)}
                      min={field.min}
                      max={field.max}
                      disabled={isSubmitting}
                      data-testid={`limit-${field.key}-input`}
                    />
                  </div>
                ))}
              </div>
            </fieldset>

            <div className="mt-4 flex justify-end gap-2 border-t border-border pt-4">
              <button
                type="button"
                className="rounded-md border border-border bg-card px-4 py-2 text-sm font-medium text-foreground hover:bg-muted disabled:opacity-50"
                onClick={onCancel}
                disabled={isSubmitting}
                data-testid="tenant-form-cancel"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                disabled={isSubmitting}
                data-testid="tenant-form-submit"
              >
                {isSubmitting ? 'Saving...' : 'Save'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
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

  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editingTenant, setEditingTenant] = useState<Tenant | undefined>(undefined)
  const [suspendDialogOpen, setSuspendDialogOpen] = useState(false)
  const [activateDialogOpen, setActivateDialogOpen] = useState(false)
  const [targetTenant, setTargetTenant] = useState<Tenant | null>(null)
  const [inviteTenant, setInviteTenant] = useState<Tenant | null>(null)
  const [inviteEmail, setInviteEmail] = useState('')

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

  // Create mutation
  const createMutation = useMutation({
    mutationFn: (data: TenantFormData) =>
      keltaClient.admin.tenants.create({
        slug: data.slug,
        name: data.name,
        edition: data.edition,
        limits: data.limits,
        ...(data.adminEmail.trim() ? { adminEmail: data.adminEmail.trim() } : {}),
      } as CreateTenantRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tenants'] })
      showToast('Tenant created successfully.', 'success')
      handleCloseForm()
    },
    onError: (error: Error) => {
      showToast(error.message || 'Failed to create tenant.', 'error')
    },
  })

  // Update mutation
  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: TenantFormData }) =>
      keltaClient.admin.tenants.update(id, {
        name: data.name,
        edition: data.edition,
        limits: data.limits,
      } as UpdateTenantRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tenants'] })
      showToast('Tenant updated successfully.', 'success')
      handleCloseForm()
    },
    onError: (error: Error) => {
      showToast(error.message || 'Failed to update tenant.', 'error')
    },
  })

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

  const handleCreate = useCallback(() => {
    setEditingTenant(undefined)
    setIsFormOpen(true)
  }, [])

  const handleEdit = useCallback((tenant: Tenant) => {
    setEditingTenant(tenant)
    setIsFormOpen(true)
  }, [])

  const handleCloseForm = useCallback(() => {
    setIsFormOpen(false)
    setEditingTenant(undefined)
  }, [])

  const handleFormSubmit = useCallback(
    (data: TenantFormData) => {
      if (editingTenant) {
        updateMutation.mutate({ id: editingTenant.id, data })
      } else {
        createMutation.mutate(data)
      }
    },
    [editingTenant, createMutation, updateMutation]
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

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label="Loading tenants..." />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error('Failed to load tenants.')}
          onRetry={() => refetch()}
        />
      </div>
    )
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      <header className="flex items-center justify-between">
        <h1 className="m-0 text-2xl font-semibold text-foreground">Tenants</h1>
        <button
          type="button"
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={handleCreate}
          aria-label="Create Tenant"
          data-testid="create-tenant-button"
        >
          Create Tenant
        </button>
      </header>

      {tenants.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card p-16 text-center text-muted-foreground"
          data-testid="empty-state"
        >
          <p>No tenants found.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table
            className="w-full border-collapse text-sm"
            role="grid"
            aria-label="Tenants"
            data-testid="tenants-table"
          >
            <thead>
              <tr role="row" className="bg-muted">
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Slug
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Name
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Edition
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Status
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Created
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {tenants.map((tenant: Tenant, index: number) => (
                <tr
                  key={tenant.id}
                  role="row"
                  className="border-b border-border transition-colors last:border-b-0 hover:bg-muted/50"
                  data-testid={`tenant-row-${index}`}
                >
                  <td role="gridcell" className="px-4 py-3 font-medium font-mono text-foreground">
                    {tenant.slug}
                  </td>
                  <td role="gridcell" className="px-4 py-3 text-foreground">
                    {tenant.name}
                  </td>
                  <td role="gridcell" className="px-4 py-3 text-foreground">
                    {tenant.edition}
                  </td>
                  <td role="gridcell" className="px-4 py-3">
                    <StatusBadge status={tenant.status} />
                  </td>
                  <td role="gridcell" className="whitespace-nowrap px-4 py-3 text-muted-foreground">
                    {formatDate(new Date(tenant.createdAt), {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    })}
                  </td>
                  <td role="gridcell" className="w-[1%] whitespace-nowrap px-4 py-3">
                    <div className="flex gap-2">
                      <button
                        type="button"
                        className="rounded border border-border px-2 py-1 text-xs font-medium text-primary hover:border-primary hover:bg-muted"
                        onClick={() => handleEdit(tenant)}
                        aria-label={`Edit ${tenant.name}`}
                        data-testid={`edit-button-${index}`}
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        className="rounded border border-border px-2 py-1 text-xs font-medium text-primary hover:border-primary hover:bg-muted"
                        onClick={() => handleInviteClick(tenant)}
                        aria-label={`Invite admin for ${tenant.name}`}
                        data-testid={`invite-admin-button-${index}`}
                      >
                        Invite admin
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
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {isFormOpen && (
        <TenantForm
          tenant={editingTenant}
          onSubmit={handleFormSubmit}
          onCancel={handleCloseForm}
          isSubmitting={isSubmitting}
        />
      )}

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
    </div>
  )
}

export default TenantsPage
