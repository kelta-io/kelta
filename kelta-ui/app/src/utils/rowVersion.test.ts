import { describe, it, expect, vi } from 'vitest'
import { isStaleWriteError } from '@/services/apiClient'
import { resolveRowIfMatch } from './rowVersion'

function client(stored: Record<string, unknown> | undefined, etag?: string) {
  return {
    getWithMeta: vi.fn(async () => ({
      data: stored ? { data: { type: 'accounts', id: 'r1', attributes: stored } } : undefined,
      etag,
    })),
  }
}

describe('resolveRowIfMatch', () => {
  it('returns the fresh ETag when the row is still the stored version', async () => {
    const api = client({ name: 'Acme', updatedAt: '2026-10-01T10:00:00Z' }, '"v1"')
    await expect(
      resolveRowIfMatch(api, 'accounts', 'r1', { id: 'r1', updatedAt: '2026-10-01T10:00:00Z' }, 'x')
    ).resolves.toBe('"v1"')
    expect(api.getWithMeta).toHaveBeenCalledWith('/api/accounts/r1')
  })

  it('throws a stale-write error when the record was saved after the row was loaded', async () => {
    const api = client({ name: 'Acme', updatedAt: '2026-10-01T10:05:00Z' }, '"v2"')
    const error = await resolveRowIfMatch(
      api,
      'accounts',
      'r1',
      { id: 'r1', updatedAt: '2026-10-01T10:00:00Z' },
      'Changed elsewhere'
    ).catch((e: unknown) => e)
    expect(isStaleWriteError(error)).toBe(true)
    expect((error as Error).message).toBe('Changed elsewhere')
  })

  it('falls back to the fresh ETag when either side has no version', async () => {
    await expect(
      resolveRowIfMatch(client({ name: 'Acme' }, '"v1"'), 'accounts', 'r1', { id: 'r1' }, 'x')
    ).resolves.toBe('"v1"')
    await expect(
      resolveRowIfMatch(client({ name: 'Acme' }, '"v1"'), 'accounts', 'r1', undefined, 'x')
    ).resolves.toBe('"v1"')
  })
})
