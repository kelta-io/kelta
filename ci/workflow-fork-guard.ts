// node ci/workflow-fork-guard.ts [workflow-dir] [actionlint-config]   (Node >= 24; no dependencies)
//
// Fork pull requests must never run on our self-hosted runners: kelta-io/kelta is public, and a
// fork's PR code on a k8s-runner pod is arbitrary code inside the cluster. Every job a
// `pull_request` / `pull_request_review` trigger can reach that runs on a self-hosted label (a
// label from .github/actionlint.yaml, `self-hosted`, or a `matrix.*` expression whose matrix
// values include one) must carry FORK_GUARD as its whole `if:` or as a top-level `&&` operand.
// The fork-PR trigger that runs with the base repo's secrets is banned outright.
//
// Parses the subset of YAML the workflows use, line by line, like ci/path-filters.test.ts.
import { readFileSync, readdirSync } from 'node:fs';
import { basename, join } from 'node:path';

export const FORK_GUARD =
  "github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository";

const PR_TRIGGERS = new Set(['pull_request', 'pull_request_review']);
const BANNED_TRIGGER = 'pull_request_target';

export interface Job {
  name: string;
  runsOn: string;
  if?: string;
  matrix: string[];
}

export interface Workflow {
  triggers: string[];
  jobs: Job[];
}

const indentOf = (line: string): number => line.length - line.trimStart().length;

/** Drops a `#` comment that is preceded by whitespace and outside quotes. */
function stripComment(line: string): string {
  let quote: string | undefined;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quote) {
      if (c === quote) quote = undefined;
    } else if (c === "'" || c === '"') {
      quote = c;
    } else if (c === '#' && (i === 0 || /\s/.test(line[i - 1]))) {
      return line.slice(0, i).trimEnd();
    }
  }
  return line.trimEnd();
}

const unquote = (value: string): string => value.trim().replace(/^(['"])(.*)\1$/, '$2');

/** `[a, b]` → [a, b]; a scalar → [scalar]. */
function listValues(value: string): string[] {
  const v = value.trim();
  if (v.startsWith('[') && v.endsWith(']')) {
    return v.slice(1, -1).split(',').map(unquote).filter(Boolean);
  }
  return v === '' ? [] : [unquote(v)];
}

/** The meaningful lines of a mapping value: those after `start` indented deeper than `indent`. */
function block(lines: string[], start: number, indent: number): number {
  let end = start + 1;
  while (end < lines.length && (lines[end].trim() === '' || indentOf(lines[end]) > indent)) end++;
  return end;
}

/** A scalar, flow list, block scalar (`>-`, `|`) or block list value starting at `lines[i]`. */
function valueAt(lines: string[], i: number, inline: string): string {
  const end = block(lines, i, indentOf(lines[i]));
  const rest = lines.slice(i + 1, end).filter((l) => l.trim() !== '');
  if (/^[>|][-+]?$/.test(inline)) return rest.map((l) => l.trim()).join(' ');
  if (inline === '') {
    return rest
      .map((l) => /^\s*-\s+(.*)$/.exec(l)?.[1] ?? l.trim())
      .map(unquote)
      .join(' ');
  }
  return unquote(inline);
}

export function parseWorkflow(text: string): Workflow {
  const lines = text.split('\n').map(stripComment);
  const triggers: string[] = [];
  const jobs: Job[] = [];

  const on = lines.findIndex((l) => /^["']?(on|true)["']?:/.test(l));
  if (on !== -1) {
    const inline = lines[on].replace(/^[^:]+:\s*/, '');
    if (inline !== '') {
      triggers.push(...listValues(inline));
    } else {
      const end = block(lines, on, 0);
      const body = lines.slice(on + 1, end).filter((l) => l.trim() !== '');
      const keyIndent = Math.min(...body.map(indentOf));
      for (const l of body) {
        const m = /^\s*([\w-]+):/.exec(l);
        if (m && indentOf(l) === keyIndent) triggers.push(m[1]);
        const item = /^\s*-\s+([\w-]+)\s*$/.exec(l);
        if (item && indentOf(l) === keyIndent) triggers.push(item[1]);
      }
    }
  }

  const jobsAt = lines.indexOf('jobs:');
  if (jobsAt === -1) return { triggers, jobs };
  const jobsEnd = block(lines, jobsAt, 0);
  const jobIndent = Math.min(
    ...lines.slice(jobsAt + 1, jobsEnd).filter((l) => l.trim() !== '').map(indentOf),
  );
  for (let i = jobsAt + 1; i < jobsEnd; i++) {
    const key = /^\s*([\w-]+):\s*$/.exec(lines[i]);
    if (!key || indentOf(lines[i]) !== jobIndent) continue;
    const end = block(lines, i, jobIndent);
    const job: Job = { name: key[1], runsOn: '', matrix: [] };
    const fieldIndent = jobIndent + 2;
    for (let j = i + 1; j < end; j++) {
      if (indentOf(lines[j]) !== fieldIndent) continue;
      const field = /^\s*([\w-]+):\s*(.*)$/.exec(lines[j]);
      if (!field) continue;
      if (field[1] === 'runs-on') job.runsOn = valueAt(lines, j, field[2]);
      if (field[1] === 'if') job.if = valueAt(lines, j, field[2]);
      if (field[1] === 'strategy') job.matrix = lines.slice(j + 1, block(lines, j, fieldIndent));
    }
    jobs.push(job);
    i = end - 1;
  }
  return { triggers, jobs };
}

/** Every value given to `key` anywhere in a job's matrix: scalars, flow lists and block lists. */
export function matrixValues(matrix: string[], key: string): string[] {
  const values: string[] = [];
  for (let i = 0; i < matrix.length; i++) {
    const m = new RegExp(`^(\\s*)(?:-\\s+)?${key}:\\s*(.*)$`).exec(matrix[i]);
    if (!m) continue;
    if (m[2] !== '') {
      values.push(...listValues(m[2]));
      continue;
    }
    const indent = indentOf(matrix[i]);
    for (let j = i + 1; j < matrix.length && indentOf(matrix[j]) > indent; j++) {
      const item = /^\s*-\s+(.*)$/.exec(matrix[j]);
      if (item) values.push(unquote(item[1]));
    }
  }
  return values;
}

export function selfHostedLabels(actionlintConfig: string): string[] {
  const labels = ['self-hosted'];
  let inLabels = false;
  for (const line of actionlintConfig.split('\n').map(stripComment)) {
    if (/^\s*labels:\s*$/.test(line)) inLabels = true;
    else if (inLabels && /^\s*-\s+/.test(line)) labels.push(unquote(line.replace(/^\s*-\s+/, '')));
    else if (line.trim() !== '') inLabels = false;
  }
  return labels;
}

/**
 * Whether the job may land on a self-hosted runner. A `matrix.*` runner with no literal values
 * (e.g. `fromJSON`) and any other expression are treated as self-hosted: the check fails closed.
 */
export function isSelfHosted(job: Job, labels: string[]): boolean {
  const expressions = [...job.runsOn.matchAll(/\$\{\{(.*?)\}\}/g)].map((m) => m[1].trim());
  const literal = job.runsOn.replace(/\$\{\{.*?\}\}/g, ' ');
  const tokens = literal.split(/[\s,[\]]+/).map(unquote).filter(Boolean);
  if (tokens.some((t) => labels.includes(t))) return true;
  for (const expression of expressions) {
    const ref = /^matrix\.([\w-]+)$/.exec(expression);
    if (!ref) return true;
    const values = matrixValues(job.matrix, ref[1]);
    if (values.length === 0 || values.some((v) => labels.includes(v) || v.includes('${{'))) return true;
  }
  return false;
}

/** Strips one pair of parentheses that encloses the whole expression. */
function unwrap(expression: string): string {
  let e = expression.trim();
  while (e.startsWith('(') && e.endsWith(')') && topLevel(e.slice(1, -1), '').balanced) e = e.slice(1, -1).trim();
  return e;
}

/** Splits on a top-level operator (outside parentheses and quotes). */
function topLevel(expression: string, operator: string): { parts: string[]; balanced: boolean } {
  const parts: string[] = [];
  let depth = 0;
  let quote = false;
  let from = 0;
  for (let i = 0; i < expression.length; i++) {
    const c = expression[i];
    if (c === "'") quote = !quote;
    if (quote) continue;
    if (c === '(') depth++;
    if (c === ')' && --depth < 0) return { parts, balanced: false };
    if (operator && depth === 0 && expression.startsWith(operator, i)) {
      parts.push(expression.slice(from, i));
      from = i + operator.length;
      i += operator.length - 1;
    }
  }
  parts.push(expression.slice(from));
  return { parts, balanced: depth === 0 && !quote };
}

const normalise = (expression: string): string =>
  expression.replace(/^\$\{\{([\s\S]*)\}\}$/, '$1').replace(/\s+/g, ' ').trim();

/** The guard is the whole condition, or one operand of a top-level `&&` chain. */
export function hasForkGuard(condition: string | undefined): boolean {
  if (condition === undefined) return false;
  const guard = normalise(FORK_GUARD);
  const expression = unwrap(normalise(condition));
  if (expression === guard) return true;
  if (topLevel(expression, '||').parts.length > 1) return false;
  return topLevel(expression, '&&').parts.some((part) => unwrap(part) === guard);
}

export function checkWorkflow(file: string, text: string, labels: string[]): string[] {
  const problems: string[] = [];
  const name = basename(file);
  if (text.includes(BANNED_TRIGGER)) {
    problems.push(`${name}: uses ${BANNED_TRIGGER}, which runs fork code with the base repo's secrets`);
  }
  const workflow = parseWorkflow(text);
  if (!workflow.triggers.some((t) => PR_TRIGGERS.has(t))) return problems;
  for (const job of workflow.jobs) {
    if (isSelfHosted(job, labels) && !hasForkGuard(job.if)) {
      problems.push(
        `${name}: job ${job.name} runs on a self-hosted runner (runs-on: ${job.runsOn}) on a pull request without the fork guard in its if:`,
      );
    }
  }
  return problems;
}

export function checkDirectory(dir: string, actionlintConfig: string): string[] {
  const labels = selfHostedLabels(readFileSync(actionlintConfig, 'utf8'));
  return readdirSync(dir)
    .filter((f) => /\.ya?ml$/.test(f))
    .sort()
    .flatMap((f) => checkWorkflow(join(dir, f), readFileSync(join(dir, f), 'utf8'), labels));
}

if (import.meta.main) {
  const root = join(import.meta.dirname, '..');
  const dir = process.argv[2] ?? join(root, '.github/workflows');
  const config = process.argv[3] ?? join(root, '.github/actionlint.yaml');
  const problems = checkDirectory(dir, config);
  for (const problem of problems) console.error(`::error::${problem}`);
  if (problems.length > 0) {
    console.error(`Expected each such job's if: to be, or to AND in: ${FORK_GUARD}`);
    process.exit(1);
  }
  console.log(`workflow-fork-guard: every pull-request-reachable self-hosted job in ${dir} carries the fork guard`);
}
