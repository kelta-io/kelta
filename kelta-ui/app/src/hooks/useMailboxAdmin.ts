import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'

/**
 * Administration of support mailboxes, over /api/support/mailboxes/**.
 *
 * Distinct from useMailbox, which is the agent's console. These endpoints all require
 * MANAGE_SUPPORT_MAILBOX; the console ones require only membership.
 */

export interface Mailbox {
  id: string
  name: string
  description?: string | null
  address: string
  replyFromAddress?: string | null
  replyFromName?: string | null
  verpDomain?: string | null
  webhookKey: string
  inboundProvider: string
  providerTopicArn?: string | null
  inboundSecretHint?: string | null
  inboundSecretRotatedAt?: string | null
  inboundPrevSecretExpiresAt?: string | null
  slaFirstResponseMinutes?: number | null
  slaResolutionMinutes?: number | null
  slaRiskThresholdPct?: number | null
  businessTimezone?: string | null
  autoReplyEnabled: boolean
  autoReplyMinConfidence?: number | null
  maxAutoRepliesPerThread?: number | null
  aiDraftEnabled: boolean
  requireVerifiedSenderForAccountData: boolean
  active: boolean
  /** Present only in the response that created or rotated it. Never readable again. */
  inboundSecret?: string
  inboundSecretNotice?: string
}

export interface MailboxAccessGrant {
  id: string
  mailboxId: string
  principalType: 'USER' | 'GROUP'
  principalId: string
  role: 'VIEWER' | 'AGENT' | 'MANAGER'
}

export interface EscalationContact {
  id: string
  mailboxId: string
  level: 'WARN' | 'BREACH' | 'BREACH_2' | 'BREACH_3'
  userId: string
  channels: string[] | string
}

interface AutoReplyBreakdownRow {
  outcome: string
  veto_reason?: string | null
  matched_category?: string | null
  count: number
}

interface AutoReplyDecision {
  id: string
  threadId: string
  subject?: string | null
  requesterEmail?: string | null
  outcome: 'SENT' | 'SHADOW' | 'VETOED'
  vetoReason?: string | null
  matchedCategory?: string | null
  confidence?: number | null
  ambiguous?: boolean | null
  createdAt?: string | null
}

export interface AutoReplyReport {
  shadowMode: boolean
  days: number
  breakdown: AutoReplyBreakdownRow[]
  autoReplied: number
  followedUp: number
  /** Null when nothing was auto-replied, rather than a misleading 0. */
  followUpRate: number | null
  recent: AutoReplyDecision[]
}

interface JsonApiCollection<T> {
  data: Array<{ id: string; attributes: T }>
}

interface JsonApiSingle<T> {
  data: { id: string; attributes: T }
}

const unwrapList = <T>(res: JsonApiCollection<T>) =>
  (res.data ?? []).map((r) => ({ ...r.attributes, id: r.id }))

export function useMailboxes() {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['admin-mailboxes'],
    queryFn: async () =>
      unwrapList(await apiClient.get<JsonApiCollection<Mailbox>>('/api/support/mailboxes')),
  })
}

export function useMailboxAccess(mailboxId: string | null) {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['admin-mailbox-access', mailboxId],
    queryFn: async () =>
      unwrapList(
        await apiClient.get<JsonApiCollection<MailboxAccessGrant>>(
          `/api/support/mailboxes/${mailboxId}/access`
        )
      ),
    enabled: !!mailboxId,
  })
}

export function useEscalationContacts(mailboxId: string | null) {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['admin-mailbox-escalation', mailboxId],
    queryFn: async () =>
      unwrapList(
        await apiClient.get<JsonApiCollection<EscalationContact>>(
          `/api/support/mailboxes/${mailboxId}/escalation-contacts`
        )
      ),
    enabled: !!mailboxId,
  })
}

export function useAutoReplyReport(mailboxId: string | null, days = 14) {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['admin-mailbox-autoreply', mailboxId, days],
    queryFn: () =>
      apiClient.get<AutoReplyReport>(
        `/api/support/mailboxes/${mailboxId}/auto-reply-report?days=${days}`
      ),
    enabled: !!mailboxId,
  })
}

export function useMailboxAdminActions(mailboxId?: string) {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['admin-mailboxes'] })
    void queryClient.invalidateQueries({ queryKey: ['admin-mailbox-access', mailboxId] })
    void queryClient.invalidateQueries({ queryKey: ['admin-mailbox-escalation', mailboxId] })
  }

  const create = useMutation({
    mutationFn: (attrs: Partial<Mailbox>) =>
      apiClient.post<JsonApiSingle<Mailbox>>('/api/support/mailboxes', attrs),
    onSuccess: invalidate,
  })

  const update = useMutation({
    mutationFn: (attrs: Partial<Mailbox>) =>
      apiClient.patch<JsonApiSingle<Mailbox>>(`/api/support/mailboxes/${mailboxId}`, attrs),
    onSuccess: invalidate,
  })

  const rotateSecret = useMutation({
    mutationFn: () =>
      apiClient.post<JsonApiSingle<Mailbox>>(
        `/api/support/mailboxes/${mailboxId}/rotate-secret`,
        {}
      ),
    onSuccess: invalidate,
  })

  const grantAccess = useMutation({
    mutationFn: (grant: { principalType: string; principalId: string; role: string }) =>
      apiClient.post(`/api/support/mailboxes/${mailboxId}/access`, grant),
    onSuccess: invalidate,
  })

  const revokeAccess = useMutation({
    mutationFn: (accessId: string) =>
      apiClient.delete(`/api/support/mailboxes/${mailboxId}/access/${accessId}`),
    onSuccess: invalidate,
  })

  const addEscalationContact = useMutation({
    mutationFn: (contact: { level: string; userId: string; channels: string[] }) =>
      apiClient.post(`/api/support/mailboxes/${mailboxId}/escalation-contacts`, contact),
    onSuccess: invalidate,
  })

  const removeEscalationContact = useMutation({
    mutationFn: (contactId: string) =>
      apiClient.delete(`/api/support/mailboxes/${mailboxId}/escalation-contacts/${contactId}`),
    onSuccess: invalidate,
  })

  return {
    create,
    update,
    rotateSecret,
    grantAccess,
    revokeAccess,
    addEscalationContact,
    removeEscalationContact,
  }
}
