import { describe, it, expect, beforeEach } from 'vitest'
import { useMemoryLocalStorage } from '@/test/memoryStorage'
import { STORAGE_KEY, forgetTenant, recentTenants, rememberTenant } from './recentTenants'

describe('recentTenants', () => {
  beforeEach(() => {
    useMemoryLocalStorage()
  })

  it('is empty on a first visit', () => {
    expect(recentTenants()).toEqual([])
  })

  it('remembers a tenant and the user who signed in', () => {
    rememberTenant('spotopened', { email: 'Craig@Example.com', name: 'Craig' }, 'SpotOpened')

    const [t] = recentTenants()
    expect(t.slug).toBe('spotopened')
    expect(t.name).toBe('SpotOpened')
    // Emails are compared case-insensitively, so the same person is one entry.
    expect(t.users).toEqual([
      expect.objectContaining({ email: 'craig@example.com', name: 'Craig' }),
    ])
  })

  it('moves a repeat sign-in to the front rather than duplicating it', () => {
    rememberTenant('a', { email: 'x@example.com' }, undefined, window, new Date('2026-01-01'))
    rememberTenant('b', { email: 'x@example.com' }, undefined, window, new Date('2026-01-02'))
    rememberTenant('a', { email: 'x@example.com' }, undefined, window, new Date('2026-01-03'))

    expect(recentTenants().map((t) => t.slug)).toEqual(['a', 'b'])
    expect(recentTenants()[0].users).toHaveLength(1)
  })

  it('keeps the display name once learned, even if a later call omits it', () => {
    rememberTenant('a', { email: 'x@example.com' }, 'Acme')
    rememberTenant('a', { email: 'x@example.com' })

    expect(recentTenants()[0].name).toBe('Acme')
  })

  it('records the tenant before the user identity has loaded, then fills the user in', () => {
    // The shell renders before /me resolves; the first call has a slug and no user.
    rememberTenant('a')
    expect(recentTenants()[0].users).toEqual([])
    rememberTenant('a', { email: 'x@example.com' })
    expect(recentTenants()[0].users).toHaveLength(1)
  })

  it('never remembers the placeholder slug', () => {
    rememberTenant('default', { email: 'x@example.com' })
    expect(recentTenants()).toEqual([])
  })

  it('forgets a tenant on request', () => {
    rememberTenant('a', { email: 'x@example.com' })
    rememberTenant('b', { email: 'x@example.com' })
    forgetTenant('a')
    expect(recentTenants().map((t) => t.slug)).toEqual(['b'])
  })

  it('treats corrupt storage as empty rather than throwing', () => {
    window.localStorage.setItem(STORAGE_KEY, '{not json')
    expect(recentTenants()).toEqual([])
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify([{ nope: true }, 42]))
    expect(recentTenants()).toEqual([])
  })

  it('survives a window with no usable storage', () => {
    const hostile = {
      get localStorage(): Storage {
        throw new Error('SecurityError')
      },
    } as unknown as Window
    expect(recentTenants(hostile)).toEqual([])
    expect(() => rememberTenant('a', { email: 'x@example.com' }, undefined, hostile)).not.toThrow()
  })
})
