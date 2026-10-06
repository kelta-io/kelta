import { describe, it, expect, beforeEach } from 'vitest'
import { useMemoryLocalStorage } from '@/test/memoryStorage'
import { hasStoredSession, tenantTokensKey } from './authStorage'

describe('authStorage', () => {
  beforeEach(() => {
    useMemoryLocalStorage()
  })

  it('keys tokens per tenant, and by the bare key on a custom domain', () => {
    expect(tenantTokensKey('acme')).toBe('kelta_auth_tokens:acme')
    expect(tenantTokensKey(null)).toBe('kelta_auth_tokens')
  })

  it('treats a refresh token, or an unexpired access token, as a live session', () => {
    const now = 1_000_000
    localStorage.setItem(
      tenantTokensKey('refreshable'),
      JSON.stringify({ accessToken: 'a', refreshToken: 'r', expiresAt: now - 1 })
    )
    localStorage.setItem(
      tenantTokensKey('fresh'),
      JSON.stringify({ accessToken: 'a', expiresAt: now + 60_000 })
    )
    localStorage.setItem(
      tenantTokensKey('stale'),
      JSON.stringify({ accessToken: 'a', expiresAt: now - 1 })
    )
    localStorage.setItem(tenantTokensKey('garbled'), '{not json')

    expect(hasStoredSession('refreshable', window, now)).toBe(true)
    expect(hasStoredSession('fresh', window, now)).toBe(true)
    expect(hasStoredSession('stale', window, now)).toBe(false)
    expect(hasStoredSession('garbled', window, now)).toBe(false)
    expect(hasStoredSession('never', window, now)).toBe(false)
  })
})
