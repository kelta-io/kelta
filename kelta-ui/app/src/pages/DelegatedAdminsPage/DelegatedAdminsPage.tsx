import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { DelegatedAdminScope, SaveDelegatedAdminScopeRequest } from '@kelta/sdk'
import { useApi } from '../../context/ApiContext'
import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'

export interface DelegatedAdminsPageProps {
  testId?: string
}

interface Option {
  id: string
  label: string
}

interface ScopeForm {
  name: string
  description: string
  active: boolean
  delegatedUserIds: string[]
  manageableProfileIds: string[]
  canCreateUsers: boolean
  canDeactivateUsers: boolean
  canResetPasswords: boolean
}

const EMPTY_FORM: ScopeForm = {
  name: '',
  description: '',
  active: true,
  delegatedUserIds: [],
  manageableProfileIds: [],
  canCreateUsers: false,
  canDeactivateUsers: false,
  canResetPasswords: false,
}

function MultiSelect({
  label,
  options,
  selected,
  onChange,
  testId,
}: {
  label: string
  options: Option[]
  selected: string[]
  onChange: (ids: string[]) => void
  testId?: string
}) {
  const toggle = (id: string) => {
    onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id])
  }
  return (
    <div className="mb-4">
      <span className="mb-1 block text-sm font-medium text-foreground">{label}</span>
      <div
        className="flex max-h-40 flex-wrap gap-2 overflow-y-auto rounded-md border border-border bg-background p-2"
        data-testid={testId}
      >
        {options.length === 0 ? (
          <span className="text-xs text-muted-foreground">—</span>
        ) : (
          options.map((o) => (
            <label
              key={o.id}
              className={cn(
                'flex cursor-pointer items-center gap-1 rounded-full border px-3 py-1 text-xs',
                selected.includes(o.id)
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border'
              )}
            >
              <input
                type="checkbox"
                checked={selected.includes(o.id)}
                onChange={() => toggle(o.id)}
              />
              {o.label}
            </label>
          ))
        )}
      </div>
    </div>
  )
}

function ScopeEditor({
  initial,
  userOptions,
  profileOptions,
  onSubmit,
  onCancel,
  isSubmitting,
}: {
  initial: ScopeForm
  userOptions: Option[]
  profileOptions: Option[]
  onSubmit: (form: ScopeForm) => void
  onCancel: () => void
  isSubmitting: boolean
}) {
  const { t } = useI18n()
  const [form, setForm] = useState<ScopeForm>(initial)
  const [nameError, setNameError] = useState<string | null>(null)

  const submit = () => {
    if (!form.name.trim()) {
      setNameError(t('delegatedAdmins.name') + ' *')
      return
    }
    onSubmit(form)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onCancel()
      }}
      role="presentation"
    >
      <div
        className="max-h-[90vh] w-full max-w-[560px] overflow-y-auto rounded-lg bg-card p-6 shadow-xl"
        role="dialog"
        aria-modal="true"
        data-testid="scope-editor"
      >
        <h2 className="mb-4 text-xl font-semibold">
          {initial.name ? t('delegatedAdmins.editScope') : t('delegatedAdmins.createScope')}
        </h2>

        <div className="mb-4">
          <label htmlFor="scope-name" className="mb-1 block text-sm font-medium text-foreground">
            {t('delegatedAdmins.name')} *
          </label>
          <input
            id="scope-name"
            type="text"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className={cn(
              'w-full rounded-md border border-border bg-background px-3 py-2 text-sm',
              nameError && 'border-destructive'
            )}
          />
          {nameError && <span className="mt-1 block text-xs text-destructive">{nameError}</span>}
        </div>

        <div className="mb-4">
          <label htmlFor="scope-desc" className="mb-1 block text-sm font-medium text-foreground">
            {t('delegatedAdmins.description')}
          </label>
          <input
            id="scope-desc"
            type="text"
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          />
        </div>

        <MultiSelect
          label={t('delegatedAdmins.delegatedUsers')}
          options={userOptions}
          selected={form.delegatedUserIds}
          onChange={(ids) => setForm({ ...form, delegatedUserIds: ids })}
          testId="picker-users"
        />
        <MultiSelect
          label={t('delegatedAdmins.manageableProfiles')}
          options={profileOptions}
          selected={form.manageableProfileIds}
          onChange={(ids) => setForm({ ...form, manageableProfileIds: ids })}
          testId="picker-profiles"
        />
        <p className="-mt-2 mb-4 text-xs text-muted-foreground">
          {t('delegatedAdmins.privilegedProfileHint')}
        </p>

        <div className="mb-4 flex flex-col gap-2">
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={form.canCreateUsers}
              onChange={(e) => setForm({ ...form, canCreateUsers: e.target.checked })}
            />
            {t('delegatedAdmins.canCreateUsers')}
          </label>
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={form.canDeactivateUsers}
              onChange={(e) => setForm({ ...form, canDeactivateUsers: e.target.checked })}
            />
            {t('delegatedAdmins.canDeactivateUsers')}
          </label>
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={form.canResetPasswords}
              onChange={(e) => setForm({ ...form, canResetPasswords: e.target.checked })}
            />
            {t('delegatedAdmins.canResetPasswords')}
          </label>
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={form.active}
              onChange={(e) => setForm({ ...form, active: e.target.checked })}
            />
            {t('delegatedAdmins.active')}
          </label>
        </div>

        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            className="cursor-pointer rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
            onClick={onCancel}
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:cursor-not-allowed disabled:opacity-50"
            onClick={submit}
            disabled={isSubmitting}
          >
            {isSubmitting ? t('common.saving') : t('common.save')}
          </button>
        </div>
      </div>
    </div>
  )
}

export function DelegatedAdminsPage({
  testId = 'delegated-admins-page',
}: DelegatedAdminsPageProps) {
  const queryClient = useQueryClient()
  const { t } = useI18n()
  const { keltaClient } = useApi()

  const [editing, setEditing] = useState<{ id?: string; form: ScopeForm } | null>(null)
  const [deleteId, setDeleteId] = useState<{ id: string; name: string } | null>(null)

  const { data: scopes, isLoading } = useQuery({
    queryKey: ['delegated-admin-scopes'],
    queryFn: () => keltaClient.admin.delegatedAdminScopes.list(1, 200),
  })

  const { data: users } = useQuery({
    queryKey: ['delegated-admin-scopes', 'users'],
    queryFn: () => keltaClient.admin.users.list(undefined, undefined, 0, 200),
  })

  const { data: profiles } = useQuery({
    queryKey: ['delegated-admin-scopes', 'profiles'],
    queryFn: () => keltaClient.admin.profiles.list(),
  })

  const userOptions: Option[] = useMemo(
    () =>
      (users?.content ?? []).map((u) => ({
        id: u.id,
        label: `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email,
      })),
    [users]
  )
  const profileOptions: Option[] = useMemo(
    () => (profiles ?? []).map((p) => ({ id: p.id, label: p.name })),
    [profiles]
  )

  const saveMutation = useMutation({
    mutationFn: ({ id, form }: { id?: string; form: ScopeForm }) => {
      const payload: SaveDelegatedAdminScopeRequest = {
        name: form.name,
        description: form.description || undefined,
        active: form.active,
        delegatedUserIds: form.delegatedUserIds,
        manageableProfileIds: form.manageableProfileIds,
        canCreateUsers: form.canCreateUsers,
        canDeactivateUsers: form.canDeactivateUsers,
        canResetPasswords: form.canResetPasswords,
      }
      return id
        ? keltaClient.admin.delegatedAdminScopes.update(id, payload)
        : keltaClient.admin.delegatedAdminScopes.create(payload)
    },
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['delegated-admin-scopes'] })
      toast.success(
        variables.id ? t('delegatedAdmins.updateSuccess') : t('delegatedAdmins.createSuccess')
      )
      setEditing(null)
    },
    onError: (err: Error) => toast.error(err.message || t('errors.generic')),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.delegatedAdminScopes.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['delegated-admin-scopes'] })
      toast.success(t('delegatedAdmins.deleteSuccess'))
      setDeleteId(null)
    },
    onError: (err: Error) => toast.error(err.message || t('errors.generic')),
  })

  const openCreate = () => setEditing({ form: { ...EMPTY_FORM } })
  const openEdit = (scope: DelegatedAdminScope) =>
    setEditing({
      id: scope.id,
      form: {
        name: scope.name,
        description: scope.description ?? '',
        active: scope.active,
        delegatedUserIds: scope.delegatedUserIds ?? [],
        manageableProfileIds: scope.manageableProfileIds ?? [],
        canCreateUsers: scope.canCreateUsers,
        canDeactivateUsers: scope.canDeactivateUsers,
        canResetPasswords: scope.canResetPasswords,
      },
    })

  return (
    <div className="mx-auto max-w-[1000px] p-6" data-testid={testId}>
      <header className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="m-0 text-2xl font-semibold">{t('delegatedAdmins.title')}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{t('delegatedAdmins.subtitle')}</p>
        </div>
        <button
          className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={openCreate}
        >
          {t('delegatedAdmins.createScope')}
        </button>
      </header>

      {isLoading ? (
        <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
          {t('common.loading')}
        </div>
      ) : (scopes ?? []).length === 0 ? (
        <div className="flex flex-col items-center justify-center p-12 text-muted-foreground">
          <p>{t('delegatedAdmins.noScopes')}</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr>
                <th className="border-b-2 border-border p-3 text-left font-semibold">
                  {t('delegatedAdmins.name')}
                </th>
                <th className="border-b-2 border-border p-3 text-left font-semibold">
                  {t('delegatedAdmins.delegatedUsers')}
                </th>
                <th className="border-b-2 border-border p-3 text-left font-semibold">
                  {t('delegatedAdmins.manageableProfiles')}
                </th>
                <th className="border-b-2 border-border p-3 text-left font-semibold">
                  {t('delegatedAdmins.active')}
                </th>
                <th className="border-b-2 border-border p-3 text-left font-semibold">
                  {t('common.actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {(scopes ?? []).map((scope) => (
                <tr key={scope.id} className="hover:bg-muted">
                  <td className="border-b border-border p-3 font-medium">{scope.name}</td>
                  <td className="border-b border-border p-3">
                    {(scope.delegatedUserIds ?? []).length} {t('delegatedAdmins.usersLabel')}
                  </td>
                  <td className="border-b border-border p-3">
                    {(scope.manageableProfileIds ?? []).length} {t('delegatedAdmins.profilesLabel')}
                  </td>
                  <td className="border-b border-border p-3">
                    {scope.active ? t('common.yes') : t('common.no')}
                  </td>
                  <td className="border-b border-border p-3">
                    <div className="flex gap-2">
                      <button
                        className="cursor-pointer rounded border border-border bg-card px-2 py-1 text-xs hover:bg-muted"
                        onClick={() => openEdit(scope)}
                      >
                        {t('common.edit')}
                      </button>
                      <button
                        className="cursor-pointer rounded border-none bg-destructive px-2 py-1 text-xs text-destructive-foreground hover:bg-destructive/90"
                        onClick={() => setDeleteId({ id: scope.id, name: scope.name })}
                      >
                        {t('common.delete')}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing && (
        <ScopeEditor
          initial={editing.form}
          userOptions={userOptions}
          profileOptions={profileOptions}
          onSubmit={(form) => saveMutation.mutate({ id: editing.id, form })}
          onCancel={() => setEditing(null)}
          isSubmitting={saveMutation.isPending}
        />
      )}

      {deleteId && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={(e) => e.target === e.currentTarget && setDeleteId(null)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') setDeleteId(null)
          }}
          role="presentation"
        >
          <div
            className="w-full max-w-[420px] rounded-lg bg-card p-6 shadow-xl"
            role="dialog"
            aria-modal="true"
          >
            <h2 className="mb-4 text-xl font-semibold">{t('common.confirm')}</h2>
            <p>{t('delegatedAdmins.deleteConfirm', { name: deleteId.name })}</p>
            <div className="mt-6 flex justify-end gap-2">
              <button
                className="cursor-pointer rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
                onClick={() => setDeleteId(null)}
              >
                {t('common.cancel')}
              </button>
              <button
                className="cursor-pointer rounded-md border-none bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground disabled:opacity-50"
                onClick={() => deleteMutation.mutate(deleteId.id)}
                disabled={deleteMutation.isPending}
              >
                {deleteMutation.isPending ? t('common.saving') : t('common.delete')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
