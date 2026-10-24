/**
 * Pure, immutable tree mutations on a `PageComponent[]` forest. Every drop/move/resize in the
 * `@dnd-kit` canvas routes through these helpers (slice 2c) — they are the single place that knows how
 * to insert/move/remove/patch a node, so "drop only appends to root" is fixed once here. All functions
 * return a NEW tree and never mutate their input; `null` `parentId` means the root level.
 */
import type { PageComponent, ResponsiveSpan } from './pageModel'

/** A located node + the id of its parent (`null` = root). */
interface FoundNode {
  node: PageComponent
  parentId: string | null
}

/** Find a node anywhere in the tree, returning the node + its parent id (`null` = root), or `null`. */
export function findNode(tree: PageComponent[], id: string): FoundNode | null {
  const walk = (nodes: PageComponent[], parentId: string | null): FoundNode | null => {
    for (const node of nodes) {
      if (node.id === id) return { node, parentId }
      if (node.children) {
        const hit = walk(node.children, node.id)
        if (hit) return hit
      }
    }
    return null
  }
  return walk(tree, null)
}

/** The direct children of `parentId` (`null` = root). Returns the live array (read-only use only). */
function childrenOf(tree: PageComponent[], parentId: string | null): PageComponent[] {
  if (parentId === null) return tree
  const found = findNode(tree, parentId)
  return found?.node.children ?? []
}

/** Rebuild the tree applying `fn` to every node (children first), returning a new forest. */
function mapTree(
  tree: PageComponent[],
  fn: (node: PageComponent) => PageComponent
): PageComponent[] {
  return tree.map((node) => {
    const next = node.children ? { ...node, children: mapTree(node.children, fn) } : node
    return fn(next)
  })
}

/** True if `maybeChildId` is `ancestorId` itself or lives anywhere inside its subtree. */
function isDescendant(
  tree: PageComponent[],
  ancestorId: string,
  maybeChildId: string | null
): boolean {
  if (maybeChildId === null) return false
  if (maybeChildId === ancestorId) return true
  const ancestor = findNode(tree, ancestorId)?.node
  if (!ancestor?.children) return false
  return findNode(ancestor.children, maybeChildId) !== null
}

/** Return `node` with its `span` key removed entirely. */
function omitSpan(node: PageComponent): PageComponent {
  if (node.span === undefined) return node
  const { span: _omit, ...rest } = node
  void _omit
  return rest
}

/**
 * Insert `node` under `parentId` (or root when `null`) at `index` (append when omitted / out of range).
 * Pure — returns a new tree.
 */
export function insertNode(
  tree: PageComponent[],
  node: PageComponent,
  parentId: string | null,
  index?: number
): PageComponent[] {
  const insertInto = (children: PageComponent[]): PageComponent[] => {
    const next = [...children]
    const at = index === undefined || index < 0 || index > next.length ? next.length : index
    next.splice(at, 0, node)
    return next
  }

  if (parentId === null) return insertInto(tree)

  return mapTree(tree, (n) =>
    n.id === parentId ? { ...n, children: insertInto(n.children ?? []) } : n
  )
}

/** Remove the node with `id` anywhere in the tree. Returns an equivalent new tree if `id` is absent. */
export function removeNode(tree: PageComponent[], id: string): PageComponent[] {
  const strip = (nodes: PageComponent[]): PageComponent[] =>
    nodes
      .filter((n) => n.id !== id)
      .map((n) => (n.children ? { ...n, children: strip(n.children) } : n))
  return strip(tree)
}

/** Shallow-merge `patch` into the target node's `props`. No-op (new tree) when `id` is absent. */
export function updateProps(
  tree: PageComponent[],
  id: string,
  patch: Record<string, unknown>
): PageComponent[] {
  return mapTree(tree, (n) =>
    n.id === id ? { ...n, props: { ...n.props, ...patch } as PageComponent['props'] } : n
  )
}

/**
 * Move `id` to live under `toParentId` (or root when `null`) at `index`. Implemented as
 * remove-then-insert with two guards:
 *  - no-op (returns the INPUT reference) if the move resolves to the same parent + same slot,
 *  - throws if `toParentId` is `id` or a descendant of `id` (would orphan the subtree).
 * Index is interpreted AFTER removal when moving within the same parent (so drag-down-by-one is stable).
 */
export function moveNode(
  tree: PageComponent[],
  id: string,
  toParentId: string | null,
  index: number
): PageComponent[] {
  const found = findNode(tree, id)
  if (!found) return tree
  if (toParentId === id || isDescendant(tree, id, toParentId)) {
    throw new Error(`moveNode: cannot move ${id} into itself or a descendant`)
  }

  // No-op guard: same parent and the requested slot already holds this node.
  if (found.parentId === toParentId) {
    const siblings = childrenOf(tree, toParentId)
    const currentIndex = siblings.findIndex((c) => c.id === id)
    // After removal the array is one shorter, so clamp the target to the post-removal length.
    const target = Math.min(index, siblings.length - 1)
    if (currentIndex === target) return tree
  }

  const without = removeNode(tree, id)
  return insertNode(without, found.node, toParentId, index)
}

/** Set (or clear) the responsive span on a node. `undefined` removes the `span` key. No-op if absent. */
export function setSpan(
  tree: PageComponent[],
  id: string,
  span: ResponsiveSpan | undefined
): PageComponent[] {
  return mapTree(tree, (n) =>
    n.id === id ? (span === undefined ? omitSpan(n) : { ...n, span }) : n
  )
}
