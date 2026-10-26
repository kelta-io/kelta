// node ci/endpoint-index.ts [--write]   (Node >= 24; no dependencies)
//
// Builds the public endpoint index, docs/authoring/endpoints.md, from the Spring mapping
// annotations in the kelta-worker, kelta-auth, kelta-ai and kelta-mcp main sources, joined with
// the gateway's route table (RouteConfigService.STATIC_ROUTES and
// COLLECTION_CHECKED_STATIC_ROUTES, the gateway application.yml path lists, the per-collection
// routes of the system collections). Each public endpoint is linked to the docs page that
// documents it, or classified `internal` with a reason, in ci/endpoint-index.classification.json.
//
// --write regenerates the page (add --list-unclassified to print what is left). Without it, the
// script checks what ci/endpoint-index.test.ts checks in CI: the committed page equals a fresh
// render, every classification is well-formed with a reason or a docs link the site publishes,
// and the number of unclassified endpoints equals ci/endpoint-index.baseline.json.
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';

export const REPO_ROOT = join(import.meta.dirname, '..');
export const PAGE_PATH = 'docs/authoring/endpoints.md';
export const CLASSIFICATION_PATH = 'ci/endpoint-index.classification.json';
export const BASELINE_PATH = 'ci/endpoint-index.baseline.json';
export const WRITE_COMMAND = 'node ci/endpoint-index.ts --write';

const SERVICES = ['kelta-worker', 'kelta-auth', 'kelta-ai', 'kelta-mcp'] as const;
export type Service = (typeof SERVICES)[number];

/**
 * Source roots per service. The worker also serves the runtime library's controllers — the
 * generic collection router (`DynamicCollectionRouter`) among them.
 */
const SOURCE_ROOTS: Record<Service, string[]> = {
  'kelta-worker': ['kelta-worker/src/main/java', 'kelta-platform/runtime/runtime-core/src/main/java'],
  'kelta-auth': ['kelta-auth/src/main/java'],
  'kelta-ai': ['kelta-ai/src/main/java'],
  'kelta-mcp': ['kelta-mcp/src/main/java'],
};

const ROUTE_CONFIG = 'kelta-gateway/src/main/java/io/kelta/gateway/service/RouteConfigService.java';
const ROUTE_INITIALIZER = 'kelta-gateway/src/main/java/io/kelta/gateway/config/RouteInitializer.java';
const ROUTE_AUTHZ = 'kelta-gateway/src/main/java/io/kelta/gateway/authz/RouteAuthorizationFilter.java';
const GATEWAY_YML = 'kelta-gateway/src/main/resources/application.yml';
const SYSTEM_COLLECTIONS_DIR = 'kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/model/system';
const PERMISSIONS_SOURCE = 'kelta-worker/src/main/java/io/kelta/worker/listener/TenantProvisioningHook.java';
const AUTH_SECURITY_DIR = 'kelta-auth/src/main/java/io/kelta/auth/config';

const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];
const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

// ---------------------------------------------------------------------------------------------
// Java source scanning
// ---------------------------------------------------------------------------------------------

/**
 * Two same-length views of a Java source: `code` with comments blanked, `masked` with string
 * and char literal contents blanked as well, so structure (braces, parens, annotations) can be
 * scanned on `masked` while literal values are read from `code` at the same offsets.
 */
export function maskJava(src: string): { code: string; masked: string } {
  const code = src.split('');
  const masked = src.split('');
  const blank = (arr: string[], i: number) => {
    if (arr[i] !== '\n') arr[i] = ' ';
  };
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const next = src[i + 1];
    if (c === '/' && next === '/') {
      while (i < src.length && src[i] !== '\n') {
        blank(code, i);
        blank(masked, i);
        i++;
      }
    } else if (c === '/' && next === '*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end === -1 ? src.length : end + 2;
      for (; i < stop; i++) {
        blank(code, i);
        blank(masked, i);
      }
    } else if (c === '"' && src.startsWith('"""', i)) {
      const end = src.indexOf('"""', i + 3);
      const stop = end === -1 ? src.length : end;
      for (i += 3; i < stop; i++) blank(masked, i);
      i += 3;
    } else if (c === '"' || c === "'") {
      i++;
      while (i < src.length && src[i] !== c && src[i] !== '\n') {
        if (src[i] === '\\') {
          blank(masked, i);
          i++;
        }
        blank(masked, i);
        i++;
      }
      i++;
    } else {
      i++;
    }
  }
  return { code: code.join(''), masked: masked.join('') };
}

/** Index of the bracket closing the one at `open` (same kind), scanning `masked`. */
export function matchBracket(masked: string, open: number): number {
  const opener = masked[open];
  const closer = opener === '(' ? ')' : opener === '{' ? '}' : ']';
  let depth = 0;
  for (let i = open; i < masked.length; i++) {
    if (masked[i] === opener) depth++;
    else if (masked[i] === closer && --depth === 0) return i;
  }
  return masked.length;
}

function skipSpace(s: string, i: number): number {
  while (i < s.length && /\s/.test(s[i])) i++;
  return i;
}

/** Splits `s` on top-level commas, ignoring commas inside brackets and string literals. */
export function splitTopLevel(s: string, masked: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < masked.length; i++) {
    const c = masked[i];
    if (c === '(' || c === '{' || c === '[') depth++;
    else if (c === ')' || c === '}' || c === ']') depth--;
    else if (c === ',' && depth === 0) {
      parts.push(s.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(s.slice(start));
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

/** `static final String NAME = "value";` constants of a source, by name. */
export function stringConstants(code: string): Map<string, string> {
  const constants = new Map<string, string>();
  for (const m of code.matchAll(/\bstatic\s+final\s+String\s+(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*;/g)) {
    constants.set(m[1], m[2]);
  }
  return constants;
}

/**
 * Resolves an annotation value to its string literals: `"a"`, `{"a", "b"}`, a `CONSTANT` of the
 * same source or a `"a" + CONSTANT` concatenation. Throws on anything else, so a mapping the
 * generator cannot read fails loudly instead of disappearing from the index.
 */
function resolveStrings(value: string, constants: Map<string, string>, where: string): string[] {
  const trimmed = value.trim();
  const masked = maskJava(trimmed).masked;
  const inner = masked.startsWith('{') ? trimmed.slice(1, matchBracket(masked, 0)) : trimmed;
  const elements = splitTopLevel(inner, maskJava(inner).masked);
  return elements.map((element) => {
    const elementMasked = maskJava(element).masked;
    const pieces: string[] = [];
    let from = 0;
    for (let i = 0; i <= element.length; i++) {
      if (i === element.length || elementMasked[i] === '+') {
        pieces.push(element.slice(from, i).trim());
        from = i + 1;
      }
    }
    return pieces
      .map((p) => {
        const literal = p.match(/^"((?:[^"\\]|\\.)*)"$/);
        if (literal) return literal[1];
        const name = p.split('.').pop()!;
        const constant = constants.get(name);
        if (constant !== undefined) return constant;
        throw new Error(`${where}: cannot resolve mapping value ${JSON.stringify(p)}`);
      })
      .join('');
  });
}

interface Annotation {
  name: string;
  /** Arguments as written (comments blanked), or '' when the annotation has none. */
  args: string;
  start: number;
  end: number;
}

/** The parsed attributes of one mapping annotation. */
interface MappingAttrs {
  paths: string[];
  methods: string[];
}

function parseMappingAttrs(annotation: Annotation, constants: Map<string, string>, where: string): MappingAttrs {
  const verb = annotation.name.replace(/Mapping$/, '').toUpperCase();
  const attrs: MappingAttrs = { paths: [], methods: verb === 'REQUEST' ? [] : [verb] };
  for (const part of splitTopLevel(annotation.args, maskJava(annotation.args).masked)) {
    const named = part.match(/^(\w+)\s*=\s*([\s\S]*)$/);
    const key = named ? named[1] : 'value';
    const value = named ? named[2] : part;
    if (key === 'value' || key === 'path') {
      attrs.paths.push(...resolveStrings(value, constants, where));
    } else if (key === 'method') {
      attrs.methods.push(...[...value.matchAll(/\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b/g)].map((m) => m[1]));
    }
  }
  if (attrs.paths.length === 0) attrs.paths.push('');
  return attrs;
}

interface TypeDecl {
  name: string;
  open: number;
  close: number;
  mapping?: MappingAttrs;
}

/** One handler mapping as declared in source, before the gateway is consulted. */
export interface SourceMapping {
  service: Service;
  controller: string;
  handler: string;
  method: string;
  path: string;
  /** System permissions the handler (or a same-class helper it calls) references. */
  handlerPermissions: string[];
}

const MAPPING_RE = /@(Get|Post|Put|Patch|Delete|Request)Mapping\b/g;
const TYPE_RE = /\b(class|interface|record|enum)\s+([A-Z]\w*)\b/g;

/** Turns `{id:[a-z]+}` into `{id}`, joins class and method paths, drops a trailing slash. */
export function joinPath(base: string, sub: string): string {
  const clean = (p: string) => p.replace(/\$\{[^:}]+:([^}]*)\}/g, '$1').replace(/\{(\w+):[^}]*\}/g, '{$1}');
  const parts = [clean(base), clean(sub)].filter((p) => p.length > 0).map((p) => (p.startsWith('/') ? p : `/${p}`));
  const path = parts.join('').replace(/\/+/g, '/');
  return path.length > 1 ? path.replace(/\/$/, '') : path || '/';
}

/**
 * Drops `/x/{p1}`, `/x/{p1}/{p2}` … from a path list that also holds `/x/**`: those are the
 * depth-spelled variants of one catch-all (see the `/**` pitfall in CLAUDE.md), not separate
 * endpoints.
 */
function collapseCatchAll(paths: string[]): string[] {
  const catchAlls = paths.filter((p) => p.endsWith('/**')).map((p) => p.slice(0, -3));
  return paths.filter(
    (p) => !catchAlls.some((base) => p.startsWith(`${base}/`) && /^(\/\{\w+\})+$/.test(p.slice(base.length)) && !p.endsWith('/**')),
  );
}

/**
 * Every mapping annotation of one Java source, resolved against its class-level mapping.
 * `permissions` is the set of system permission names a handler can reference.
 */
export function parseJavaMappings(src: string, file: string, service: Service, permissions: Set<string>): SourceMapping[] {
  const { code, masked } = maskJava(src);
  const constants = stringConstants(code);
  const lineOf = (offset: number) => code.slice(0, offset).split('\n').length;

  const annotationAt = (start: number, nameEnd: number, name: string): Annotation => {
    const after = skipSpace(masked, nameEnd);
    if (masked[after] === '(') {
      const close = matchBracket(masked, after);
      return { name, args: code.slice(after + 1, close), start, end: close + 1 };
    }
    return { name, args: '', start, end: nameEnd };
  };

  // What an annotation ending at `from` annotates: skip further annotations, then read the
  // declaration head up to its first ( { ; or =.
  const declarationAfter = (from: number): { head: string; at: number } => {
    let i = skipSpace(masked, from);
    while (masked[i] === '@') {
      const m = masked.slice(i).match(/^@[\w.]+/);
      if (!m) break;
      i = skipSpace(masked, i + m[0].length);
      if (masked[i] === '(') i = skipSpace(masked, matchBracket(masked, i) + 1);
    }
    const rest = masked.slice(i);
    const stop = rest.search(/[({;=]/);
    return { head: rest.slice(0, stop === -1 ? rest.length : stop), at: i + (stop === -1 ? rest.length : stop) };
  };

  const types: TypeDecl[] = [];
  for (const m of masked.matchAll(TYPE_RE)) {
    const before = masked.slice(0, m.index).trimEnd();
    if (before.endsWith('.')) continue; // Foo.class
    const open = masked.indexOf('{', m.index);
    if (open === -1) continue;
    types.push({ name: m[2], open, close: matchBracket(masked, open) });
  }
  const innermostType = (offset: number) =>
    types.filter((t) => t.open < offset && offset < t.close).sort((a, b) => b.open - a.open)[0];

  const methodBodies = new Map<string, string>();
  for (const m of masked.matchAll(/\b(\w+)\s*\([^;{]*?\)\s*(?:throws\s+[\w.,\s]+)?\{/g)) {
    if (['if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try'].includes(m[1])) continue;
    const open = m.index + m[0].length - 1;
    if (!methodBodies.has(m[1])) methodBodies.set(m[1], code.slice(open, matchBracket(masked, open) + 1));
  }

  const permissionsIn = (body: string): Set<string> => {
    const found = new Set<string>();
    for (const lit of body.matchAll(/"([A-Z_]+)"/g)) if (permissions.has(lit[1])) found.add(lit[1]);
    for (const id of body.matchAll(/\b([A-Z][A-Z0-9_]+)\b/g)) {
      const value = constants.get(id[1]) ?? (permissions.has(id[1]) ? id[1] : undefined);
      if (value && permissions.has(value)) found.add(value);
    }
    return found;
  };

  const methodMappings: Array<{ annotation: Annotation; head: string; at: number }> = [];
  for (const m of masked.matchAll(MAPPING_RE)) {
    const annotation = annotationAt(m.index, m.index + m[0].length, `${m[1]}Mapping`);
    const { head, at } = declarationAfter(annotation.end);
    const where = `${file}:${lineOf(m.index)}`;
    if (/\b(class|interface|record|enum)\b/.test(head)) {
      const declared = types.filter((t) => t.open > annotation.end).sort((a, b) => a.open - b.open)[0];
      if (declared) declared.mapping = parseMappingAttrs(annotation, constants, where);
    } else {
      methodMappings.push({ annotation, head, at });
    }
  }

  const mappings: SourceMapping[] = [];
  for (const { annotation, head, at } of methodMappings) {
    const where = `${file}:${lineOf(annotation.start)}`;
    const attrs = parseMappingAttrs(annotation, constants, where);
    const type = innermostType(annotation.start);
    const handler = head.trim().split(/\s+/).pop() ?? '';
    let body = '';
    if (masked[at] === '(') {
      const open = masked.indexOf('{', matchBracket(masked, at));
      const semi = masked.indexOf(';', matchBracket(masked, at));
      if (open !== -1 && (semi === -1 || open < semi)) body = code.slice(open, matchBracket(masked, open) + 1);
    }
    const found = permissionsIn(body);
    for (const call of body.matchAll(/\b((?:require|check|ensure|assert|authorize)\w*)\s*\(/g)) {
      const helper = methodBodies.get(call[1]);
      if (helper && call[1] !== handler) for (const p of permissionsIn(helper)) found.add(p);
    }
    found.delete('API_ACCESS');

    const base = type?.mapping ?? { paths: [''], methods: [] };
    const methods = attrs.methods.length > 0 ? attrs.methods : base.methods.length > 0 ? base.methods : ['ANY'];
    const paths = collapseCatchAll(base.paths.flatMap((b) => attrs.paths.map((p) => joinPath(b, p))));
    for (const path of [...new Set(paths)]) {
      for (const method of [...new Set(methods)]) {
        mappings.push({
          service,
          controller: type?.name ?? '',
          handler,
          method,
          path,
          handlerPermissions: [...found].sort(),
        });
      }
    }
  }
  return mappings;
}

// ---------------------------------------------------------------------------------------------
// Gateway route table
// ---------------------------------------------------------------------------------------------

export interface StaticRoute {
  id: string;
  /** The route's path without its trailing `/**`. */
  prefix: string;
}

export interface CollectionCheck {
  includeReads: boolean;
  writeOverride: string | null;
  exempt: RegExp | null;
}

/** A gateway write rule hard-coded in RouteAuthorizationFilter for one static route. */
export interface FilterWriteRule {
  routeId: string;
  pathPrefix: string | null;
  permission: string;
}

export interface GatewayTable {
  staticRoutes: StaticRoute[];
  checks: Map<string, CollectionCheck>;
  filterWriteRules: FilterWriteRule[];
  systemCollections: Set<string>;
  unauthenticatedPaths: string[];
  publicPaths: string[];
}

export function parseStaticRoutes(routeConfig: string, routeInitializer: string): StaticRoute[] {
  const { code } = maskJava(routeConfig);
  const table = code.slice(code.indexOf('STATIC_ROUTES = {'), code.indexOf('};', code.indexOf('STATIC_ROUTES = {')));
  const routes = [...table.matchAll(/\{\s*"([^"]+)",\s*"([^"]+)",\s*"[^"]*"\s*\}/g)].map((m) => ({
    id: m[1],
    prefix: m[2].replace(/\/\*\*$/, ''),
  }));
  if (routes.length === 0) throw new Error(`${ROUTE_CONFIG}: no STATIC_ROUTES entries found`);
  for (const m of maskJava(routeInitializer).code.matchAll(/new RouteDefinition\(\s*"(static-[^"]+)",\s*"([^"]+)"/g)) {
    routes.push({ id: m[1].replace(/^static-/, ''), prefix: m[2].replace(/\/\*\*$/, '') });
  }
  return routes;
}

export function parseCollectionChecks(routeConfig: string): Map<string, CollectionCheck> {
  const { code } = maskJava(routeConfig);
  const start = code.indexOf('COLLECTION_CHECKED_STATIC_ROUTES = Map.ofEntries(');
  if (start === -1) throw new Error(`${ROUTE_CONFIG}: COLLECTION_CHECKED_STATIC_ROUTES not found`);
  const open = code.indexOf('(', start);
  const body = code.slice(open + 1, matchBracket(maskJava(code).masked, open));
  const checks = new Map<string, CollectionCheck>();
  for (const entry of body.matchAll(
    /Map\.entry\(\s*"([^"]+)",\s*new StaticCollectionCheck\(\s*(true|false),\s*(?:"(\w+)"|null)(?:,\s*Pattern\.compile\("((?:[^"\\]|\\.)*)"\))?\s*\)\)/g,
  )) {
    checks.set(entry[1], {
      includeReads: entry[2] === 'true',
      writeOverride: entry[3] ?? null,
      exempt: entry[4] ? new RegExp(entry[4].replace(/\\\\/g, '\\')) : null,
    });
  }
  if (checks.size === 0) throw new Error(`${ROUTE_CONFIG}: no COLLECTION_CHECKED_STATIC_ROUTES entries parsed`);
  return checks;
}

export function parseFilterWriteRules(routeAuthz: string): FilterWriteRule[] {
  const { code } = maskJava(routeAuthz);
  return [
    ...code.matchAll(
      /"static-([\w-]+)"\.equals\(collectionId\)\s*(?:&&\s*path\.startsWith\("([^"]+)"\)\s*)?&&\s*StaticCollectionCheck\.isWrite\(method\)\)\s*\{\s*return cerbosService\.checkSystemPermission\(principal,\s*"(\w+)"\)/g,
    ),
  ].map((m) => ({ routeId: m[1], pathPrefix: m[2] ?? null, permission: m[3] }));
}

/** `key: a,b,c` under `kelta.gateway.security` in the gateway application.yml. */
export function parseYamlList(yml: string, key: string): string[] {
  const line = yml.split('\n').find((l) => new RegExp(`^\\s+${key}:\\s`).test(l));
  if (!line) throw new Error(`${GATEWAY_YML}: ${key} not found`);
  return line
    .replace(/^[^:]+:\s*/, '')
    .replace(/\s+#.*$/, '')
    .split(',')
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
}

export function parseSystemCollections(sources: string[]): Set<string> {
  const names = new Set<string>();
  for (const src of sources) for (const m of src.matchAll(/\b(?:readOnlySystemBuilder|systemBuilder)\(\s*"([^"]+)"/g)) names.add(m[1]);
  return names;
}

export function parsePermissions(src: string): Set<string> {
  const { code } = maskJava(src);
  const list = code.match(/ALL_PERMISSIONS\s*=\s*List\.of\(([^)]*)\)/);
  if (!list) throw new Error(`${PERMISSIONS_SOURCE}: ALL_PERMISSIONS not found`);
  return new Set([...list[1].matchAll(/"(\w+)"/g)].map((m) => m[1]));
}

/** Path patterns kelta-auth's security chains `permitAll()`. */
export function parseAuthPermitAll(sources: string[]): string[] {
  const patterns: string[] = [];
  for (const src of sources) {
    const { code, masked } = maskJava(src);
    const constants = stringConstants(code);
    for (const m of masked.matchAll(/\.requestMatchers\(/g)) {
      const open = m.index + m[0].length - 1;
      const close = matchBracket(masked, open);
      if (!/^\s*\.permitAll\(\)/.test(masked.slice(close + 1))) continue;
      for (const arg of splitTopLevel(code.slice(open + 1, close), masked.slice(open + 1, close))) {
        const literal = arg.match(/^"([^"]*)"$/);
        const value = literal ? literal[1] : constants.get(arg);
        if (value) patterns.push(value);
      }
    }
  }
  return patterns;
}

function segmentMatch(path: string, prefix: string): boolean {
  const p = prefix.replace(/\/$/, '');
  return p.length > 0 && (path === p || path.startsWith(`${p}/`));
}

function antMatch(path: string, pattern: string): boolean {
  if (pattern.endsWith('/**')) return segmentMatch(path, pattern.slice(0, -3));
  return path === pattern;
}

/** A concrete stand-in for a templated path, for matching against the gateway's regexes. */
function concrete(path: string): string {
  return path.replace(/\{\w+\}/g, 'x').replace(/\/\*\*$/, '');
}

// ---------------------------------------------------------------------------------------------
// Public endpoints
// ---------------------------------------------------------------------------------------------

export interface Endpoint {
  service: Service;
  method: string;
  path: string;
  permission: string;
  sources: string[];
}

export interface Unrouted {
  service: Service;
  method: string;
  path: string;
  source: string;
}

function gatewayPermission(table: GatewayTable, method: string, path: string, route: StaticRoute | null, handler: string[]): string {
  const handlerPart = handler.length > 0 ? `; handler checks ${handler.join(' / ')}` : '';
  if (table.unauthenticatedPaths.some((p) => segmentMatch(path, p))) return `none (unauthenticated)${handlerPart}`;
  if (method === 'GET' && table.publicPaths.some((p) => segmentMatch(path, p))) {
    return `none (public read)${handlerPart}`;
  }
  const writes = WRITE_METHODS.has(method) || method === 'ANY';
  if (route) {
    const extra: string[] = [];
    const check = table.checks.get(route.id);
    if (check && !(check.exempt && check.exempt.test(concrete(path))) && (check.includeReads || writes)) {
      extra.push(check.writeOverride ? `${route.id} grant or ${check.writeOverride}` : `${route.id} grant`);
    }
    for (const rule of table.filterWriteRules) {
      if (rule.routeId === route.id && writes && (!rule.pathPrefix || segmentMatch(path, rule.pathPrefix))) {
        extra.push(rule.permission);
      }
    }
    const handlerPerms = handler.filter((p) => !extra.some((e) => e.split(' ').includes(p)));
    const gate = ['API_ACCESS', ...extra].join(' + ');
    return handlerPerms.length > 0 ? `${gate}; handler checks ${handlerPerms.join(' / ')}` : gate;
  }
  return `API_ACCESS + collection grant${handlerPart}`;
}

/**
 * Joins source mappings with the gateway table. kelta-auth and kelta-mcp have their own
 * ingress, so every mapping of theirs is public; a worker or kelta-ai mapping is public when a
 * gateway route reaches it.
 */
export function buildEndpoints(mappings: SourceMapping[], table: GatewayTable, authPermitAll: string[]): { endpoints: Endpoint[]; unrouted: Unrouted[] } {
  const byKey = new Map<string, Endpoint>();
  const unrouted: Unrouted[] = [];
  for (const m of mappings) {
    const source = `${m.controller}#${m.handler}`;
    let permission: string;
    if (m.service === 'kelta-auth') {
      const open = authPermitAll.some((p) => antMatch(m.path, p));
      permission = open ? 'none (permitAll)' : 'kelta-auth session';
      if (m.handlerPermissions.length > 0) permission += `; handler checks ${m.handlerPermissions.join(' / ')}`;
    } else if (m.service === 'kelta-mcp') {
      permission = 'PAT (tools re-authorized by the gateway)';
    } else {
      const routes = table.staticRoutes
        .filter((r) => segmentMatch(m.path, r.prefix))
        .filter((r) => (m.service === 'kelta-ai') === (r.id === 'ai'))
        .sort((a, b) => b.prefix.length - a.prefix.length);
      const segment = m.path.split('/')[2] ?? '';
      const collectionRoute =
        m.service === 'kelta-worker' && m.path.startsWith('/api/') && (/^\{\w+\}$/.test(segment) || table.systemCollections.has(segment));
      if (routes.length > 0) {
        permission = gatewayPermission(table, m.method, m.path, routes[0], m.handlerPermissions);
      } else if (collectionRoute) {
        permission = gatewayPermission(table, m.method, m.path, null, m.handlerPermissions);
      } else {
        unrouted.push({ service: m.service, method: m.method, path: m.path, source });
        continue;
      }
    }
    const key = `${m.method} ${m.path}`;
    const existing = byKey.get(key);
    if (existing) {
      if (!existing.sources.includes(source)) existing.sources.push(source);
    } else {
      byKey.set(key, { service: m.service, method: m.method, path: m.path, permission, sources: [source] });
    }
  }
  const order = (a: { path: string; method: string }, b: { path: string; method: string }) =>
    a.path < b.path ? -1 : a.path > b.path ? 1 : methodRank(a.method) - methodRank(b.method);
  return {
    endpoints: [...byKey.values()].sort(order),
    unrouted: [...new Map(unrouted.map((u) => [`${u.method} ${u.path}`, u])).values()].sort(order),
  };
}

function methodRank(method: string): number {
  const i = HTTP_METHODS.indexOf(method);
  return i === -1 ? HTTP_METHODS.length : i;
}

// ---------------------------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------------------------

export type Classification = { docs: string; internal?: never } | { internal: string; docs?: never };

/**
 * Finds the classification entry for an endpoint. Keys are `METHOD /path` (exact) or a pattern:
 * `*` for any method and/or a path ending in `/**` for that path and everything below it. An
 * exact key wins, then the pattern with the longest path, then a method-specific pattern.
 */
export function classify(endpoint: { method: string; path: string }, entries: Record<string, Classification>): { key: string; value: Classification } | null {
  const exact = `${endpoint.method} ${endpoint.path}`;
  if (entries[exact]) return { key: exact, value: entries[exact] };
  let best: { key: string; score: number } | null = null;
  for (const key of Object.keys(entries)) {
    const [method, pattern] = key.split(' ');
    if (method !== '*' && method !== endpoint.method) continue;
    const matches = pattern.endsWith('/**') ? segmentMatch(endpoint.path, pattern.slice(0, -3)) : pattern === endpoint.path;
    if (!matches) continue;
    const score = pattern.length * 2 + (method === '*' ? 0 : 1);
    if (!best || score > best.score) best = { key, score };
  }
  return best ? { key: best.key, value: entries[best.key] } : null;
}

export interface Model {
  endpoints: Array<Endpoint & { classification: { key: string; value: Classification } | null }>;
  unrouted: Unrouted[];
}

export function unclassifiedCount(model: Model): number {
  return model.endpoints.filter((e) => !e.classification).length;
}

/** null when the count equals the baseline; otherwise the failure message. */
export function ratchet(count: number, baseline: number): string | null {
  if (count > baseline) {
    return (
      `${count} public endpoints are unclassified, above the baseline of ${baseline}. ` +
      `Link each new endpoint to the page that documents it, or classify it internal with a reason, in ${CLASSIFICATION_PATH}.`
    );
  }
  if (count < baseline) {
    return `${count} public endpoints are unclassified, below the baseline of ${baseline}: lower the baseline to ${count} in ${BASELINE_PATH}.`;
  }
  return null;
}

/** Problems with the classification file itself: missing reasons, malformed or unused entries. */
export function classificationProblems(model: Model, entries: Record<string, Classification>): string[] {
  const problems: string[] = [];
  const used = new Set(model.endpoints.map((e) => e.classification?.key).filter(Boolean));
  for (const [key, value] of Object.entries(entries)) {
    if (!/^(\*|[A-Z]+) \/\S*$/.test(key)) problems.push(`${key}: key must be "METHOD /path" or "* /path/**"`);
    const hasDocs = typeof value.docs === 'string';
    const hasInternal = 'internal' in value;
    if (hasDocs === hasInternal) problems.push(`${key}: needs exactly one of "docs" or "internal"`);
    if (hasInternal && (typeof value.internal !== 'string' || value.internal.trim().length < 10)) {
      problems.push(`${key}: an internal classification needs a reason`);
    }
    if (hasDocs && !value.docs!.startsWith('/docs/')) problems.push(`${key}: docs link must start with /docs/`);
    if (!used.has(key)) problems.push(`${key}: matches no public endpoint — remove it`);
  }
  return problems;
}

// ---------------------------------------------------------------------------------------------
// Docs pages (for link checks)
// ---------------------------------------------------------------------------------------------

/** GitHub-style heading slug, as rehype-slug renders it on the docs site. */
export function slug(heading: string): string {
  return heading
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_]{1,2}([^*_]+)[*_]{1,2}/g, '$1')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .replace(/ /g, '-');
}

function anchorsOf(markdown: string): Set<string> {
  const anchors = new Set<string>();
  const counts = new Map<string, number>();
  let fenced = false;
  for (const line of markdown.split('\n')) {
    if (/^(```|~~~)/.test(line)) fenced = !fenced;
    const h = !fenced && line.match(/^#{1,6}\s+(.*?)\s*#*\s*$/);
    if (!h) continue;
    const base = slug(h[1]);
    const n = counts.get(base) ?? 0;
    counts.set(base, n + 1);
    anchors.add(n === 0 ? base : `${base}-${n}`);
  }
  return anchors;
}

/** Docs URL → the headings it renders, for every page the docs site publishes from this repo. */
export function docsPages(root: string): Map<string, Set<string>> {
  const pages = new Map<string, Set<string>>();
  const content = join(root, 'kelta-marketing/src/content/docs');
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (/\.mdx?$/.test(name)) {
        const id = relative(content, full).replace(/\.mdx?$/, '');
        pages.set(`/docs/${id}/`, anchorsOf(readFileSync(full, 'utf8').replace(/^---\n[\s\S]*?\n---\n/, '')));
      }
    }
  };
  walk(content);
  const meta = readFileSync(join(root, 'kelta-marketing/src/docs/externalMeta.ts'), 'utf8');
  for (const m of meta.matchAll(/'(authoring|cli)\/([\w-]+)':\s*\{/g)) {
    const file = m[1] === 'authoring' ? `docs/authoring/${m[2]}.md` : `kelta-web/packages/cli/${m[2].toUpperCase()}.md`;
    const href = m[1] === 'authoring' ? `/docs/reference/${m[2]}/` : `/docs/cli/${m[2]}/`;
    pages.set(href, existsSync(join(root, file)) ? anchorsOf(readFileSync(join(root, file), 'utf8')) : new Set());
  }
  return pages;
}

/** Docs links in the classification that point at a page or anchor the site does not publish. */
export function brokenDocsLinks(entries: Record<string, Classification>, pages: Map<string, Set<string>>): string[] {
  const broken: string[] = [];
  for (const [key, value] of Object.entries(entries)) {
    if (typeof value.docs !== 'string') continue;
    const [path, fragment] = value.docs.split('#');
    const anchors = pages.get(path);
    if (!anchors) broken.push(`${key}: ${value.docs} — no such docs page`);
    else if (fragment !== undefined && !anchors.has(fragment)) broken.push(`${key}: ${value.docs} — no such heading on ${path}`);
  }
  return broken;
}

// ---------------------------------------------------------------------------------------------
// Collecting and rendering
// ---------------------------------------------------------------------------------------------

export function javaFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...javaFiles(full));
    else if (name.endsWith('.java')) out.push(full);
  }
  return out;
}

export function readClassification(root: string): Record<string, Classification> {
  return JSON.parse(readFileSync(join(root, CLASSIFICATION_PATH), 'utf8')).endpoints;
}

export function readBaseline(root: string): number {
  return JSON.parse(readFileSync(join(root, BASELINE_PATH), 'utf8')).unclassified;
}

export function loadGatewayTable(root: string): GatewayTable {
  const read = (p: string) => readFileSync(join(root, p), 'utf8');
  const yml = read(GATEWAY_YML);
  return {
    staticRoutes: parseStaticRoutes(read(ROUTE_CONFIG), read(ROUTE_INITIALIZER)),
    checks: parseCollectionChecks(read(ROUTE_CONFIG)),
    filterWriteRules: parseFilterWriteRules(read(ROUTE_AUTHZ)),
    systemCollections: parseSystemCollections(javaFiles(join(root, SYSTEM_COLLECTIONS_DIR)).map((f) => readFileSync(f, 'utf8'))),
    unauthenticatedPaths: parseYamlList(yml, 'unauthenticated-paths'),
    publicPaths: parseYamlList(yml, 'public-paths'),
  };
}

export function collect(root: string = REPO_ROOT, entries: Record<string, Classification> = readClassification(root)): Model {
  const permissions = parsePermissions(readFileSync(join(root, PERMISSIONS_SOURCE), 'utf8'));
  const mappings: SourceMapping[] = [];
  for (const service of SERVICES) {
    for (const file of SOURCE_ROOTS[service].flatMap((dir) => javaFiles(join(root, dir)))) {
      const src = readFileSync(file, 'utf8');
      if (!MAPPING_RE.test(src)) continue;
      MAPPING_RE.lastIndex = 0;
      mappings.push(...parseJavaMappings(src, relative(root, file), service, permissions));
    }
  }
  const authPermitAll = parseAuthPermitAll(javaFiles(join(root, AUTH_SECURITY_DIR)).map((f) => readFileSync(f, 'utf8')));
  const { endpoints, unrouted } = buildEndpoints(mappings, loadGatewayTable(root), authPermitAll);
  return { endpoints: endpoints.map((e) => ({ ...e, classification: classify(e, entries) })), unrouted };
}

const SERVICE_LABEL: Record<Service, string> = {
  'kelta-worker': 'worker',
  'kelta-auth': 'auth',
  'kelta-ai': 'ai',
  'kelta-mcp': 'mcp',
};

function cell(text: string): string {
  return text.replace(/\|/g, '\\|');
}

export function render(model: Model): string {
  const total = model.endpoints.length;
  const documented = model.endpoints.filter((e) => e.classification?.value.docs).length;
  const internal = model.endpoints.filter((e) => e.classification?.value.internal).length;
  const unclassified = total - documented - internal;
  const lines = [
    '# Public Endpoint Index',
    '',
    '<!-- Generated by ci/endpoint-index.ts from the controller mappings and the gateway route table.',
    `     Do not edit by hand: change ${CLASSIFICATION_PATH} or the source, then run ${WRITE_COMMAND} -->`,
    '',
    'Every HTTP endpoint a client outside the platform can reach, generated from the Spring mapping',
    'annotations of the services and the gateway route table, with the permission it requires and the',
    'page that documents it.',
    '',
    '## What counts as public',
    '',
    'An endpoint is **public** when a request from outside the cluster can reach it:',
    '',
    '- a **kelta-worker** or **kelta-ai** mapping is public when a gateway route reaches it — one of the',
    '  gateway\'s static routes (`/api/admin/**`, `/api/flows/**`, `/api/ai/**`, …) or the per-collection',
    '  route every collection gets (`/api/{collection}/**`, which also covers the system collections);',
    '- every **kelta-auth** mapping is public: kelta-auth has its own ingress, which sends every path of the',
    '  auth host to it, so its own security chain is the gate;',
    '- every **kelta-mcp** mapping is public: kelta-mcp is reached on the API origin at `/{tenantSlug}/mcp/...`',
    '  without a gateway route, authenticates the caller\'s personal access token, and forwards each tool call',
    '  through the gateway, which authorizes it.',
    '',
    'A worker mapping that no gateway route reaches (the `/internal/**` service-to-service endpoints, for',
    'example) is not public. Those are left out of the table and only counted at the end of the page.',
    '',
    'Paths are relative to the API host, except the `auth` rows, which are on the auth host. Behind the',
    'gateway, a tenant\'s requests may also carry its slug first (`/{tenantSlug}/api/...`); the gateway strips',
    'it before routing.',
    '',
    '## Reading the permission column',
    '',
    '- `none (unauthenticated)` — the path is in the gateway\'s `unauthenticated-paths`; the endpoint',
    '  verifies the caller itself (a signature, a signed token).',
    '- `none (public read)` — a `GET` under the gateway\'s `public-paths` (UI bootstrap metadata), served',
    '  without a token for the resolved tenant.',
    '- `API_ACCESS` — the gateway checks the `API_ACCESS` system permission on every authenticated call.',
    '- `API_ACCESS + <collection> grant or <PERMISSION>` — a static route that fronts a system collection:',
    '  the gateway also checks the caller\'s grant on that collection for the HTTP method, or for a write the',
    '  named system permission.',
    '- `API_ACCESS + collection grant` — a per-collection route: the collection\'s object permission for the',
    '  HTTP method (read, create, edit, delete).',
    '- A further `+ PERMISSION` is a system permission the gateway checks for that method and path.',
    '- `; handler checks A / B` — system permissions the handler (or a `require…`/`check…` helper in its',
    '  class) references, found statically: one of them may be required, or each may gate a different branch',
    '  — the linked page says which. A check made in a service the handler calls is not seen here.',
    '- `none (permitAll)` and `kelta-auth session` — kelta-auth\'s security chain: open, or a signed-in',
    '  session (or bearer token) required.',
    '',
    '`internal` marks an endpoint only the platform\'s own admin UI or services call; it is reachable, but',
    'not a supported integration surface and may change without notice.',
    '',
    `**${total}** public endpoints: **${documented}** linked to docs, **${internal}** internal,`,
    `**${unclassified}** not yet documented.`,
    '',
    '## Endpoints',
    '',
    '| Method | Path | Service | Permission | Docs |',
    '| --- | --- | --- | --- | --- |',
  ];
  for (const e of model.endpoints) {
    const c = e.classification?.value;
    const docs = c?.docs ? `[${c.docs.replace(/^\/docs\//, '').replace(/\/(#.*)?$/, '')}](${c.docs})` : c?.internal ? `internal — ${c.internal}` : 'undocumented';
    lines.push(`| ${e.method} | \`${cell(e.path)}\` | ${SERVICE_LABEL[e.service]} | ${cell(e.permission)} | ${cell(docs)} |`);
  }
  lines.push(
    '',
    '## Not routed',
    '',
    `${model.unrouted.length} worker mappings are reached by no gateway route and are not public.`,
    '',
  );
  return `${lines.join('\n')}`;
}

/** null when the committed page is the fresh render; otherwise the failure message. */
export function stalePage(committed: string, rendered: string): string | null {
  if (committed === rendered) return null;
  const at = [...committed].findIndex((c, i) => c !== rendered[i]);
  const line = committed.slice(0, at === -1 ? committed.length : at).split('\n').length;
  return `${PAGE_PATH} differs from a fresh render (first difference at line ${line}) — run \`${WRITE_COMMAND}\` and commit the result.`;
}

export function check(root: string = REPO_ROOT): string[] {
  const entries = readClassification(root);
  const model = collect(root, entries);
  const failures: string[] = [];
  const committed = existsSync(join(root, PAGE_PATH)) ? readFileSync(join(root, PAGE_PATH), 'utf8') : '';
  const stale = stalePage(committed, render(model));
  if (stale) failures.push(stale);
  failures.push(...classificationProblems(model, entries));
  failures.push(...brokenDocsLinks(entries, docsPages(root)));
  const verdict = ratchet(unclassifiedCount(model), readBaseline(root));
  if (verdict) failures.push(verdict);
  return failures;
}

if (import.meta.main) {
  if (process.argv.includes('--write')) {
    const model = collect();
    writeFileSync(join(REPO_ROOT, PAGE_PATH), render(model));
    console.log(`Wrote ${PAGE_PATH}: ${model.endpoints.length} public endpoints, ${unclassifiedCount(model)} unclassified, ${model.unrouted.length} not routed.`);
    if (process.argv.includes('--list-unclassified')) {
      for (const e of model.endpoints.filter((x) => !x.classification)) console.log(`${e.method} ${e.path}\t${e.permission}\t${e.sources.join(',')}`);
    }
  } else {
    const failures = check();
    for (const f of failures) console.error(f);
    process.exit(failures.length > 0 ? 1 : 0);
  }
}
