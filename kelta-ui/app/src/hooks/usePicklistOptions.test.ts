/**
 * `usePicklistOptions` tests (slice 2f). Verifies the picklist source resolution (FIELD vs GLOBAL via
 * `fieldTypeConfig.globalPicklistId` or the legacy pre-#1222 `picklistSourceId`/`picklistSourceType`
 * dialect, handling both the parsed-object and JSON-string forms), `isActive` filtering, `sortOrder`
 * ordering, and the empty-on-error fallback — matching `ObjectFormPage` behaviour.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import {
  usePicklistOptions,
  usePicklistDisplayMap,
  usePicklistDisplayMaps,
  resolvePicklistSource,
  resolveGlobalPicklistId,
} from './usePicklistOptions'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

type PicklistFieldInput = Pick<FieldDefinition, 'id' | 'name' | 'type' | 'fieldTypeConfig'>

const mockGetList = vi.fn()
vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: { getList: (url: string) => mockGetList(url) } }),
}))

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return React.createElement(QueryClientProvider, { client: queryClient }, children)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('resolvePicklistSource', () => {
  it('defaults to the field id with FIELD source', () => {
    expect(resolvePicklistSource({ id: 'f1', fieldTypeConfig: undefined })).toEqual({
      sourceId: 'f1',
      sourceType: 'FIELD',
    })
  })

  it('uses globalPicklistId + GLOBAL when fieldTypeConfig is an object', () => {
    expect(
      resolvePicklistSource({ id: 'f1', fieldTypeConfig: { globalPicklistId: 'gp-9' } })
    ).toEqual({ sourceId: 'gp-9', sourceType: 'GLOBAL' })
  })

  it('handles fieldTypeConfig delivered as a JSON string', () => {
    expect(
      resolvePicklistSource({ id: 'f1', fieldTypeConfig: '{"globalPicklistId":"gp-7"}' })
    ).toEqual({ sourceId: 'gp-7', sourceType: 'GLOBAL' })
  })

  it('falls back to FIELD on malformed JSON config', () => {
    expect(resolvePicklistSource({ id: 'f1', fieldTypeConfig: '{not json' })).toEqual({
      sourceId: 'f1',
      sourceType: 'FIELD',
    })
  })

  it('resolves the legacy picklistSourceId + picklistSourceType GLOBAL dialect', () => {
    // Written by the MCP admin tooling before #1222; still present on old fields.
    expect(
      resolvePicklistSource({
        id: 'f1',
        fieldTypeConfig: { picklistSourceId: 'gp-legacy', picklistSourceType: 'GLOBAL' },
      })
    ).toEqual({ sourceId: 'gp-legacy', sourceType: 'GLOBAL' })
  })

  it('ignores picklistSourceId when picklistSourceType is not GLOBAL', () => {
    expect(
      resolvePicklistSource({
        id: 'f1',
        fieldTypeConfig: { picklistSourceId: 'f1', picklistSourceType: 'FIELD' },
      })
    ).toEqual({ sourceId: 'f1', sourceType: 'FIELD' })
  })

  it('prefers globalPicklistId when both dialects are present', () => {
    expect(
      resolvePicklistSource({
        id: 'f1',
        fieldTypeConfig: {
          globalPicklistId: 'gp-modern',
          picklistSourceId: 'gp-old',
          picklistSourceType: 'GLOBAL',
        },
      })
    ).toEqual({ sourceId: 'gp-modern', sourceType: 'GLOBAL' })
  })
})

describe('resolveGlobalPicklistId', () => {
  it('reads the modern globalPicklistId key', () => {
    expect(resolveGlobalPicklistId({ globalPicklistId: 'gp-1' })).toBe('gp-1')
  })

  it('reads the legacy dialect from a JSON string config', () => {
    expect(
      resolveGlobalPicklistId('{"picklistSourceId":"gp-2","picklistSourceType":"GLOBAL"}')
    ).toBe('gp-2')
  })

  it('returns undefined for empty, malformed, or non-GLOBAL configs', () => {
    expect(resolveGlobalPicklistId(undefined)).toBeUndefined()
    expect(resolveGlobalPicklistId('{not json')).toBeUndefined()
    expect(
      resolveGlobalPicklistId({ picklistSourceId: 'x', picklistSourceType: 'FIELD' })
    ).toBeUndefined()
  })
})

describe('usePicklistOptions', () => {
  it('fetches FIELD values, drops inactive, and sorts by sortOrder', async () => {
    mockGetList.mockResolvedValueOnce([
      { value: 'b', label: 'B', isActive: true, sortOrder: 2 },
      { value: 'a', label: 'A', isActive: true, sortOrder: 1 },
      { value: 'x', label: 'X', isActive: false, sortOrder: 0 },
    ])
    const { result } = renderHook(
      () => usePicklistOptions({ id: 'f1', fieldTypeConfig: undefined }),
      {
        wrapper,
      }
    )
    await waitFor(() => expect(result.current.options).toEqual(['a', 'b']))
    expect(mockGetList).toHaveBeenCalledWith(
      expect.stringContaining('filter[picklistSourceId][eq]=f1')
    )
    expect(mockGetList).toHaveBeenCalledWith(
      expect.stringContaining('filter[picklistSourceType][eq]=FIELD')
    )
  })

  it('fetches GLOBAL values when fieldTypeConfig.globalPicklistId is set', async () => {
    mockGetList.mockResolvedValueOnce([{ value: 'g', label: 'G', isActive: true, sortOrder: 0 }])
    const { result } = renderHook(
      () => usePicklistOptions({ id: 'f1', fieldTypeConfig: { globalPicklistId: 'gp-9' } }),
      { wrapper }
    )
    await waitFor(() => expect(result.current.options).toEqual(['g']))
    expect(mockGetList).toHaveBeenCalledWith(
      expect.stringContaining('filter[picklistSourceId][eq]=gp-9')
    )
    expect(mockGetList).toHaveBeenCalledWith(
      expect.stringContaining('filter[picklistSourceType][eq]=GLOBAL')
    )
  })

  it('returns an empty list on fetch error', async () => {
    mockGetList.mockRejectedValueOnce(new Error('boom'))
    const { result } = renderHook(
      () => usePicklistOptions({ id: 'f1', fieldTypeConfig: undefined }),
      {
        wrapper,
      }
    )
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.options).toEqual([])
  })

  it('does not fetch when disabled (e.g. editor mode)', () => {
    renderHook(() => usePicklistOptions({ id: 'f1', fieldTypeConfig: undefined }, false), {
      wrapper,
    })
    expect(mockGetList).not.toHaveBeenCalled()
  })
})

describe('usePicklistDisplayMap', () => {
  it('maps raw value to label/color/description, dropping inactive values', async () => {
    mockGetList.mockResolvedValueOnce([
      {
        value: 'in_progress',
        label: 'In progress',
        color: '#F59E0B',
        isActive: true,
        sortOrder: 0,
      },
      { value: 'archived', label: 'Archived', isActive: false, sortOrder: 1 },
    ])
    const { result } = renderHook(
      () => usePicklistDisplayMap({ id: 'f1', fieldTypeConfig: undefined }),
      { wrapper }
    )
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.displayMap.get('in_progress')).toEqual({
      label: 'In progress',
      color: '#F59E0B',
      description: undefined,
    })
    expect(result.current.displayMap.has('archived')).toBe(false)
  })

  it('returns an empty map on fetch error', async () => {
    mockGetList.mockRejectedValueOnce(new Error('boom'))
    const { result } = renderHook(
      () => usePicklistDisplayMap({ id: 'f1', fieldTypeConfig: undefined }),
      { wrapper }
    )
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.displayMap.size).toBe(0)
  })

  it('does not fetch when disabled', () => {
    renderHook(() => usePicklistDisplayMap({ id: 'f1', fieldTypeConfig: undefined }, false), {
      wrapper,
    })
    expect(mockGetList).not.toHaveBeenCalled()
  })
})

describe('usePicklistDisplayMaps', () => {
  it('keys the result by field name, one entry per picklist/multi_picklist field', async () => {
    mockGetList.mockImplementation((url: string) => {
      if (url.includes('SourceId][eq]=f-stage')) {
        return Promise.resolve([
          {
            value: 'in_progress',
            label: 'In progress',
            color: '#F59E0B',
            isActive: true,
            sortOrder: 0,
          },
        ])
      }
      if (url.includes('SourceId][eq]=f-tags')) {
        return Promise.resolve([{ value: 'vip', label: 'VIP', isActive: true, sortOrder: 0 }])
      }
      return Promise.resolve([])
    })
    const fields: PicklistFieldInput[] = [
      { id: 'f-stage', name: 'stage', type: 'picklist', fieldTypeConfig: undefined },
      { id: 'f-tags', name: 'tags', type: 'multi_picklist', fieldTypeConfig: undefined },
      { id: 'f-name', name: 'name', type: 'string', fieldTypeConfig: undefined },
    ]
    const { result } = renderHook(() => usePicklistDisplayMaps(fields), { wrapper })
    await waitFor(() =>
      expect(result.current.displayMaps.stage?.get('in_progress')?.label).toBe('In progress')
    )
    expect(result.current.displayMaps.tags?.get('vip')?.label).toBe('VIP')
    expect(result.current.displayMaps.name).toBeUndefined()
  })

  it('does not fetch when disabled', () => {
    const fields: PicklistFieldInput[] = [
      { id: 'f-stage', name: 'stage', type: 'picklist', fieldTypeConfig: undefined },
    ]
    renderHook(() => usePicklistDisplayMaps(fields, false), { wrapper })
    expect(mockGetList).not.toHaveBeenCalled()
  })
})
