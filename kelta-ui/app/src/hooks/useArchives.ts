import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'

/**
 * Telehealth archive data hooks (slice 7) over the /api/telehealth/archives
 * API. Authorization is enforced server-side: staff see the tenant; a PORTAL
 * actor is forced to their own history; retention settings + legal hold require
 * the MANAGE_DATA system permission.
 */

type ArchiveSourceType = 'CONVERSATION' | 'VIDEO_SESSION'

export interface ArchiveSummary {
  id: string
  sourceType: ArchiveSourceType
  sourceId: string
  appointmentId?: string | null
  portalUserId?: string | null
  sha256?: string | null
  archivedAt?: string | null
  archivedBy?: string | null
  retentionUntil?: string | null
  legalHold: boolean
  purgedAt?: string | null
}

interface ArchiveArtifact {
  id: string
  fileName: string
  contentType: string
  downloadUrl?: string
}

export interface ArchiveDetail extends ArchiveSummary {
  artifacts: ArchiveArtifact[]
}

export interface RetentionSettings {
  archiveAfterDays: number
  retentionYears: number
  purgeLiveAfterDays: number
}

export interface ArchiveListFilters {
  sourceType?: ArchiveSourceType
  portalUserId?: string
  from?: string
  to?: string
}

export function useArchives(filters: ArchiveListFilters = {}, enabled = true) {
  const { apiClient } = useApi()
  const params = new URLSearchParams()
  if (filters.sourceType) params.set('sourceType', filters.sourceType)
  if (filters.portalUserId) params.set('portalUserId', filters.portalUserId)
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  const search = params.toString()

  return useQuery({
    queryKey: ['telehealth-archives', search],
    queryFn: async () => {
      const response = await apiClient.get<{ data: ArchiveSummary[] }>(
        `/api/telehealth/archives${search ? `?${search}` : ''}`
      )
      return response.data
    },
    enabled,
  })
}

/** Detail incl. presigned artifact download URLs (each GET is server-side audited). */
export function useArchiveDetail(archiveId: string | null) {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['telehealth-archive', archiveId],
    queryFn: () => apiClient.get<ArchiveDetail>(`/api/telehealth/archives/${archiveId}`),
    enabled: archiveId != null,
  })
}

export function useRetentionSettings(enabled = true) {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['telehealth-retention-settings'],
    queryFn: () => apiClient.get<RetentionSettings>('/api/telehealth/retention-settings'),
    enabled,
  })
}

export function useUpdateRetentionSettings() {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (settings: RetentionSettings) =>
      apiClient.put<RetentionSettings>('/api/telehealth/retention-settings', settings),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['telehealth-retention-settings'] })
    },
  })
}

export function useSetLegalHold() {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      apiClient.post<{ id: string; legalHold: boolean }>(
        `/api/telehealth/archives/${id}/legal-hold`,
        { enabled }
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['telehealth-archives'] })
    },
  })
}
