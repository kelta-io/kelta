/**
 * Generates the two page-authoring artifacts the worker serves, from the kelta-ui sources that own
 * them:
 *
 * - `page-widgets.json` — the built-in widget catalogue, derived from the widget registry
 *   (`src/pages/PageBuilderPage/widgets/registry.tsx` plus the built-ins that register into it).
 * - `schema/ui-page-config.schema.json` — the JSON Schema for a `ui-pages.config` document.
 *
 * Both are committed under `kelta-worker/src/main/resources/` and served by `PageWidgetsController`
 * (`GET /api/pages/widgets`, `GET /api/pages/config-schema`), so an API, MCP or CLI author can
 * discover the widget vocabulary and the config shape without reading kelta-ui source.
 *
 * `registry.freshness.test.ts` pins the committed files to this generator's output — a registry
 * change without a regeneration fails it. Regenerate with `npm run gen:page-widgets`.
 */
import { widgetRegistry } from '../src/pages/PageBuilderPage/widgets/registry'
import { registerBuiltinWidgets } from '../src/pages/PageBuilderPage/widgets/builtins'
import type {
  PropFieldSchema,
  WidgetCategory,
  WidgetDescriptor,
} from '../src/pages/PageBuilderPage/widgets/types'
import type { EventName, PropValue } from '../src/pages/PageBuilderPage/model/pageModel'
import { MAX_PAGE_DATA_SOURCES } from '../src/pages/PageBuilderPage/model/limits'

/** The server's `Pagination.MAX_HTTP_PAGE_SIZE` — a data source's own `limit` is clamped to it. */
const MAX_DATA_SOURCE_LIMIT = 200

/** One inspector-editable prop, as published. Mirrors {@link PropFieldSchema} minus inspector-only hints. */
export interface CatalogueProp {
  key: string
  label: string
  kind: PropFieldSchema['kind']
  bindable: boolean
  options?: { label: string; value: string }[]
}

/** One widget, as published — the descriptor minus its `icon` (a React component, not serializable). */
export interface CatalogueWidget {
  type: string
  label: string
  category: WidgetCategory
  acceptsChildren: boolean
  paletteHidden?: boolean
  defaultProps: Record<string, PropValue>
  propSchema: CatalogueProp[]
  supportedEvents?: EventName[]
  source: 'builtin'
}

export interface WidgetCatalogue {
  widgets: CatalogueWidget[]
}

function toCatalogueProp(prop: PropFieldSchema): CatalogueProp {
  const out: CatalogueProp = {
    key: prop.key,
    label: prop.label,
    kind: prop.kind,
    bindable: prop.bindable === true,
  }
  if (prop.options) out.options = prop.options
  return out
}

function toCatalogueWidget(descriptor: WidgetDescriptor): CatalogueWidget {
  const out: CatalogueWidget = {
    type: descriptor.type,
    label: descriptor.label,
    category: descriptor.category,
    acceptsChildren: descriptor.acceptsChildren === true,
    defaultProps: descriptor.defaultProps,
    propSchema: descriptor.propSchema.map(toCatalogueProp),
    source: 'builtin',
  }
  if (descriptor.paletteHidden) out.paletteHidden = true
  if (descriptor.supportedEvents) out.supportedEvents = descriptor.supportedEvents
  return out
}

/**
 * Build the built-in widget catalogue. Sorted by `type` so the generated file is stable regardless
 * of the order the built-in modules happen to register in.
 */
export function buildWidgetCatalogue(): WidgetCatalogue {
  registerBuiltinWidgets()
  const widgets = widgetRegistry
    .list()
    .map(toCatalogueWidget)
    .sort((a, b) => a.type.localeCompare(b.type))
  return { widgets }
}

/**
 * The JSON Schema for a `ui-pages.config` document (schemaVersion 2).
 *
 * `additionalProperties` stays open on the config and on a component: the worker round-trips
 * `config` verbatim, so a forward-compatible key written by a newer builder must not be reported
 * as invalid, and legacy component keys (`position`) still occur on pages authored before the
 * tree model landed.
 */
export const PAGE_CONFIG_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://kelta.io/schema/ui-page-config.schema.json',
  title: 'Kelta ui-pages config',
  description:
    'The `config` JSON column of a `ui-pages` record. Widget `type` values come from GET /api/pages/widgets; POST /api/ui-pages/validate checks the parts a JSON Schema cannot (unknown widget types, bindings to undeclared data sources).',
  type: 'object',
  properties: {
    schemaVersion: {
      const: 2,
      description: 'Marks the component-tree config model. Absent on pre-tree (legacy) pages.',
    },
    layout: {
      type: 'object',
      deprecated: true,
      description:
        'Inert legacy layout descriptor. The widget tree and per-child `span` own layout; this round-trips untouched.',
    },
    components: { $ref: '#/$defs/componentList' },
    variables: { type: 'array', items: { $ref: '#/$defs/variable' } },
    dataSources: {
      type: 'array',
      maxItems: MAX_PAGE_DATA_SOURCES,
      items: { $ref: '#/$defs/dataSource' },
      description: `On-load queries run client-side over the authorized JSON:API path, exposed to bindings as \`data.<name>\`. At most ${MAX_PAGE_DATA_SOURCES} per page.`,
    },
    access: {
      type: 'object',
      properties: {
        requiredPermission: {
          type: 'string',
          description: 'System permission a caller must hold for the page to render.',
        },
      },
    },
    isHomePage: {
      type: 'boolean',
      description:
        "Overrides the end-user app's default landing page. Author at most one per tenant.",
    },
  },
  additionalProperties: true,
  $defs: {
    componentList: {
      type: 'array',
      items: { $ref: '#/$defs/component' },
    },
    component: {
      type: 'object',
      required: ['type'],
      properties: {
        id: { type: 'string', description: 'Stable node id, unique within the page.' },
        type: {
          type: 'string',
          description: 'Widget type. Built-in types are listed by GET /api/pages/widgets.',
        },
        props: {
          type: 'object',
          description: 'Prop values: literals, `{{ ... }}` merge-tag strings, or `$bind` objects.',
          additionalProperties: { $ref: '#/$defs/propValue' },
        },
        events: { $ref: '#/$defs/events' },
        span: { $ref: '#/$defs/span' },
        children: { $ref: '#/$defs/componentList' },
      },
      additionalProperties: true,
    },
    propValue: {
      description:
        'A literal (string/number/boolean/null/array/object) or a binding object. A literal string may carry `{{ ... }}` merge tags.',
      if: { type: 'object', required: ['$bind'] },
      then: { $ref: '#/$defs/binding' },
    },
    binding: {
      type: 'object',
      required: ['$bind'],
      properties: {
        $bind: {
          type: 'string',
          description: 'Expression over the binding scope. Roots: record, vars, page, item, data.',
        },
        mode: { enum: ['path', 'expr'], description: 'Defaults to "path".' },
      },
    },
    events: {
      type: 'object',
      description: 'Wired actions keyed by event name.',
      propertyNames: { enum: ['onClick', 'onChange', 'onSubmit', 'onLoad'] },
      additionalProperties: { type: 'array', items: { type: 'object' } },
    },
    span: {
      type: 'object',
      description: 'Responsive column span on the 12-column grid.',
      required: ['base'],
      properties: {
        base: { $ref: '#/$defs/spanValue' },
        sm: { $ref: '#/$defs/spanValue' },
        md: { $ref: '#/$defs/spanValue' },
        lg: { $ref: '#/$defs/spanValue' },
      },
      additionalProperties: false,
    },
    spanValue: { type: 'integer', minimum: 1, maximum: 12 },
    variable: {
      type: 'object',
      required: ['name'],
      properties: {
        name: { type: 'string', description: 'Referenced by bindings as `vars.<name>`.' },
        type: { enum: ['string', 'number', 'boolean', 'json'] },
        default: {},
        kind: {
          enum: ['static', 'computed'],
          description:
            'Absent means "static". A computed variable derives its value from `expression`.',
        },
        expression: { type: 'string', description: 'Formula expression for `kind: "computed"`.' },
      },
      additionalProperties: false,
    },
    dataSource: {
      type: 'object',
      required: ['name', 'collection'],
      properties: {
        name: { type: 'string', description: 'Exposed to bindings as `data.<name>`.' },
        collection: { type: 'string' },
        fields: { type: 'array', items: { type: 'string' } },
        filter: {
          type: 'object',
          description:
            'Field-to-value map. Values are literals or `$bind` objects and are always compared with EQ; no other operator is supported.',
          additionalProperties: { $ref: '#/$defs/propValue' },
        },
        sort: { type: 'array', items: { type: 'string' } },
        limit: {
          type: 'integer',
          minimum: 1,
          maximum: MAX_DATA_SOURCE_LIMIT,
          description: `Page size, clamped to the server's ${MAX_DATA_SOURCE_LIMIT}-row HTTP cap.`,
        },
        mode: { enum: ['list', 'single'] },
        recordId: { $ref: '#/$defs/propValue' },
      },
      additionalProperties: false,
    },
  },
} as const

/** Serialize a generated artifact the way the committed file stores it. */
export function serialize(artifact: unknown): string {
  return `${JSON.stringify(artifact, null, 2)}\n`
}
