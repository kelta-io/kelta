/**
 * useInlineFieldCommit — the detail pages' in-place field save. Two commits inside one refetch
 * window must not conflict with each other: the second waits for the first and sends the version
 * the first one's refresh returned, not the stale one the page last rendered.
 */
import { describe, it, expect, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { ApiError } from '@/services/apiClient'
import { useInlineFieldCommit } from './useInlineFieldCommit'
import type { UseInlineFieldCommitOptions } from './useInlineFieldCommit'

/** A fake record server: each successful write bumps the version; a stale If-Match is a 409. */
function fakeServer(initial = 1) {
  let version = initial
  const etagOf = (v: number) => `"v${v}"`
  const writes: Array<{ field: string; ifMatch: string | undefined }> = []
  const write = vi.fn(async (field: string, _value: unknown, ifMatch: string | undefined) => {
    writes.push({ field, ifMatch })
    await Promise.resolve()
    if (ifMatch !== etagOf(version)) {
      throw new ApiError(409, 'stale', [{ field: '', message: 'stale', code: 'STALE_WRITE' }])
    }
    version += 1
  })
  const refresh = vi.fn(async () => {
    await Promise.resolve()
    return etagOf(version)
  })
  return { write, refresh, writes, etag: () => etagOf(version), bumpElsewhere: () => version++ }
}

function setup(
  options: Partial<UseInlineFieldCommitOptions> &
    Pick<UseInlineFieldCommitOptions, 'write' | 'refresh'>
) {
  return renderHook((props: UseInlineFieldCommitOptions) => useInlineFieldCommit(props), {
    initialProps: { etag: '"v1"', ...options },
  })
}

describe('useInlineFieldCommit', () => {
  it('runs two commits made in one render back to back, each with the latest version', async () => {
    const server = fakeServer()
    const onCommitted = vi.fn()
    const { result } = setup({ write: server.write, refresh: server.refresh, onCommitted })

    // Both commits start before the page re-renders with the first one's new ETag.
    await act(async () => {
      await Promise.all([result.current('name', 'Ada'), result.current('notes', 'hi')])
    })

    expect(server.writes).toEqual([
      { field: 'name', ifMatch: '"v1"' },
      { field: 'notes', ifMatch: '"v2"' },
    ])
    expect(onCommitted.mock.calls).toEqual([['name'], ['notes']])
  })

  it('sends the version a refresh returned even before the page re-renders with it', async () => {
    const server = fakeServer()
    const { result } = setup({ write: server.write, refresh: server.refresh })

    await act(() => result.current('name', 'Ada'))
    // The page still renders "v1" (no rerender) — the next commit must not send it.
    await act(() => result.current('notes', 'hi'))

    expect(server.writes.map((w) => w.ifMatch)).toEqual(['"v1"', '"v2"'])
  })

  it('follows the rendered ETag when the record changes for another reason', async () => {
    const server = fakeServer()
    const { result, rerender } = setup({ write: server.write, refresh: server.refresh })

    server.bumpElsewhere()
    rerender({ etag: server.etag(), write: server.write, refresh: server.refresh })
    await act(() => result.current('name', 'Ada'))

    expect(server.writes).toEqual([{ field: 'name', ifMatch: '"v2"' }])
  })

  it('on a real conflict reports it, reloads, rejects — and the next commit uses the reloaded version', async () => {
    const server = fakeServer()
    const onConflict = vi.fn()
    const { result } = setup({ write: server.write, refresh: server.refresh, onConflict })

    server.bumpElsewhere() // someone else saved; the page still has "v1"
    let error: unknown
    await act(async () => {
      error = await result.current('name', 'Ada').catch((e: unknown) => e)
    })
    expect(error).toBeInstanceOf(ApiError)
    expect(onConflict).toHaveBeenCalledTimes(1)
    expect(server.refresh).toHaveBeenCalledTimes(1)

    await act(() => result.current('name', 'Ada'))
    expect(server.writes.map((w) => w.ifMatch)).toEqual(['"v1"', '"v2"'])
  })

  it('keeps the queue going after a failed commit and does not treat other errors as conflicts', async () => {
    const onConflict = vi.fn()
    const write = vi
      .fn<UseInlineFieldCommitOptions['write']>()
      .mockRejectedValueOnce(new ApiError(400, 'Invalid', [{ field: 'name', message: 'Invalid' }]))
      .mockResolvedValueOnce(undefined)
    const refresh = vi.fn(async () => '"v2"')
    const { result } = setup({ write, refresh, onConflict })

    const outcomes = await act(async () =>
      Promise.allSettled([result.current('name', ''), result.current('notes', 'hi')])
    )

    expect(outcomes.map((o) => o.status)).toEqual(['rejected', 'fulfilled'])
    expect(onConflict).not.toHaveBeenCalled()
    expect(write).toHaveBeenNthCalledWith(2, 'notes', 'hi', '"v1"')
  })
})
