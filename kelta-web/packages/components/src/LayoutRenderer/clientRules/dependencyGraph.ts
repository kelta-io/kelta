/**
 * Dependency graph utilities for the layout client-rules engine.
 *
 * Each compute/default rule has a `target` field and a list of dependencies
 * (extracted from the formula AST or declared explicitly). When a dependency
 * field changes, downstream targets must recompute. Recompute order is the
 * topological order of the graph; cycles are rejected up front.
 */

export interface RuleNode {
  id: string;
  target: string;
  dependsOn: string[];
}

export interface CycleError {
  cycle: string[];
}

export type TopoResult = { ok: true; order: string[] } | { ok: false; cycle: string[] };

/**
 * Kahn's algorithm. Returns rule ids in evaluation order, or a cycle path
 * when a cycle is detected.
 *
 * The graph is built only over rule targets — fields that are not the target
 * of any rule (raw user inputs) are not nodes in the graph; rules depend on
 * them but they have no outgoing edges.
 */
export function topologicalSort(rules: RuleNode[]): TopoResult {
  if (rules.length === 0) return { ok: true, order: [] };

  const targetToRule = new Map<string, RuleNode>();
  for (const r of rules) {
    if (targetToRule.has(r.target)) {
      // Multiple rules write to the same target: order by declaration (sort stable).
      // We still keep the first as the canonical for dependency lookup; the engine
      // executes both in sortOrder/declaration order. This is intentional —
      // overriding writes are allowed and documented behavior.
      continue;
    }
    targetToRule.set(r.target, r);
  }

  const inDegree = new Map<string, number>();
  const adjacency = new Map<string, string[]>();
  for (const r of rules) {
    inDegree.set(r.id, 0);
    adjacency.set(r.id, []);
  }

  for (const r of rules) {
    for (const dep of r.dependsOn) {
      const depRule = targetToRule.get(dep);
      if (!depRule) continue;
      adjacency.get(depRule.id)!.push(r.id);
      inDegree.set(r.id, (inDegree.get(r.id) ?? 0) + 1);
    }
  }

  const queue: string[] = [];
  for (const [id, deg] of inDegree) if (deg === 0) queue.push(id);

  const order: string[] = [];
  while (queue.length > 0) {
    const id = queue.shift()!;
    order.push(id);
    for (const next of adjacency.get(id) ?? []) {
      const nextDeg = (inDegree.get(next) ?? 0) - 1;
      inDegree.set(next, nextDeg);
      if (nextDeg === 0) queue.push(next);
    }
  }

  if (order.length === rules.length) {
    return { ok: true, order };
  }

  // Cycle detected — recover one cycle path for diagnostics.
  const remaining = rules.filter((r) => !order.includes(r.id));
  const cycle = traceCycle(remaining);
  return { ok: false, cycle };
}

function traceCycle(rules: RuleNode[]): string[] {
  if (rules.length === 0) return [];
  const targetToRule = new Map<string, RuleNode>();
  for (const r of rules) targetToRule.set(r.target, r);

  const start = rules[0];
  const visited: string[] = [start.id];
  const visitedSet = new Set<string>([start.id]);
  let current = start;

  for (let i = 0; i < rules.length + 1; i++) {
    const nextDep = current.dependsOn.find((d) => targetToRule.has(d));
    if (!nextDep) break;
    const next = targetToRule.get(nextDep)!;
    if (visitedSet.has(next.id)) {
      const idx = visited.indexOf(next.id);
      return visited.slice(idx).concat(next.id);
    }
    visited.push(next.id);
    visitedSet.add(next.id);
    current = next;
  }
  return visited;
}

/**
 * For a given changed field name, returns the rule ids whose dependencies
 * include that field, transitively. Used to skip work when only a small
 * subset of rules need to re-evaluate after a change.
 *
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `useLayoutRules`).
 * Copy it into your app if you depend on it.
 */
export function downstreamRules(
  changedField: string,
  rules: RuleNode[],
  evaluationOrder: string[]
): string[] {
  const ruleById = new Map(rules.map((r) => [r.id, r]));
  const targetToRule = new Map<string, RuleNode>();
  for (const r of rules) {
    if (!targetToRule.has(r.target)) targetToRule.set(r.target, r);
  }

  const dirty = new Set<string>();
  const stack: string[] = [];
  for (const r of rules) {
    if (r.dependsOn.includes(changedField)) {
      dirty.add(r.id);
      stack.push(r.id);
    }
  }

  while (stack.length > 0) {
    const id = stack.pop()!;
    const r = ruleById.get(id);
    if (!r) continue;
    const downstream = rules.filter((other) => other.dependsOn.includes(r.target));
    for (const d of downstream) {
      if (!dirty.has(d.id)) {
        dirty.add(d.id);
        stack.push(d.id);
      }
    }
  }

  return evaluationOrder.filter((id) => dirty.has(id));
}
