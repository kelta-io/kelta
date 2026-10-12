import { describe, it, expect } from 'vitest'
import { parseRecordPath, recordRoutes } from './recordRoutes'

describe('recordRoutes', () => {
  describe('admin', () => {
    const routes = recordRoutes('admin', 'acme')

    it('builds the /resources record URLs', () => {
      expect(routes.variant).toBe('admin')
      expect(routes.list('orders')).toBe('/acme/resources/orders')
      expect(routes.new('orders')).toBe('/acme/resources/orders/new')
      expect(routes.detail('orders', 'r1')).toBe('/acme/resources/orders/r1')
      expect(routes.edit('orders', 'r1')).toBe('/acme/resources/orders/r1/edit')
      expect(routes.clone('orders', 'r1')).toBe('/acme/resources/orders/new?clone=r1')
    })
  })

  describe('enduser', () => {
    const routes = recordRoutes('enduser', 'acme')

    it('builds the /app/o record URLs', () => {
      expect(routes.variant).toBe('enduser')
      expect(routes.list('orders')).toBe('/acme/app/o/orders')
      expect(routes.new('orders')).toBe('/acme/app/o/orders/new')
      expect(routes.detail('orders', 'r1')).toBe('/acme/app/o/orders/r1')
      expect(routes.edit('orders', 'r1')).toBe('/acme/app/o/orders/r1/edit')
      expect(routes.clone('orders', 'r1')).toBe('/acme/app/o/orders/new?clone=r1')
    })
  })

  it('leaves valid collection names and UUIDs unchanged', () => {
    const id = '0f8b2c1e-5d4a-4b3c-9a8e-7f6d5c4b3a21'
    expect(recordRoutes('enduser', 'acme').detail('order_items', id)).toBe(
      `/acme/app/o/order_items/${id}`
    )
    expect(recordRoutes('admin', 'acme').detail('order-items', id)).toBe(
      `/acme/resources/order-items/${id}`
    )
  })

  it('encodes path segments', () => {
    const routes = recordRoutes('admin', 'acme')
    expect(routes.detail('a/b', 'x y')).toBe('/acme/resources/a%2Fb/x%20y')
    expect(routes.edit('a?b', 'id#1')).toBe('/acme/resources/a%3Fb/id%231/edit')
    expect(routes.clone('orders', 'a&b')).toBe('/acme/resources/orders/new?clone=a%26b')
  })

  it('appends a list / new query from a string, URLSearchParams or a map', () => {
    const routes = recordRoutes('enduser', 'acme')
    expect(routes.list('orders', 'view=v1&pageSize=50')).toBe(
      '/acme/app/o/orders?view=v1&pageSize=50'
    )
    expect(routes.list('orders', '?view=v1')).toBe('/acme/app/o/orders?view=v1')
    expect(routes.list('orders', new URLSearchParams({ filter: '[{"a":1}]' }))).toBe(
      `/acme/app/o/orders?filter=${encodeURIComponent('[{"a":1}]')}`
    )
    expect(routes.new('lines', { order_ref: 'r1' })).toBe('/acme/app/o/lines/new?order_ref=r1')
  })

  it('builds root-relative URLs without a tenant slug', () => {
    expect(recordRoutes('enduser', '').detail('orders', 'r1')).toBe('/app/o/orders/r1')
    expect(recordRoutes('admin', '').list('orders')).toBe('/resources/orders')
  })

  it('adds no "?" for an empty query', () => {
    const routes = recordRoutes('admin', 'acme')
    expect(routes.list('orders', '')).toBe('/acme/resources/orders')
    expect(routes.list('orders', new URLSearchParams())).toBe('/acme/resources/orders')
    expect(routes.new('orders', {})).toBe('/acme/resources/orders/new')
  })
})

describe('parseRecordPath', () => {
  it.each([
    ['/acme/resources/orders', { variant: 'admin', kind: 'list' }],
    ['/acme/resources/orders/new', { variant: 'admin', kind: 'new' }],
    ['/acme/resources/orders/r1', { variant: 'admin', kind: 'detail', id: 'r1' }],
    ['/acme/resources/orders/r1/edit', { variant: 'admin', kind: 'edit', id: 'r1' }],
    ['/acme/app/o/orders', { variant: 'enduser', kind: 'list' }],
    ['/acme/app/o/orders/', { variant: 'enduser', kind: 'list' }],
    ['/acme/app/o/orders/new', { variant: 'enduser', kind: 'new' }],
    ['/acme/app/o/orders/r1', { variant: 'enduser', kind: 'detail', id: 'r1' }],
    ['/acme/app/o/orders/r1/edit', { variant: 'enduser', kind: 'edit', id: 'r1' }],
  ])('reads %s', (path, expected) => {
    expect(parseRecordPath(path)).toEqual({ tenantSlug: 'acme', collection: 'orders', ...expected })
  })

  it('round-trips every URL recordRoutes builds', () => {
    for (const variant of ['admin', 'enduser'] as const) {
      const routes = recordRoutes(variant, 'acme')
      expect(parseRecordPath(routes.list('a/b'))).toMatchObject({ kind: 'list', collection: 'a/b' })
      expect(parseRecordPath(routes.new('orders'))).toMatchObject({ variant, kind: 'new' })
      expect(parseRecordPath(routes.detail('orders', 'x y'))).toMatchObject({
        variant,
        kind: 'detail',
        id: 'x y',
      })
      expect(parseRecordPath(routes.edit('orders', 'r1'))).toMatchObject({
        variant,
        kind: 'edit',
        id: 'r1',
      })
    }
  })

  it.each([
    '/acme/resources',
    '/acme/app/home',
    '/acme/app/o',
    '/acme/collections/orders',
    '/acme/resources/orders/new/edit',
    '/acme/resources/orders/r1/history',
    '/resources/orders',
  ])('returns null for %s', (path) => {
    expect(parseRecordPath(path)).toBeNull()
  })
})
