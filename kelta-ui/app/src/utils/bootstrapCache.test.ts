/**
 * Unit tests for the bootstrap menu-item tree assembly (submenus via parentId)
 * and for the failure-tolerant tenant-translation fetch.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { buildItemTree, clearBootstrapCache, fetchBootstrapConfig } from './bootstrapCache'

const item = (id: string, parentId?: string | null) => ({
  id,
  label: id,
  parentId: parentId ?? null,
})

describe('buildItemTree', () => {
  it('keeps a flat list flat', () => {
    const tree = buildItemTree([item('a'), item('b')])
    expect(tree.map((i) => i.id)).toEqual(['a', 'b'])
    expect(tree[0].children).toBeUndefined()
  })

  it('nests children under their parent preserving order', () => {
    const tree = buildItemTree([item('group'), item('a', 'group'), item('b', 'group'), item('c')])
    expect(tree.map((i) => i.id)).toEqual(['group', 'c'])
    const children = tree[0].children as Array<{ id: string }>
    expect(children.map((i) => i.id)).toEqual(['a', 'b'])
  })

  it('keeps an item with an unresolvable parentId at the top level', () => {
    const tree = buildItemTree([item('a', 'missing'), item('b')])
    expect(tree.map((i) => i.id)).toEqual(['a', 'b'])
  })

  it('falls back to the flat list when a cycle would swallow every item', () => {
    const tree = buildItemTree([item('a', 'b'), item('b', 'a')])
    expect(tree.map((i) => i.id)).toEqual(['a', 'b'])
    expect(tree[0].children).toBeUndefined()
  })

  it('ignores a self-referencing parentId', () => {
    const tree = buildItemTree([item('a', 'a')])
    expect(tree.map((i) => i.id)).toEqual(['a'])
  })
})

describe('fetchBootstrapConfig — oidc providers', () => {
  afterEach(() => {
    clearBootstrapCache()
    vi.restoreAllMocks()
  })

  it('offers only active identity providers as sign-in options', async () => {
    const provider = (id: string, attributes: Record<string, unknown>) => ({ id, attributes })
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            data: url.includes('/api/oidc-providers')
              ? [
                  provider('internal', { name: 'Internal', isInternal: true, active: true }),
                  provider('dead', { name: 'Deactivated IdP', isInternal: false, active: false }),
                  provider('legacy', { name: 'No active attribute', isInternal: false }),
                ]
              : [],
          }),
        } as Response)
      )
    )

    const config = (await fetchBootstrapConfig()) as { oidcProviders: Array<{ id: string }> }

    expect(config.oidcProviders.map((p) => p.id)).toEqual(['internal', 'legacy'])
  })
})

describe('fetchBootstrapConfig — tenant translations', () => {
  afterEach(() => {
    clearBootstrapCache()
    vi.restoreAllMocks()
  })

  const ok = (data: unknown[]) =>
    Promise.resolve({ ok: true, status: 200, json: async () => ({ data }) } as Response)

  /** Every bootstrap call succeeds except ui-translations, which answers with `status`. */
  const stubFetch = (status: number) =>
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        url.includes('/api/ui-translations')
          ? Promise.resolve({ ok: false, status, json: async () => ({}) } as Response)
          : ok([])
      )
    )

  it('warns and falls back to built-in strings when translations are rejected', async () => {
    // 401 is what a missing gateway public-paths prefix produces. The overlay must
    // degrade rather than fail, but it must not degrade silently.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    stubFetch(401)

    const config = (await fetchBootstrapConfig()) as { translations: Record<string, unknown> }

    expect(config.translations).toEqual({})
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('HTTP 401'))
  })

  it('does not fail the whole bootstrap when translations are unavailable', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    stubFetch(500)

    await expect(fetchBootstrapConfig()).resolves.toBeDefined()
  })
})
