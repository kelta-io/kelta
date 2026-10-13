import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { MessageCircle, Video, X } from 'lucide-react'
import {
  ConversationListItem,
  MessageComposer,
  MessageList,
  type ChatMessageItem,
} from '@kelta/components'
import { useI18n } from '../../../context/I18nContext'
import { useMyIdentity } from '../../../hooks/useMyIdentity'
import { useSystemPermissions } from '../../../hooks/useSystemPermissions'
import {
  isUnread,
  useChatMessages,
  useConversationActions,
  useConversations,
  useSendChatMessage,
  useStartConversation,
  type ChatView,
} from '../../../hooks/useChat'
import { classifyVideoTokenError, useVideoToken, type VideoToken } from '../../../hooks/useVideo'
import { LazyVideoRoom } from '../VisitPage/LazyVideoRoom'
import { usePresence } from '../../../realtime/usePresence'
import { useArchives } from '../../../hooks/useArchives'
import { ArchivedThreadBanner } from './ArchivedThreadBanner'
import { cn } from '@/lib/utils'

/** UI tabs: the base chat views plus an Archived status filter (slice 7). */
type ChatTab = ChatView | 'archived'

/**
 * Staff chat console (telehealth slice 3): inbox (mine / queue / all) +
 * conversation thread over the /api/chat API. Mirrors the ApprovalsInboxPage
 * idioms; the thread reuses the @kelta/components chat primitives shared with
 * the portal chat-panel widget.
 */
export function ChatConsolePage({ testId = 'chat-console' }: { testId?: string }) {
  const { t, formatDate } = useI18n()
  const { identity } = useMyIdentity()
  const { hasPermission } = useSystemPermissions()
  const canManage = hasPermission('MANAGE_CHAT')

  // The 'archived' UI tab is a status filter over the existing views: managers
  // see the whole tenant's archived threads, others just their own.
  const [tab, setTab] = useState<ChatTab>('mine')
  const [activeId, setActiveId] = useState<string | null>(null)
  // In-thread video escalation (telehealth slice 6): a held token swaps the
  // thread body for the live VideoRoom until the agent leaves.
  const [videoGrant, setVideoGrant] = useState<VideoToken | null>(null)

  const view: ChatView = tab === 'archived' ? (canManage ? 'all' : 'mine') : tab
  const conversations = useConversations(
    view,
    tab === 'archived' ? { status: 'ARCHIVED' } : undefined
  )
  const messages = useChatMessages(activeId)
  const send = useSendChatMessage(activeId)
  const { claim, close } = useConversationActions(activeId)
  const startConversation = useStartConversation()
  const videoToken = useVideoToken()
  const presence = usePresence(activeId ? `chat:${activeId}` : null)

  const active = useMemo(
    () => (conversations.data ?? []).find((c) => c.id === activeId) ?? null,
    [conversations.data, activeId]
  )
  // Archive metadata for the active ARCHIVED conversation (banner + downloads).
  const archives = useArchives(
    { sourceType: 'CONVERSATION' },
    tab === 'archived' || active?.status === 'ARCHIVED'
  )
  const activeArchive =
    active?.status === 'ARCHIVED'
      ? ((archives.data ?? []).find((a) => a.sourceId === active.id) ?? null)
      : null
  const othersPresent = presence.filter((user) => user.id !== identity?.userId)

  const tabs: { key: ChatTab; label: string; visible: boolean }[] = [
    { key: 'mine', label: t('chat.tabMine', 'My conversations'), visible: true },
    { key: 'queue', label: t('chat.tabQueue', 'Queue'), visible: true },
    { key: 'all', label: t('chat.tabAll', 'All'), visible: canManage },
    { key: 'archived', label: t('chat.statusArchived', 'Archived'), visible: true },
  ]

  const statusLabels = {
    OPEN: t('chat.statusOpen', 'Open'),
    ASSIGNED: t('chat.statusAssigned', 'Assigned'),
    CLOSED: t('chat.statusClosed', 'Closed'),
    ARCHIVED: t('chat.statusArchived', 'Archived'),
  }

  const handleSend = async (body: string) => {
    try {
      await send.mutateAsync(body)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t('errors.generic', 'Something went wrong'))
      throw err
    }
  }

  const handleClaim = () => {
    claim.mutate(undefined, {
      onSuccess: () => toast.success(t('chat.claimSuccess', 'Conversation assigned to you')),
      onError: (err: Error) => toast.error(err.message),
    })
  }

  const handleClose = () => {
    close.mutate(undefined, {
      onSuccess: () => toast.success(t('chat.closeSuccess', 'Conversation closed')),
      onError: (err: Error) => toast.error(err.message),
    })
  }

  const handleNewConversation = () => {
    startConversation.mutate(
      { subject: t('chat.newConversationSubject', 'Internal conversation') },
      {
        onSuccess: (conversation) => setActiveId(conversation.id),
        onError: (err: Error) => toast.error(err.message),
      }
    )
  }

  const handleStartVideo = () => {
    if (!activeId) return
    videoToken.mutate(
      { kind: 'conversation', id: activeId },
      {
        onSuccess: (grant) => setVideoGrant(grant),
        onError: (err) => {
          const reason = classifyVideoTokenError(err)
          const message =
            reason === 'feature-off'
              ? t(
                  'telehealth.visit.errorFeatureOff',
                  'Video visits are not enabled for this account.'
                )
              : reason === 'budget-exhausted'
                ? t(
                    'telehealth.visit.errorBudget',
                    'The video minutes for this account have run out. Please contact support.'
                  )
                : reason === 'not-participant'
                  ? t(
                      'telehealth.visit.errorNotParticipant',
                      'You’re not a participant on this visit.'
                    )
                  : t(
                      'telehealth.visit.errorGeneric',
                      'We couldn’t start the video visit. Please try again.'
                    )
          toast.error(message)
        },
      }
    )
  }

  // The assigned agent (the person who owns the thread) can escalate to video.
  const isAssignedAgent = active != null && active.assignedTo === identity?.userId
  const canWrite = active != null && active.status !== 'CLOSED' && active.status !== 'ARCHIVED'

  return (
    <div
      className="mx-auto flex h-[calc(100vh-8rem)] max-w-[1200px] gap-4 p-6"
      data-testid={testId}
    >
      {/* Inbox rail */}
      <div className="flex w-[340px] shrink-0 flex-col overflow-hidden rounded-[10px] border border-border bg-card">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <h1 className="m-0 text-base font-semibold">{t('chat.title', 'Chat')}</h1>
          <button
            className="cursor-pointer rounded-md border border-border bg-card px-2 py-1 text-xs hover:bg-muted"
            onClick={handleNewConversation}
            data-testid={`${testId}-new`}
          >
            {t('chat.newConversation', 'New')}
          </button>
        </div>
        <div className="flex border-b border-border" role="tablist">
          {tabs
            .filter((item) => item.visible)
            .map((item) => (
              <button
                key={item.key}
                role="tab"
                aria-selected={tab === item.key}
                onClick={() => setTab(item.key)}
                className={cn(
                  'flex-1 cursor-pointer border-b-2 px-3 py-2 text-sm',
                  tab === item.key
                    ? 'border-primary font-medium text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground'
                )}
                data-testid={`${testId}-tab-${item.key}`}
              >
                {item.label}
              </button>
            ))}
        </div>
        <div className="flex-1 overflow-y-auto" data-testid={`${testId}-list`}>
          {conversations.isLoading ? (
            <div className="p-6 text-center text-sm text-muted-foreground">
              {t('common.loading', 'Loading…')}
            </div>
          ) : (conversations.data ?? []).length === 0 ? (
            <div className="flex flex-col items-center gap-2 p-8 text-muted-foreground">
              <MessageCircle size={20} />
              <p className="text-sm">{t('chat.emptyList', 'No conversations')}</p>
            </div>
          ) : (
            (conversations.data ?? []).map((conversation) => (
              <ConversationListItem
                key={conversation.id}
                conversation={conversation}
                active={conversation.id === activeId}
                unread={isUnread(conversation)}
                timeLabel={
                  conversation.lastMessageAt
                    ? formatDate(new Date(conversation.lastMessageAt), {
                        dateStyle: 'medium',
                        timeStyle: 'short',
                      })
                    : undefined
                }
                statusLabels={statusLabels}
                onClick={setActiveId}
                testId={`${testId}-item-${conversation.id}`}
              />
            ))
          )}
        </div>
      </div>

      {/* Thread */}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-[10px] border border-border bg-card">
        {active == null ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-2 text-muted-foreground">
            <MessageCircle size={24} />
            <p className="text-sm">{t('chat.selectConversation', 'Select a conversation')}</p>
          </div>
        ) : (
          <>
            <div className="flex items-center justify-between border-b border-border px-4 py-3">
              <div className="min-w-0">
                <h2 className="m-0 truncate text-sm font-semibold">
                  {active.subject || active.id}
                </h2>
                <span className="text-xs text-muted-foreground">
                  {statusLabels[active.status]}
                  {othersPresent.length > 0 &&
                    ` · ${t('chat.presentNow', '{{count}} viewing now').replace(
                      '{{count}}',
                      String(othersPresent.length)
                    )}`}
                </span>
              </div>
              <div className="flex shrink-0 gap-2">
                {isAssignedAgent && canWrite && !videoGrant && (
                  <button
                    className="flex cursor-pointer items-center gap-1 rounded-md border-none bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                    onClick={handleStartVideo}
                    disabled={videoToken.isPending}
                    data-testid={`${testId}-start-video`}
                  >
                    <Video size={14} />
                    {t('telehealth.visit.startVideo', 'Start video')}
                  </button>
                )}
                {videoGrant && (
                  <button
                    className="flex cursor-pointer items-center gap-1 rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
                    onClick={() => setVideoGrant(null)}
                    data-testid={`${testId}-end-video`}
                  >
                    <X size={14} />
                    {t('telehealth.visit.endVideo', 'End video')}
                  </button>
                )}
                {active.status === 'OPEN' && !active.assignedTo && (
                  <button
                    className="cursor-pointer rounded-md border-none bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                    onClick={handleClaim}
                    disabled={claim.isPending}
                    data-testid={`${testId}-claim`}
                  >
                    {t('chat.claim', 'Claim')}
                  </button>
                )}
                {canWrite && (
                  <button
                    className="cursor-pointer rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted disabled:opacity-50"
                    onClick={handleClose}
                    disabled={close.isPending}
                    data-testid={`${testId}-close`}
                  >
                    {t('chat.close', 'Close')}
                  </button>
                )}
              </div>
            </div>
            {activeArchive && <ArchivedThreadBanner archive={activeArchive} />}
            {videoGrant ? (
              <div className="flex-1 overflow-hidden p-3" data-testid={`${testId}-video`}>
                <LazyVideoRoom
                  serverUrl={videoGrant.url}
                  token={videoGrant.token}
                  onLeave={() => setVideoGrant(null)}
                  onError={(err) => toast.error(err.message)}
                />
              </div>
            ) : (
              <MessageList
                messages={(messages.data ?? []) as ChatMessageItem[]}
                currentUserId={identity?.userId}
                emptyText={t('chat.emptyThread', 'No messages yet — say hello')}
                testId={`${testId}-messages`}
              />
            )}
            {!videoGrant &&
              (canWrite ? (
                <MessageComposer
                  onSend={handleSend}
                  placeholder={t('chat.composerPlaceholder', 'Type a message…')}
                  sendLabel={t('chat.send', 'Send')}
                  testId={`${testId}-composer`}
                />
              ) : (
                <div className="border-t border-border bg-muted/40 p-3 text-center text-xs text-muted-foreground">
                  {active.status === 'ARCHIVED'
                    ? t('chat.readOnlyArchived', 'This conversation is archived and read-only')
                    : t('chat.readOnly', 'This conversation is closed')}
                </div>
              ))}
          </>
        )}
      </div>
    </div>
  )
}
