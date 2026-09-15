import { describe, it, expect } from 'vitest'
import {
  buildWidgetCatalogue,
  serialize,
  PAGE_CONFIG_SCHEMA,
} from '../../../../scripts/generate-page-widgets'

/**
 * Freshness pin for the two page-authoring artifacts the worker serves.
 *
 * `kelta-worker/src/main/resources/page-widgets.json` is generated from this app's widget registry,
 * so a widget added, renamed, recategorised or given a new prop here — without a regeneration —
 * would leave `GET /api/pages/widgets` describing a catalogue that no longer exists, and the
 * `ui-pages` before-save hook rejecting a type the builder happily offers. Comparing the committed
 * file against a fresh generation makes that a test failure instead of a silent drift.
 *
 * Regenerate both files with `npm run gen:page-widgets`.
 */
const WORKER_RESOURCES = '../../../../../../kelta-worker/src/main/resources'

describe('generated page artifacts are in sync with the widget registry', () => {
  it('page-widgets.json matches a fresh generation', async () => {
    await expect(serialize(buildWidgetCatalogue())).toMatchFileSnapshot(
      `${WORKER_RESOURCES}/page-widgets.json`
    )
  })

  it('ui-page-config.schema.json matches a fresh generation', async () => {
    await expect(serialize(PAGE_CONFIG_SCHEMA)).toMatchFileSnapshot(
      `${WORKER_RESOURCES}/schema/ui-page-config.schema.json`
    )
  })

  it('publishes every registered built-in with the fields the catalogue contract promises', () => {
    const { widgets } = buildWidgetCatalogue()
    expect(widgets.length).toBeGreaterThan(0)
    for (const widget of widgets) {
      expect(widget).toMatchObject({
        type: expect.any(String),
        label: expect.any(String),
        category: expect.any(String),
        acceptsChildren: expect.any(Boolean),
        source: 'builtin',
      })
      expect(widget).not.toHaveProperty('icon')
      for (const prop of widget.propSchema) {
        expect(prop).toMatchObject({
          key: expect.any(String),
          kind: expect.any(String),
          bindable: expect.any(Boolean),
        })
      }
    }
  })
})
