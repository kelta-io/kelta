import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'

export interface PendingApprovalRow {
  /** approval-step-instances row id */
  stepInstanceId: string
  /** approval-instances row id — the id the approve/reject endpoints take */
  instanceId: string
  collectionId: string | null
  collectionName: string | null
  recordId: string | null
  submittedBy: string | null
  submittedAt: string | null
}

export interface SubmissionRow {
  instanceId: string
  collectionId: string | null
  collectionName: string | null
  recordId: string | null
  status: string
  submittedAt: string | null
  completedAt: string | null
}

interface JsonApiResource {
  id: string
  type: string
  attributes: Record<string, unknown>
  relationships?: Record<string, { data?: { id: string; type: string } | null }>
}

interface JsonApiDocument {
  data: JsonApiResource[]
  included?: JsonApiResource[]
}

const PAGE = 'page[size]=50'

function findIncluded(
  doc: JsonApiDocument,
  ref: { id: string; type: string } | null | undefined
): JsonApiResource | undefined {
  if (!ref) return undefined
  return doc.included?.find((r) => r.id === ref.id && r.type === ref.type)
}

function relRef(resource: JsonApiResource, name: string) {
  return resource.relationships?.[name]?.data ?? null
}

function attrOrRelId(resource: JsonApiResource, name: string): string | null {
  const rel = relRef(resource, name)
  if (rel) return rel.id
  const attr = resource.attributes[name]
  return typeof attr === 'string' ? attr : null
}

const MY_APPROVALS_QUERY_KEY = 'my-approvals'

/**
 * The caller's approval workload over the generic JSON:API system-collection routes:
 * steps pending on them (`approval-step-instances` filtered by assignedTo) and the
 * approvals they submitted (`approval-instances` filtered by submittedBy). `include=`
 * resolves the MASTER_DETAIL parents so rows carry record/collection context without
 * N+1 fetches. `userId` must be the canonical platform_user UUID (useMyIdentity).
 */
export function useMyApprovals(userId: string | undefined) {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const pendingQuery = useQuery({
    queryKey: [MY_APPROVALS_QUERY_KEY, userId, 'pending'],
    enabled: !!userId,
    staleTime: 60 * 1000,
    queryFn: async (): Promise<PendingApprovalRow[]> => {
      const doc = await apiClient.get<JsonApiDocument>(
        `/api/approval-step-instances?filter[assignedTo][eq]=${encodeURIComponent(userId!)}` +
          `&filter[status][eq]=PENDING` +
          `&include=approvalInstanceId,approvalInstanceId.collectionId&${PAGE}`
      )
      const rows = doc.data ?? []
      return rows.map((step) => {
        const instanceRef = relRef(step, 'approvalInstanceId')
        const instance = findIncluded(doc, instanceRef)
        const instanceId = instanceRef?.id ?? attrOrRelId(step, 'approvalInstanceId')
        const collection = instance
          ? findIncluded(doc, relRef(instance, 'collectionId'))
          : undefined
        return {
          stepInstanceId: step.id,
          instanceId: instanceId ?? '',
          collectionId: instance ? attrOrRelId(instance, 'collectionId') : null,
          collectionName: collection ? ((collection.attributes.name as string) ?? null) : null,
          recordId: instance ? ((instance.attributes.recordId as string) ?? null) : null,
          submittedBy: instance ? ((instance.attributes.submittedBy as string) ?? null) : null,
          submittedAt: instance ? ((instance.attributes.submittedAt as string) ?? null) : null,
        }
      })
    },
  })

  const submissionsQuery = useQuery({
    queryKey: [MY_APPROVALS_QUERY_KEY, userId, 'submissions'],
    enabled: !!userId,
    staleTime: 60 * 1000,
    queryFn: async (): Promise<SubmissionRow[]> => {
      const doc = await apiClient.get<JsonApiDocument>(
        `/api/approval-instances?filter[submittedBy][eq]=${encodeURIComponent(userId!)}` +
          `&include=collectionId&sort=-submittedAt&${PAGE}`
      )
      const rows = doc.data ?? []
      return rows.map((instance) => {
        const collection = findIncluded(doc, relRef(instance, 'collectionId'))
        return {
          instanceId: instance.id,
          collectionId: attrOrRelId(instance, 'collectionId'),
          collectionName: collection ? ((collection.attributes.name as string) ?? null) : null,
          recordId: (instance.attributes.recordId as string) ?? null,
          status: (instance.attributes.status as string) ?? 'PENDING',
          submittedAt: (instance.attributes.submittedAt as string) ?? null,
          completedAt: (instance.attributes.completedAt as string) ?? null,
        }
      })
    },
  })

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: [MY_APPROVALS_QUERY_KEY, userId] })

  return {
    pending: pendingQuery.data ?? [],
    submissions: submissionsQuery.data ?? [],
    pendingCount: pendingQuery.data?.length ?? 0,
    isLoading: pendingQuery.isLoading || submissionsQuery.isLoading,
    error: pendingQuery.error ?? submissionsQuery.error,
    invalidate,
  }
}

/** Count-only consumer for the TopNavBar bell — same query key, shared cache. */
export function usePendingApprovalsCount(userId: string | undefined): { count: number } {
  const { pendingCount } = useMyApprovals(userId)
  return { count: pendingCount }
}
