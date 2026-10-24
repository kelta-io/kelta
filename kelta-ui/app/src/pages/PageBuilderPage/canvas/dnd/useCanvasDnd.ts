/**
 * The canvas DnD controller (slice 2c). Owns the sensors (Pointer + Keyboard for a11y), the active-drag
 * state for the `DragOverlay`, and the `onDragEnd` routing that turns a drop into a single `treeOps` call:
 *  - palette source → `insertNode` at the drop container + index (descriptor `defaultProps` applied),
 *  - existing node  → `moveNode` to the drop container + index (no-op + cycle guards live in `treeOps`).
 * The new tree is handed back through `onChange`; if a move is a no-op (`treeOps` returns the input
 * reference), `onChange` is NOT called so an idle drop never marks the page dirty.
 */
import { useCallback, useMemo, useState } from 'react'
import {
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import { sortableKeyboardCoordinates } from '@dnd-kit/sortable'
import type { PageComponent } from '../../model/pageModel'
import { insertNode, moveNode } from '../../model/treeOps'
import { widgetRegistry } from '../../widgets/registry'
import { isNodeDrag, isPaletteDrag, type DropData } from './types'

let nodeSeq = 0

/** Generate a unique node id for a palette-dropped widget. */
function generateNodeId(): string {
  nodeSeq += 1
  return `comp_${Date.now().toString(36)}_${nodeSeq.toString(36)}`
}

/** Build a fresh node for `widgetType`, seeded with the descriptor's default props. */
function makeNode(widgetType: string): PageComponent {
  const descriptor = widgetRegistry.get(widgetType)
  return {
    id: generateNodeId(),
    type: widgetType,
    props: { ...descriptor.defaultProps },
  }
}

interface ActiveDrag {
  /** A palette widget type, or an existing node id (for the `DragOverlay` preview). */
  source: 'palette' | 'node'
  widgetType?: string
  nodeId?: string
}

export interface UseCanvasDnd {
  sensors: ReturnType<typeof useSensors>
  onDragStart: (event: DragStartEvent) => void
  onDragEnd: (event: DragEndEvent) => void
  onDragCancel: () => void
  activeDrag: ActiveDrag | null
}

export interface UseCanvasDndArgs {
  tree: PageComponent[]
  /** Called with the new tree on a real insert/move (NOT on a no-op drop). */
  onChange: (next: PageComponent[]) => void
  /** Optional select callback fired when a palette node is dropped (selects the new node). */
  onSelectNew?: (id: string) => void
}

/** Wire the canvas DnD: sensors, active-drag state, and the palette-vs-node `onDragEnd` routing. */
export function useCanvasDnd({ tree, onChange, onSelectNew }: UseCanvasDndArgs): UseCanvasDnd {
  const [activeDrag, setActiveDrag] = useState<ActiveDrag | null>(null)

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  )

  const onDragStart = useCallback((event: DragStartEvent) => {
    const data = event.active.data.current
    if (isPaletteDrag(data)) {
      setActiveDrag({ source: 'palette', widgetType: data.widgetType })
    } else if (isNodeDrag(data)) {
      setActiveDrag({ source: 'node', nodeId: data.nodeId })
    }
  }, [])

  const onDragCancel = useCallback(() => setActiveDrag(null), [])

  const onDragEnd = useCallback(
    (event: DragEndEvent) => {
      setActiveDrag(null)
      const active = event.active.data.current
      const over = event.over?.data.current as DropData | undefined
      if (!over) return // dropped on nothing

      if (isPaletteDrag(active)) {
        const node = makeNode(active.widgetType)
        onChange(insertNode(tree, node, over.container, over.index))
        onSelectNew?.(node.id)
        return
      }

      if (isNodeDrag(active)) {
        let next: PageComponent[]
        try {
          next = moveNode(tree, active.nodeId, over.container, over.index)
        } catch {
          // Cycle guard (move into self/descendant) — ignore the illegal drop.
          return
        }
        // No-op guard: treeOps returns the input reference when nothing moved.
        if (next !== tree) onChange(next)
      }
    },
    [tree, onChange, onSelectNew]
  )

  return useMemo(
    () => ({ sensors, onDragStart, onDragEnd, onDragCancel, activeDrag }),
    [sensors, onDragStart, onDragEnd, onDragCancel, activeDrag]
  )
}
