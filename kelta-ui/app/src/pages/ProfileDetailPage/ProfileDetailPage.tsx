/**
 * ProfileDetailPage Component
 *
 * Displays detailed information about a security profile including basic info,
 * system permissions, object permissions, field permissions, custom authorization
 * rules, and a policy test panel. Provides inline editing for non-system profiles.
 */

import React, { useState, useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Shield, Lock, ArrowLeft, Trash2, Pencil, Save, X } from 'lucide-react'
import { useApi } from '../../context/ApiContext'
import { useTenant } from '../../context/TenantContext'
import { useI18n } from '../../context/I18nContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { SystemPermissionChecklist } from '@/components/SecurityEditor'
import { ObjectPermissionMatrix } from '@/components/SecurityEditor'
import { FieldPermissionEditor } from '@/components/SecurityEditor'
import { CustomPolicyEditor } from '@/components/SecurityEditor'
import { PolicyTestPanel } from '@/components/SecurityEditor'
import type { ObjectPermission } from '@/components/SecurityEditor'
import type { CollectionRef, FieldRef, FieldPermission } from '@/components/SecurityEditor'
import { useCollectionSummaries } from '@/hooks/useCollectionSummaries'
import { useExtractIncluded } from '@/hooks/useIncludedResources'
import { flattenResource, unwrapResource } from '@/utils/jsonapi'
import type { JsonApiResource } from '@/utils/jsonapi'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { FieldLabel } from '@/components/kelta'

interface ProfileDetail {
  id: string
  name: string
  description: string | null
  isSystem: boolean
  createdAt: string
  updatedAt: string
}

interface ProfileSystemPermission {
  id: string
  profileId: string
  permissionName: string
  granted: boolean
}

interface ProfileObjectPermission {
  id: string
  profileId: string
  collectionId: string
  canCreate: boolean
  canRead: boolean
  canEdit: boolean
  canDelete: boolean
}

interface ProfileFieldPermission {
  id: string
  profileId: string
  fieldId: string
  visibility: string
}

interface FieldDefinition {
  id: string
  name: string
  type: string
  collectionId: string
}

/** Gateway clamps HTTP page size at MAX_HTTP_PAGE_SIZE = 200. */
const FIELDS_PAGE_SIZE = 200

export interface ProfileDetailPageProps {
  testId?: string
}

export function ProfileDetailPage({
  testId = 'profile-detail-page',
}: ProfileDetailPageProps): React.ReactElement {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { apiClient } = useApi()
  const { tenantSlug } = useTenant()
  const { formatDate } = useI18n()
  const queryClient = useQueryClient()

  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)

  // Basic info editing
  const [isEditingBasicInfo, setIsEditingBasicInfo] = useState(false)
  const [editName, setEditName] = useState('')
  const [editDescription, setEditDescription] = useState('')

  // System permissions editing
  const [isEditingPermissions, setIsEditingPermissions] = useState(false)
  const [localPermissions, setLocalPermissions] = useState<Record<string, boolean>>({})

  // Object permissions editing
  const [isEditingObjectPerms, setIsEditingObjectPerms] = useState(false)
  const [localObjectPermissions, setLocalObjectPermissions] = useState<
    Map<string, Record<string, boolean>>
  >(new Map())

  // Field permissions editing
  const [isEditingFieldPerms, setIsEditingFieldPerms] = useState(false)
  const [localFieldPermissions, setLocalFieldPermissions] = useState<Map<string, string>>(new Map())

  // Fetch profile detail with included permissions in a single request
  const {
    data: rawProfileResponse,
    isLoading: isLoadingProfile,
    error: profileError,
    refetch: refetchProfile,
  } = useQuery({
    queryKey: ['profile', id],
    queryFn: () =>
      apiClient.get(
        `/api/profiles/${id}?include=profile-system-permissions,profile-object-permissions,profile-field-permissions`
      ),
    enabled: !!id,
  })

  // Unwrap the profile from the JSON:API envelope
  const profile = useMemo(
    () => (rawProfileResponse ? unwrapResource<ProfileDetail>(rawProfileResponse) : undefined),
    [rawProfileResponse]
  )

  // Extract included permissions from the JSON:API response
  const systemPermissions = useExtractIncluded<ProfileSystemPermission>(
    rawProfileResponse,
    'profile-system-permissions'
  )
  const objectPermissions = useExtractIncluded<ProfileObjectPermission>(
    rawProfileResponse,
    'profile-object-permissions'
  )
  const fieldPermissions = useExtractIncluded<ProfileFieldPermission>(
    rawProfileResponse,
    'profile-field-permissions'
  )

  // Fetch collections for name resolution and field permissions
  const { summaryMap: collectionSummaryMap } = useCollectionSummaries()

  // Fetch fields for field permissions editor.
  // The HTTP page size clamps at MAX_HTTP_PAGE_SIZE (200), so walk every page —
  // a single oversized request silently returns only the first 200 fields.
  const { data: fieldsData } = useQuery({
    queryKey: ['all-fields'],
    queryFn: async () => {
      const collected: JsonApiResource[] = []
      let pageNumber = 1
      let totalPages = 1
      do {
        const response = (await apiClient.get(
          `/api/fields?page[size]=${FIELDS_PAGE_SIZE}&page[number]=${pageNumber}`
        )) as { data?: JsonApiResource[]; metadata?: { totalPages?: number } }
        if (Array.isArray(response?.data)) {
          collected.push(...response.data)
        }
        totalPages = response?.metadata?.totalPages ?? 1
        pageNumber += 1
      } while (pageNumber <= totalPages)
      return collected
    },
    enabled: !!id,
  })

  const allFields: FieldDefinition[] = useMemo(() => {
    if (!Array.isArray(fieldsData)) return []
    return fieldsData
      .map((resource) => flattenResource<Record<string, unknown>>(resource))
      .map((f) => ({
        id: (f.id as string) ?? '',
        name: (f.name as string) ?? '',
        type: (f.type as string) ?? '',
        // collectionId is a JSON:API relationship, not an attribute —
        // flattenResource surfaces it as a flat id.
        collectionId: (f.collectionId as string) ?? '',
      }))
      .filter((f) => f.id !== '' && f.collectionId !== '')
  }, [fieldsData])

  // Convert system permissions array → Record<string, boolean> for checklist
  const permissionsMap = useMemo(() => {
    const map: Record<string, boolean> = {}
    systemPermissions.forEach((sp) => {
      map[sp.permissionName] = sp.granted
    })
    return map
  }, [systemPermissions])

  // The displayed permissions: use localPermissions when editing, permissionsMap when not
  const displayedPermissions = isEditingPermissions ? localPermissions : permissionsMap

  // Merge object permissions with collection names
  const objectPermissionsWithNames: ObjectPermission[] = useMemo(() => {
    return objectPermissions.map((op) => ({
      collectionId: op.collectionId,
      collectionName:
        collectionSummaryMap[op.collectionId]?.displayName ??
        collectionSummaryMap[op.collectionId]?.name ??
        op.collectionId,
      canCreate: isEditingObjectPerms
        ? (localObjectPermissions.get(op.id)?.canCreate ?? op.canCreate)
        : op.canCreate,
      canRead: isEditingObjectPerms
        ? (localObjectPermissions.get(op.id)?.canRead ?? op.canRead)
        : op.canRead,
      canEdit: isEditingObjectPerms
        ? (localObjectPermissions.get(op.id)?.canEdit ?? op.canEdit)
        : op.canEdit,
      canDelete: isEditingObjectPerms
        ? (localObjectPermissions.get(op.id)?.canDelete ?? op.canDelete)
        : op.canDelete,
    }))
  }, [objectPermissions, collectionSummaryMap, isEditingObjectPerms, localObjectPermissions])

  // Build field permissions data for FieldPermissionEditor
  const fieldPermissionCollections: CollectionRef[] = useMemo(() => {
    const seen = new Set<string>()
    return allFields
      .filter((f) => {
        if (seen.has(f.collectionId)) return false
        seen.add(f.collectionId)
        return true
      })
      .map((f) => ({
        id: f.collectionId,
        name:
          collectionSummaryMap[f.collectionId]?.displayName ??
          collectionSummaryMap[f.collectionId]?.name ??
          f.collectionId,
      }))
  }, [allFields, collectionSummaryMap])

  const fieldPermissionFields: FieldRef[] = useMemo(() => {
    return allFields.map((f) => ({
      id: f.id,
      name: f.name,
      type: f.type,
      collectionId: f.collectionId,
    }))
  }, [allFields])

  const fieldPermissionEntries: FieldPermission[] = useMemo(() => {
    return fieldPermissions.map((fp) => ({
      fieldId: fp.fieldId,
      visibility: isEditingFieldPerms
        ? (localFieldPermissions.get(fp.id) ?? fp.visibility)
        : fp.visibility,
    })) as FieldPermission[]
  }, [fieldPermissions, isEditingFieldPerms, localFieldPermissions])

  // Check if system permissions have been modified
  const hasPermissionChanges = useMemo(() => {
    const keys = new Set([...Object.keys(permissionsMap), ...Object.keys(localPermissions)])
    for (const key of keys) {
      if ((permissionsMap[key] ?? false) !== (localPermissions[key] ?? false)) {
        return true
      }
    }
    return false
  }, [permissionsMap, localPermissions])

  // Check if object permissions have been modified
  const hasObjectPermChanges = useMemo(() => {
    return localObjectPermissions.size > 0
  }, [localObjectPermissions])

  // Check if field permissions have been modified
  const hasFieldPermChanges = useMemo(() => {
    return localFieldPermissions.size > 0
  }, [localFieldPermissions])

  // Save basic info mutation
  const saveBasicInfoMutation = useMutation({
    mutationFn: async ({ name, description }: { name: string; description: string }) => {
      return apiClient.patchResource(`/api/profiles/${id}`, { name, description })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['profile', id] })
      toast.success('Profile updated successfully')
      setIsEditingBasicInfo(false)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update profile')
    },
  })

  // Save system permissions mutation
  const savePermissionsMutation = useMutation({
    mutationFn: async (permissions: Record<string, boolean>) => {
      const updates = systemPermissions
        .filter(
          (sp) =>
            permissions[sp.permissionName] !== undefined &&
            permissions[sp.permissionName] !== sp.granted
        )
        .map((sp) =>
          apiClient.patchResource(`/api/profile-system-permissions/${sp.id}`, {
            granted: permissions[sp.permissionName],
          })
        )
      await Promise.all(updates)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['profile', id] })
      toast.success('System permissions saved successfully')
      setIsEditingPermissions(false)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to save permissions')
    },
  })

  // Save object permissions mutation
  const saveObjectPermsMutation = useMutation({
    mutationFn: async () => {
      const updates: Promise<unknown>[] = []
      localObjectPermissions.forEach((changes, permId) => {
        updates.push(apiClient.patchResource(`/api/profile-object-permissions/${permId}`, changes))
      })
      await Promise.all(updates)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['profile', id] })
      toast.success('Object permissions saved successfully')
      setIsEditingObjectPerms(false)
      setLocalObjectPermissions(new Map())
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to save object permissions')
    },
  })

  // Save field permissions mutation
  const saveFieldPermsMutation = useMutation({
    mutationFn: async () => {
      const updates: Promise<unknown>[] = []
      localFieldPermissions.forEach((visibility, permId) => {
        updates.push(
          apiClient.patchResource(`/api/profile-field-permissions/${permId}`, { visibility })
        )
      })
      await Promise.all(updates)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['profile', id] })
      toast.success('Field permissions saved successfully')
      setIsEditingFieldPerms(false)
      setLocalFieldPermissions(new Map())
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to save field permissions')
    },
  })

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: () => apiClient.deleteResource(`/api/profiles/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['profiles'] })
      toast.success('Profile deleted successfully')
      navigate(`/${tenantSlug}/profiles`)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete profile')
    },
  })

  // Basic info handlers
  const handleEditBasicInfo = useCallback(() => {
    if (profile) {
      setEditName(profile.name)
      setEditDescription(profile.description ?? '')
      setIsEditingBasicInfo(true)
    }
  }, [profile])

  const handleCancelBasicInfo = useCallback(() => {
    setIsEditingBasicInfo(false)
  }, [])

  const handleSaveBasicInfo = useCallback(() => {
    saveBasicInfoMutation.mutate({ name: editName, description: editDescription })
  }, [saveBasicInfoMutation, editName, editDescription])

  // System permission handlers
  const handlePermissionChange = useCallback((name: string, granted: boolean) => {
    setLocalPermissions((prev) => ({ ...prev, [name]: granted }))
  }, [])

  const handleEditPermissions = useCallback(() => {
    setLocalPermissions(permissionsMap)
    setIsEditingPermissions(true)
  }, [permissionsMap])

  const handleCancelEditPermissions = useCallback(() => {
    setLocalPermissions(permissionsMap)
    setIsEditingPermissions(false)
  }, [permissionsMap])

  const handleSavePermissions = useCallback(() => {
    savePermissionsMutation.mutate(localPermissions)
  }, [savePermissionsMutation, localPermissions])

  // Object permission handlers
  const handleEditObjectPerms = useCallback(() => {
    setLocalObjectPermissions(new Map())
    setIsEditingObjectPerms(true)
  }, [])

  const handleCancelObjectPerms = useCallback(() => {
    setLocalObjectPermissions(new Map())
    setIsEditingObjectPerms(false)
  }, [])

  const handleSaveObjectPerms = useCallback(() => {
    saveObjectPermsMutation.mutate()
  }, [saveObjectPermsMutation])

  const handleObjectPermChange = useCallback(
    (collectionId: string, field: string, value: boolean) => {
      // Find the permission record by collectionId
      const perm = objectPermissions.find((op) => op.collectionId === collectionId)
      if (!perm) return
      setLocalObjectPermissions((prev) => {
        const next = new Map(prev)
        const existing = next.get(perm.id) ?? {}
        next.set(perm.id, { ...existing, [field]: value })
        return next
      })
    },
    [objectPermissions]
  )

  // Field permission handlers
  const handleEditFieldPerms = useCallback(() => {
    setLocalFieldPermissions(new Map())
    setIsEditingFieldPerms(true)
  }, [])

  const handleCancelFieldPerms = useCallback(() => {
    setLocalFieldPermissions(new Map())
    setIsEditingFieldPerms(false)
  }, [])

  const handleSaveFieldPerms = useCallback(() => {
    saveFieldPermsMutation.mutate()
  }, [saveFieldPermsMutation])

  const handleFieldPermChange = useCallback(
    (fieldId: string, visibility: string) => {
      // Find the permission record by fieldId
      const perm = fieldPermissions.find((fp) => fp.fieldId === fieldId)
      if (!perm) return
      setLocalFieldPermissions((prev) => {
        const next = new Map(prev)
        next.set(perm.id, visibility)
        return next
      })
    },
    [fieldPermissions]
  )

  // Delete handlers
  const handleDeleteClick = useCallback(() => {
    setDeleteDialogOpen(true)
  }, [])

  const handleDeleteConfirm = useCallback(() => {
    deleteMutation.mutate()
  }, [deleteMutation])

  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
  }, [])

  // Loading state
  const isLoading = isLoadingProfile
  const error = profileError

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label="Loading profile..." />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={error instanceof Error ? error : new Error('Failed to load profile')}
          onRetry={() => refetchProfile()}
        />
      </div>
    )
  }

  if (!profile) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="text-center text-muted-foreground">Profile not found.</div>
      </div>
    )
  }

  const isEditable = !profile.isSystem

  return (
    <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
      {/* Back link */}
      <Link
        to={`/${tenantSlug}/profiles`}
        className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
        data-testid="back-link"
      >
        <ArrowLeft size={14} />
        Back to Profiles
      </Link>

      {/* Header */}
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          {profile.isSystem ? (
            <Lock className="h-6 w-6 text-muted-foreground" />
          ) : (
            <Shield className="h-6 w-6 text-muted-foreground" />
          )}
          <div>
            {isEditingBasicInfo ? (
              <div className="space-y-2">
                <div>
                  <FieldLabel htmlFor="edit-profile-name">Name</FieldLabel>
                  <Input
                    id="edit-profile-name"
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    className="max-w-xs"
                    data-testid="edit-name-input"
                  />
                </div>
                <div>
                  <FieldLabel htmlFor="edit-profile-description">Description</FieldLabel>
                  <Textarea
                    id="edit-profile-description"
                    value={editDescription}
                    onChange={(e) => setEditDescription(e.target.value)}
                    className="max-w-md"
                    rows={2}
                    data-testid="edit-description-input"
                  />
                </div>
              </div>
            ) : (
              <>
                <div className="flex items-center gap-3">
                  <h1 className="text-2xl font-semibold text-foreground">{profile.name}</h1>
                  {profile.isSystem && (
                    <span
                      className={cn(
                        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
                        'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                      )}
                      data-testid="system-badge"
                    >
                      System
                    </span>
                  )}
                </div>
                {profile.description && (
                  <p className="mt-1 text-sm text-muted-foreground">{profile.description}</p>
                )}
              </>
            )}
          </div>
        </div>
        {isEditable && (
          <div className="flex gap-2">
            {isEditingBasicInfo ? (
              <>
                <Button
                  variant="outline"
                  onClick={handleCancelBasicInfo}
                  disabled={saveBasicInfoMutation.isPending}
                  data-testid="cancel-basic-info-button"
                >
                  <X size={16} className="mr-1" />
                  Cancel
                </Button>
                <Button
                  onClick={handleSaveBasicInfo}
                  disabled={!editName.trim() || saveBasicInfoMutation.isPending}
                  data-testid="save-basic-info-button"
                >
                  <Save size={16} className="mr-1" />
                  {saveBasicInfoMutation.isPending ? 'Saving...' : 'Save'}
                </Button>
              </>
            ) : (
              <>
                <Button
                  variant="outline"
                  onClick={handleEditBasicInfo}
                  data-testid="edit-basic-info-button"
                >
                  <Pencil size={16} className="mr-1" />
                  Edit
                </Button>
                <Button
                  variant="destructive"
                  onClick={handleDeleteClick}
                  data-testid="delete-button"
                >
                  <Trash2 size={16} className="mr-1" />
                  Delete
                </Button>
              </>
            )}
          </div>
        )}
      </header>

      {/* Metadata */}
      <div className="flex gap-6 text-sm text-muted-foreground">
        <span>
          Created:{' '}
          {formatDate(new Date(profile.createdAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          })}
        </span>
        <span>
          Updated:{' '}
          {formatDate(new Date(profile.updatedAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          })}
        </span>
      </div>

      {/* System Permissions */}
      <section data-testid="system-permissions-section">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-foreground">System Permissions</h2>
          {isEditable && (
            <div className="flex gap-2">
              {isEditingPermissions ? (
                <>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleCancelEditPermissions}
                    disabled={savePermissionsMutation.isPending}
                    data-testid="cancel-edit-button"
                  >
                    <X size={14} className="mr-1" />
                    Cancel
                  </Button>
                  <Button
                    size="sm"
                    onClick={handleSavePermissions}
                    disabled={!hasPermissionChanges || savePermissionsMutation.isPending}
                    data-testid="save-permissions-button"
                  >
                    <Save size={14} className="mr-1" />
                    {savePermissionsMutation.isPending ? 'Saving...' : 'Save'}
                  </Button>
                </>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleEditPermissions}
                  data-testid="edit-permissions-button"
                >
                  <Pencil size={14} className="mr-1" />
                  Edit Permissions
                </Button>
              )}
            </div>
          )}
        </div>
        <SystemPermissionChecklist
          permissions={displayedPermissions}
          onChange={handlePermissionChange}
          readOnly={!isEditable || !isEditingPermissions}
          testId="system-permissions"
        />
      </section>

      {/* Object Permissions */}
      {objectPermissionsWithNames.length > 0 && (
        <section data-testid="object-permissions-section">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-semibold text-foreground">Object Permissions</h2>
            {isEditable && (
              <div className="flex gap-2">
                {isEditingObjectPerms ? (
                  <>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={handleCancelObjectPerms}
                      disabled={saveObjectPermsMutation.isPending}
                      data-testid="cancel-object-perms-button"
                    >
                      <X size={14} className="mr-1" />
                      Cancel
                    </Button>
                    <Button
                      size="sm"
                      onClick={handleSaveObjectPerms}
                      disabled={!hasObjectPermChanges || saveObjectPermsMutation.isPending}
                      data-testid="save-object-perms-button"
                    >
                      <Save size={14} className="mr-1" />
                      {saveObjectPermsMutation.isPending ? 'Saving...' : 'Save'}
                    </Button>
                  </>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleEditObjectPerms}
                    data-testid="edit-object-perms-button"
                  >
                    <Pencil size={14} className="mr-1" />
                    Edit Permissions
                  </Button>
                )}
              </div>
            )}
          </div>
          <ObjectPermissionMatrix
            permissions={objectPermissionsWithNames}
            onChange={handleObjectPermChange}
            readOnly={!isEditable || !isEditingObjectPerms}
            testId="object-permissions"
          />
        </section>
      )}

      {/* Field Permissions */}
      {fieldPermissionCollections.length > 0 && (
        <section data-testid="field-permissions-section">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-semibold text-foreground">Field Permissions</h2>
            {isEditable && (
              <div className="flex gap-2">
                {isEditingFieldPerms ? (
                  <>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={handleCancelFieldPerms}
                      disabled={saveFieldPermsMutation.isPending}
                      data-testid="cancel-field-perms-button"
                    >
                      <X size={14} className="mr-1" />
                      Cancel
                    </Button>
                    <Button
                      size="sm"
                      onClick={handleSaveFieldPerms}
                      disabled={!hasFieldPermChanges || saveFieldPermsMutation.isPending}
                      data-testid="save-field-perms-button"
                    >
                      <Save size={14} className="mr-1" />
                      {saveFieldPermsMutation.isPending ? 'Saving...' : 'Save'}
                    </Button>
                  </>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleEditFieldPerms}
                    data-testid="edit-field-perms-button"
                  >
                    <Pencil size={14} className="mr-1" />
                    Edit Permissions
                  </Button>
                )}
              </div>
            )}
          </div>
          <FieldPermissionEditor
            collections={fieldPermissionCollections}
            fields={fieldPermissionFields}
            permissions={fieldPermissionEntries}
            onChange={handleFieldPermChange}
            readOnly={!isEditable || !isEditingFieldPerms}
            testId="field-permissions"
          />
        </section>
      )}

      {/* Custom Authorization Rules */}
      {isEditable && (
        <section data-testid="custom-rules-section">
          <h2 className="mb-4 text-lg font-semibold text-foreground">Custom authorization rules</h2>
          <CustomPolicyEditor
            profileId={id!}
            tenantId=""
            readOnly={profile.isSystem}
            testId="custom-rules"
          />
        </section>
      )}

      {/* Policy Test */}
      {isEditable && (
        <section data-testid="policy-test-section">
          <h2 className="mb-4 text-lg font-semibold text-foreground">Test Policy</h2>
          <PolicyTestPanel profileId={id} testId="policy-test" />
        </section>
      )}

      {/* Delete Confirmation Dialog */}
      <ConfirmDialog
        open={deleteDialogOpen}
        title="Delete Profile"
        message={`Are you sure you want to delete "${profile.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
        variant="danger"
      />
    </div>
  )
}
