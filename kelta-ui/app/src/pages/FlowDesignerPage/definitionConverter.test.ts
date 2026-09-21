import { describe, it, expect } from 'vitest'
import {
  definitionToNodesAndEdges,
  nodesToDefinition,
  catchLayoutEdges,
} from './definitionConverter'
import type { FlowDefinition } from './types'

const branching: FlowDefinition = {
  StartAt: 'Fetch',
  States: {
    Fetch: {
      Type: 'Task',
      Resource: 'QUERY_RECORDS',
      Next: 'HasRows',
      Catch: [{ ErrorEquals: ['States.ALL'], Next: 'Failed' }],
    },
    HasRows: {
      Type: 'Choice',
      Choices: [{ Variable: '$.count', NumericGreaterThan: 0, Next: 'Process' }],
      Default: 'Nothing',
    },
    Process: { Type: 'Task', Resource: 'UPDATE_RECORD', Next: 'Done' },
    Nothing: { Type: 'Succeed' },
    Done: { Type: 'Succeed' },
    Failed: { Type: 'Fail', Error: 'FetchFailed' },
  },
}

describe('definitionToNodesAndEdges', () => {
  it('auto-lays out a definition that has no saved positions', () => {
    const { nodes } = definitionToNodesAndEdges(branching)
    const pos = Object.fromEntries(nodes.map((n) => [n.id, n.position]))

    // StartAt on top, ranks descend along the happy path
    expect(pos.Fetch.y).toBe(0)
    expect(pos.HasRows.y).toBeGreaterThan(pos.Fetch.y)
    expect(pos.Process.y).toBeGreaterThan(pos.HasRows.y)
    expect(pos.Done.y).toBeGreaterThan(pos.Process.y)
    // choice targets share a rank, side by side
    expect(pos.Process.y).toBe(pos.Nothing.y)
    expect(pos.Process.x).not.toBe(pos.Nothing.x)
    // catch handler hangs below the step that raises it, not at the top
    expect(pos.Failed.y).toBeGreaterThan(pos.Fetch.y)
    // not the legacy single column
    expect(new Set(nodes.map((n) => n.position.x)).size).toBeGreaterThan(1)
  })

  it('keeps saved positions untouched', () => {
    const saved: FlowDefinition = {
      ...branching,
      _metadata: {
        nodePositions: {
          Fetch: { x: 10, y: 20 },
          HasRows: { x: 30, y: 40 },
          Process: { x: 50, y: 60 },
          Nothing: { x: 70, y: 80 },
          Done: { x: 90, y: 100 },
          Failed: { x: 110, y: 120 },
        },
      },
    }
    const { nodes } = definitionToNodesAndEdges(saved)
    for (const n of nodes) {
      expect(n.position).toEqual(saved._metadata?.nodePositions?.[n.id])
    }
  })

  it('uses the legacy column fallback for nodes missing from a partially-saved map', () => {
    const partial: FlowDefinition = {
      ...branching,
      _metadata: { nodePositions: { Fetch: { x: 10, y: 20 } } },
    }
    const { nodes } = definitionToNodesAndEdges(partial)
    const byId = Object.fromEntries(nodes.map((n) => [n.id, n.position]))
    expect(byId.Fetch).toEqual({ x: 10, y: 20 })
    expect(byId.HasRows).toEqual({ x: 250, y: 120 })
  })

  it('does not emit canvas edges for Catch handlers', () => {
    const { edges } = definitionToNodesAndEdges(branching)
    expect(edges.some((e) => e.target === 'Failed')).toBe(false)
  })
})

describe('catchLayoutEdges', () => {
  it('derives layout-only edges from node.data.catch', () => {
    const { nodes } = definitionToNodesAndEdges(branching)
    expect(catchLayoutEdges(nodes)).toEqual([{ source: 'Fetch', target: 'Failed' }])
  })

  it('ignores nodes without catch rules', () => {
    expect(catchLayoutEdges([{ id: 'x', position: { x: 0, y: 0 }, data: {} }])).toEqual([])
  })
})

describe('nodesToDefinition', () => {
  it('round-trips positions and transitions after auto layout', () => {
    const { nodes, edges } = definitionToNodesAndEdges(branching)
    const out = nodesToDefinition(nodes, edges, branching)

    expect(out.StartAt).toBe('Fetch')
    expect(out.States.Fetch.Next).toBe('HasRows')
    expect(out.States.Fetch.Catch?.[0].Next).toBe('Failed')
    expect(out.States.HasRows.Choices?.[0].Next).toBe('Process')
    expect(out.States.HasRows.Default).toBe('Nothing')
    expect(out.States.Process.Next).toBe('Done')
    for (const n of nodes) {
      expect(out._metadata?.nodePositions?.[n.id]).toEqual(n.position)
    }
  })
})
