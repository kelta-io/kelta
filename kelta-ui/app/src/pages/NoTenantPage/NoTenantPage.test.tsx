import { describe, it, expect, beforeEach } from 'vitest'
import { useMemoryLocalStorage } from '@/test/memoryStorage'
import { render, screen, fireEvent } from '@testing-library/react'
import { NoTenantPage } from './NoTenantPage'
import { rememberTenant } from '@/lib/recentTenants'

describe('NoTenantPage', () => {
  beforeEach(() => {
    useMemoryLocalStorage()
  })

  it('asks for a tenant when this browser has never signed in anywhere', () => {
    render(<NoTenantPage />)
    expect(screen.getByText('Tenant Required')).toBeDefined()
    expect(screen.queryByTestId('recent-tenants')).toBeNull()
  })

  it('offers remembered workspaces and users instead', () => {
    rememberTenant('spotopened', { email: 'craig@example.com', name: 'Craig' }, 'SpotOpened')

    render(<NoTenantPage />)

    expect(screen.queryByText('Tenant Required')).toBeNull()
    // The workspace link keeps the slug — the thing the Home Screen launch was losing.
    const tenant = screen.getByTestId('recent-tenant-spotopened').querySelector('a')
    expect(tenant?.getAttribute('href')).toBe('/spotopened/app')
    // The user link carries a login hint for the IdP.
    expect(
      screen.getByTestId('recent-user-spotopened-craig@example.com').getAttribute('href')
    ).toBe('/spotopened/login?login_hint=craig%40example.com')
  })

  it('can forget a workspace, and falls back to the plain message when none remain', () => {
    rememberTenant('spotopened', { email: 'craig@example.com' })
    render(<NoTenantPage />)

    fireEvent.click(screen.getByTestId('forget-tenant-spotopened'))

    expect(screen.getByText('Tenant Required')).toBeDefined()
  })
})
