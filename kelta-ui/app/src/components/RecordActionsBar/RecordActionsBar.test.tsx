/**
 * RecordActionsBar — Clone routes to the create form of the record stack the bar renders in
 * (record-stack convergence P7), carrying the source record as `?clone=<id>`.
 */
import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { I18nProvider } from '@/context/I18nContext'
import { RecordStackProvider } from '@/context/RecordStackContext'
import type { RecordStackVariant } from '@/utils/recordRoutes'
import type { ApiClient } from '@/services/apiClient'
import { RecordActionsBar } from './RecordActionsBar'

vi.mock('@/context/TenantContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/context/TenantContext')>()),
  getTenantSlug: () => 'acme',
}))
vi.mock('@/components', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))

// Render the dropdown's items inline so they are clickable in jsdom (Radix pointer capture),
// as QuickActionsMenu.test does.
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: { children: React.ReactNode; asChild?: boolean }) => (
    <div>{children}</div>
  ),
  DropdownMenuContent: ({ children }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div>{children}</div>
  ),
  DropdownMenuItem: ({
    children,
    onClick,
    'data-testid': testId,
  }: React.PropsWithChildren<{ onClick?: () => void; 'data-testid'?: string }>) => (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/interactive-supports-focus
    <div role="menuitem" onClick={onClick} data-testid={testId}>
      {children}
    </div>
  ),
  DropdownMenuSeparator: () => <hr />,
}))

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

function renderBar(variant?: RecordStackVariant) {
  const apiClient = { getList: vi.fn(async () => []) } as unknown as ApiClient
  const bar = (
    <RecordActionsBar
      collectionName="accounts"
      recordId="r1"
      onEdit={vi.fn()}
      onDelete={vi.fn()}
      onBack={vi.fn()}
      isFavorite={false}
      onToggleFavorite={vi.fn()}
      apiClient={apiClient}
    />
  )
  render(
    <I18nProvider>
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/acme/resources/accounts/r1']}>
          {variant ? <RecordStackProvider variant={variant}>{bar}</RecordStackProvider> : bar}
          <LocationProbe />
        </MemoryRouter>
      </QueryClientProvider>
    </I18nProvider>
  )
}

describe('RecordActionsBar clone', () => {
  it.each([
    ['admin', '/acme/resources/accounts/new?clone=r1'],
    ['enduser', '/acme/app/o/accounts/new?clone=r1'],
  ] as const)('in the %s stack opens %s', (variant, expected) => {
    renderBar(variant)
    fireEvent.click(screen.getByTestId('actions-clone'))
    expect(screen.getByTestId('location').textContent).toBe(expected)
  })
})
