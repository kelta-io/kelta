import { useMemo, useState, useCallback } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { useApi } from '../../context/ApiContext'
import type {
  CreatePlatformUserRequest,
  DelegatedNamedRef,
  DelegatedUpdateUserRequest,
} from '@kelta/sdk'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useToast } from '../../components/Toast'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { useDelegatedAdmin } from '../../hooks/useDelegatedAdmin'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useCrudMutations,
} from '../../components/AdminCrud'
import { cn } from '@/lib/utils'

interface PlatformUser {
  id: string
  email: string
  firstName: string
  lastName: string
  username?: string
  status: 'ACTIVE' | 'INACTIVE' | 'LOCKED' | 'PENDING_ACTIVATION'
  userType?: 'INTERNAL' | 'PORTAL'
  locale: string
  timezone: string
  profileId?: string
  managerId?: string
  lastLoginAt?: string
  loginCount: number
  mfaEnabled: boolean
  createdAt: string
  updatedAt: string
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const createUserSchema = z.object({
  email: z
    .string()
    .refine((v) => v.trim().length > 0, 'Email is required')
    .refine((v) => !v.trim() || EMAIL_PATTERN.test(v), 'Invalid email'),
  firstName: z.string().refine((v) => v.trim().length > 0, 'First name is required'),
  lastName: z.string().refine((v) => v.trim().length > 0, 'Last name is required'),
  username: z.string(),
  // Always required — the worker rejects a create without a profileId.
  profileId: z.string().refine((v) => v.length > 0, 'Profile is required'),
})

type CreateUserFormData = z.infer<typeof createUserSchema>

const CREATE_USER_DEFAULTS: CreateUserFormData = {
  email: '',
  firstName: '',
  lastName: '',
  username: '',
  profileId: '',
}

export interface UsersPageProps {
  testId?: string
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span
      className={cn(
        'inline-block rounded-full px-2 py-0.5 text-xs font-medium',
        status === 'ACTIVE' &&
          'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
        status === 'INACTIVE' && 'bg-muted text-foreground',
        status === 'LOCKED' && 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
        status === 'PENDING_ACTIVATION' &&
          'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
      )}
    >
      {status}
    </span>
  )
}

/**
 * Compact edit dialog for delegated (scoped) mode — whitelisted fields limited
 * to the caller's manageable profiles. Full admins keep the UserDetailPage
 * instead.
 */
function DelegatedEditDialog({
  user,
  profileOptions,
  onClose,
  onSaved,
}: {
  user: PlatformUser
  profileOptions: DelegatedNamedRef[]
  onClose: () => void
  onSaved: () => void
}) {
  const { t } = useI18n()
  const { keltaClient } = useApi()
  const { showToast } = useToast()
  const [form, setForm] = useState({
    firstName: user.firstName ?? '',
    lastName: user.lastName ?? '',
    username: user.username ?? '',
    profileId: user.profileId ?? '',
  })
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    try {
      const update: DelegatedUpdateUserRequest = {
        firstName: form.firstName,
        lastName: form.lastName,
        username: form.username || undefined,
      }
      if (form.profileId && form.profileId !== user.profileId) {
        update.profileId = form.profileId
      }
      await keltaClient.admin.delegated.users.update(user.id, update)
      showToast(t('users.updateSuccess'), 'success')
      onSaved()
    } catch (err) {
      showToast((err as Error).message || t('errors.generic'), 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={(e) => e.target === e.currentTarget && onClose()}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
      role="presentation"
    >
      <div
        className="w-full max-w-[480px] rounded-lg bg-card p-6 shadow-xl"
        role="dialog"
        aria-modal="true"
        data-testid="delegated-edit-dialog"
      >
        <h2 className="mb-1 text-xl font-semibold">{t('users.editUser')}</h2>
        <p className="mb-4 text-sm text-muted-foreground">{user.email}</p>
        <div className="mb-4">
          <label
            htmlFor="edit-firstName"
            className="mb-1 block text-sm font-medium text-foreground"
          >
            {t('users.firstName')}
          </label>
          <input
            id="edit-firstName"
            type="text"
            value={form.firstName}
            onChange={(e) => setForm({ ...form, firstName: e.target.value })}
            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          />
        </div>
        <div className="mb-4">
          <label htmlFor="edit-lastName" className="mb-1 block text-sm font-medium text-foreground">
            {t('users.lastName')}
          </label>
          <input
            id="edit-lastName"
            type="text"
            value={form.lastName}
            onChange={(e) => setForm({ ...form, lastName: e.target.value })}
            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          />
        </div>
        <div className="mb-4">
          <label htmlFor="edit-username" className="mb-1 block text-sm font-medium text-foreground">
            {t('users.username')}
          </label>
          <input
            id="edit-username"
            type="text"
            value={form.username}
            onChange={(e) => setForm({ ...form, username: e.target.value })}
            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          />
        </div>
        <div className="mb-4">
          <label htmlFor="edit-profile" className="mb-1 block text-sm font-medium text-foreground">
            {t('users.profile')}
          </label>
          <select
            id="edit-profile"
            data-testid="edit-profile-select"
            value={form.profileId}
            onChange={(e) => setForm({ ...form, profileId: e.target.value })}
            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          >
            {profileOptions.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            className="cursor-pointer rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
            onClick={onClose}
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:cursor-not-allowed disabled:opacity-50"
            onClick={handleSave}
            disabled={saving}
          >
            {saving ? t('common.saving') : t('common.save')}
          </button>
        </div>
      </div>
    </div>
  )
}

export function UsersPage({ testId = 'users-page' }: UsersPageProps) {
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { keltaClient } = useApi()
  const { showToast } = useToast()
  const navigate = useNavigate()
  const { hasPermission } = useSystemPermissions()
  const { summary, isDelegated } = useDelegatedAdmin()

  // Delegated scoped mode: caller lacks MANAGE_USERS but is listed in an active
  // delegated-admin scope. All data flows through /api/admin/delegated/* then.
  const scoped = !hasPermission('MANAGE_USERS') && isDelegated

  // The create form always requires a profile (the worker rejects a create
  // without a profileId): scoped mode offers the caller's manageable subset,
  // full-admin mode offers the tenant's profiles.
  const { data: allProfiles } = useQuery({
    queryKey: ['profiles'],
    queryFn: () =>
      keltaClient.admin.profiles.list() as unknown as Promise<{ id: string; name: string }[]>,
    enabled: !scoped,
  })
  const createProfileOptions: DelegatedNamedRef[] = scoped
    ? (summary?.manageableProfiles ?? [])
    : (allProfiles ?? []).map((p) => ({ id: p.id, name: p.name }))

  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editUser, setEditUser] = useState<PlatformUser | null>(null)
  const [filter, setFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [page, setPage] = useState(0)

  const [confirmDialog, setConfirmDialog] = useState<{
    open: boolean
    userId: string
    action: 'deactivate' | 'activate'
    userName: string
  }>({ open: false, userId: '', action: 'deactivate', userName: '' })

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['users', scoped, filter, statusFilter, page],
    queryFn: async () => {
      if (scoped) {
        const list = (await keltaClient.admin.delegated.users.list(1, 200)) as PlatformUser[]
        return { content: list, totalPages: 1 }
      }
      return keltaClient.admin.users.list(filter || undefined, statusFilter || undefined, page, 20)
    },
  })

  const [portalInviteOpen, setPortalInviteOpen] = useState(false)
  const [portalInvite, setPortalInvite] = useState({ email: '', firstName: '', lastName: '' })

  const portalInviteMutation = useMutation({
    mutationFn: () =>
      keltaClient.admin.users.invitePortal({
        email: portalInvite.email,
        firstName: portalInvite.firstName || undefined,
        lastName: portalInvite.lastName || undefined,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      showToast(t('users.portalInviteSuccess'), 'success')
      setPortalInviteOpen(false)
      setPortalInvite({ email: '', firstName: '', lastName: '' })
    },
    onError: (err: Error) => {
      showToast(err.message || t('errors.generic'), 'error')
    },
  })

  // Create only — edits go to UserDetailPage (full admin) or DelegatedEditDialog
  // (scoped), and users are deactivated rather than deleted.
  const { createMutation } = useCrudMutations<PlatformUser, CreateUserFormData>({
    queryKey: ['users'],
    create: (formData) =>
      scoped
        ? keltaClient.admin.delegated.users.create({
            email: formData.email,
            firstName: formData.firstName,
            lastName: formData.lastName,
            username: formData.username || undefined,
            profileId: formData.profileId ?? '',
          })
        : keltaClient.admin.users.create(formData as CreatePlatformUserRequest),
    messages: {
      created: t('users.createSuccess'),
      updated: t('users.updateSuccess'),
      deleted: '',
      error: t('errors.generic'),
    },
  })

  const statusMutation = useMutation({
    mutationFn: async ({
      userId,
      action,
    }: {
      userId: string
      action: 'deactivate' | 'activate'
    }) => {
      const status = action === 'activate' ? 'ACTIVE' : 'INACTIVE'
      if (scoped) {
        await keltaClient.admin.delegated.users.update(userId, { status })
        return
      }
      if (action === 'activate') {
        await keltaClient.admin.users.activate(userId)
      } else {
        await keltaClient.admin.users.deactivate(userId)
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      showToast(
        confirmDialog.action === 'deactivate'
          ? t('users.deactivateSuccess')
          : t('users.activateSuccess'),
        'success'
      )
      setConfirmDialog({ open: false, userId: '', action: 'deactivate', userName: '' })
    },
    onError: (err: Error) => {
      showToast(err.message || t('errors.generic'), 'error')
    },
  })

  const handleStatusAction = useCallback(
    (user: PlatformUser, action: 'deactivate' | 'activate') => {
      setConfirmDialog({
        open: true,
        userId: user.id,
        action,
        userName: `${user.firstName} ${user.lastName}`,
      })
    },
    []
  )

  const confirmAction = useCallback(() => {
    statusMutation.mutate({
      userId: confirmDialog.userId,
      action: confirmDialog.action,
    })
  }, [confirmDialog, statusMutation])

  // The delegated endpoint is already server-filtered by scope; search/status narrow client-side.
  const users = useMemo(() => {
    const rawUsers = (data?.content ?? []) as PlatformUser[]
    if (!scoped) return rawUsers
    const search = filter.trim().toLowerCase()
    return rawUsers.filter((u) => {
      if (statusFilter && u.status !== statusFilter) return false
      if (!search) return true
      return [u.email, u.firstName, u.lastName, u.username]
        .filter(Boolean)
        .some((v) => (v as string).toLowerCase().includes(search))
    })
  }, [scoped, data, filter, statusFilter])
  const totalPages = scoped ? 1 : (data?.totalPages ?? 0)

  const canCreate = !scoped || summary?.canCreateUsers === true
  const canToggleStatus = !scoped || summary?.canDeactivateUsers === true

  // Scoped admins edit in the compact delegated dialog; full admins open the detail page.
  const openUser = useCallback(
    (user: PlatformUser) =>
      scoped ? setEditUser(user) : navigate(`/${getTenantSlug()}/users/${user.id}`),
    [scoped, navigate]
  )

  // Server-paginated and server-filtered, so the grid neither sorts nor filters
  // the current page client-side.
  const columns = useMemo<AdminColumn<PlatformUser>[]>(
    () => [
      {
        id: 'name',
        header: t('users.name'),
        sortable: false,
        accessor: (user) => `${user.firstName} ${user.lastName}`,
        cell: (user) => (
          <button
            className="cursor-pointer border-none bg-transparent p-0 text-left font-medium text-primary hover:underline"
            onClick={() => openUser(user)}
          >
            {user.firstName} {user.lastName}
          </button>
        ),
      },
      { id: 'email', header: t('users.email'), sortable: false },
      {
        id: 'username',
        header: t('users.username'),
        sortable: false,
        cell: (user) => user.username || '—',
      },
      {
        id: 'status',
        header: t('users.status'),
        sortable: false,
        cell: (user) => <StatusBadge status={user.status} />,
      },
      {
        id: 'userType',
        header: t('users.type'),
        sortable: false,
        cell: (user) => (
          <span
            className={cn(
              'inline-block rounded-full px-2 py-0.5 text-xs font-medium',
              user.userType === 'PORTAL'
                ? 'bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300'
                : 'bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300'
            )}
          >
            {user.userType === 'PORTAL' ? t('users.typePortal') : t('users.typeInternal')}
          </span>
        ),
      },
      {
        id: 'lastLoginAt',
        header: t('users.lastLogin'),
        sortable: false,
        cell: (user) =>
          user.lastLoginAt ? formatDate(new Date(user.lastLoginAt)) : t('users.never'),
      },
    ],
    [t, formatDate, openUser]
  )

  if (error) {
    return (
      <div className="mx-auto max-w-[1200px] p-6" data-testid={testId}>
        <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
          <p>{t('errors.generic')}</p>
          <button
            onClick={() => refetch()}
            className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
          >
            {t('common.retry')}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-[1200px] p-6" data-testid={testId}>
      <header className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="m-0 text-2xl font-semibold">{t('users.title')}</h1>
          {scoped && (
            <span
              data-testid="delegated-scope-badge"
              className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-950 dark:text-amber-300"
            >
              {t('users.delegatedScope')}
            </span>
          )}
        </div>
        <div className="flex gap-2">
          {!scoped && (
            <button
              data-testid="invite-portal-user"
              className="cursor-pointer rounded-md border border-border bg-card px-4 py-2 text-sm font-medium text-foreground hover:bg-muted"
              onClick={() => setPortalInviteOpen(true)}
            >
              {t('users.invitePortal')}
            </button>
          )}
          {canCreate && (
            <button
              className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
              onClick={() => setIsFormOpen(true)}
            >
              {t('users.createUser')}
            </button>
          )}
        </div>
      </header>

      <div className="mb-4 flex gap-4">
        <input
          type="text"
          placeholder={t('users.searchPlaceholder')}
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value)
            setPage(0)
          }}
          className="flex-1 rounded-md border border-border bg-background px-3 py-2 text-sm"
        />
        <select
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value)
            setPage(0)
          }}
          className="min-w-[140px] rounded-md border border-border bg-background px-3 py-2 text-sm"
        >
          <option value="">{t('users.allStatuses')}</option>
          <option value="ACTIVE">{t('users.statusActive')}</option>
          <option value="INACTIVE">{t('users.statusInactive')}</option>
          <option value="LOCKED">{t('users.statusLocked')}</option>
          <option value="PENDING_ACTIVATION">Pending Activation</option>
        </select>
      </div>

      {isLoading ? (
        <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
          {t('common.loading')}
        </div>
      ) : users.length === 0 ? (
        <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
          <p>{t('common.noResults')}</p>
        </div>
      ) : (
        <>
          <AdminDataTable
            tableId="users"
            testId="users-table"
            ariaLabel={t('users.title')}
            actionsHeader={t('common.actions')}
            filterable={false}
            columns={columns}
            rows={users}
            rowKey={(user) => user.id}
            renderActions={(user) => (
              <div className="flex justify-end gap-2">
                <button
                  className="cursor-pointer rounded border border-border bg-card px-2 py-1 text-xs hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                  onClick={() => openUser(user)}
                >
                  {t('common.edit')}
                </button>
                {canToggleStatus &&
                  (user.status === 'ACTIVE' ? (
                    <button
                      className="cursor-pointer rounded border-none bg-destructive px-2 py-1 text-xs text-destructive-foreground hover:bg-destructive/90"
                      onClick={() => handleStatusAction(user, 'deactivate')}
                    >
                      {t('users.deactivate')}
                    </button>
                  ) : (
                    <button
                      className="cursor-pointer rounded border-none bg-emerald-500 px-2 py-1 text-xs text-white hover:bg-emerald-600"
                      onClick={() => handleStatusAction(user, 'activate')}
                    >
                      {t('users.activate')}
                    </button>
                  ))}
              </div>
            )}
          />

          {totalPages > 1 && (
            <div className="mt-4 flex items-center justify-center gap-4 border-t border-border pt-4">
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="cursor-pointer rounded border border-border bg-card px-2 py-1 text-xs hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
              >
                {t('common.previous')}
              </button>
              <span className="text-sm">
                {t('common.pageOf', { current: page + 1, total: totalPages })}
              </span>
              <button
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
                className="cursor-pointer rounded border border-border bg-card px-2 py-1 text-xs hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
              >
                {t('common.next')}
              </button>
            </div>
          )}
        </>
      )}

      <CrudFormDialog<CreateUserFormData>
        open={isFormOpen}
        onClose={() => setIsFormOpen(false)}
        title={t('users.createUser')}
        schema={createUserSchema}
        defaultValues={CREATE_USER_DEFAULTS}
        onSubmit={(formData) =>
          createMutation.mutate(formData, { onSuccess: () => setIsFormOpen(false) })
        }
        isSubmitting={createMutation.isPending}
        testIdPrefix="create-user"
        submitLabel={t('common.create')}
      >
        <CrudTextField<CreateUserFormData>
          name="email"
          id="email"
          label={t('users.email')}
          type="email"
          required
        />
        <CrudTextField<CreateUserFormData>
          name="firstName"
          id="firstName"
          label={t('users.firstName')}
          required
        />
        <CrudTextField<CreateUserFormData>
          name="lastName"
          id="lastName"
          label={t('users.lastName')}
          required
        />
        <CrudTextField<CreateUserFormData>
          name="username"
          id="username"
          label={t('users.username')}
        />
        {/* Delegated scoped mode offers the caller's manageable subset;
            full-admin mode offers the tenant's profiles. */}
        <CrudSelectField<CreateUserFormData>
          name="profileId"
          id="profileId"
          label={t('users.profile')}
          required
          placeholder={t('common.select')}
          options={createProfileOptions.map((p) => ({ value: p.id, label: p.name }))}
          testId="create-profile-select"
        />
      </CrudFormDialog>

      {editUser && scoped && (
        <DelegatedEditDialog
          user={editUser}
          profileOptions={summary?.manageableProfiles ?? []}
          onClose={() => setEditUser(null)}
          onSaved={() => {
            setEditUser(null)
            queryClient.invalidateQueries({ queryKey: ['users'] })
          }}
        />
      )}

      {portalInviteOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={(e) => {
            if (e.target === e.currentTarget) setPortalInviteOpen(false)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') setPortalInviteOpen(false)
          }}
          role="presentation"
        >
          <div
            className="w-full max-w-[480px] rounded-lg bg-card p-6 shadow-xl"
            role="dialog"
            aria-modal="true"
            data-testid="portal-invite-dialog"
          >
            <h2 className="mb-2 text-xl font-semibold">{t('users.invitePortal')}</h2>
            <p className="mb-4 text-sm text-muted-foreground">{t('users.portalInviteHint')}</p>
            <form
              onSubmit={(e) => {
                e.preventDefault()
                portalInviteMutation.mutate()
              }}
            >
              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium" htmlFor="portal-email">
                  {t('users.email')}
                </label>
                <input
                  id="portal-email"
                  type="email"
                  required
                  value={portalInvite.email}
                  onChange={(e) => setPortalInvite({ ...portalInvite, email: e.target.value })}
                  className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                />
              </div>
              <div className="mb-3 flex gap-3">
                <div className="flex-1">
                  <label className="mb-1 block text-sm font-medium" htmlFor="portal-first-name">
                    {t('users.firstName')}
                  </label>
                  <input
                    id="portal-first-name"
                    type="text"
                    value={portalInvite.firstName}
                    onChange={(e) =>
                      setPortalInvite({ ...portalInvite, firstName: e.target.value })
                    }
                    className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                  />
                </div>
                <div className="flex-1">
                  <label className="mb-1 block text-sm font-medium" htmlFor="portal-last-name">
                    {t('users.lastName')}
                  </label>
                  <input
                    id="portal-last-name"
                    type="text"
                    value={portalInvite.lastName}
                    onChange={(e) => setPortalInvite({ ...portalInvite, lastName: e.target.value })}
                    className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                  />
                </div>
              </div>
              <div className="mt-6 flex justify-end gap-2">
                <button
                  type="button"
                  className="cursor-pointer rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
                  onClick={() => setPortalInviteOpen(false)}
                >
                  {t('common.cancel')}
                </button>
                <button
                  type="submit"
                  className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                  disabled={portalInviteMutation.isPending}
                >
                  {portalInviteMutation.isPending
                    ? t('common.saving')
                    : t('users.sendPortalInvite')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {confirmDialog.open && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={(e) => {
            if (e.target === e.currentTarget) setConfirmDialog({ ...confirmDialog, open: false })
          }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') setConfirmDialog({ ...confirmDialog, open: false })
          }}
          role="presentation"
        >
          <div
            className="w-full max-w-[480px] rounded-lg bg-card p-6 shadow-xl"
            role="dialog"
            aria-modal="true"
          >
            <h2 className="mb-4 text-xl font-semibold">{t('common.confirm')}</h2>
            <p>
              {confirmDialog.action === 'deactivate'
                ? t('users.deactivateConfirm', { name: confirmDialog.userName })
                : t('users.activateConfirm', { name: confirmDialog.userName })}
            </p>
            <div className="mt-6 flex justify-end gap-2">
              <button
                className="cursor-pointer rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
                onClick={() => setConfirmDialog({ ...confirmDialog, open: false })}
              >
                {t('common.cancel')}
              </button>
              <button
                className={cn(
                  'cursor-pointer rounded-md border-none px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50',
                  confirmDialog.action === 'deactivate'
                    ? 'bg-destructive hover:bg-destructive/90'
                    : 'bg-primary hover:bg-primary/90'
                )}
                onClick={confirmAction}
                disabled={statusMutation.isPending}
              >
                {statusMutation.isPending ? t('common.saving') : t('common.confirm')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
