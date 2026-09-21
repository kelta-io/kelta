/**
 * Zero-dependency layered ("Sugiyama-lite") layout for the flow designer.
 *
 * Top-to-bottom: every canvas node has its target handle on top and its source
 * handle on the bottom, so ranks run downward from `StartAt`. Pipeline:
 *
 *   1. normalise edges (drop self-loops, unknown endpoints, duplicates)
 *   2. break cycles — DFS from StartAt, back-edges reversed for ranking only
 *   3. rank by longest path from the roots
 *   4. insert dummy vertices for edges spanning more than one rank
 *   5. reduce crossings — barycenter sweeps + adjacent transposition
 *   6. assign x — median-of-neighbours with block overlap resolution
 *   7. assign y — cumulative rank heights
 *   8. snap to the canvas grid, disconnected components stacked underneath
 *
 * Deterministic: identical input always yields identical positions.
 */

export interface LayoutNode {
  id: string
  /** React Flow node type (task | choice | control | terminal) — used for fallback sizes. */
  type?: string
  width?: number
  height?: number
}

export interface LayoutEdge {
  source: string
  target: string
}

export interface LayoutOptions {
  /** Root of the graph; ranked 0 and placed at the top. */
  startAt?: string
  /** Vertical gap between ranks (leaves room for edge labels). */
  rankGap?: number
  /** Horizontal gap between neighbouring nodes on one rank. */
  nodeGap?: number
  /** Snap grid — matches the canvas `snapGrid`. */
  grid?: number
}

export type LayoutPositions = Record<string, { x: number; y: number }>

interface Size {
  width: number
  height: number
}

/** Fallback sizes per React Flow node type (matches the `min-w-*` classes on the node components). */
export const DEFAULT_NODE_SIZES: Record<string, Size> = {
  task: { width: 180, height: 56 },
  control: { width: 160, height: 56 },
  choice: { width: 140, height: 56 },
  terminal: { width: 120, height: 40 },
}

const FALLBACK_SIZE: Size = { width: 180, height: 56 }
const DUMMY_WIDTH = 24
const SWEEP_ITERATIONS = 8

export function nodeSize(node: LayoutNode): Size {
  const fallback = (node.type && DEFAULT_NODE_SIZES[node.type]) || FALLBACK_SIZE
  return {
    width: node.width && node.width > 0 ? node.width : fallback.width,
    height: node.height && node.height > 0 ? node.height : fallback.height,
  }
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

export function autoLayout(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  opts: LayoutOptions = {}
): LayoutPositions {
  const { startAt, rankGap = 72, nodeGap = 48, grid = 16 } = opts
  if (nodes.length === 0) return {}

  const byId = new Map<string, LayoutNode>()
  for (const n of nodes) byId.set(n.id, n)

  const cleanEdges = normaliseEdges(edges, byId)
  const components = splitComponents(nodes, cleanEdges, startAt)

  const positions: LayoutPositions = {}
  let yOffset = 0
  for (const comp of components) {
    const compEdges = cleanEdges.filter((e) => comp.has(e.source) && comp.has(e.target))
    const compNodes = nodes.filter((n) => comp.has(n.id))
    const root = startAt && comp.has(startAt) ? startAt : compNodes[0].id
    const laid = layoutComponent(compNodes, compEdges, root, rankGap, nodeGap)

    let bottom = 0
    for (const n of compNodes) {
      const p = laid[n.id]
      positions[n.id] = { x: snap(p.x, grid), y: snap(p.y + yOffset, grid) }
      bottom = Math.max(bottom, positions[n.id].y + nodeSize(n).height)
    }
    yOffset = bottom + rankGap * 1.5
  }

  return positions
}

// ---------------------------------------------------------------------------
// 1. Normalise
// ---------------------------------------------------------------------------

function normaliseEdges(edges: LayoutEdge[], byId: Map<string, LayoutNode>): LayoutEdge[] {
  const seen = new Set<string>()
  const out: LayoutEdge[] = []
  for (const e of edges) {
    if (!byId.has(e.source) || !byId.has(e.target) || e.source === e.target) continue
    const key = `${e.source}->${e.target}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ source: e.source, target: e.target })
  }
  return out
}

/** Weakly connected components; the one containing `startAt` (else the first node) comes first. */
function splitComponents(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  startAt: string | undefined
): Set<string>[] {
  const parent = new Map<string, string>()
  const find = (x: string): string => {
    let r = x
    while (parent.get(r) !== r) r = parent.get(r) as string
    // path compression
    let c = x
    while (parent.get(c) !== r) {
      const next = parent.get(c) as string
      parent.set(c, r)
      c = next
    }
    return r
  }
  for (const n of nodes) parent.set(n.id, n.id)
  for (const e of edges) parent.set(find(e.source), find(e.target))

  const groups = new Map<string, Set<string>>()
  for (const n of nodes) {
    const r = find(n.id)
    let g = groups.get(r)
    if (!g) {
      g = new Set()
      groups.set(r, g)
    }
    g.add(n.id)
  }

  const ordered = [...groups.values()]
  const mainIdx = ordered.findIndex((g) => (startAt ? g.has(startAt) : g.has(nodes[0].id)))
  if (mainIdx > 0) {
    const [main] = ordered.splice(mainIdx, 1)
    ordered.unshift(main)
  }
  return ordered
}

// ---------------------------------------------------------------------------
// Per-component layout
// ---------------------------------------------------------------------------

interface Vertex {
  id: string
  dummy: boolean
  width: number
  height: number
  rank: number
  /** index within its layer */
  pos: number
  /** left x */
  x: number
  preds: Vertex[]
  succs: Vertex[]
}

function layoutComponent(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  root: string,
  rankGap: number,
  nodeGap: number
): LayoutPositions {
  // 2. cycle breaking ------------------------------------------------------
  const succ = new Map<string, string[]>()
  for (const n of nodes) succ.set(n.id, [])
  for (const e of edges) (succ.get(e.source) as string[]).push(e.target)

  const state = new Map<string, 0 | 1 | 2>()
  const rootIndex = new Map<string, number>()
  const dfsOrder: string[] = []
  // Edges in DAG direction; `reversed` marks back-edges (they only influence
  // ranking — React Flow draws them as loops, so they get no lane of their own).
  const dagEdges: Array<LayoutEdge & { reversed: boolean }> = []
  const dagEdgeKeys = new Set<string>()
  const addDagEdge = (source: string, target: string, reversed: boolean): void => {
    const key = `${source}->${target}`
    if (dagEdgeKeys.has(key)) return
    dagEdgeKeys.add(key)
    dagEdges.push({ source, target, reversed })
  }
  let currentRoot = 0

  const visit = (u: string): void => {
    state.set(u, 1)
    rootIndex.set(u, currentRoot)
    dfsOrder.push(u)
    for (const v of succ.get(u) as string[]) {
      const sv = state.get(v) ?? 0
      if (sv === 1 || (sv === 2 && (rootIndex.get(v) as number) < currentRoot)) {
        // back-edge (or an edge into an earlier component-tree): reverse for ranking
        addDagEdge(v, u, true)
      } else {
        addDagEdge(u, v, false)
        if (sv === 0) visit(v)
      }
    }
    state.set(u, 2)
  }

  const visitOrder = [root, ...nodes.map((n) => n.id).filter((id) => id !== root)]
  for (const id of visitOrder) {
    if ((state.get(id) ?? 0) === 0) {
      visit(id)
      currentRoot++
    }
  }

  // 3. ranking — longest path from roots -----------------------------------
  const dagPreds = new Map<string, string[]>()
  for (const n of nodes) dagPreds.set(n.id, [])
  for (const e of dagEdges) (dagPreds.get(e.target) as string[]).push(e.source)

  const rank = new Map<string, number>()
  const rankOf = (id: string): number => {
    const cached = rank.get(id)
    if (cached !== undefined) return cached
    let r = 0
    for (const p of dagPreds.get(id) as string[]) r = Math.max(r, rankOf(p) + 1)
    rank.set(id, r)
    return r
  }
  for (const n of nodes) rankOf(n.id)

  // 4. vertices + dummies ---------------------------------------------------
  const vertices = new Map<string, Vertex>()
  const dfsIndex = new Map<string, number>()
  dfsOrder.forEach((id, i) => dfsIndex.set(id, i))

  for (const n of nodes) {
    const size = nodeSize(n)
    vertices.set(n.id, {
      id: n.id,
      dummy: false,
      width: size.width,
      height: size.height,
      rank: rank.get(n.id) as number,
      pos: 0,
      x: 0,
      preds: [],
      succs: [],
    })
  }

  const link = (a: Vertex, b: Vertex): void => {
    a.succs.push(b)
    b.preds.push(a)
  }

  let dummyCounter = 0
  for (const e of dagEdges) {
    if (e.reversed) continue
    const from = vertices.get(e.source) as Vertex
    const to = vertices.get(e.target) as Vertex
    let prev = from
    for (let r = from.rank + 1; r < to.rank; r++) {
      const d: Vertex = {
        id: `__dummy_${dummyCounter++}`,
        dummy: true,
        width: DUMMY_WIDTH,
        height: 0,
        rank: r,
        pos: 0,
        x: 0,
        preds: [],
        succs: [],
      }
      vertices.set(d.id, d)
      link(prev, d)
      prev = d
    }
    link(prev, to)
  }

  const maxRank = Math.max(...nodes.map((n) => rank.get(n.id) as number))
  const layers: Vertex[][] = Array.from({ length: maxRank + 1 }, () => [])
  // initial order: DFS discovery order; dummies right after their source's slot
  const initialKey = (v: Vertex): number => {
    if (!v.dummy) return dfsIndex.get(v.id) as number
    let p = v
    while (p.dummy) p = p.preds[0]
    return (dfsIndex.get(p.id) as number) + 0.5
  }
  const sortedVertices = [...vertices.values()].sort(
    (a, b) => initialKey(a) - initialKey(b) || a.id.localeCompare(b.id)
  )
  for (const v of sortedVertices) layers[v.rank].push(v)
  reindex(layers)

  // 5. crossing reduction ----------------------------------------------------
  let best = snapshot(layers)
  let bestCrossings = countCrossings(layers)
  for (let iter = 0; iter < SWEEP_ITERATIONS && bestCrossings > 0; iter++) {
    const down = iter % 2 === 0
    if (down) {
      for (let r = 1; r < layers.length; r++) barycenterSort(layers[r], 'preds')
    } else {
      for (let r = layers.length - 2; r >= 0; r--) barycenterSort(layers[r], 'succs')
    }
    reindex(layers)
    transpose(layers)
    const crossings = countCrossings(layers)
    if (crossings < bestCrossings) {
      bestCrossings = crossings
      best = snapshot(layers)
    }
  }
  restore(layers, best)

  // 6. x coordinates ---------------------------------------------------------
  for (const layer of layers) {
    let x = 0
    for (const v of layer) {
      v.x = x
      x += v.width + nodeGap
    }
  }
  const passes: Array<'preds' | 'succs'> = ['preds', 'succs', 'preds', 'succs', 'preds']
  for (const dir of passes) {
    if (dir === 'preds') {
      for (let r = 1; r < layers.length; r++) placeLayer(layers[r], dir, nodeGap)
    } else {
      for (let r = layers.length - 2; r >= 0; r--) placeLayer(layers[r], dir, nodeGap)
    }
  }

  // 7. y coordinates ---------------------------------------------------------
  const layerHeight = layers.map((layer) => Math.max(0, ...layer.map((v) => v.height)))
  const layerY: number[] = []
  let y = 0
  for (let r = 0; r < layers.length; r++) {
    layerY.push(y)
    y += layerHeight[r] + rankGap
  }

  let minX = Infinity
  for (const v of vertices.values()) if (!v.dummy) minX = Math.min(minX, v.x)

  const out: LayoutPositions = {}
  for (const v of vertices.values()) {
    if (v.dummy) continue
    // top-aligned within the rank band: incoming edges land on the top handle
    out[v.id] = { x: v.x - minX, y: layerY[v.rank] }
  }
  return out
}

// ---------------------------------------------------------------------------
// Ordering helpers
// ---------------------------------------------------------------------------

function reindex(layers: Vertex[][]): void {
  for (const layer of layers) layer.forEach((v, i) => (v.pos = i))
}

function snapshot(layers: Vertex[][]): Vertex[][] {
  return layers.map((l) => [...l])
}

function restore(layers: Vertex[][], saved: Vertex[][]): void {
  for (let r = 0; r < layers.length; r++) layers[r] = saved[r]
  reindex(layers)
}

function barycenterSort(layer: Vertex[], dir: 'preds' | 'succs'): void {
  const key = new Map<Vertex, number>()
  for (const v of layer) {
    const nbrs = v[dir]
    key.set(v, nbrs.length === 0 ? v.pos : nbrs.reduce((s, n) => s + n.pos, 0) / nbrs.length)
  }
  layer.sort((a, b) => (key.get(a) as number) - (key.get(b) as number) || a.pos - b.pos)
}

/** Crossings between two adjacent layers, counted over edges from `upper` to `lower`. */
function crossingsBetween(upper: Vertex[]): number {
  const pairs: Array<[number, number]> = []
  for (const u of upper) for (const v of u.succs) pairs.push([u.pos, v.pos])
  let count = 0
  for (let i = 0; i < pairs.length; i++) {
    for (let j = i + 1; j < pairs.length; j++) {
      const [u1, v1] = pairs[i]
      const [u2, v2] = pairs[j]
      if ((u1 < u2 && v1 > v2) || (u1 > u2 && v1 < v2)) count++
    }
  }
  return count
}

function countCrossings(layers: Vertex[][]): number {
  let total = 0
  for (let r = 0; r < layers.length - 1; r++) total += crossingsBetween(layers[r])
  return total
}

/** Greedy adjacent swaps while they reduce crossings with the neighbouring layers. */
function transpose(layers: Vertex[][]): void {
  let improved = true
  let guard = 0
  while (improved && guard++ < 50) {
    improved = false
    for (let r = 0; r < layers.length; r++) {
      const layer = layers[r]
      for (let i = 0; i < layer.length - 1; i++) {
        const before = localCrossings(layers, r)
        swap(layer, i)
        const after = localCrossings(layers, r)
        if (after < before) {
          improved = true
        } else {
          swap(layer, i)
        }
      }
    }
  }
}

function swap(layer: Vertex[], i: number): void {
  const a = layer[i]
  const b = layer[i + 1]
  layer[i] = b
  layer[i + 1] = a
  a.pos = i + 1
  b.pos = i
}

function localCrossings(layers: Vertex[][], r: number): number {
  let c = 0
  if (r > 0) c += crossingsBetween(layers[r - 1])
  if (r < layers.length - 1) c += crossingsBetween(layers[r])
  return c
}

// ---------------------------------------------------------------------------
// Coordinate helpers
// ---------------------------------------------------------------------------

function median(values: number[]): number {
  const s = [...values].sort((a, b) => a - b)
  const mid = Math.floor(s.length / 2)
  return s.length % 2 === 1 ? s[mid] : (s[mid - 1] + s[mid]) / 2
}

/**
 * Move each vertex toward the median centre of its neighbours in `dir`, then
 * resolve overlaps left→right. Vertices that were pushed form a block with the
 * vertex that pushed them; each block is shifted back left by its mean overshoot
 * (clamped against the previous block) so displacement is shared evenly.
 *
 * Dummy neighbours win over real ones: React Flow's smoothstep edge runs its
 * long vertical segment at the target's x, so the endpoints of a rank-spanning
 * edge are pulled onto the dummy lane and the intermediate nodes stay clear of it.
 */
function placeLayer(layer: Vertex[], dir: 'preds' | 'succs', gap: number): void {
  if (layer.length === 0) return
  const desired = layer.map((v) => {
    const nbrs = v[dir]
    if (nbrs.length === 0) return v.x
    const dummies = nbrs.filter((n) => n.dummy)
    const ref = dummies.length > 0 ? dummies : nbrs
    return median(ref.map((n) => n.x + n.width / 2)) - v.width / 2
  })

  // left→right push
  const x: number[] = []
  const blockStart: number[] = []
  for (let i = 0; i < layer.length; i++) {
    const minLeft = i === 0 ? -Infinity : x[i - 1] + layer[i - 1].width + gap
    if (desired[i] >= minLeft) {
      x.push(desired[i])
      blockStart.push(i)
    } else {
      x.push(minLeft)
    }
  }

  // shift each block back by its mean overshoot — lanes (dummies) are rigid, so a
  // block containing one is shifted to put the lane exactly where it wants to be
  let prevRight = -Infinity
  for (let b = 0; b < blockStart.length; b++) {
    const start = blockStart[b]
    const end = b + 1 < blockStart.length ? blockStart[b + 1] : layer.length
    const members = layer.slice(start, end)
    const rigid = members.some((v) => v.dummy)
    let overshoot = 0
    let count = 0
    for (let i = start; i < end; i++) {
      if (rigid && !layer[i].dummy) continue
      overshoot += x[i] - desired[i]
      count++
    }
    overshoot /= count
    let shift = -overshoot
    const clampedStart = Math.max(x[start] + shift, prevRight)
    shift = clampedStart - x[start]
    for (let i = start; i < end; i++) x[i] += shift
    prevRight = x[end - 1] + layer[end - 1].width + gap
  }

  layer.forEach((v, i) => (v.x = x[i]))
}

function snap(value: number, grid: number): number {
  if (grid <= 0) return Math.round(value)
  return Math.round(value / grid) * grid
}
