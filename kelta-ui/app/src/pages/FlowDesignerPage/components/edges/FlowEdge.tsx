import { memo } from 'react'
import {
  BaseEdge,
  getSmoothStepPath,
  useStore,
  type EdgeProps,
  type ReactFlowState,
} from '@xyflow/react'

/** Edge `type` registered for every designer edge. */
export const FLOW_EDGE_TYPE = 'flow'

/** How far left of the leftmost node a loop-back edge runs. */
const LOOP_MARGIN = 48

const selectLeftmostX = (state: ReactFlowState): number => {
  let min = Infinity
  for (const node of state.nodes) min = Math.min(min, node.position.x)
  return min
}

/**
 * Smoothstep edge that routes loop-backs around the diagram instead of
 * through it. The stock smoothstep path runs a backwards edge's vertical
 * segment at the midpoint between source and target x — which, in a
 * top-to-bottom flow, is the column every intermediate step sits in. When the
 * target is above the source we pin that segment to the left of the leftmost
 * node so the loop hugs the outside of the diagram.
 */
export const FlowEdge = memo(function FlowEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  label,
  labelStyle,
  labelShowBg,
  labelBgStyle,
  labelBgPadding,
  labelBgBorderRadius,
  style,
  markerStart,
  markerEnd,
  interactionWidth,
}: EdgeProps) {
  const leftmostX = useStore(selectLeftmostX)
  const loopsBack = targetY < sourceY && Number.isFinite(leftmostX)

  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    centerX: loopsBack ? leftmostX - LOOP_MARGIN : undefined,
  })

  return (
    <BaseEdge
      id={id}
      path={path}
      labelX={labelX}
      labelY={labelY}
      label={label}
      labelStyle={labelStyle}
      labelShowBg={labelShowBg}
      labelBgStyle={labelBgStyle}
      labelBgPadding={labelBgPadding}
      labelBgBorderRadius={labelBgBorderRadius}
      style={style}
      markerStart={markerStart}
      markerEnd={markerEnd}
      interactionWidth={interactionWidth}
    />
  )
})
