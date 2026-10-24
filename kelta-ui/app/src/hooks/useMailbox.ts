import { useCallback } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../context/ApiContext'

/**
 * Support mailbox data hooks over /api/support/**.
 *
 * Polling is the primary mechanism, matching useChat, and for the same structural reason: an inbox
 * is defined by the arrival of threads nobody has joined yet, and conversation-scoped push by
 * design only reaches sockets that already joined. SLA state is a second reason — it changes with
 * the clock, not with an event, so the list has to re-derive on an interval regardless.
 */

const THREADS_POLL_MS = 30_000
const SUMMARY_POLL_MS = 60_000

export type MailboxView = 'open' | 'mine' | 'unassigned' | 'atRisk' | 'closed' | 'all'

export type SlaState = 'NONE' | 'PENDING' | 'AT_RISK' | 'BREACHED' | 'MET'

export interface MailboxSummaryRef {
  id: string
  name: string
  address: string
  active: boolean
}

export interface MailboxThread {
  id: string
  mailboxId: string
  subject?: string | null
  status: string
  priority?: string | null
  assignedTo?: string | null
  requesterEmail: string
  requesterName?: string | null
  requesterVerified: boolean
  category?: string | null
  messageCount?: number | null
  lastMessageAt?: string | null
  firstResponseAt?: string | null
  slaFirstResponseDueAt?: string | null
  slaFirstResponseState?: SlaState | null
  slaResolutionDueAt?: string | null
  slaResolutionState?: SlaState | null
  resolvedAt?: string | null
  closedAt?: string | null
  lastReadAt?: string | null
}

export interface MailboxAttachment {
  id: string
  filename: string
  /** Determined server-side from the bytes, not from the sender's MIME header. */
  contentType: string
  /** What the sender claimed. Shown only when it disagrees with `contentType`. */
  declaredContentType?: string | null
  sizeBytes?: number | null
  inline?: boolean | null
  scanStatus?: string | null
  /** False when the content was never stored, or when a scan found malware. */
  downloadable?: boolean | null
}

export interface MailboxMessage {
  id: string
  direction: 'INBOUND' | 'OUTBOUND'
  kind: string
  fromAddress?: string | null
  fromName?: string | null
  toAddresses?: string | null
  subject?: string | null
  bodyText?: string | null
  bodyHtmlSanitized?: string | null
  snippet?: string | null
  spfResult?: string | null
  dkimResult?: string | null
  dmarcResult?: string | null
  spamVerdict?: string | null
  isBulk?: boolean | null
  isBounce?: boolean | null
  autoSubmitted?: string | null
  deliveryStatus?: string | null
  sentAt?: string | null
  receivedAt?: string | null
  attachments?: MailboxAttachment[]
}

interface MailboxEscalation {
  id: string
  clock: string
  level: string
  slaDueAt?: string | null
  createdAt?: string | null
}

export interface MailboxThreadDetail extends MailboxThread {
  messages: MailboxMessage[]
  escalations: MailboxEscalation[]
}

export interface MailboxCounts {
  open: number
  unassigned: number
  atRisk: number
  breached: number
}

/** JSON:API envelope unwrap — /api/support returns resource objects, not plain arrays. */
interface JsonApiCollection<T> {
  data: Array<{ id: string; attributes: T }>
}

interface JsonApiSingle<T> {
  data: { id: string; attributes: T }
}

export function useMyMailboxes() {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['support-mailboxes'],
    queryFn: async () => {
      const res = await apiClient.get<JsonApiCollection<MailboxSummaryRef>>(
        '/api/support/my-mailboxes'
      )
      return (res.data ?? []).map((r) => ({ ...r.attributes, id: r.id }))
    },
    staleTime: 5 * 60_000,
  })
}

export function useMailboxThreads(view: MailboxView, mailboxId?: string) {
  const { apiClient } = useApi()
  const params = new URLSearchParams({ view })
  if (mailboxId) params.set('mailboxId', mailboxId)

  return useQuery({
    queryKey: ['support-threads', view, mailboxId ?? null],
    queryFn: async () => {
      const res = await apiClient.get<JsonApiCollection<MailboxThread>>(
        `/api/support/threads?${params.toString()}`
      )
      return (res.data ?? []).map((r) => ({ ...r.attributes, id: r.id }))
    },
    refetchInterval: THREADS_POLL_MS,
  })
}

export function useMailboxThread(threadId: string | null) {
  const { apiClient } = useApi()
  return useQuery({
    queryKey: ['support-thread', threadId],
    queryFn: async () => {
      const res = await apiClient.get<JsonApiSingle<MailboxThreadDetail>>(
        `/api/support/threads/${threadId}`
      )
      return { ...res.data.attributes, id: res.data.id }
    },
    enabled: !!threadId,
    refetchInterval: THREADS_POLL_MS,
  })
}

export function useMailboxCounts(mailboxId?: string) {
  const { apiClient } = useApi()
  const params = new URLSearchParams()
  if (mailboxId) params.set('mailboxId', mailboxId)

  return useQuery({
    queryKey: ['support-summary', mailboxId ?? null],
    queryFn: () =>
      apiClient.get<MailboxCounts>(`/api/support/threads/summary?${params.toString()}`),
    refetchInterval: SUMMARY_POLL_MS,
  })
}

/** Claim, assign and status changes all invalidate the same keys, so they share a hook. */
export function useThreadActions(threadId: string) {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['support-thread', threadId] })
    void queryClient.invalidateQueries({ queryKey: ['support-threads'] })
    void queryClient.invalidateQueries({ queryKey: ['support-summary'] })
  }

  const claim = useMutation({
    mutationFn: () => apiClient.post(`/api/support/threads/${threadId}/claim`, {}),
    onSuccess: invalidate,
  })

  const assign = useMutation({
    mutationFn: (assignedTo: string | null) =>
      apiClient.post(`/api/support/threads/${threadId}/assign`, { assignedTo }),
    onSuccess: invalidate,
  })

  const setStatus = useMutation({
    mutationFn: (status: string) =>
      apiClient.post(`/api/support/threads/${threadId}/status`, { status }),
    onSuccess: invalidate,
  })

  const reply = useMutation({
    mutationFn: (bodyText: string) =>
      apiClient.post(`/api/support/threads/${threadId}/reply`, { bodyText }),
    onSuccess: invalidate,
  })

  return { claim, assign, setStatus, reply }
}

/**
 * True when the thread has activity the viewer has not seen.
 *
 * Closed threads are never unread: a resolved conversation should not keep drawing attention.
 */
export function isThreadUnread(thread: MailboxThread): boolean {
  if (!thread.lastMessageAt) return false
  if (['RESOLVED', 'CLOSED', 'ARCHIVED', 'SPAM'].includes(thread.status)) return false
  if (!thread.lastReadAt) return true
  return new Date(thread.lastMessageAt) > new Date(thread.lastReadAt)
}

/**
 * The SLA state to display, worst-first.
 *
 * A breach on either clock outranks at-risk on the other: showing the milder of the two would
 * under-report a conversation that is already late.
 */
export function worstSlaState(thread: MailboxThread): {
  state: SlaState
  dueAt: string | null
} {
  const candidates: Array<{ state: SlaState; dueAt: string | null }> = [
    { state: thread.slaFirstResponseState ?? 'NONE', dueAt: thread.slaFirstResponseDueAt ?? null },
    { state: thread.slaResolutionState ?? 'NONE', dueAt: thread.slaResolutionDueAt ?? null },
  ]
  const rank: Record<SlaState, number> = { BREACHED: 4, AT_RISK: 3, PENDING: 2, MET: 1, NONE: 0 }
  return candidates.reduce((worst, c) => (rank[c.state] > rank[worst.state] ? c : worst))
}

/** Minute-granular countdown text. Returns null when there is nothing meaningful to show. */
export function formatSlaCountdown(dueAt: string | null, state: SlaState): string | null {
  if (!dueAt || state === 'NONE' || state === 'MET') return null
  const deltaMs = new Date(dueAt).getTime() - Date.now()
  const overdue = deltaMs < 0
  const totalMinutes = Math.floor(Math.abs(deltaMs) / 60_000)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  const span = hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`
  return overdue ? `Overdue ${span}` : `${span} left`
}

/**
 * Downloads one attachment through the membership-checked endpoint.
 *
 * <p>Fetched as a blob rather than linked with an anchor, because the request needs the bearer
 * token an `<a href>` cannot carry. The object URL is revoked immediately after the click: it is a
 * handle to the file's bytes held in this document, and leaving it alive leaves attachment content
 * reachable from the page for the rest of the session.
 *
 * The `download` attribute is set unconditionally, so the browser saves the file instead of
 * navigating to it, whatever the response headers say. The server sends
 * `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` as well — this is the
 * third of three, not the only one.
 */
export function useDownloadAttachment(): {
  download: (messageId: string, attachment: MailboxAttachment) => Promise<void>
} {
  const { apiClient } = useApi()

  const download = useCallback(
    async (messageId: string, attachment: MailboxAttachment): Promise<void> => {
      const blob = await apiClient.getBlob(
        `/api/support/messages/${messageId}/attachments/${attachment.id}`
      )

      const url = window.URL.createObjectURL(blob)
      try {
        const a = document.createElement('a')
        a.href = url
        a.download = attachment.filename || 'attachment'
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
      } finally {
        window.URL.revokeObjectURL(url)
      }
    },
    [apiClient]
  )

  return { download }
}
