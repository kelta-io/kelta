import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { AlertTriangle, Inbox, Mail, Paperclip, ShieldAlert, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { StatusBadge } from '@/components/kelta'
import { LoadingSpinner, ErrorMessage } from '../../../components'
import { MailboxHtmlBody } from '../../../components/MailboxHtmlBody'
import {
  formatSlaCountdown,
  isThreadUnread,
  useMailboxCounts,
  useMailboxThread,
  useMailboxThreads,
  useMyMailboxes,
  useDownloadAttachment,
  useThreadActions,
  worstSlaState,
  type MailboxAttachment,
  type MailboxMessage,
  type MailboxThread,
  type MailboxView,
  type SlaState,
} from '../../../hooks/useMailbox'

/**
 * The support mailbox console.
 *
 * Mirrors ChatConsolePage: a two-pane list/detail with tabs that re-filter one list rather than
 * swapping panels. Three things differ, and they account for everything unusual here — the
 * counterparty is unauthenticated, the body is attacker-supplied HTML, and a thread carries an SLA
 * clock that changes with time rather than with events.
 *
 * There is deliberately no separate approval queue. A reply only makes sense next to the message
 * that provoked it and the sender's verification state; a standalone list would let someone
 * approve a reply without seeing that the sender is unverified, which is the exact failure the
 * whole disclosure rule exists to prevent.
 */

const TABS: Array<{ id: MailboxView; label: string }> = [
  { id: 'open', label: 'Open' },
  { id: 'mine', label: 'Mine' },
  { id: 'unassigned', label: 'Unassigned' },
  { id: 'atRisk', label: 'At risk' },
  { id: 'closed', label: 'Closed' },
]

/** Countdown ticks per minute, not per second: the display is minute-granular. */
const COUNTDOWN_TICK_MS = 30_000

function slaVariant(state: SlaState): 'active' | 'pending' | 'failed' | 'inactive' {
  switch (state) {
    case 'BREACHED':
      return 'failed'
    case 'AT_RISK':
      return 'pending'
    case 'MET':
      return 'active'
    default:
      return 'inactive'
  }
}

/**
 * How much the sender's claimed identity is worth.
 *
 * DMARC proves the sending domain, never the person, so a pass is shown as "domain verified" and
 * never as an identity. An unrecognised value maps to unverified rather than verified — the safe
 * direction when we do not know.
 */
function senderTrust(message: MailboxMessage | undefined): {
  label: string
  variant: 'active' | 'pending' | 'failed'
  icon: typeof ShieldCheck
} {
  if (message?.dmarcResult?.toUpperCase() === 'PASS') {
    return { label: 'Domain verified', variant: 'pending', icon: ShieldCheck }
  }
  return { label: 'Unverified sender', variant: 'failed', icon: ShieldAlert }
}

export function MailboxConsolePage() {
  const [mailboxId, setMailboxId] = useState<string | undefined>(undefined)
  const [tab, setTab] = useState<MailboxView>('open')
  // A push notification or escalation email links to ?thread=<id>. Honour it as the initial
  // selection only — once the agent clicks elsewhere the URL should not drag them back.
  const [searchParams] = useSearchParams()
  const [selectedId, setSelectedId] = useState<string | null>(() => searchParams.get('thread'))
  const [showHtml, setShowHtml] = useState(false)
  const [draft, setDraft] = useState('')
  const [, forceTick] = useState(0)

  const mailboxes = useMyMailboxes()
  const threads = useMailboxThreads(tab, mailboxId)
  const counts = useMailboxCounts(mailboxId)
  const thread = useMailboxThread(selectedId)
  const actions = useThreadActions(selectedId ?? '')

  // Re-render on a timer so countdowns stay honest without the server pushing anything. Nothing
  // else would move them: SLA state changes with the clock, not with an event.
  useEffect(() => {
    const handle = window.setInterval(() => forceTick((n) => n + 1), COUNTDOWN_TICK_MS)
    return () => window.clearInterval(handle)
  }, [])

  // The detail pane fetches by id, independently of the list, so a selected thread deliberately
  // stays open after it leaves the current filter. Clearing it would eject the agent from the very
  // conversation they just resolved — and syncing selection to the list in an effect would also be
  // a cascading render for no benefit.
  const threadList = threads.data ?? []

  const latestInbound = useMemo(
    () => thread.data?.messages?.filter((m) => m.direction === 'INBOUND').at(-1),
    [thread.data]
  )

  if (mailboxes.isLoading) {
    return <LoadingSpinner />
  }
  if (mailboxes.error) {
    return <ErrorMessage error="Could not load your mailboxes." />
  }

  const available = mailboxes.data ?? []
  if (available.length === 0) {
    // An honest empty state rather than an access error: not being a member of a mailbox yet is a
    // normal situation, not a failure.
    return (
      <div className="mx-auto flex max-w-lg flex-col items-center gap-3 p-12 text-center">
        <Inbox className="h-8 w-8 text-muted-foreground" aria-hidden />
        <p className="text-sm font-medium text-foreground">No mailboxes yet</p>
        <p className="text-sm text-muted-foreground">
          You are not a member of any support mailbox. An administrator can add you from Setup →
          Support Mailboxes.
        </p>
      </div>
    )
  }

  const summary = counts.data

  return (
    <div className="mx-auto flex h-[calc(100vh-8rem)] max-w-[1200px] gap-4 p-6">
      {/* Rail */}
      <aside className="flex w-[380px] shrink-0 flex-col gap-3">
        <Select
          value={mailboxId ?? '__all__'}
          onValueChange={(v) => setMailboxId(v === '__all__' ? undefined : v)}
        >
          <SelectTrigger data-testid="mailbox-selector">
            <SelectValue placeholder="All mailboxes" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="__all__">All mailboxes</SelectItem>
            {available.map((m) => (
              <SelectItem key={m.id} value={m.id}>
                {m.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {summary && (
          <div className="flex items-center gap-2" data-testid="mailbox-summary">
            <StatusBadge variant="inactive" label={`${summary.open} open`} />
            {summary.atRisk > 0 && (
              <StatusBadge variant="pending" label={`${summary.atRisk} at risk`} />
            )}
            {summary.breached > 0 && (
              <StatusBadge variant="failed" label={`${summary.breached} breached`} />
            )}
          </div>
        )}

        <div role="tablist" className="flex flex-wrap gap-1">
          {TABS.map((t) => (
            <button
              key={t.id}
              role="tab"
              aria-selected={tab === t.id}
              onClick={() => setTab(t.id)}
              className={`rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${
                tab === t.id
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-muted'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto rounded-md border border-border">
          {threads.isLoading && <LoadingSpinner />}
          {!threads.isLoading && threadList.length === 0 && (
            <p className="p-4 text-sm text-muted-foreground">Nothing here.</p>
          )}
          {threadList.map((t) => (
            <ThreadRow
              key={t.id}
              thread={t}
              selected={t.id === selectedId}
              onSelect={() => {
                setSelectedId(t.id)
                setShowHtml(false)
                // A draft belongs to the thread it was written for; carrying it across would risk
                // sending one customer's answer to another.
                setDraft('')
              }}
            />
          ))}
        </div>
      </aside>

      {/* Thread */}
      <section className="flex min-w-0 flex-1 flex-col rounded-md border border-border">
        {!selectedId && (
          <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
            Select a conversation.
          </div>
        )}

        {selectedId && thread.isLoading && <LoadingSpinner />}
        {selectedId && thread.error && <ErrorMessage error="Could not load this conversation." />}

        {selectedId && thread.data && (
          <>
            <header className="flex items-start justify-between gap-4 border-b border-border px-4 py-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-foreground">
                  {thread.data.subject || '(no subject)'}
                </p>
                <p className="truncate text-xs text-muted-foreground">
                  {/* Address always shown, never the display name alone — the display name is
                      chosen by the sender and is the easiest thing in an email to lie with. */}
                  {thread.data.requesterName
                    ? `${thread.data.requesterName} <${thread.data.requesterEmail}>`
                    : thread.data.requesterEmail}
                </p>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <TrustBadge message={latestInbound} />
                <SlaBadge thread={thread.data} />
              </div>
            </header>

            <div className="flex items-center gap-2 border-b border-border px-4 py-2">
              <Button
                size="sm"
                variant="outline"
                disabled={actions.claim.isPending}
                onClick={() =>
                  actions.claim.mutate(undefined, {
                    onSuccess: () => toast.success('Assigned to you'),
                    onError: () => toast.error('Could not claim this conversation'),
                  })
                }
              >
                Claim
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={actions.setStatus.isPending}
                onClick={() =>
                  actions.setStatus.mutate('WAITING_ON_CUSTOMER', {
                    onSuccess: () => toast.success('SLA clock paused'),
                    onError: () => toast.error('Could not update this conversation'),
                  })
                }
              >
                Waiting on customer
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={actions.setStatus.isPending}
                onClick={() =>
                  actions.setStatus.mutate('RESOLVED', {
                    onSuccess: () => toast.success('Marked resolved'),
                    onError: () => toast.error('Could not update this conversation'),
                  })
                }
              >
                Resolve
              </Button>
            </div>

            {thread.data.escalations.length > 0 && (
              <div
                className="flex items-center gap-2 border-b border-border bg-destructive/10 px-4 py-2 text-xs text-foreground"
                data-testid="mailbox-escalation-banner"
              >
                <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden />
                <span>
                  Escalated ({thread.data.escalations[0].level}) —{' '}
                  {thread.data.escalations[0].createdAt
                    ? new Date(thread.data.escalations[0].createdAt).toLocaleString()
                    : ''}
                </span>
              </div>
            )}

            <div className="flex-1 space-y-4 overflow-y-auto p-4">
              {thread.data.messages.map((m) => (
                <MessageBubble
                  key={m.id}
                  message={m}
                  showHtml={showHtml}
                  onToggleHtml={() => setShowHtml((v) => !v)}
                />
              ))}
            </div>

            <ReplyComposer
              key={selectedId}
              disabled={actions.reply.isPending}
              onSend={(text) =>
                actions.reply.mutate(text, {
                  onSuccess: () => {
                    setDraft('')
                    toast.success('Reply sent')
                  },
                  onError: (error: unknown) => {
                    // The server names why it declined (suppressed address, mail loop, unattended
                    // recipient). Surfacing that beats a generic failure the agent cannot act on.
                    const message =
                      (error as { serverMessage?: string })?.serverMessage ??
                      'Could not send the reply'
                    toast.error(message)
                  },
                })
              }
              value={draft}
              onChange={setDraft}
            />
          </>
        )}
      </section>
    </div>
  )
}

function ThreadRow({
  thread,
  selected,
  onSelect,
}: {
  thread: MailboxThread
  selected: boolean
  onSelect: () => void
}) {
  const { state, dueAt } = worstSlaState(thread)
  const countdown = formatSlaCountdown(dueAt, state)
  const unread = isThreadUnread(thread)
  const accent =
    state === 'BREACHED'
      ? 'border-l-destructive'
      : state === 'AT_RISK'
        ? 'border-l-amber-500'
        : 'border-l-transparent'

  return (
    <button
      onClick={onSelect}
      className={`flex w-full flex-col gap-1 border-b border-l-2 border-border px-3 py-2 text-left transition-colors ${accent} ${
        selected ? 'bg-muted' : 'hover:bg-muted/50'
      }`}
      data-testid="mailbox-thread-row"
    >
      <div className="flex items-center justify-between gap-2">
        <span
          className={`truncate text-xs ${unread ? 'font-semibold text-foreground' : 'text-muted-foreground'}`}
        >
          {thread.requesterEmail}
        </span>
        {countdown && <StatusBadge variant={slaVariant(state)} label={countdown} />}
      </div>
      <span
        className={`truncate text-sm ${unread ? 'font-medium text-foreground' : 'text-foreground'}`}
      >
        {thread.subject || '(no subject)'}
      </span>
      <span className="truncate text-[11px] text-muted-foreground">
        {thread.status}
        {thread.messageCount
          ? ` · ${thread.messageCount} message${thread.messageCount === 1 ? '' : 's'}`
          : ''}
      </span>
    </button>
  )
}

function TrustBadge({ message }: { message: MailboxMessage | undefined }) {
  const trust = senderTrust(message)
  const Icon = trust.icon
  return (
    <span className="flex items-center gap-1" data-testid="mailbox-sender-trust">
      <Icon className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
      <StatusBadge variant={trust.variant} label={trust.label} />
    </span>
  )
}

function SlaBadge({ thread }: { thread: MailboxThread }) {
  const { state, dueAt } = worstSlaState(thread)
  const countdown = formatSlaCountdown(dueAt, state)
  if (!countdown) {
    return <span className="text-xs text-muted-foreground">—</span>
  }
  return (
    <span title={dueAt ? new Date(dueAt).toLocaleString() : undefined}>
      <StatusBadge variant={slaVariant(state)} label={countdown} />
    </span>
  )
}

/**
 * One attachment: its name, what it actually is, and a way to save it.
 *
 * The filename is rendered as text inside a button, never as an `<a href>` to the file. Nothing
 * here navigates to attachment content, so no sender-chosen string ever becomes a URL this app
 * follows.
 *
 * When the sniffed type and the sender's declared type disagree, both are shown. That mismatch is
 * the most useful thing an agent can know before saving a file — a `.png` that is actually
 * `text/html` is not a mislabelled image.
 */
function AttachmentRow({
  messageId,
  attachment,
}: {
  messageId: string
  attachment: MailboxAttachment
}) {
  const { download } = useDownloadAttachment()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const declared = attachment.declaredContentType
  const mismatch = !!declared && declared.toLowerCase() !== attachment.contentType.toLowerCase()
  const infected = attachment.scanStatus === 'INFECTED'
  const canDownload = attachment.downloadable !== false && !infected

  const onDownload = async () => {
    setBusy(true)
    setError(null)
    try {
      await download(messageId, attachment)
    } catch {
      setError('Could not download that attachment')
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="text-[11px] text-muted-foreground">
      <div className="flex flex-wrap items-center gap-2">
        <Paperclip className="h-3 w-3 shrink-0" aria-hidden />
        <span className="font-medium text-foreground break-all">{attachment.filename}</span>
        <span>· {attachment.contentType}</span>
        {typeof attachment.sizeBytes === 'number' && (
          <span>· {formatBytes(attachment.sizeBytes)}</span>
        )}
        {canDownload && (
          <Button
            variant="ghost"
            size="sm"
            className="h-5 px-1 text-[11px]"
            onClick={onDownload}
            disabled={busy}
            data-testid={`mailbox-attachment-download-${attachment.id}`}
          >
            {busy ? 'Saving…' : 'Download'}
          </Button>
        )}
      </div>

      {mismatch && (
        <div className="mt-0.5 text-amber-600" data-testid="mailbox-attachment-mismatch">
          Sender labelled this {declared}. Its contents are {attachment.contentType}.
        </div>
      )}
      {infected && (
        <div className="mt-0.5 text-destructive">
          Blocked: this attachment failed a malware scan.
        </div>
      )}
      {!infected && attachment.downloadable === false && (
        <div className="mt-0.5">Contents were not stored, so this cannot be downloaded.</div>
      )}
      {error && <div className="mt-0.5 text-destructive">{error}</div>}
    </li>
  )
}

/** Bytes in the units a human reads, for a size that is only ever informational. */
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function MessageBubble({
  message,
  showHtml,
  onToggleHtml,
}: {
  message: MailboxMessage
  showHtml: boolean
  onToggleHtml: () => void
}) {
  const inbound = message.direction === 'INBOUND'
  const hasHtml = !!message.bodyHtmlSanitized

  return (
    <div className={`flex ${inbound ? 'justify-start' : 'justify-end'}`}>
      <div
        className={`max-w-[85%] rounded-md border border-border p-3 ${
          inbound ? 'bg-card' : 'bg-primary/5'
        }`}
      >
        <div className="mb-1 flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
          <Mail className="h-3 w-3" aria-hidden />
          <span>{message.fromAddress ?? (inbound ? 'unknown' : 'us')}</span>
          {message.receivedAt && <span>· {new Date(message.receivedAt).toLocaleString()}</span>}
          {message.isBulk && <StatusBadge variant="inactive" label="Bulk" />}
          {message.autoSubmitted && <StatusBadge variant="inactive" label="Automated" />}
        </div>

        {/* Plain text is the default view. Most support mail is text anyway, and the safest render
            is the one that does not happen. */}
        {!showHtml && (
          <p className="whitespace-pre-wrap break-words text-sm text-foreground">
            {message.bodyText || message.snippet || '(no text body)'}
          </p>
        )}

        {showHtml && hasHtml && <MailboxHtmlBody html={message.bodyHtmlSanitized ?? ''} />}

        {hasHtml && (
          <Button
            variant="ghost"
            size="sm"
            className="mt-2 h-6 px-1 text-[11px]"
            onClick={onToggleHtml}
            data-testid="mailbox-toggle-html"
          >
            {showHtml ? 'Show plain text' : 'Show original formatting'}
          </Button>
        )}

        {(message.attachments?.length ?? 0) > 0 && (
          <ul className="mt-2 space-y-1" data-testid="mailbox-attachments">
            {message.attachments?.map((a) => (
              <AttachmentRow key={a.id} messageId={message.id} attachment={a} />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

/**
 * Plain-text reply box.
 *
 * Plain text only, deliberately: nothing here authors HTML, so nothing here can inject it. Rich
 * replies arrive with templates, which own their markup and are authored by staff rather than
 * assembled from a text box.
 *
 * There is no recipient field. The server reads the address from the thread and ignores anything
 * else, so offering one would be a lie about what the button does.
 */
function ReplyComposer({
  value,
  onChange,
  onSend,
  disabled,
}: {
  value: string
  onChange: (v: string) => void
  onSend: (text: string) => void
  disabled: boolean
}) {
  const trimmed = value.trim()
  return (
    <footer className="border-t border-border p-3">
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Write a reply…"
        rows={3}
        disabled={disabled}
        className="w-full resize-y rounded-md border border-border bg-background p-2 text-sm outline-none focus:ring-1 focus:ring-ring"
        data-testid="mailbox-reply-input"
      />
      <div className="mt-2 flex items-center justify-between">
        <span className="text-[11px] text-muted-foreground">
          Sends to the address on this conversation.
        </span>
        <Button
          size="sm"
          disabled={disabled || trimmed.length === 0}
          onClick={() => onSend(trimmed)}
          data-testid="mailbox-reply-send"
        >
          {disabled ? 'Sending…' : 'Send reply'}
        </Button>
      </div>
    </footer>
  )
}
