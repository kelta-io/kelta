/**
 * Binding scope + the `getPath` dot/`[n]` walker, and the resolver seam.
 *
 * In slice 2a `resolveBindings` was an identity no-op declared here. Slice 2d moves the REAL resolver
 * into {@link ./resolveBindings} and this module RE-EXPORTS it, so the existing `renderTree.tsx` import
 * (`{ resolveBindings, BindingScope }` from this file) keeps working with an unchanged signature. The
 * resolved-node invariant (parent spec) still holds: props handed to a widget's `Render` are always
 * already resolved; descriptors must not resolve again (the sole exception is `list`/`repeater`, which
 * re-resolves its children under each per-row `item` scope).
 */

/** The reactive scope a binding resolves against. All roots are optional; absent roots resolve to null. */
export interface BindingScope {
  /** The current record (or repeat row via `item`). */
  record?: Record<string, unknown>
  /** Page-level variables (usePageVariables). */
  vars?: Record<string, unknown>
  /** Route params / page meta: `{ slug, path, params }`. */
  page?: { slug?: string; path?: string; params?: Record<string, string> }
  /** On-load data source results, keyed by source name (usePageDataSources). */
  data?: Record<string, unknown>
  /** Per-row scope inside a `list`/`repeater` (also aliased into `record`). */
  item?: Record<string, unknown>
}

/** Tokens that could traverse the prototype chain — refused (return null) per parent §"Security". */
const UNSAFE_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

/**
 * Walk a dotted/indexed path against the scope. Supports `a.b`, `a[0]`, `a.b[2].c`.
 * Returns `null` for any missing segment (never throws) — matches the formula engine's missing-leaf
 * semantics so `mode:'path'` and `mode:'expr'` agree on absent values.
 *
 * Prototype-pollution guard (parent §"Security"): any `__proto__`/`constructor`/`prototype` token
 * short-circuits to `null` so a bound path can never reach the prototype chain.
 */
export function getPath(scope: BindingScope, path: string): unknown {
  if (!path) return null
  // Tokenize "a.b[0].c" → ['a','b','0','c'].
  const tokens = path
    .replace(/\[(\d+)\]/g, '.$1')
    .split('.')
    .filter((t) => t.length > 0)
  if (tokens.length === 0) return null
  let cur: unknown = scope
  for (const tok of tokens) {
    if (UNSAFE_KEYS.has(tok)) return null // refuse prototype-chain traversal
    if (cur == null) return null
    if (typeof cur !== 'object') return null
    cur = (cur as Record<string, unknown>)[tok]
  }
  return cur === undefined ? null : cur
}

// Re-export the real resolver so the 2a render path's import is unchanged.
export { resolveBindings } from './resolveBindings'
