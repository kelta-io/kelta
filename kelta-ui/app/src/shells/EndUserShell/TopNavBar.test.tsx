/**
 * TopNavBar tests — collection tabs route to `…/o/<collection>` and page tabs to `…/p/<slug>`.
 *
 * Radix-backed Sheet is mocked to render its children directly (avoids ResizeObserver in jsdom).
 */
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { TopNavBar } from './TopNavBar'
import type { NavTab } from './TopNavBar'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ tenantSlug: 'couchpicks' }),
  Link: ({ to, children, ...rest }: React.PropsWithChildren<{ to: string }>) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}))

vi.mock('@/hooks/useSystemPermissions', () => ({
  useSystemPermissions: () => ({ hasPermission: () => false }),
}))

// Radix DropdownMenu (app switcher) rendered flat for jsdom.
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: React.PropsWithChildren<{ asChild?: boolean }>) => (
    <div>{children}</div>
  ),
  DropdownMenuContent: ({ children }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div>{children}</div>
  ),
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

vi.mock('@/components/ui/sheet', () => ({
  Sheet: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  SheetTrigger: ({ children }: React.PropsWithChildren<{ asChild?: boolean }>) => (
    <div>{children}</div>
  ),
  SheetContent: ({ children }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div>{children}</div>
  ),
  SheetHeader: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
  SheetTitle: ({ children }: React.PropsWithChildren) => <div>{children}</div>,
}))

const TABS: NavTab[] = [
  { key: '/resources/titles', kind: 'collection', target: 'titles', label: 'Titles' },
  { key: '/app/p/dashboard', kind: 'page', target: 'dashboard', label: 'Dashboard' },
]

describe('TopNavBar', () => {
  beforeEach(() => mockNavigate.mockClear())

  function desktopNav() {
    return within(screen.getByRole('navigation', { name: 'Object navigation' }))
  }

  it('renders both collection and page tabs', () => {
    render(<TopNavBar tabs={TABS} />)
    expect(desktopNav().getByText('Titles')).toBeInTheDocument()
    expect(desktopNav().getByText('Dashboard')).toBeInTheDocument()
  })

  it('navigates to the object list when a collection tab is clicked', () => {
    render(<TopNavBar tabs={TABS} />)
    fireEvent.click(desktopNav().getByText('Titles'))
    expect(mockNavigate).toHaveBeenCalledWith('/couchpicks/app/o/titles')
  })

  it('navigates to the custom page when a page tab is clicked', () => {
    render(<TopNavBar tabs={TABS} />)
    fireEvent.click(desktopNav().getByText('Dashboard'))
    expect(mockNavigate).toHaveBeenCalledWith('/couchpicks/app/p/dashboard')
  })

  it('appends a collection tab query string to the href and still highlights it as active', () => {
    const tabsWithQuery: NavTab[] = [
      {
        key: '/resources/tasks?view=shared:abc&pageSize=100',
        kind: 'collection',
        target: 'tasks',
        label: 'Shared Tasks',
        query: 'view=shared:abc&pageSize=100',
      },
    ]
    render(<TopNavBar tabs={tabsWithQuery} />)
    const tab = desktopNav().getByText('Shared Tasks')
    fireEvent.click(tab)
    expect(mockNavigate).toHaveBeenCalledWith(
      '/couchpicks/app/o/tasks?view=shared:abc&pageSize=100'
    )
    expect(tab).toHaveAttribute('aria-current', 'page')
  })

  it('groups page items under a Pages heading in the mobile menu', () => {
    render(<TopNavBar tabs={TABS} />)
    // Mobile sheet (mocked open) renders its Collections + Pages section headings.
    expect(screen.getByText('Collections')).toBeInTheDocument()
    expect(screen.getByText('Pages')).toBeInTheDocument()
  })
})

describe('app switcher (apps/nav v2)', () => {
  const APPS = [
    { id: 'sales', name: 'Sales', icon: 'briefcase' },
    { id: 'support', name: 'Support' },
  ]

  it('is hidden with fewer than two apps (launcher button remains)', () => {
    render(<TopNavBar tabs={TABS} apps={[APPS[0]]} activeAppId="sales" onAppChange={vi.fn()} />)
    expect(screen.queryByTestId('app-switcher-trigger')).toBeNull()
    expect(screen.getByLabelText('App launcher')).toBeInTheDocument()
  })

  it('shows the active app name and lists every app', () => {
    render(<TopNavBar tabs={TABS} apps={APPS} activeAppId="support" onAppChange={vi.fn()} />)
    expect(
      within(screen.getByTestId('app-switcher-trigger')).getByText('Support')
    ).toBeInTheDocument()
    expect(screen.getByTestId('app-switcher-sales')).toBeInTheDocument()
    expect(screen.getByTestId('app-switcher-support')).toBeInTheDocument()
  })

  it('fires onAppChange with the picked app id', () => {
    const onAppChange = vi.fn()
    render(<TopNavBar tabs={TABS} apps={APPS} activeAppId="sales" onAppChange={onAppChange} />)
    fireEvent.click(screen.getByTestId('app-switcher-support'))
    expect(onAppChange).toHaveBeenCalledWith('support')
  })
})

describe('submenu groups', () => {
  const GROUPED: NavTab[] = [
    { key: '/resources/countries', kind: 'collection', target: 'countries', label: 'Countries' },
    {
      key: 'group:faqs',
      kind: 'group',
      target: '',
      label: 'FAQs',
      children: [
        { key: '/resources/faqs', kind: 'collection', target: 'faqs', label: 'FAQs' },
        {
          key: '/resources/faq-translations',
          kind: 'collection',
          target: 'faq-translations',
          label: 'FAQ Translations',
        },
      ],
    },
  ]

  it('renders a group as a dropdown of its children', () => {
    render(<TopNavBar tabs={GROUPED} />)
    expect(screen.getByTestId('nav-group-FAQs')).toBeInTheDocument()
    expect(screen.getByTestId('nav-group-item-FAQ Translations')).toBeInTheDocument()
  })

  it('navigates to a child target when a group entry is clicked', () => {
    render(<TopNavBar tabs={GROUPED} />)
    fireEvent.click(screen.getByTestId('nav-group-item-FAQ Translations'))
    expect(mockNavigate).toHaveBeenCalledWith('/couchpicks/app/o/faq-translations')
  })

  it('flattens group children into the mobile Collections list', () => {
    render(<TopNavBar tabs={GROUPED} />)
    const nav = screen.getByLabelText('Mobile navigation')
    expect(within(nav).getByText('FAQ Translations')).toBeInTheDocument()
  })
})
