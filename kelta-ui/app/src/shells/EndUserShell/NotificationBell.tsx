/**
 * NotificationBell Component
 *
 * The caller's in-app notifications (written by the SEND_NOTIFICATION flow step) in the
 * end-user header. The badge is `meta.unreadCount` of `GET /api/me/notifications`; the
 * dropdown lists the unread ones. Opening one POSTs `/api/me/notifications/{id}/read`,
 * takes the returned count, and follows the notification's link when it has one.
 *
 * The pending approvals + unread chats inbox rides in the same dropdown as its own entry; with
 * no unread notifications but a non-empty inbox, the bell shows a dot instead of a count.
 */

import React from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, Inbox } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useApi } from '@/context/ApiContext'
import { useI18n } from '@/context/I18nContext'

interface NotificationResource {
  id: string
  type: string
  attributes: {
    title: string
    body: string
    link: string | null
    level: string
    readAt: string | null
    createdAt: string
  }
}

interface NotificationsDocument {
  data: NotificationResource[]
  meta: { unreadCount: number }
}

interface MarkReadDocument {
  meta: { unreadCount: number }
}

const UNREAD_URL = '/api/me/notifications?unread=true&page[size]=20'
const NOTIFICATIONS_QUERY_KEY = ['my-notifications', 'unread'] as const

interface NotificationBellProps {
  /** Pending approvals + unread chats */
  inboxCount?: number
  /** Open the approvals/chat inbox */
  onInboxOpen?: () => void
}

export function NotificationBell({
  inboxCount = 0,
  onInboxOpen,
}: NotificationBellProps): React.ReactElement {
  const { apiClient } = useApi()
  const { t, formatDate } = useI18n()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { tenantSlug } = useParams<{ tenantSlug: string }>()

  const { data } = useQuery({
    queryKey: NOTIFICATIONS_QUERY_KEY,
    queryFn: () => apiClient.get<NotificationsDocument>(UNREAD_URL),
    refetchInterval: 60 * 1000,
  })

  const markRead = useMutation({
    mutationFn: (id: string) =>
      apiClient.post<MarkReadDocument>(`/api/me/notifications/${encodeURIComponent(id)}/read`, {}),
    onSuccess: (response, id) => {
      queryClient.setQueryData<NotificationsDocument>(NOTIFICATIONS_QUERY_KEY, (current) =>
        current
          ? {
              data: current.data.filter((n) => n.id !== id),
              meta: { unreadCount: response.meta.unreadCount },
            }
          : current
      )
    },
  })

  const unreadCount = data?.meta.unreadCount ?? 0
  const notifications = data?.data ?? []

  const follow = (link: string) => {
    if (/^https?:\/\//i.test(link)) {
      window.location.assign(link)
    } else if (link.startsWith('/') && !link.startsWith('//')) {
      const tenantPrefix = `/${tenantSlug}`
      navigate(
        link === tenantPrefix || link.startsWith(`${tenantPrefix}/`)
          ? link
          : `${tenantPrefix}${link}`
      )
    }
  }

  const open = (notification: NotificationResource) => {
    markRead.mutate(notification.id)
    if (notification.attributes.link) {
      follow(notification.attributes.link)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className="relative h-8 w-8"
          aria-label={
            unreadCount > 0
              ? t('notificationBell.labelUnread', { count: unreadCount })
              : t('notificationBell.label')
          }
          data-testid="notification-bell"
        >
          <Bell className="h-4 w-4" />
          {unreadCount > 0 ? (
            <Badge
              variant="destructive"
              className="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center px-1 text-[10px]"
              data-testid="notification-bell-badge"
            >
              {unreadCount > 99 ? '99+' : unreadCount}
            </Badge>
          ) : (
            inboxCount > 0 && (
              <span
                className="absolute right-0.5 top-0.5 h-2 w-2 rounded-full bg-destructive"
                data-testid="notification-bell-inbox-dot"
                aria-hidden
              />
            )
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80">
        {onInboxOpen && (
          <>
            <DropdownMenuItem onClick={onInboxOpen} data-testid="notification-bell-inbox">
              <Inbox className="mr-2 h-4 w-4" />
              <span className="flex-1">{t('notificationBell.inbox')}</span>
              {inboxCount > 0 && (
                <Badge variant="secondary" className="ml-2">
                  {inboxCount > 99 ? '99+' : inboxCount}
                </Badge>
              )}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
          </>
        )}
        <DropdownMenuLabel>{t('notificationBell.title')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {notifications.length === 0 ? (
          <p className="px-2 py-3 text-sm text-muted-foreground" data-testid="notification-empty">
            {t('notificationBell.empty')}
          </p>
        ) : (
          notifications.map((notification) => (
            <DropdownMenuItem
              key={notification.id}
              onClick={() => open(notification)}
              className="flex flex-col items-start gap-0.5"
              data-testid={`notification-item-${notification.id}`}
            >
              <span className="text-sm font-medium">{notification.attributes.title}</span>
              {notification.attributes.body && (
                <span className="line-clamp-2 text-xs text-muted-foreground">
                  {notification.attributes.body}
                </span>
              )}
              <span className="text-[11px] text-muted-foreground">
                {formatDate(new Date(notification.attributes.createdAt), {
                  dateStyle: 'medium',
                  timeStyle: 'short',
                })}
              </span>
            </DropdownMenuItem>
          ))
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
