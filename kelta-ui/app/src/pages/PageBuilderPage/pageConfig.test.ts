import { describe, it, expect } from 'vitest'
import Ajv2020 from 'ajv/dist/2020'
import { readComponents, readConfig, mergeConfig } from './pageConfig'
import type { PageConfig } from './pageConfig'
import { PAGE_CONFIG_SCHEMA } from '../../../scripts/generate-page-widgets'
import type { PageComponent } from './PageBuilderPage'

const comp = (id: string): PageComponent => ({
  id,
  type: 'heading',
  props: {},
  position: { row: 0, column: 0, width: 12, height: 1 },
})

/**
 * Every `config` shape this file exercises, named so the JSON Schema case below can hold
 * `ui-page-config.schema.json` against the same documents the helpers are tested with.
 */
const FIXTURES = {
  empty: {},
  legacyLayoutOnly: { layout: { type: 'single' } },
  legacyComponents: { components: [comp('legacy')] },
  gridWithComponents: { layout: { type: 'grid' }, components: [comp('old')] },
  singleWithComponents: { layout: { type: 'single' }, components: [comp('keep')] },
  schemaVersionOnly: { schemaVersion: 2 },
  v2Siblings: {
    variables: [{ name: 'count', type: 'number', default: 0 }],
    dataSources: [{ name: 'orders', collection: 'orders', mode: 'list' }],
    access: { requiredPermission: 'orders:view' },
  },
  keptVariables: { variables: [{ name: 'keep', type: 'string' }], schemaVersion: 2 },
} satisfies Record<string, PageConfig>

describe('pageConfig', () => {
  describe('readComponents', () => {
    it('prefers config.components', () => {
      const page = { config: { components: [comp('a')] }, components: [comp('legacy')] }
      expect(readComponents(page).map((c) => c.id)).toEqual(['a'])
    })

    it('falls back to legacy top-level components', () => {
      expect(readComponents({ components: [comp('legacy')] }).map((c) => c.id)).toEqual(['legacy'])
    })

    it('returns [] for null/empty', () => {
      expect(readComponents(null)).toEqual([])
      expect(readComponents({})).toEqual([])
    })
  })

  describe('readConfig', () => {
    it('returns the config object or {}', () => {
      expect(
        readConfig({ config: FIXTURES.legacyLayoutOnly } as unknown as Parameters<
          typeof readConfig
        >[0])
      ).toEqual({
        layout: { type: 'single' },
      })
      expect(readConfig({})).toEqual({})
      expect(readConfig(null)).toEqual({})
    })
  })

  describe('mergeConfig', () => {
    it('overlays components while preserving layout', () => {
      const result = mergeConfig(FIXTURES.gridWithComponents, {
        components: [comp('new')],
      })
      expect(result.layout).toEqual({ type: 'grid' })
      expect(result.components?.map((c) => c.id)).toEqual(['new'])
    })

    it('overlays layout while preserving components', () => {
      const result = mergeConfig(FIXTURES.singleWithComponents, {
        layout: { type: 'sidebar' },
      })
      expect(result.layout).toEqual({ type: 'sidebar' })
      expect(result.components?.map((c) => c.id)).toEqual(['keep'])
    })

    it('overlays schemaVersion:2 while preserving the 2a-covered keys (components/layout)', () => {
      const result = mergeConfig(
        { layout: { type: 'grid' }, components: [comp('keep')] },
        { schemaVersion: 2 }
      )
      expect(result.schemaVersion).toBe(2)
      expect(result.layout).toEqual({ type: 'grid' })
      expect(result.components?.map((c) => c.id)).toEqual(['keep'])
    })

    it('overlays variables/dataSources/access when passed', () => {
      const result = mergeConfig({}, FIXTURES.v2Siblings)
      expect(result.variables).toEqual([{ name: 'count', type: 'number', default: 0 }])
      expect(result.dataSources).toEqual([{ name: 'orders', collection: 'orders', mode: 'list' }])
      expect(result.access).toEqual({ requiredPermission: 'orders:view' })
    })

    it('leaves an omitted key untouched (never wipes the existing value)', () => {
      const result = mergeConfig(FIXTURES.keptVariables, { components: [comp('x')] })
      // variables/schemaVersion omitted from changes ⇒ preserved.
      expect(result.variables).toEqual([{ name: 'keep', type: 'string' }])
      expect(result.schemaVersion).toBe(2)
    })

    it('does NOT invent schemaVersion when only components are passed (additive helper)', () => {
      const result = mergeConfig({ components: [comp('a')] }, { components: [comp('b')] })
      expect(result.schemaVersion).toBeUndefined()
    })
  })

  /**
   * `ui-page-config.schema.json` is generated from {@link PAGE_CONFIG_SCHEMA} and served by the
   * worker at `GET /api/pages/config-schema`. A schema that rejected a config these helpers
   * happily round-trip would send authors chasing a shape the platform itself does not write —
   * so every fixture above, and every config `mergeConfig` produces from them, must validate.
   */
  describe('ui-page-config.schema.json', () => {
    const validate = new Ajv2020({ strict: false }).compile(PAGE_CONFIG_SCHEMA)

    const merged = Object.values(FIXTURES).map((existing) =>
      mergeConfig(existing, { components: [comp('merged')], schemaVersion: 2 })
    )

    it.each([...Object.entries(FIXTURES), ...merged.map((c, i) => [`merged[${i}]`, c] as const)])(
      'validates %s',
      (_name, config) => {
        expect(validate(config) || validate.errors).toBe(true)
      }
    )

    it('rejects a span outside the 12-column grid', () => {
      expect(validate({ components: [{ id: 'a', type: 'heading', span: { base: 13 } }] })).toBe(
        false
      )
    })

    it('rejects a data source limit above the 200-row cap', () => {
      expect(
        validate({ dataSources: [{ name: 'orders', collection: 'orders', limit: 500 }] })
      ).toBe(false)
    })

    it('rejects a binding whose mode is not path/expr', () => {
      expect(
        validate({
          components: [
            { id: 'a', type: 'text', props: { content: { $bind: 'record.name', mode: 'sql' } } },
          ],
        })
      ).toBe(false)
    })
  })
})
