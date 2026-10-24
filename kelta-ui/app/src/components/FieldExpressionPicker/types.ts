/**
 * Shared types for FieldExpressionPicker.
 */

import type { FieldType } from '../../hooks/useCollectionSchema'

/**
 * A field-discovery namespace that is not backed by a runtime collection — used
 * for envelope variables like `recipient.*` or `currentUser.*` that are always
 * available regardless of the related collection.
 */
export interface StaticNamespace {
  /** Path prefix, e.g. "recipient" or "currentUser". */
  name: string
  /** Human label, e.g. "Recipient". */
  label: string
  /** Flat list of leaf fields under this namespace. */
  fields: { name: string; displayName: string; type: FieldType | string }[]
}

/**
 * Function category groupings used by the Functions tab.
 */
type FunctionCategory = 'logical' | 'text' | 'math' | 'date' | 'conversion'

/**
 * Catalog entry describing a built-in formula function.
 *
 * Mirrors {@code BuiltInFunctions} on the backend so the picker can offer the
 * exact set of functions the renderer supports.
 */
export interface FunctionDef {
  name: string
  category: FunctionCategory
  /** Argument names (used to build a stub like `IF(${condition}, ${then}, ${else})`). */
  args: string[]
  /** Return type for filtering when {@link FieldExpressionPickerProps#allowedTypes} is set. */
  returnType: FieldType | 'any'
  /** One-line description shown in the function list. */
  description: string
  /** Full documentation paragraph shown when expanded. */
  docs?: string
  /** Example invocation. */
  example?: string
}

/**
 * Mode for the picker.
 *
 * - `expression`: full expression mode — fields and functions both available.
 * - `path-only`: hides functions tab; only field paths can be picked.
 */
export type FieldExpressionMode = 'expression' | 'path-only'
