import { useMemo, useState, useCallback } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { z } from 'zod'
import { useApi } from '../../context/ApiContext'
import type {
  CreatePlatformUserRequest,
  DelegatedNamedRef,
  DelegatedUpdateUserRequest,
} from '@kelta/sdk'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { useDelegatedAdmin } from '../../hooks/useDelegatedAdmin'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useCrudMutations,
} from '../../components/AdminCrud'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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

/** Delegated (scoped) edit: the whitelisted fields, none required. */
const delegatedEditSchema = z.object({
  firstName: z.string(),
  lastName: z.string(),
  username: z.string(),
  profileId: z.string(),
})

type DelegatedEditFormData = z.infer<typeof delegatedEditSchema>

const portalInviteSchema = z.object({
  email: createUserSchema.shape.email,
  firstName: z.string(),
  lastName: z.string(),
})

type PortalInviteFormData = z.infer<typeof portalInviteSchema>

const PORTAL_INVITE_DEFAULTS: PortalInviteFormData = { email: '', firstName: '', lastName: '' }

type StatusAction = 'deactivate' | 'activate'

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

export function UsersPage({ testId = 'users-page' }: UsersPageProps) {
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { keltaClient } = useApi()
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
  const [portalInviteOpen, setPortalInviteOpen] = useState(false)
  const [statusTarget, setStatusTarget] = useState<{
    user: PlatformUser
    action: StatusAction
  } | null>(null)
  const [filter, setFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [page, setPage] = useState(0)

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

  const crudMessages = {
    created: t('users.createSuccess'),
    updated: t('users.updateSuccess'),
    deleted: '',
    error: t('errors.generic'),
  }

  // Create only — edits go to UserDetailPage (full admin) or the delegated edit
  // dialog (scoped), and users are deactivated rather than deleted.
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
    messages: crudMessages,
  })

  const { updateMutation: delegatedEditMutation } = useCrudMutations<
    PlatformUser,
    DelegatedEditFormData
  >({
    queryKey: ['users'],
    update: (user, values) => {
      const update: DelegatedUpdateUserRequest = {
        firstName: values.firstName,
        lastName: values.lastName,
        username: values.username || undefined,
      }
      if (values.profileId && values.profileId !== user.profileId) {
        update.profileId = values.profileId
      }
      return keltaClient.admin.delegated.users.update(user.id, update)
    },
    messages: crudMessages,
  })

  const { createMutation: portalInviteMutation } = useCrudMutations<
    PlatformUser,
    PortalInviteFormData
  >({
    queryKey: ['users'],
    create: (values) =>
      keltaClient.admin.users.invitePortal({
        email: values.email,
        firstName: values.firstName || undefined,
        lastName: values.lastName || undefined,
      }),
    messages: {
      created: t('users.portalInviteSuccess'),
      updated: '',
      deleted: '',
      error: t('errors.generic'),
    },
  })

  const statusMutation = useMutation({
    mutationFn: async ({ userId, action }: { userId: string; action: StatusAction }) => {
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
    onSuccess: (_, { action }) => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      toast.success(
        action === 'deactivate' ? t('users.deactivateSuccess') : t('users.activateSuccess')
      )
      setStatusTarget(null)
    },
    onError: (err: Error) => {
      toast.error(err.message || t('errors.generic'))
    },
  })

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
            type="button"
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

  return (
    <AdminCrudPage
      testId={testId}
      title={t('users.title')}
      titleAdornment={
        scoped && (
          <span
            data-testid="delegated-scope-badge"
            className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-950 dark:text-amber-300"
          >
            {t('users.delegatedScope')}
          </span>
        )
      }
      actions={
        <div className="flex gap-2">
          {!scoped && (
            <Button
              type="button"
              variant="outline"
              onClick={() => setPortalInviteOpen(true)}
              data-testid="invite-portal-user"
            >
              {t('users.invitePortal')}
            </Button>
          )}
          {canCreate && (
            <Button
              type="button"
              onClick={() => setIsFormOpen(true)}
              data-testid="create-user-button"
            >
              {t('users.createUser')}
            </Button>
          )}
        </div>
      }
      // Server-side search/filter: the toolbar stays mounted while each query loads.
      toolbar={
        <div className="flex gap-4">
          <Input
            type="text"
            placeholder={t('users.searchPlaceholder')}
            aria-label={t('users.searchPlaceholder')}
            value={filter}
            onChange={(e) => {
              setFilter(e.target.value)
              setPage(0)
            }}
            className="flex-1"
          />
          <select
            aria-label={t('users.status')}
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value)
              setPage(0)
            }}
            className="h-9 min-w-[140px] rounded-md border border-input bg-transparent px-3 py-1 text-sm"
          >
            <option value="">{t('users.allStatuses')}</option>
            <option value="ACTIVE">{t('users.statusActive')}</option>
            <option value="INACTIVE">{t('users.statusInactive')}</option>
            <option value="LOCKED">{t('users.statusLocked')}</option>
            <option value="PENDING_ACTIVATION">Pending Activation</option>
          </select>
        </div>
      }
      isLoading={isLoading}
      error={error}
      loadErrorMessage={t('errors.generic')}
      onRetry={() => refetch()}
      isEmpty={users.length === 0}
      emptyMessage={t('common.noResults')}
      list={
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
                <Button type="button" variant="outline" size="sm" onClick={() => openUser(user)}>
                  {t('common.edit')}
                </Button>
                {canToggleStatus &&
                  (user.status === 'ACTIVE' ? (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="border-destructive/30 text-destructive hover:bg-destructive/10"
                      onClick={() => setStatusTarget({ user, action: 'deactivate' })}
                    >
                      {t('users.deactivate')}
                    </Button>
                  ) : (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setStatusTarget({ user, action: 'activate' })}
                    >
                      {t('users.activate')}
                    </Button>
                  ))}
              </div>
            )}
          />

          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-4 border-t border-border pt-4">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                {t('common.previous')}
              </Button>
              <span className="text-sm">
                {t('common.pageOf', { current: page + 1, total: totalPages })}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                {t('common.next')}
              </Button>
            </div>
          )}
        </>
      }
    >
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

      {/* Delegated (scoped) edit — whitelisted fields limited to the caller's
          manageable profiles. Full admins edit on UserDetailPage instead. */}
      <CrudFormDialog<DelegatedEditFormData>
        open={scoped && editUser !== null}
        onClose={() => setEditUser(null)}
        title={t('users.editUser')}
        description={editUser?.email}
        schema={delegatedEditSchema}
        defaultValues={{
          firstName: editUser?.firstName ?? '',
          lastName: editUser?.lastName ?? '',
          username: editUser?.username ?? '',
          profileId: editUser?.profileId ?? '',
        }}
        onSubmit={(values) => {
          if (editUser) {
            delegatedEditMutation.mutate(
              { row: editUser, values },
              { onSuccess: () => setEditUser(null) }
            )
          }
        }}
        isSubmitting={delegatedEditMutation.isPending}
        testIdPrefix="delegated-edit"
      >
        <CrudTextField<DelegatedEditFormData>
          name="firstName"
          id="edit-firstName"
          label={t('users.firstName')}
        />
        <CrudTextField<DelegatedEditFormData>
          name="lastName"
          id="edit-lastName"
          label={t('users.lastName')}
        />
        <CrudTextField<DelegatedEditFormData>
          name="username"
          id="edit-username"
          label={t('users.username')}
        />
        <CrudSelectField<DelegatedEditFormData>
          name="profileId"
          id="edit-profile"
          label={t('users.profile')}
          options={(summary?.manageableProfiles ?? []).map((p) => ({
            value: p.id,
            label: p.name,
          }))}
          testId="edit-profile-select"
        />
      </CrudFormDialog>

      <CrudFormDialog<PortalInviteFormData>
        open={portalInviteOpen}
        onClose={() => setPortalInviteOpen(false)}
        title={t('users.invitePortal')}
        description={t('users.portalInviteHint')}
        schema={portalInviteSchema}
        defaultValues={PORTAL_INVITE_DEFAULTS}
        onSubmit={(values) =>
          portalInviteMutation.mutate(values, { onSuccess: () => setPortalInviteOpen(false) })
        }
        isSubmitting={portalInviteMutation.isPending}
        testIdPrefix="portal-invite"
        submitLabel={t('users.sendPortalInvite')}
      >
        <CrudTextField<PortalInviteFormData>
          name="email"
          id="portal-email"
          label={t('users.email')}
          type="email"
          required
        />
        <div className="grid grid-cols-2 gap-3">
          <CrudTextField<PortalInviteFormData>
            name="firstName"
            id="portal-first-name"
            label={t('users.firstName')}
          />
          <CrudTextField<PortalInviteFormData>
            name="lastName"
            id="portal-last-name"
            label={t('users.lastName')}
          />
        </div>
      </CrudFormDialog>

      <ConfirmDialog
        open={statusTarget !== null}
        title={t('common.confirm')}
        message={
          statusTarget
            ? t(
                statusTarget.action === 'deactivate'
                  ? 'users.deactivateConfirm'
                  : 'users.activateConfirm',
                { name: `${statusTarget.user.firstName} ${statusTarget.user.lastName}` }
              )
            : ''
        }
        confirmLabel={t('common.confirm')}
        cancelLabel={t('common.cancel')}
        variant={statusTarget?.action === 'deactivate' ? 'danger' : 'default'}
        onConfirm={() => {
          if (statusTarget) {
            statusMutation.mutate({ userId: statusTarget.user.id, action: statusTarget.action })
          }
        }}
        onCancel={() => setStatusTarget(null)}
      />
    </AdminCrudPage>
  )
}
