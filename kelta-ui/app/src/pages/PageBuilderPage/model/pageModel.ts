/**
 * Page-builder v2 component/page model — the shared shape the registry, the inspector, and both
 * renderers (editor preview + runtime) agree on. See `.claude/docs/specs/page-builder-parity.md`.
 *
 * A prop value is either a literal or a {@link Binding}. In slice 2a the binding resolver is an
 * identity no-op (literals only); slice 2d makes it real. The server never parses `$bind` — it
 * round-trips the config untouched, so all binding/data resolution stays client-side (Cerbos/FLS).
 */

/** An expression binding. `$bind` holds a bare token (e.g. `record.name`); `{{…}}` is display-only. */
export interface Binding {
  $bind: string
  mode?: 'path' | 'expr'
}

export type PropValue =
  string | number | boolean | null | Binding | PropValue[] | { [key: string]: PropValue }

/** Narrowing guard: is this prop value a binding object rather than a literal? */
export function isBinding(value: unknown): value is Binding {
  return (
    !!value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    typeof (value as Binding).$bind === 'string'
  )
}

/** Per-child responsive column span on the 12-col grid (1..12 per breakpoint). */
export interface ResponsiveSpan {
  base: number
  sm?: number
  md?: number
  lg?: number
}

/** A wired event action. The runtime is implemented in slice 2e. */
export type PageAction =
  | { action: 'runFlow'; flowId: string; input?: Record<string, PropValue>; awaitResult?: boolean }
  | { action: 'navigate'; to: string; params?: Record<string, PropValue>; newTab?: boolean }
  | { action: 'openUrl'; url: PropValue; newTab?: boolean }
  | {
      action: 'createRecord' | 'updateRecord'
      collection: string
      attributes: Record<string, PropValue>
      recordId?: PropValue
    }
  | { action: 'refreshData'; dataSource: string }
  | { action: 'setVar'; name: string; value: PropValue }
  | { action: 'showToast'; level: 'info' | 'success' | 'error'; message: PropValue }

export type EventName = 'onClick' | 'onChange' | 'onSubmit' | 'onLoad'
export type EventHandlers = Partial<Record<EventName, PageAction[]>>

/**
 * The canonical builder node. Structurally compatible with the runtime `PageNode` and the legacy
 * builder `PageComponent` (extra legacy fields like `position` are tolerated and ignored).
 */
export interface PageComponent {
  id: string
  type: string
  props: Record<string, PropValue>
  events?: EventHandlers
  span?: ResponsiveSpan
  children?: PageComponent[]
}
