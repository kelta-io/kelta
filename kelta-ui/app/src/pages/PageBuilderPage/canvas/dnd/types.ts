/**
 * DnD data payloads + stable id conventions for the `@dnd-kit` page canvas (slice 2c). The id helpers
 * encode the source/target kind so `onDragEnd` can route a drop without a registry lookup: palette tiles
 * are draggable sources (`palette:<type>`), canvas nodes are sortable items (`node:<id>`), and every
 * container (incl. the root) is a droppable (`container:<id|root>`).
 */

/** Attached to `useDraggable` for a palette tile. */
export interface PaletteDragData {
  source: 'palette'
  widgetType: string
}

/** Attached to `useSortable` for an existing node in the canvas. */
export interface NodeDragData {
  source: 'node'
  nodeId: string
  parentId: string | null
}

/** Attached to each droppable container + each sortable slot, read on drag-over/end. */
export interface DropData {
  /** The container node id this droppable belongs to (`null` = root canvas). */
  container: string | null
  /** Slot index within that container. */
  index: number
}

/** `palette:<type>` — a draggable palette tile. */
export const PALETTE_ID = (type: string): string => `palette:${type}`
/** `node:<id>` — a sortable canvas node. */
export const NODE_ID = (id: string): string => `node:${id}`
/** `container:<id|root>` — a droppable container (root when `null`). */
export const CONTAINER_ID = (id: string | null): string => `container:${id ?? 'root'}`

/** Narrow a dnd data blob to `PaletteDragData`. */
export function isPaletteDrag(data: unknown): data is PaletteDragData {
  return !!data && (data as PaletteDragData).source === 'palette'
}

/** Narrow a dnd data blob to `NodeDragData`. */
export function isNodeDrag(data: unknown): data is NodeDragData {
  return !!data && (data as NodeDragData).source === 'node'
}
