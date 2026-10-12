/**
 * ProfilesPage Component
 *
 * Displays a list of security profiles with create, edit, clone, and delete
 * functionality. System profiles can only be cloned (not edited or deleted).
 * Clicking a row navigates to the detail page. Built on the shared AdminCrud
 * building block.
 */

import React, { useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { useApi } from '../../context/ApiContext'
import { useTenant } from '../../context/TenantContext'
import { useI18n } from '../../context/I18nContext'
import { useToast, ConfirmDialog } from '../../components'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import { Button } from '@/components/ui/button'
import { Lock, Plus, Pencil, Copy, Trash2 } from 'lucide-react'
import type { CreateProfileRequest, UpdateProfileRequest } from '@kelta/sdk'

interface SecurityProfile {
  id: string
  name: string
  description: string | null
  isSystem: boolean
  createdAt: string
  updatedAt: string
}

const profileSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Profile name is required')
    .refine((v) => v.length <= 255, 'Name must be 255 characters or fewer'),
  description: z.string(),
})

type ProfileFormData = z.infer<typeof profileSchema>

export interface ProfilesPageProps {
  testId?: string
}

const ACTION_BUTTON =
  'inline-flex items-center gap-1 rounded border border-border px-2 py-1 text-xs font-medium'

function SystemBadge() {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-950 dark:text-amber-300">
      <Lock size={12} />
      System
    </span>
  )
}

export function ProfilesPage({ testId = 'profiles-page' }: ProfilesPageProps): React.ReactElement {
  const { apiClient, keltaClient } = useApi()
  const { tenantSlug } = useTenant()
  const { formatDate } = useI18n()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { showToast } = useToast()

  const {
    data: profiles,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['profiles'],
    queryFn: () => keltaClient.admin.profiles.list() as unknown as Promise<SecurityProfile[]>,
  })
  const profileList = profiles ?? []

  const crud = useAdminCrud<SecurityProfile, ProfileFormData>({
    queryKey: ['profiles'],
    create: (data) => keltaClient.admin.profiles.create(data as CreateProfileRequest),
    update: (profile, data) =>
      keltaClient.admin.profiles.update(profile.id, data as UpdateProfileRequest),
    remove: (profile) => keltaClient.admin.profiles.delete(profile.id),
    messages: {
      created: 'Profile created successfully',
      updated: 'Profile updated successfully',
      deleted: 'Profile deleted successfully',
      error: 'Failed to save profile',
      createError: 'Failed to create profile',
      updateError: 'Failed to update profile',
      deleteError: 'Failed to delete profile',
    },
  })

  const cloneMutation = useMutation({
    mutationFn: (id: string) =>
      apiClient.postResource<SecurityProfile>(`/api/profiles/${id}/clone`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['profiles'] })
      showToast('Profile cloned successfully', 'success')
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to clone profile', 'error')
    },
  })

  const columns = useMemo<AdminColumn<SecurityProfile>[]>(
    () => [
      {
        id: 'name',
        header: 'Name',
        cellClassName: 'font-medium text-foreground',
      },
      {
        id: 'description',
        header: 'Description',
        cell: (p) => p.description || '—',
        cellClassName: 'max-w-[300px] truncate text-muted-foreground',
      },
      {
        id: 'isSystem',
        header: 'System',
        cell: (p) => (p.isSystem ? <SystemBadge /> : null),
      },
      {
        id: 'createdAt',
        header: 'Created',
        cell: (p) =>
          formatDate(new Date(p.createdAt), { year: 'numeric', month: 'short', day: 'numeric' }),
        cellClassName: 'text-muted-foreground',
      },
    ],
    [formatDate]
  )

  const editing = crud.editing

  return (
    <AdminCrudPage
      testId={testId}
      title="Profiles"
      actions={
        <Button onClick={crud.openCreate} data-testid="new-profile-button">
          <Plus size={16} />
          New Profile
        </Button>
      }
      isLoading={isLoading}
      error={error}
      loadErrorMessage="Failed to load profiles."
      onRetry={() => refetch()}
      isEmpty={profileList.length === 0}
      emptyMessage="No profiles found. Create your first profile to get started."
      list={
        <AdminDataTable
          tableId="profiles"
          testId="profiles-table"
          ariaLabel="Profiles"
          columns={columns}
          rows={profileList}
          rowKey={(p) => p.id}
          rowTestId={(_, index) => `profile-row-${index}`}
          onRowClick={(p) => navigate(`/${tenantSlug}/profiles/${p.id}`)}
          renderActions={(profile, index) => (
            <div className="flex justify-end gap-2">
              {!profile.isSystem && (
                <button
                  type="button"
                  className={`${ACTION_BUTTON} text-primary hover:border-primary hover:bg-muted`}
                  onClick={() => crud.openEdit(profile)}
                  data-testid={`edit-button-${index}`}
                  title="Edit profile"
                >
                  <Pencil size={12} />
                  Edit
                </button>
              )}
              <button
                type="button"
                className={`${ACTION_BUTTON} text-primary hover:border-primary hover:bg-muted`}
                onClick={() => cloneMutation.mutate(profile.id)}
                data-testid={`clone-button-${index}`}
                title="Clone profile"
              >
                <Copy size={12} />
                Clone
              </button>
              {!profile.isSystem && (
                <button
                  type="button"
                  className={`${ACTION_BUTTON} text-destructive hover:border-destructive hover:bg-destructive/10`}
                  onClick={() => crud.requestDelete(profile)}
                  data-testid={`delete-button-${index}`}
                  title="Delete profile"
                >
                  <Trash2 size={12} />
                  Delete
                </button>
              )}
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<ProfileFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? 'Edit Profile' : 'New Profile'}
        schema={profileSchema}
        defaultValues={{
          name: editing?.name ?? '',
          description: editing?.description ?? '',
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="profile"
      >
        <CrudTextField<ProfileFormData>
          name="name"
          id="profile-name"
          label="Name"
          required
          placeholder="Enter profile name"
          testId="profile-name-input"
          errorTestId="profile-name-error"
        />
        <CrudTextField<ProfileFormData>
          name="description"
          id="profile-description"
          label="Description"
          multiline
          placeholder="Enter profile description"
          testId="profile-description-input"
        />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Profile"
        message={`Are you sure you want to delete "${crud.deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}

export default ProfilesPage
