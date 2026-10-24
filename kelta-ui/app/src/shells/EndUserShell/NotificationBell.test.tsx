/**
 * NotificationBell tests — against a mocked /api/me/notifications: no badge at zero unread,
 * badge + unread list otherwise, and opening one POSTs /{id}/read, drops the count and follows
 * the tenant-relative link.
 *
 * Radix DropdownMenu is rendered flat for jsdom.
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const { mockGet, mockPost, mockNavigate } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  mockPost: vi.fn(),
  mockNavigate: vi.fn(),
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ tenantSlug: 'acme' }),
}))

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: { get: mockGet, post: mockPost } }),
}))

vi.mock('@/context/I18nContext', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, string | number>) =>
      params ? `${key}:${JSON.stringify(params)}` : key,
    formatDate: (date: Date) => date.toISOString(),
  }),
}))

vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: React.PropsWithChildren<{ asChild?: boolean }>) => (
    <div>{children}</div>
  ),
  DropdownMenuContent: ({ children }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div>{children}</div>
  ),
  DropdownMenuLabel: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  DropdownMenuSeparator: () => <hr />,
  DropdownMenuItem: ({
    children,
    onClick,
    ...rest
  }: React.PropsWithChildren<{ onClick?: () => void }>) => (
    <button type="button" onClick={onClick} {...rest}>
      {children}
    </button>
  ),
}))

import { NotificationBell } from './NotificationBell'

function notification(id: string, title: string, link: string | null = null) {
  return {
    id,
    type: 'notifications',
    attributes: {
      title,
      body: `${title} body`,
      link,
      level: 'INFO',
      readAt: null,
      createdAt: '2026-10-24T10:00:00Z',
    },
  }
}

function renderBell(props: React.ComponentProps<typeof NotificationBell> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <NotificationBell {...props} />
    </QueryClientProvider>
  )
}

describe('NotificationBell', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows no badge and an empty state when nothing is unread', async () => {
    mockGet.mockResolvedValue({ data: [], meta: { unreadCount: 0 } })
    renderBell()

    expect(await screen.findByTestId('notification-empty')).toHaveTextContent(
      'notificationBell.empty'
    )
    expect(mockGet).toHaveBeenCalledWith('/api/me/notifications?unread=true&page[size]=20')
    expect(screen.queryByTestId('notification-bell-badge')).not.toBeInTheDocument()
    expect(screen.getByTestId('notification-bell')).toHaveAttribute(
      'aria-label',
      'notificationBell.label'
    )
  })

  it('shows the unread count from meta.unreadCount and lists the unread notifications', async () => {
    mockGet.mockResolvedValue({
      data: [notification('n-1', 'Order approved'), notification('n-2', 'Shipment late')],
      meta: { unreadCount: 7 },
    })
    renderBell()

    expect(await screen.findByTestId('notification-bell-badge')).toHaveTextContent('7')
    expect(screen.getByTestId('notification-bell')).toHaveAttribute(
      'aria-label',
      'notificationBell.labelUnread:{"count":7}'
    )
    expect(screen.getByTestId('notification-item-n-1')).toHaveTextContent('Order approved')
    expect(screen.getByTestId('notification-item-n-1')).toHaveTextContent('Order approved body')
    expect(screen.getByTestId('notification-item-n-2')).toHaveTextContent('Shipment late')
    expect(screen.queryByTestId('notification-empty')).not.toBeInTheDocument()
  })

  it('caps the badge at 99+', async () => {
    mockGet.mockResolvedValue({ data: [notification('n-1', 'Hi')], meta: { unreadCount: 150 } })
    renderBell()

    expect(await screen.findByTestId('notification-bell-badge')).toHaveTextContent('99+')
  })

  it('opening a notification marks it read, drops the count and follows its link', async () => {
    const user = userEvent.setup()
    mockGet.mockResolvedValue({
      data: [
        notification('n-1', 'Order approved', '/app/orders/123'),
        notification('n-2', 'Other'),
      ],
      meta: { unreadCount: 2 },
    })
    mockPost.mockResolvedValue({ data: { id: 'n-1' }, meta: { unreadCount: 1 } })
    renderBell()

    await user.click(await screen.findByTestId('notification-item-n-1'))

    expect(mockPost).toHaveBeenCalledWith('/api/me/notifications/n-1/read', {})
    await waitFor(() =>
      expect(screen.getByTestId('notification-bell-badge')).toHaveTextContent('1')
    )
    expect(screen.queryByTestId('notification-item-n-1')).not.toBeInTheDocument()
    expect(screen.getByTestId('notification-item-n-2')).toBeInTheDocument()
    expect(mockNavigate).toHaveBeenCalledWith('/acme/app/orders/123')
  })

  it('removes the badge once the last unread notification is opened, without navigating', async () => {
    const user = userEvent.setup()
    mockGet.mockResolvedValue({ data: [notification('n-1', 'Only one')], meta: { unreadCount: 1 } })
    mockPost.mockResolvedValue({ data: { id: 'n-1' }, meta: { unreadCount: 0 } })
    renderBell()

    await user.click(await screen.findByTestId('notification-item-n-1'))

    await waitFor(() =>
      expect(screen.queryByTestId('notification-bell-badge')).not.toBeInTheDocument()
    )
    expect(screen.getByTestId('notification-empty')).toBeInTheDocument()
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('shows a dot, not a count, when only the approvals/chat inbox has items, and opens it', async () => {
    const user = userEvent.setup()
    const onInboxOpen = vi.fn()
    mockGet.mockResolvedValue({ data: [], meta: { unreadCount: 0 } })
    renderBell({ inboxCount: 2, onInboxOpen })

    expect(await screen.findByTestId('notification-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('notification-bell-badge')).not.toBeInTheDocument()
    expect(screen.getByTestId('notification-bell-inbox-dot')).toBeInTheDocument()
    expect(screen.getByTestId('notification-bell-inbox')).toHaveTextContent('2')

    await user.click(screen.getByTestId('notification-bell-inbox'))
    expect(onInboxOpen).toHaveBeenCalled()
  })
})
