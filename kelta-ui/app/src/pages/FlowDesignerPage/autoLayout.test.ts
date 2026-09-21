import { describe, it, expect } from 'vitest'
import {
  autoLayout,
  nodeSize,
  DEFAULT_NODE_SIZES,
  type LayoutNode,
  type LayoutEdge,
  type LayoutPositions,
} from './autoLayout'

const GRID = 16

function n(id: string, type = 'task'): LayoutNode {
  return { id, type }
}

function e(source: string, target: string): LayoutEdge {
  return { source, target }
}

function boxes(nodes: LayoutNode[], pos: LayoutPositions) {
  return nodes.map((node) => {
    const s = nodeSize(node)
    const p = pos[node.id]
    return { id: node.id, left: p.x, right: p.x + s.width, top: p.y, bottom: p.y + s.height }
  })
}

function expectNoOverlap(nodes: LayoutNode[], pos: LayoutPositions) {
  const b = boxes(nodes, pos)
  for (let i = 0; i < b.length; i++) {
    for (let j = i + 1; j < b.length; j++) {
      const a = b[i]
      const c = b[j]
      const overlap = a.left < c.right && c.left < a.right && a.top < c.bottom && c.top < a.bottom
      expect(overlap, `${a.id} overlaps ${c.id}`).toBe(false)
    }
  }
}

function center(node: LayoutNode, pos: LayoutPositions): number {
  return pos[node.id].x + nodeSize(node).width / 2
}

/**
 * Straight-line segment crossings between forward edges (centre-to-centre).
 * Back-edges are skipped: React Flow routes them as loops, not straight lines.
 */
function segmentCrossings(nodes: LayoutNode[], edges: LayoutEdge[], pos: LayoutPositions): number {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const forward = edges.filter((edge) => pos[edge.source].y < pos[edge.target].y)
  const seg = forward.map((edge) => {
    const s = byId.get(edge.source) as LayoutNode
    const t = byId.get(edge.target) as LayoutNode
    return {
      x1: center(s, pos),
      y1: pos[s.id].y + nodeSize(s).height,
      x2: center(t, pos),
      y2: pos[t.id].y,
      source: edge.source,
      target: edge.target,
    }
  })
  const orient = (ax: number, ay: number, bx: number, by: number, cx: number, cy: number) =>
    Math.sign((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
  let count = 0
  for (let i = 0; i < seg.length; i++) {
    for (let j = i + 1; j < seg.length; j++) {
      const a = seg[i]
      const b = seg[j]
      // edges sharing an endpoint touch, they do not cross
      if (
        a.source === b.source ||
        a.target === b.target ||
        a.source === b.target ||
        a.target === b.source
      )
        continue
      const o1 = orient(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1)
      const o2 = orient(a.x1, a.y1, a.x2, a.y2, b.x2, b.y2)
      const o3 = orient(b.x1, b.y1, b.x2, b.y2, a.x1, a.y1)
      const o4 = orient(b.x1, b.y1, b.x2, b.y2, a.x2, a.y2)
      if (o1 !== o2 && o3 !== o4 && o1 !== 0 && o2 !== 0 && o3 !== 0 && o4 !== 0) count++
    }
  }
  return count
}

describe('autoLayout', () => {
  it('returns an empty map for no nodes', () => {
    expect(autoLayout([], [])).toEqual({})
  })

  it('lays a linear chain out in a single vertical column with StartAt on top', () => {
    const nodes = [n('A'), n('B'), n('C', 'terminal')]
    const pos = autoLayout(nodes, [e('A', 'B'), e('B', 'C')], { startAt: 'A' })

    expect(pos.A.y).toBe(0)
    expect(pos.A.y).toBeLessThan(pos.B.y)
    expect(pos.B.y).toBeLessThan(pos.C.y)
    // centres aligned even though the terminal node is narrower
    expect(Math.abs(center(nodes[0], pos) - center(nodes[2], pos))).toBeLessThanOrEqual(GRID)
    expectNoOverlap(nodes, pos)
  })

  it('places StartAt at the top even when it is not the first node in the list', () => {
    const nodes = [n('C', 'terminal'), n('B'), n('A')]
    const pos = autoLayout(nodes, [e('A', 'B'), e('B', 'C')], { startAt: 'A' })
    expect(pos.A.y).toBe(0)
    expect(pos.B.y).toBeGreaterThan(pos.A.y)
    expect(pos.C.y).toBeGreaterThan(pos.B.y)
  })

  it('fans Choice targets out side-by-side on one rank in rule order', () => {
    const nodes = [n('Start'), n('Pick', 'choice'), n('R1'), n('R2'), n('Fallback', 'terminal')]
    const edges = [e('Start', 'Pick'), e('Pick', 'R1'), e('Pick', 'R2'), e('Pick', 'Fallback')]
    const pos = autoLayout(nodes, edges, { startAt: 'Start' })

    expect(pos.R1.y).toBe(pos.R2.y)
    expect(pos.R2.y).toBe(pos.Fallback.y)
    expect(pos.R1.x).toBeLessThan(pos.R2.x)
    expect(pos.R2.x).toBeLessThan(pos.Fallback.x)
    // the choice sits over the middle of its children
    const childCenters = [center(nodes[2], pos), center(nodes[3], pos), center(nodes[4], pos)]
    const span = [Math.min(...childCenters), Math.max(...childCenters)]
    const pickCenter = center(nodes[1], pos)
    expect(pickCenter).toBeGreaterThanOrEqual(span[0])
    expect(pickCenter).toBeLessThanOrEqual(span[1])
    expectNoOverlap(nodes, pos)
  })

  it('puts the join of a diamond below both branches, centred between them', () => {
    const nodes = [n('Start', 'choice'), n('Left'), n('Right'), n('Join'), n('End', 'terminal')]
    const edges = [
      e('Start', 'Left'),
      e('Start', 'Right'),
      e('Left', 'Join'),
      e('Right', 'Join'),
      e('Join', 'End'),
    ]
    const pos = autoLayout(nodes, edges, { startAt: 'Start' })

    expect(pos.Left.y).toBe(pos.Right.y)
    expect(pos.Join.y).toBeGreaterThan(pos.Left.y)
    const joinCenter = center(nodes[3], pos)
    const lo = Math.min(center(nodes[1], pos), center(nodes[2], pos))
    const hi = Math.max(center(nodes[1], pos), center(nodes[2], pos))
    expect(joinCenter).toBeGreaterThan(lo)
    expect(joinCenter).toBeLessThan(hi)
    expectNoOverlap(nodes, pos)
  })

  it('handles loops (back-edges) without hanging and keeps the loop target at its forward rank', () => {
    // Start -> Work -> Check -> (Default) Done | (Rule 1) Work
    const nodes = [n('Start'), n('Work'), n('Check', 'choice'), n('Done', 'terminal')]
    const edges = [e('Start', 'Work'), e('Work', 'Check'), e('Check', 'Done'), e('Check', 'Work')]
    const pos = autoLayout(nodes, edges, { startAt: 'Start' })

    expect(pos.Start.y).toBe(0)
    expect(pos.Work.y).toBeGreaterThan(pos.Start.y)
    expect(pos.Check.y).toBeGreaterThan(pos.Work.y)
    expect(pos.Done.y).toBeGreaterThan(pos.Check.y)
    expectNoOverlap(nodes, pos)
  })

  it('survives a two-node cycle and self-loops', () => {
    const nodes = [n('A'), n('B')]
    const pos = autoLayout(nodes, [e('A', 'B'), e('B', 'A'), e('A', 'A')], { startAt: 'A' })
    expect(pos.A.y).toBe(0)
    expect(pos.B.y).toBeGreaterThan(0)
    expectNoOverlap(nodes, pos)
  })

  it('keeps nodes off the path of an edge that spans several ranks', () => {
    // A -> B -> C -> D plus a long edge A -> D. B and C must not sit inside the
    // horizontal span reserved for the long edge.
    const nodes = [n('A', 'choice'), n('B'), n('C'), n('D', 'terminal')]
    const edges = [e('A', 'B'), e('B', 'C'), e('C', 'D'), e('A', 'D')]
    const pos = autoLayout(nodes, edges, { startAt: 'A' })

    expect(pos.B.y).toBeGreaterThan(pos.A.y)
    expect(pos.C.y).toBeGreaterThan(pos.B.y)
    expect(pos.D.y).toBeGreaterThan(pos.C.y)
    // smoothstep runs the long vertical segment at the target's x — B and C must
    // not sit on it, and the endpoints share the lane
    const laneX = center(nodes[3], pos)
    for (const mid of [nodes[1], nodes[2]]) {
      const left = pos[mid.id].x
      const right = left + nodeSize(mid).width
      expect(laneX < left || laneX > right, `${mid.id} sits on the A->D lane`).toBe(true)
    }
    expect(Math.abs(center(nodes[0], pos) - laneX)).toBeLessThanOrEqual(GRID)
    expectNoOverlap(nodes, pos)
    expect(segmentCrossings(nodes, edges, pos)).toBe(0)
  })

  it('reorders a rank to remove an avoidable crossing', () => {
    // Insertion order would draw A->D and B->C crossing; layout should flip C/D.
    const nodes = [n('Start', 'choice'), n('A'), n('B'), n('C'), n('D')]
    const edges = [e('Start', 'A'), e('Start', 'B'), e('A', 'D'), e('B', 'C')]
    const pos = autoLayout(nodes, edges, { startAt: 'Start' })

    expect(segmentCrossings(nodes, edges, pos)).toBe(0)
    expect(pos.A.x < pos.B.x).toBe(pos.D.x < pos.C.x)
    expectNoOverlap(nodes, pos)
  })

  it('stacks disconnected nodes below the main graph without overlap', () => {
    const nodes = [n('Start'), n('End', 'terminal'), n('Orphan'), n('Orphan2')]
    const edges = [e('Start', 'End'), e('Orphan', 'Orphan2')]
    const pos = autoLayout(nodes, edges, { startAt: 'Start' })

    const mainBottom = Math.max(pos.Start.y, pos.End.y)
    expect(pos.Orphan.y).toBeGreaterThan(mainBottom)
    expect(pos.Orphan2.y).toBeGreaterThan(pos.Orphan.y)
    expectNoOverlap(nodes, pos)
  })

  it('ranks a Catch handler below the step that raises it', () => {
    const nodes = [n('Start'), n('Risky'), n('Handler'), n('Done', 'terminal')]
    const edges = [e('Start', 'Risky'), e('Risky', 'Done'), e('Risky', 'Handler')]
    const pos = autoLayout(nodes, edges, { startAt: 'Start' })
    expect(pos.Handler.y).toBeGreaterThan(pos.Risky.y)
    expect(pos.Handler.y).toBe(pos.Done.y)
  })

  it('does not let a stray edge into StartAt pull it off the top rank', () => {
    const nodes = [n('Start'), n('Next'), n('Stray')]
    const pos = autoLayout(nodes, [e('Start', 'Next'), e('Stray', 'Start')], { startAt: 'Start' })
    expect(pos.Start.y).toBe(0)
    expectNoOverlap(nodes, pos)
  })

  it('ignores edges whose endpoints are not nodes', () => {
    const nodes = [n('A'), n('B')]
    const pos = autoLayout(nodes, [e('A', 'B'), e('A', 'ghost'), e('ghost', 'B')], {
      startAt: 'A',
    })
    expect(Object.keys(pos).sort()).toEqual(['A', 'B'])
    expect(pos.B.y).toBeGreaterThan(pos.A.y)
  })

  it('uses measured sizes when present and type fallbacks otherwise', () => {
    expect(nodeSize({ id: 'x', type: 'terminal' })).toEqual(DEFAULT_NODE_SIZES.terminal)
    expect(nodeSize({ id: 'x', type: 'nope' }).width).toBe(180)
    expect(nodeSize({ id: 'x', type: 'task', width: 320, height: 90 })).toEqual({
      width: 320,
      height: 90,
    })

    const wide: LayoutNode = { id: 'Wide', type: 'task', width: 400, height: 56 }
    const nodes = [n('Start', 'choice'), wide, n('Narrow')]
    const pos = autoLayout(nodes, [e('Start', 'Wide'), e('Start', 'Narrow')], { startAt: 'Start' })
    expectNoOverlap(nodes, pos)
  })

  it('is deterministic and snaps every coordinate to the grid at non-negative offsets', () => {
    const nodes = [
      n('FindFleetState'),
      n('HaveFleetState', 'choice'),
      n('NoFleetState', 'terminal'),
      n('StampGateRun'),
      n('WriteGateRun'),
      n('Verdicts'),
      n('ApplyVerdicts', 'control'),
      n('AnyReady', 'choice'),
      n('Done', 'terminal'),
    ]
    const edges = [
      e('FindFleetState', 'HaveFleetState'),
      e('HaveFleetState', 'StampGateRun'),
      e('HaveFleetState', 'NoFleetState'),
      e('StampGateRun', 'WriteGateRun'),
      e('WriteGateRun', 'Verdicts'),
      e('Verdicts', 'ApplyVerdicts'),
      e('ApplyVerdicts', 'AnyReady'),
      e('AnyReady', 'Done'),
      e('AnyReady', 'FindFleetState'),
    ]
    const first = autoLayout(nodes, edges, { startAt: 'FindFleetState' })
    const second = autoLayout([...nodes], [...edges], { startAt: 'FindFleetState' })
    expect(second).toEqual(first)

    for (const p of Object.values(first)) {
      expect(p.x % GRID).toBe(0)
      expect(p.y % GRID).toBe(0)
      expect(p.x).toBeGreaterThanOrEqual(0)
      expect(p.y).toBeGreaterThanOrEqual(0)
    }
    expect(first.FindFleetState.y).toBe(0)
    expectNoOverlap(nodes, first)
    expect(segmentCrossings(nodes, edges, first)).toBe(0)
  })

  it('honours custom grid and gaps', () => {
    const nodes = [n('A'), n('B')]
    const pos = autoLayout(nodes, [e('A', 'B')], { startAt: 'A', grid: 10, rankGap: 100 })
    expect(pos.B.y % 10).toBe(0)
    expect(pos.B.y).toBeGreaterThanOrEqual(DEFAULT_NODE_SIZES.task.height + 100 - 10)
  })
})
