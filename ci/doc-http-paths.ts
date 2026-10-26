// node ci/doc-http-paths.ts   (Node >= 24; no dependencies)
//
// Checks that every `METHOD /api/...` the docs show resolves to a mapping the platform serves.
// The mappings come from ci/endpoint-index.ts (collect() — the same parse that generates
// docs/authoring/endpoints.md, DynamicCollectionRouter's collection routes included); this file
// only reads the docs and matches.
//
// What is read, in kelta-marketing/src/content/docs/**, docs/** and every README.md outside
// node_modules, .git and .claude:
//   - inline code spans holding `METHOD <url>` (`GET /api/x`, `kelta api POST /api/x`);
//   - lines inside fenced blocks holding `METHOD <url>` (HTTP request lines, `kelta api` calls);
//   - curl commands in fenced blocks or inline code (continuation lines joined), with the method
//     from -X/--request, else POST when a body flag is given, else GET.
// Only URLs whose path starts with `/api/`, or one segment then `/api/`, are checked. Scheme and
// host are dropped, as are the query string and fragment; a leading segment is tried both kept
// (`/portal/api/login/request` on kelta-auth) and dropped (`/{tenantSlug}/api/...` or a literal
// slug — the gateway strips the slug before routing). `{id}`, `:id` and `<id>` are path variables; a concrete segment
// (`/api/accounts`, an example uuid) matches a mapping variable. A concrete second segment the
// gateway sends to a static route only matches a collection route (`/api/{collectionName}/...`)
// when it names a system collection, so `/api/admin/typo` does not pass as a record read.
//
// Prose that deliberately shows a path no mapping serves goes in ci/doc-http-paths.exclusions.json
// as { "file", "method", "path", "reason" } with the path as the docs write it (after the prefix
// and query are dropped). Every entry needs a reason and must still match an occurrence.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { REPO_ROOT, collect, loadGatewayTable, type Endpoint, type GatewayTable } from './endpoint-index.ts';

export const EXCLUSIONS_PATH = 'ci/doc-http-paths.exclusions.json';
const DOC_DIRS = ['kelta-marketing/src/content/docs', 'docs'];
const README_SKIP = new Set(['node_modules', '.git', '.claude', 'dist', 'build', 'target']);

const METHODS = 'GET|POST|PUT|PATCH|DELETE';
const METHOD_URL_RE = new RegExp(`(?:^|[\\s\`'"(])((?:${METHODS})(?:\\\\?[/|](?:${METHODS}))*)\\s+(\\S*/api\\b\\S*)`, 'g');
const BODY_FLAG_RE = /\s(-d|--data\S*|-F|--form|--json|-T|--upload-file)(\s|=)/;

export interface Occurrence {
  file: string;
  line: number;
  method: string;
  /** The path as written after the host, without query or fragment. */
  path: string;
}

export interface Exclusion {
  file: string;
  method: string;
  path: string;
  reason: string;
}

/**
 * The path of a docs URL that holds `/api/`: scheme and host (or a `$BASE_URL`-style
 * placeholder) dropped, as are the query string, fragment, quotes and trailing punctuation.
 */
export function apiPath(url: string): string | null {
  if (!url.includes('/api/') && !url.endsWith('/api')) return null;
  const afterHost = url.startsWith('/') ? url : url.replace(/^[a-z]+:\/\//i, '').replace(/^[^/]*/, '');
  const path = afterHost.replace(/[?#].*$/, '').replace(/["'`)\],;.:\\]+$/, '');
  if (!/^(\/[^/]+)?\/api(\/|$)/.test(path)) return null;
  return path.length > 1 ? path.replace(/\/$/, '') : path;
}

/**
 * The paths an occurrence may be served at: as written, and, when one segment precedes `/api/`
 * (`/{tenantSlug}/api/...`, `/acme/api/...`), without it — the gateway strips the slug.
 */
export function candidatePaths(path: string): string[] {
  return path.startsWith('/api/') || path === '/api' ? [path] : [path, path.replace(/^\/[^/]+/, '')];
}

function curlOccurrence(command: string): { method: string; url: string } | null {
  const m = command.match(/(?:^|[\s`$(|;&])curl\s(.*)$/);
  if (!m) return null;
  const args = ` ${m[1]} `;
  const url = args.split(/\s+/).find((t) => t.includes('/api/'));
  if (!url) return null;
  const explicit = args.match(new RegExp(`\\s(?:-X|--request)[\\s=]*['"]?(${METHODS})\\b`));
  const method = explicit ? explicit[1] : BODY_FLAG_RE.test(args) ? 'POST' : 'GET';
  return { method, url };
}

/** Every checked `METHOD /api/...` occurrence of one markdown file. */
export function extractOccurrences(markdown: string, file: string): Occurrence[] {
  const out: Occurrence[] = [];
  const add = (line: number, method: string, url: string) => {
    const path = apiPath(url);
    if (path) out.push({ file, line, method, path });
  };
  const scan = (text: string, line: number) => {
    const curl = curlOccurrence(text);
    if (curl) {
      add(line, curl.method, curl.url);
      return;
    }
    for (const m of text.matchAll(METHOD_URL_RE)) for (const method of m[1].split(/\\?[/|]/)) add(line, method, m[2]);
  };

  // Inline code spans may wrap, so they are found per paragraph and placed on their first line.
  let paragraph: string[] = [];
  let paragraphStart = 0;
  const flush = () => {
    const text = paragraph.join('\n');
    for (const span of text.matchAll(/(`+)([\s\S]+?)\1/g)) {
      scan(span[2].replace(/\s*\n\s*/g, ' '), paragraphStart + text.slice(0, span.index).split('\n').length - 1);
    }
    paragraph = [];
  };

  const lines = markdown.split('\n');
  let fence: string | null = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const marker = line.match(/^\s*(```+|~~~+)/);
    if (marker) {
      flush();
      if (fence === null) fence = marker[1];
      else if (marker[1].startsWith(fence)) fence = null;
      continue;
    }
    if (fence !== null) {
      let logical = line;
      const start = i + 1;
      while (/\\\s*$/.test(logical) && i + 1 < lines.length) logical = `${logical.replace(/\\\s*$/, ' ')}${lines[++i]}`;
      scan(logical, start);
      continue;
    }
    if (line.trim() === '') {
      flush();
      continue;
    }
    if (paragraph.length === 0) paragraphStart = i + 1;
    paragraph.push(line);
  }
  flush();
  return out;
}

const isVariable = (segment: string) => /^(\{[^}]*\}|:\w+|<[^>]*>|\$\{?\w+\}?|\*\*?)$/.test(segment);

/** `{id}`, `:id` and `<id>` all become `{}`, so variants of one path compare equal. */
export function normalisePath(path: string): string {
  return path
    .split('/')
    .map((s) => (isVariable(s) && !s.startsWith('*') ? '{}' : s))
    .join('/');
}

/**
 * Whether a docs path is served by an endpoint path. A mapping variable matches any segment, a
 * mapping literal only the same literal, and a trailing `/**` everything below it. A collection
 * route (second segment a variable) does not match a concrete second segment the gateway routes
 * statically, unless that segment is a system collection.
 */
export function pathMatches(docPath: string, mappingPath: string, table: Pick<GatewayTable, 'staticRoutes' | 'systemCollections'>): boolean {
  const doc = normalisePath(docPath).split('/');
  const mapping = mappingPath.split('/');
  const catchAll = mapping[mapping.length - 1] === '**';
  if (catchAll ? doc.length < mapping.length - 1 : doc.length !== mapping.length) return false;
  for (let i = 0; i < mapping.length; i++) {
    if (catchAll && i === mapping.length - 1) return true;
    const m = mapping[i];
    if (/^\{[^}]*\}$/.test(m)) continue;
    if (m !== doc[i]) return false;
  }
  if (/^\{[^}]*\}$/.test(mapping[2] ?? '') && doc[2] !== '{}') {
    const routedStatically = table.staticRoutes.some((r) => docPath === r.prefix || docPath.startsWith(`${r.prefix}/`));
    if (routedStatically && !table.systemCollections.has(doc[2])) return false;
  }
  return true;
}

export function resolves(occurrence: Pick<Occurrence, 'method' | 'path'>, endpoints: Array<Pick<Endpoint, 'method' | 'path'>>, table: Pick<GatewayTable, 'staticRoutes' | 'systemCollections'>): boolean {
  return candidatePaths(occurrence.path).some((path) =>
    endpoints.some((e) => (e.method === occurrence.method || e.method === 'ANY') && pathMatches(path, e.path, table)),
  );
}

const excludes = (x: Exclusion, o: Occurrence) => x.file === o.file && x.method === o.method && normalisePath(x.path) === normalisePath(o.path);

/** The failure lines: one `file:line  METHOD /path` per unresolved occurrence, then exclusion problems. */
export function failures(occurrences: Occurrence[], endpoints: Array<Pick<Endpoint, 'method' | 'path'>>, table: Pick<GatewayTable, 'staticRoutes' | 'systemCollections'>, exclusions: Exclusion[]): string[] {
  const out: string[] = [];
  for (const o of occurrences) {
    if (exclusions.some((x) => excludes(x, o))) continue;
    if (!resolves(o, endpoints, table)) out.push(`${o.file}:${o.line}  ${o.method} ${o.path}  — no controller mapping serves this path`);
  }
  for (const x of exclusions) {
    const label = `${EXCLUSIONS_PATH}: ${x.file} ${x.method} ${x.path}`;
    if (typeof x.reason !== 'string' || x.reason.trim().length === 0) out.push(`${label} — needs a reason`);
    if (!occurrences.some((o) => excludes(x, o))) out.push(`${label} — matches no occurrence in the docs; remove it`);
  }
  return out;
}

function markdownFiles(dir: string, skip: Set<string>, readmeOnly: boolean): string[] {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    if (skip.has(name)) continue;
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...markdownFiles(full, skip, readmeOnly));
    else if (readmeOnly ? name === 'README.md' : /\.mdx?$/.test(name)) out.push(full);
  }
  return out;
}

/** The docs this check reads, repo-relative. */
export function docFiles(root: string = REPO_ROOT): string[] {
  const files = [
    ...DOC_DIRS.flatMap((d) => markdownFiles(join(root, d), new Set(), false)),
    ...markdownFiles(root, README_SKIP, true),
  ];
  return [...new Set(files.map((f) => relative(root, f)))].sort();
}

export function readExclusions(root: string = REPO_ROOT): Exclusion[] {
  return JSON.parse(readFileSync(join(root, EXCLUSIONS_PATH), 'utf8')).exclusions;
}

export function collectOccurrences(root: string = REPO_ROOT): Occurrence[] {
  return docFiles(root).flatMap((f) => extractOccurrences(readFileSync(join(root, f), 'utf8'), f));
}

export function check(root: string = REPO_ROOT): string[] {
  return failures(collectOccurrences(root), collect(root).endpoints, loadGatewayTable(root), readExclusions(root));
}

if (import.meta.main) {
  const found = check();
  for (const f of found) console.error(f);
  process.exit(found.length > 0 ? 1 : 0);
}
