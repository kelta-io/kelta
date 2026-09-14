import { useState, useCallback, useMemo, useRef, useEffect, type FormEvent } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams, Link } from 'react-router-dom'
import { useApi } from '../../context/ApiContext'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useToast } from '../../components/Toast'
import { ConfirmDialog } from '../../components'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { cn } from '@/lib/utils'

interface MintTokenFormData {
  name: string
  expiresInDays: number
}

interface PlatformUser {
  id: string
  email: string
  firstName: string
  lastName: string
  username?: string
  status: 'ACTIVE' | 'INACTIVE' | 'LOCKED' | 'PENDING_ACTIVATION'
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

interface ProfileSummary {
  id: string
  name: string
  description: string | null
  system: boolean
}

interface LoginHistoryEntry {
  id: string
  userId: string
  loginTime: string
  sourceIp: string
  loginType: 'UI' | 'API' | 'OAUTH' | 'SERVICE_ACCOUNT'
  status: 'SUCCESS' | 'FAILED' | 'LOCKED_OUT'
  userAgent: string
}

interface UpdateFormData {
  firstName: string
  lastName: string
  username: string
  locale: string
  timezone: string
}

export interface UserDetailPageProps {
  testId?: string
}

function StatusBadge({ status }: { status: string }) {
  const colorMap: Record<string, string> = {
    ACTIVE: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
    INACTIVE: 'bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-300',
    LOCKED: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
    PENDING_ACTIVATION: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
  }

  return (
    <span
      className={cn(
        'inline-block rounded-full px-2 py-0.5 text-xs font-medium',
        colorMap[status] || ''
      )}
    >
      {status}
    </span>
  )
}

function LoginStatusLabel({ status }: { status: string }) {
  const colorMap: Record<string, string> = {
    SUCCESS: 'font-medium text-emerald-700 dark:text-emerald-300',
    FAILED: 'font-medium text-red-700 dark:text-red-300',
    LOCKED_OUT: 'font-medium text-amber-700 dark:text-amber-300',
  }

  return <span className={colorMap[status] || ''}>{status}</span>
}

export function UserDetailPage({ testId = 'user-detail-page' }: UserDetailPageProps) {
  const { id } = useParams<{ id: string }>()
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { apiClient, keltaClient } = useApi()
  const { showToast } = useToast()
  const { hasPermission } = useSystemPermissions()
  const navigate = useNavigate()
  const tenantSlug = getTenantSlug()

  const [activeTab, setActiveTab] = useState<'details' | 'security' | 'loginHistory'>('details')
  const [isEditing, setIsEditing] = useState(false)
  const [formData, setFormData] = useState<UpdateFormData>({
    firstName: '',
    lastName: '',
    username: '',
    locale: '',
    timezone: '',
  })
  const [historyPage, setHistoryPage] = useState(0)
  const [showMfaResetConfirm, setShowMfaResetConfirm] = useState(false)
  const [showPasswordResetConfirm, setShowPasswordResetConfirm] = useState(false)
  const [showMintTokenForm, setShowMintTokenForm] = useState(false)
  const [mintedToken, setMintedToken] = useState<string | null>(null)
  const {
    data: user,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['users', id],
    queryFn: async () => {
      const result = await apiClient.getOne<PlatformUser>(`/api/users/${id}`)
      setFormData({
        firstName: result.firstName,
        lastName: result.lastName,
        username: result.username || '',
        locale: result.locale,
        timezone: result.timezone,
      })
      return result
    },
    enabled: !!id,
  })

  const { data: loginHistory, isLoading: historyLoading } = useQuery({
    queryKey: ['users', id, 'login-history', historyPage],
    queryFn: async () => {
      const params = new URLSearchParams()
      params.append('page[number]', historyPage.toString())
      params.append('page[size]', '20')
      return apiClient.getPage<LoginHistoryEntry>(
        `/api/login-history?filter[userId][eq]=${id}&${params}`
      )
    },
    enabled: !!id && activeTab === 'loginHistory',
  })

  // Security tab data
  const { data: profiles } = useQuery({
    queryKey: ['profiles'],
    queryFn: () => apiClient.getList<ProfileSummary>('/api/profiles'),
    enabled: activeTab === 'security',
  })

  const profileId = user?.profileId
  const currentProfile = useMemo(() => {
    if (!profiles || !profileId) return null
    return profiles.find((p) => p.id === profileId) ?? null
  }, [profiles, profileId])

  const updateMutation = useMutation({
    mutationFn: (data: UpdateFormData) =>
      apiClient.putResource<PlatformUser>(`/api/users/${id}`, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users', id] })
      queryClient.invalidateQueries({ queryKey: ['users'] })
      showToast(t('users.updateSuccess'), 'success')
      setIsEditing(false)
    },
    onError: (err: Error) => {
      showToast(err.message || t('errors.generic'), 'error')
    },
  })

  const statusMutation = useMutation({
    mutationFn: (action: 'deactivate' | 'activate') =>
      apiClient.patchResource(`/api/users/${id}`, {
        status: action === 'activate' ? 'ACTIVE' : 'INACTIVE',
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users', id] })
      queryClient.invalidateQueries({ queryKey: ['users'] })
      showToast(t('users.statusUpdateSuccess'), 'success')
    },
    onError: (err: Error) => {
      showToast(err.message || t('errors.generic'), 'error')
    },
  })

  const changeProfileMutation = useMutation({
    mutationFn: (profileId: string) =>
      apiClient.putResource<PlatformUser>(`/api/users/${id}`, { profileId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users', id] })
      showToast('Profile updated successfully', 'success')
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to update profile', 'error')
    },
  })

  // MFA status query
  const { data: mfaStatus } = useQuery({
    queryKey: ['users', id, 'mfa-status'],
    queryFn: () => keltaClient.admin.mfa.getUserStatus(id!),
    enabled: !!id && activeTab === 'security' && !!user?.mfaEnabled,
  })

  // MFA reset mutation
  const resetMfaMutation = useMutation({
    mutationFn: () => keltaClient.admin.mfa.resetUser(id!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users', id] })
      queryClient.invalidateQueries({ queryKey: ['users', id, 'mfa-status'] })
      showToast('MFA reset successfully', 'success')
      setShowMfaResetConfirm(false)
    },
    onError: () => {
      showToast('Failed to reset MFA', 'error')
      setShowMfaResetConfirm(false)
    },
  })

  // Password reset mutation
  const resetPasswordMutation = useMutation({
    mutationFn: () => keltaClient.admin.users.resetPassword(id!),
    onSuccess: () => {
      showToast(
        'Password reset initiated. User will be prompted to change their password on next login.',
        'success'
      )
      setShowPasswordResetConfirm(false)
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to reset password', 'error')
      setShowPasswordResetConfirm(false)
    },
  })

  // Admin-on-behalf-of PAT mint (MANAGE_USERS) — mirrors ApiTokensPage's self-service
  // createMutation, but targets this user id instead of the caller.
  const mintTokenMutation = useMutation({
    mutationFn: (data: MintTokenFormData) =>
      keltaClient.admin.users.tokens.create(id!, {
        name: data.name,
        expiresInDays: data.expiresInDays,
      }),
    onSuccess: (result) => {
      showToast('Token minted successfully', 'success')
      setShowMintTokenForm(false)
      setMintedToken(result.token)
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to mint token', 'error')
    },
  })

  const handleSave = useCallback(() => {
    updateMutation.mutate(formData)
  }, [formData, updateMutation])

  const handleCancel = useCallback(() => {
    if (user) {
      setFormData({
        firstName: user.firstName,
        lastName: user.lastName,
        username: user.username || '',
        locale: user.locale,
        timezone: user.timezone,
      })
    }
    setIsEditing(false)
  }, [user])

  if (error) {
    return (
      <div className="mx-auto max-w-[1200px] space-y-6 p-6" data-testid={testId}>
        <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
          <p>{t('errors.generic')}</p>
          <button
            onClick={() => refetch()}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {t('common.retry')}
          </button>
        </div>
      </div>
    )
  }

  if (isLoading || !user) {
    return (
      <div className="mx-auto max-w-[1200px] space-y-6 p-6" data-testid={testId}>
        <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
          {t('common.loading')}
        </div>
      </div>
    )
  }

  const historyEntries = loginHistory?.content ?? []
  const historyTotalPages = loginHistory?.totalPages ?? 0

  return (
    <div className="mx-auto max-w-[1200px] space-y-6 p-6" data-testid={testId}>
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <button
            className="rounded-md border border-border bg-transparent px-3 py-2 text-sm text-muted-foreground hover:bg-muted"
            onClick={() => navigate(`/${tenantSlug}/users`)}
          >
            {t('common.back')}
          </button>
          <h1 className="m-0 text-2xl font-semibold text-foreground">
            {user.firstName} {user.lastName}
          </h1>
          <StatusBadge status={user.status} />
        </div>
        <div className="flex gap-2">
          {user.status === 'ACTIVE' ? (
            <button
              className="rounded-md bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground hover:bg-destructive/90"
              onClick={() => statusMutation.mutate('deactivate')}
              disabled={statusMutation.isPending}
            >
              {t('users.deactivate')}
            </button>
          ) : (
            <button
              className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              onClick={() => statusMutation.mutate('activate')}
              disabled={statusMutation.isPending}
            >
              {t('users.activate')}
            </button>
          )}
        </div>
      </header>

      <div className="-mb-[2px] flex border-b-2 border-border">
        <button
          className={cn(
            '-mb-[2px] border-b-2 border-transparent bg-transparent px-6 py-3 text-sm font-medium text-muted-foreground hover:text-foreground',
            activeTab === 'details' && 'border-primary text-primary'
          )}
          onClick={() => setActiveTab('details')}
        >
          {t('users.details')}
        </button>
        <button
          className={cn(
            '-mb-[2px] border-b-2 border-transparent bg-transparent px-6 py-3 text-sm font-medium text-muted-foreground hover:text-foreground',
            activeTab === 'security' && 'border-primary text-primary'
          )}
          onClick={() => setActiveTab('security')}
        >
          Security
        </button>
        <button
          className={cn(
            '-mb-[2px] border-b-2 border-transparent bg-transparent px-6 py-3 text-sm font-medium text-muted-foreground hover:text-foreground',
            activeTab === 'loginHistory' && 'border-primary text-primary'
          )}
          onClick={() => setActiveTab('loginHistory')}
        >
          {t('users.loginHistory')}
        </button>
      </div>

      {activeTab === 'details' && (
        <div className="rounded-lg border border-border bg-card p-6">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t('users.email')}
              </label>
              <input
                type="email"
                value={user.email}
                disabled
                className="w-full rounded-md border border-border bg-muted px-3 py-2 text-sm text-muted-foreground"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t('users.username')}
              </label>
              <input
                type="text"
                value={formData.username}
                onChange={(e) => setFormData({ ...formData, username: e.target.value })}
                disabled={!isEditing}
                className={cn(
                  'w-full rounded-md border border-border px-3 py-2 text-sm text-foreground',
                  !isEditing && 'bg-muted text-muted-foreground'
                )}
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t('users.firstName')}
              </label>
              <input
                type="text"
                value={formData.firstName}
                onChange={(e) => setFormData({ ...formData, firstName: e.target.value })}
                disabled={!isEditing}
                className={cn(
                  'w-full rounded-md border border-border px-3 py-2 text-sm text-foreground',
                  !isEditing && 'bg-muted text-muted-foreground'
                )}
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t('users.lastName')}
              </label>
              <input
                type="text"
                value={formData.lastName}
                onChange={(e) => setFormData({ ...formData, lastName: e.target.value })}
                disabled={!isEditing}
                className={cn(
                  'w-full rounded-md border border-border px-3 py-2 text-sm text-foreground',
                  !isEditing && 'bg-muted text-muted-foreground'
                )}
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t('users.locale')}
              </label>
              <input
                type="text"
                value={formData.locale}
                onChange={(e) => setFormData({ ...formData, locale: e.target.value })}
                disabled={!isEditing}
                className={cn(
                  'w-full rounded-md border border-border px-3 py-2 text-sm text-foreground',
                  !isEditing && 'bg-muted text-muted-foreground'
                )}
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t('users.timezone')}
              </label>
              <input
                type="text"
                value={formData.timezone}
                onChange={(e) => setFormData({ ...formData, timezone: e.target.value })}
                disabled={!isEditing}
                className={cn(
                  'w-full rounded-md border border-border px-3 py-2 text-sm text-foreground',
                  !isEditing && 'bg-muted text-muted-foreground'
                )}
              />
            </div>
          </div>

          <div className="mt-4 flex gap-8 border-t border-border pt-4 text-sm text-muted-foreground">
            <div className="flex flex-col gap-0.5">
              <span className="font-medium text-muted-foreground">{t('users.lastLogin')}</span>
              <span>
                {user.lastLoginAt ? formatDate(new Date(user.lastLoginAt)) : t('users.never')}
              </span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="font-medium text-muted-foreground">{t('users.loginCount')}</span>
              <span>{user.loginCount}</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="font-medium text-muted-foreground">{t('users.mfaEnabled')}</span>
              <span>{user.mfaEnabled ? t('common.yes') : t('common.no')}</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="font-medium text-muted-foreground">{t('users.created')}</span>
              <span>{formatDate(new Date(user.createdAt))}</span>
            </div>
          </div>

          <div className="mt-6 flex justify-end gap-2">
            {isEditing ? (
              <>
                <button
                  className="rounded-md border border-border bg-secondary px-4 py-2 text-sm text-foreground hover:bg-muted"
                  onClick={handleCancel}
                >
                  {t('common.cancel')}
                </button>
                <button
                  className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                  onClick={handleSave}
                  disabled={updateMutation.isPending}
                >
                  {updateMutation.isPending ? t('common.saving') : t('common.save')}
                </button>
              </>
            ) : (
              <button
                className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
                onClick={() => setIsEditing(true)}
              >
                {t('common.edit')}
              </button>
            )}
          </div>
        </div>
      )}

      {activeTab === 'security' && (
        <div className="space-y-6">
          {/* Profile Section */}
          <div className="rounded-lg border border-border bg-card p-6">
            <h2 className="mb-4 text-lg font-semibold text-foreground">Profile</h2>
            <div className="flex items-center gap-4">
              <label htmlFor="profile-select" className="text-sm font-medium text-muted-foreground">
                Assigned Profile
              </label>
              <select
                id="profile-select"
                value={user.profileId || ''}
                onChange={(e) => {
                  if (e.target.value) {
                    changeProfileMutation.mutate(e.target.value)
                  }
                }}
                disabled={changeProfileMutation.isPending}
                className="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground"
                data-testid="profile-select"
              >
                <option value="" disabled>
                  Select a profile
                </option>
                {(profiles ?? []).map((profile) => (
                  <option key={profile.id} value={profile.id}>
                    {profile.name}
                    {profile.system ? ' (System)' : ''}
                  </option>
                ))}
              </select>
              {currentProfile && (
                <Link
                  to={`/${tenantSlug}/profiles/${currentProfile.id}`}
                  className="text-sm text-primary hover:underline"
                >
                  View Profile
                </Link>
              )}
            </div>
          </div>

          {/* MFA Section */}
          <div className="rounded-lg border border-border bg-card p-6">
            <h2 className="mb-4 text-lg font-semibold text-foreground">
              Multi-Factor Authentication
            </h2>
            <div className="flex items-center justify-between">
              <div className="space-y-1">
                <p className="text-sm text-muted-foreground">
                  MFA Status:{' '}
                  <span
                    className={
                      user.mfaEnabled ? 'text-green-500 font-medium' : 'text-muted-foreground'
                    }
                  >
                    {user.mfaEnabled ? 'Enabled' : 'Not enabled'}
                  </span>
                </p>
                {user.mfaEnabled && mfaStatus && (
                  <>
                    {mfaStatus.enrolledAt && (
                      <p className="text-xs text-muted-foreground">
                        Enrolled: {formatDate(new Date(mfaStatus.enrolledAt))}
                      </p>
                    )}
                    <p className="text-xs text-muted-foreground">
                      Recovery codes remaining: {mfaStatus.remainingRecoveryCodes}
                    </p>
                  </>
                )}
              </div>
              {user.mfaEnabled && (
                <button
                  className="rounded-md border border-destructive px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/10 disabled:opacity-50"
                  onClick={() => setShowMfaResetConfirm(true)}
                  disabled={resetMfaMutation.isPending}
                  data-testid="reset-mfa-button"
                >
                  Reset MFA
                </button>
              )}
            </div>
          </div>

          {/* Password Management Section */}
          <div className="rounded-lg border border-border bg-card p-6">
            <h2 className="mb-4 text-lg font-semibold text-foreground">Password Management</h2>
            <div className="flex items-center justify-between">
              <p className="text-sm text-muted-foreground">
                Force the user to change their password on next login.
              </p>
              <button
                className="rounded-md border border-border bg-secondary px-4 py-2 text-sm font-medium text-foreground hover:bg-muted disabled:opacity-50"
                onClick={() => setShowPasswordResetConfirm(true)}
                disabled={resetPasswordMutation.isPending}
                data-testid="reset-password-button"
              >
                Reset Password
              </button>
            </div>
          </div>

          {/* Personal Access Tokens Section — MANAGE_USERS only */}
          {hasPermission('MANAGE_USERS') && (
            <div className="rounded-lg border border-border bg-card p-6">
              <h2 className="mb-4 text-lg font-semibold text-foreground">Personal Access Tokens</h2>
              <div className="flex items-center justify-between">
                <p className="text-sm text-muted-foreground">
                  Mint a personal access token on this user's behalf for scripted/API access.
                </p>
                <button
                  className="rounded-md border border-border bg-secondary px-4 py-2 text-sm font-medium text-foreground hover:bg-muted disabled:opacity-50"
                  onClick={() => setShowMintTokenForm(true)}
                  disabled={mintTokenMutation.isPending}
                  data-testid="mint-token-button"
                >
                  Mint Token
                </button>
              </div>

              {mintedToken && (
                <div className="mt-4 rounded-lg border border-amber-300 bg-amber-50 p-4 dark:border-amber-700 dark:bg-amber-950">
                  <p className="mb-2 text-sm font-medium text-amber-800 dark:text-amber-300">
                    Copy this token now. It will not be shown again.
                  </p>
                  <div className="flex items-center gap-2">
                    <code
                      className="flex-1 break-all rounded bg-muted px-2 py-1.5 font-mono text-xs text-foreground"
                      data-testid="minted-token-value"
                    >
                      {mintedToken}
                    </code>
                    <button
                      type="button"
                      className="shrink-0 rounded-md border border-border bg-secondary px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
                      onClick={() => setMintedToken(null)}
                      data-testid="dismiss-minted-token"
                    >
                      Done
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}

          {showMintTokenForm && (
            <MintTokenForm
              onSubmit={(data) => mintTokenMutation.mutate(data)}
              onCancel={() => setShowMintTokenForm(false)}
              isSubmitting={mintTokenMutation.isPending}
            />
          )}

          <ConfirmDialog
            open={showMfaResetConfirm}
            title="Reset MFA"
            message="Are you sure you want to reset MFA for this user? They will need to re-enroll their authenticator."
            confirmLabel="Reset MFA"
            onConfirm={() => resetMfaMutation.mutate()}
            onCancel={() => setShowMfaResetConfirm(false)}
            variant="danger"
          />

          <ConfirmDialog
            open={showPasswordResetConfirm}
            title="Reset Password"
            message="This will force the user to change their password on their next login. Are you sure?"
            confirmLabel="Reset Password"
            onConfirm={() => resetPasswordMutation.mutate()}
            onCancel={() => setShowPasswordResetConfirm(false)}
            variant="danger"
          />
        </div>
      )}

      {activeTab === 'loginHistory' && (
        <div className="rounded-lg border border-border bg-card p-6">
          {historyLoading ? (
            <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
              {t('common.loading')}
            </div>
          ) : historyEntries.length === 0 ? (
            <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
              <p>{t('users.noLoginHistory')}</p>
            </div>
          ) : (
            <>
              <table className="w-full border-collapse text-sm">
                <thead>
                  <tr>
                    <th className="border-b-2 border-border px-4 py-3 text-left font-semibold text-foreground">
                      {t('users.loginTime')}
                    </th>
                    <th className="border-b-2 border-border px-4 py-3 text-left font-semibold text-foreground">
                      {t('users.loginType')}
                    </th>
                    <th className="border-b-2 border-border px-4 py-3 text-left font-semibold text-foreground">
                      {t('users.loginStatus')}
                    </th>
                    <th className="border-b-2 border-border px-4 py-3 text-left font-semibold text-foreground">
                      {t('users.sourceIp')}
                    </th>
                    <th className="border-b-2 border-border px-4 py-3 text-left font-semibold text-foreground">
                      {t('users.userAgent')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {historyEntries.map((entry) => (
                    <tr key={entry.id} className="border-b border-border hover:bg-muted/50">
                      <td className="px-4 py-3 text-foreground">
                        {formatDate(new Date(entry.loginTime))}
                      </td>
                      <td className="px-4 py-3 text-foreground">{entry.loginType}</td>
                      <td className="px-4 py-3">
                        <LoginStatusLabel status={entry.status} />
                      </td>
                      <td className="px-4 py-3 text-foreground">{entry.sourceIp}</td>
                      <td className="max-w-[200px] truncate px-4 py-3 text-foreground">
                        {entry.userAgent}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              {historyTotalPages > 1 && (
                <div className="mt-4 flex items-center justify-center gap-4 border-t border-border pt-4 text-muted-foreground">
                  <button
                    disabled={historyPage === 0}
                    onClick={() => setHistoryPage((p) => Math.max(0, p - 1))}
                    className="rounded-md border border-border bg-card px-2 py-1 text-xs text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {t('common.previous')}
                  </button>
                  <span>
                    {t('common.pageOf', { current: historyPage + 1, total: historyTotalPages })}
                  </span>
                  <button
                    disabled={historyPage >= historyTotalPages - 1}
                    onClick={() => setHistoryPage((p) => p + 1)}
                    className="rounded-md border border-border bg-card px-2 py-1 text-xs text-foreground hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {t('common.next')}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}

function MintTokenForm({
  onSubmit,
  onCancel,
  isSubmitting,
}: {
  onSubmit: (data: MintTokenFormData) => void
  onCancel: () => void
  isSubmitting: boolean
}) {
  const [name, setName] = useState('')
  const [expiresInDays, setExpiresInDays] = useState(90)
  const [nameError, setNameError] = useState<string | undefined>()
  const nameInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    nameInputRef.current?.focus()
  }, [])

  const handleSubmit = useCallback(
    (e: FormEvent) => {
      e.preventDefault()
      const trimmed = name.trim()
      if (!trimmed) {
        setNameError('Name is required')
        return
      }
      if (trimmed.length > 200) {
        setNameError('Name must be 200 characters or fewer')
        return
      }
      onSubmit({ name: trimmed, expiresInDays })
    },
    [name, expiresInDays, onSubmit]
  )

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      role="presentation"
      data-testid="mint-token-form-overlay"
    >
      <div
        className="w-full max-w-[500px] rounded-lg bg-card shadow-xl"
        role="dialog"
        aria-modal="true"
        data-testid="mint-token-form-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 className="m-0 text-xl font-semibold text-foreground">Mint Personal Access Token</h2>
          <button
            type="button"
            className="rounded p-2 text-2xl leading-none text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={onCancel}
            aria-label="Close"
          >
            &times;
          </button>
        </div>
        <div className="p-6">
          <form className="space-y-4" onSubmit={handleSubmit} noValidate>
            <div>
              <label
                htmlFor="mint-token-name"
                className="mb-1 block text-sm font-medium text-foreground"
              >
                Token Name <span className="ml-0.5 text-destructive">*</span>
              </label>
              <input
                ref={nameInputRef}
                id="mint-token-name"
                type="text"
                className={cn(
                  'w-full rounded-md border px-3 py-2 text-sm text-foreground bg-background focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary',
                  nameError ? 'border-destructive' : 'border-border'
                )}
                value={name}
                onChange={(e) => {
                  setName(e.target.value)
                  if (nameError) setNameError(undefined)
                }}
                placeholder="e.g. CI/CD Pipeline"
                disabled={isSubmitting}
                data-testid="mint-token-name-input"
              />
              {nameError && (
                <span className="mt-1 block text-xs text-destructive" role="alert">
                  {nameError}
                </span>
              )}
            </div>

            <div>
              <label
                htmlFor="mint-token-expiry"
                className="mb-1 block text-sm font-medium text-foreground"
              >
                Expiration
              </label>
              <select
                id="mint-token-expiry"
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground"
                value={expiresInDays}
                onChange={(e) => setExpiresInDays(parseInt(e.target.value, 10))}
                disabled={isSubmitting}
                data-testid="mint-token-expiry-select"
              >
                <option value={30}>30 days</option>
                <option value={60}>60 days</option>
                <option value={90}>90 days</option>
                <option value={180}>180 days</option>
                <option value={365}>365 days</option>
              </select>
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                className="rounded-md border border-border bg-secondary px-4 py-2 text-sm text-foreground hover:bg-muted disabled:opacity-50"
                onClick={onCancel}
                disabled={isSubmitting}
              >
                Cancel
              </button>
              <button
                type="submit"
                className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                disabled={isSubmitting}
                data-testid="mint-token-submit"
              >
                {isSubmitting ? 'Minting...' : 'Mint Token'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
}
