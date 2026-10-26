// node ci/doc-error-codes.ts   (Node >= 24; no dependencies)
//
// Checks the Code column of kelta-marketing/src/content/docs/api/errors.md both ways against the
// error codes the platform emits: a documented code nothing emits fails, and so does an emitted
// code the table lacks (unless SKIPPED below lists it with a reason).
//
// Scope — the services and libraries whose errors reach a public HTTP client in the JSON:API
// envelope: the `src/main/java` trees in SOURCE_ROOTS (kelta-gateway, kelta-worker, kelta-ai,
// kelta-auth, and the runtime-core, runtime-jsonapi and runtime-module-schema libraries the
// worker serves through). Left out: kelta-mcp (its errors are MCP tool results, documented with
// the MCP tools), runtime-module-core and runtime-module-integration (flow step errors, recorded on
// the flow run, not returned to the caller), and kelta-web/packages/cli (the table is HTTP-only;
// the CLI section of errors.md lists no codes of its own).
//
// Emitter shapes recognised (comments and string contents handled by maskJava from
// ci/endpoint-index.ts; constants resolved with stringConstants, in the same source or as
// `Class.CONSTANT` across sources):
//   1. A `"code"` map key: `x.put("code", V)`, `Map.of(..., "code", V, ...)`, `Map.entry("code", V)`.
//   2. A `"code": "V"` member inside a JSON string or text block (hand-written error bodies).
//   3. An argument passed to a parameter named `code` (type String) of any method, constructor or
//      record declared in scope, matched by name and arity: `errorResponse("400", "MISSING_TENANT",
//      ...)`, the 4-argument `JsonApiResponseBuilder.error(status, code, title, detail)`,
//      `new JsonApiError(status, code, title, detail)`, `new ValidationError(field, msg, code)`,
//      `setCode(V)`. CODE_PARAMETERS adds the parameters a handler emits as a code although they
//      are not named `code` (FieldError's `constraint`).
// From each value expression V the extractor takes the string literals and constants at its top
// level, so `blank ? "VALIDATION_FAILED" : fieldError.constraint()` yields VALIDATION_FAILED and a
// purely dynamic value (`code`, `status.name()`) yields nothing.
//
// Not recognised, deliberately: codes derived at run time — the 3-argument
// JsonApiResponseBuilder.error(status, title, detail) upper-snakes its title, and the gateway's
// ResponseStatusException branch uses the HttpStatus name. Those are fallbacks, not contract codes.
//
// Codes it finds that the table leaves out on purpose are in SKIPPED, each with its reason. A
// SKIPPED entry without a reason, one nothing emits any more, or one the table documents fails.
// The Code column may list several codes in one cell; a `—` cell lists none.
import { readFileSync } from 'node:fs';
import { basename, join, relative } from 'node:path';
import { REPO_ROOT, javaFiles, maskJava, matchBracket, splitTopLevel, stringConstants } from './endpoint-index.ts';

export const ERRORS_DOC = 'kelta-marketing/src/content/docs/api/errors.md';

export const SOURCE_ROOTS = [
  'kelta-gateway/src/main/java',
  'kelta-worker/src/main/java',
  'kelta-ai/src/main/java',
  'kelta-auth/src/main/java',
  'kelta-platform/runtime/runtime-core/src/main/java',
  'kelta-platform/runtime/runtime-jsonapi/src/main/java',
  'kelta-platform/runtime/runtime-module-schema/src/main/java',
];

/** Parameters emitted as an error code although not named `code`: declaring type → parameter. */
export const CODE_PARAMETERS: Record<string, string> = {
  // GlobalExceptionHandler and AtomicOperationsController put a non-blank constraint in `code`.
  FieldError: 'constraint',
};

/** Emitted codes the table leaves out on purpose, each with the reason. */
export const SKIPPED: Record<string, string> = {
  MISSING_TENANT:
    'SearchController answers it when X-Tenant-ID is absent, but the gateway answers a request with no resolved tenant with NOT_FOUND (RouteAuthorizationFilter) and otherwise always sets that header (HeaderTransformationFilter), so no client can see it.',
  invalid_input:
    "kelta-ai's ToolDispatcher returns it inside a tool result to the model during the agent loop; it never reaches an HTTP response.",
};

export interface Emission {
  code: string;
  where: string;
}

interface CodeParameter {
  name: string;
  /** The type declaring it: the class of a method, the type itself for a constructor or record. */
  owner: string;
  constructor: boolean;
  file: string;
  arity: number;
  index: number;
}

const DECL_PARAM_RE = /^(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:final\s+)?([\w.]+(?:<[\w<>?,.\s]*>)?(?:\[\])*)\s+(\w+)$/;
const CALL_RE = /(\bnew\s+)?(?:\b(\w+)\s*\.\s*)?\b([A-Za-z_]\w*)\s*\(/g;
const TYPE_RE = /\b(?:class|interface|record|enum)\s+([A-Z]\w*)/g;
const JSON_CODE_RE = /\\?"code\\?"\s*:\s*\\?"([A-Za-z_]\w*)\\?"/g;
const KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try', 'return', 'super', 'this']);

interface Source {
  file: string;
  code: string;
  masked: string;
  constants: Map<string, string>;
}

function arguments_(src: Source, open: number): Array<{ code: string; masked: string }> {
  const close = matchBracket(src.masked, open);
  const codeParts = splitTopLevel(src.code.slice(open + 1, close), src.masked.slice(open + 1, close));
  const maskedParts = splitTopLevel(src.masked.slice(open + 1, close), src.masked.slice(open + 1, close));
  return codeParts.map((code, i) => ({ code, masked: maskedParts[i] }));
}

/** Argument parts re-joined where a generic type's comma split them (`Map<String, Object> meta`). */
function parameters(parts: Array<{ masked: string }>): string[] {
  const out: string[] = [];
  for (const part of parts) {
    const text = part.masked.replace(/\s+/g, ' ');
    const last = out[out.length - 1];
    if (last !== undefined && (last.match(/</g) ?? []).length > (last.match(/>/g) ?? []).length) out[out.length - 1] = `${last}, ${text}`;
    else out.push(text);
  }
  return out;
}

/** The literals and constants at the top level of one value expression. */
export function valuesOf(expr: { code: string; masked: string }, constants: Map<string, string>, global: Map<string, Map<string, string>>): string[] {
  const values: string[] = [];
  let depth = 0;
  let topLevel = '';
  for (let i = 0; i < expr.masked.length; i++) {
    const c = expr.masked[i];
    if (c === '(' || c === '{' || c === '[') depth++;
    else if (c === ')' || c === '}' || c === ']') depth--;
    if (depth === 0 && c === '"' && !expr.masked.startsWith('"""', i)) {
      const end = expr.masked.indexOf('"', i + 1);
      values.push(expr.code.slice(i + 1, end));
      topLevel += ' '.repeat(end - i + 1);
      i = end;
      continue;
    }
    topLevel += depth === 0 ? c : ' ';
  }
  for (const m of topLevel.matchAll(/(?<![\w.])(?:([A-Z]\w*)\s*\.\s*)?([A-Z][A-Z0-9_]+)\b(?!\s*\()/g)) {
    const value = m[1] ? global.get(m[1])?.get(m[2]) : constants.get(m[2]);
    if (value !== undefined) values.push(value);
  }
  return values;
}

function lineOf(src: Source, offset: number): string {
  return `${src.file}:${src.code.slice(0, offset).split('\n').length}`;
}

/** Declarations (methods, constructors, records) that take an error code, by name. */
export function codeParameters(sources: Source[]): Map<string, CodeParameter[]> {
  const found = new Map<string, CodeParameter[]>();
  for (const src of sources) {
    const types = [...src.masked.matchAll(TYPE_RE)].map((t) => {
      const open = src.masked.indexOf('{', t.index);
      return { name: t[1], open, close: open === -1 ? -1 : matchBracket(src.masked, open) };
    });
    const typeNames = new Set(types.map((t) => t.name));
    for (const m of src.masked.matchAll(CALL_RE)) {
      const name = m[3];
      if (m[1] || m[2] || KEYWORDS.has(name)) continue;
      const open = m.index + m[0].length - 1;
      const params = parameters(arguments_(src, open)).map((p) => p.match(DECL_PARAM_RE));
      if (params.length === 0 || params.some((p) => !p)) continue;
      const wanted = CODE_PARAMETERS[name] ?? 'code';
      const index = params.findIndex((p) => p![1] === 'String' && p![2] === wanted);
      if (index === -1) continue;
      const constructor = typeNames.has(name);
      const enclosing = types.filter((t) => t.open < m.index && m.index < t.close).sort((a, b) => b.open - a.open)[0];
      const owner = constructor ? name : (enclosing?.name ?? basename(src.file, '.java'));
      const list = found.get(name) ?? [];
      if (!list.some((d) => d.owner === owner && d.arity === params.length)) {
        list.push({ name, owner, constructor, file: src.file, arity: params.length, index });
      }
      found.set(name, list);
    }
  }
  return found;
}

/**
 * Whether a call reaches a declaration: `new Owner(...)` a constructor; `Owner.name(...)` a method
 * of that type; `variable.name(...)` any method of that name; a bare `name(...)` a method of the
 * same source.
 */
function reaches(d: CodeParameter, isNew: boolean, qualifier: string | undefined, file: string): boolean {
  if (isNew) return d.constructor;
  if (d.constructor) return false;
  if (qualifier === undefined || qualifier === 'this') return d.file === file;
  return /^[A-Z]/.test(qualifier) ? qualifier === d.owner : true;
}

export function readSources(files: Array<{ file: string; text: string }>): Source[] {
  return files.map(({ file, text }) => {
    const { code, masked } = maskJava(text);
    return { file, code, masked, constants: stringConstants(code) };
  });
}

/** Every emitted error code, with where it is emitted. */
export function extractEmittedCodes(sources: Source[]): Emission[] {
  const global = new Map(sources.map((s) => [basename(s.file, '.java'), s.constants]));
  const declarations = codeParameters(sources);
  const out: Emission[] = [];
  for (const src of sources) {
    const emit = (values: string[], offset: number) => {
      for (const code of values) if (code.trim().length > 0) out.push({ code, where: lineOf(src, offset) });
    };
    for (const m of src.masked.matchAll(CALL_RE)) {
      const name = m[3];
      if (KEYWORDS.has(name)) continue;
      const open = m.index + m[0].length - 1;
      const args = arguments_(src, open);
      const value = (i: number) => valuesOf(args[i], src.constants, global);
      const isCodeKey = (i: number) => args[i]?.code === '"code"';
      if ((name === 'put' || name === 'entry') && args.length === 2 && isCodeKey(0)) {
        emit(value(1), m.index);
        continue;
      }
      if (name === 'of' && m[2] === 'Map') {
        for (let i = 0; i + 1 < args.length; i += 2) if (isCodeKey(i)) emit(value(i + 1), m.index);
        continue;
      }
      for (const d of declarations.get(name) ?? []) {
        if (d.arity !== args.length || !reaches(d, Boolean(m[1]), m[2], src.file)) continue;
        if (parameters(args).every((p) => DECL_PARAM_RE.test(p))) continue; // the declaration itself
        emit(value(d.index), m.index);
      }
    }
    for (const m of src.code.matchAll(JSON_CODE_RE)) {
      if (src.masked[m.index + m[0].length - 2] === ' ') emit([m[1]], m.index);
    }
  }
  return out;
}

export interface DocumentedCode {
  code: string;
  line: number;
}

/** The codes in the Code column of the `## Codes` table; `—` rows carry none. */
export function parseDocumentedCodes(markdown: string): DocumentedCode[] {
  const lines = markdown.split('\n');
  const start = lines.findIndex((l) => /^##\s+Codes\s*$/.test(l));
  if (start === -1) throw new Error(`${ERRORS_DOC}: no "## Codes" section`);
  const codes: DocumentedCode[] = [];
  let header = true;
  for (let i = start + 1; i < lines.length && !/^##\s/.test(lines[i]); i++) {
    const row = lines[i].match(/^\|([^|]*)\|/);
    if (!row) continue;
    if (header) {
      if (!/^\s*Code\s*$/.test(row[1])) throw new Error(`${ERRORS_DOC}:${i + 1}: the first column of the Codes table must be "Code"`);
      header = false;
      continue;
    }
    for (const m of row[1].matchAll(/`([^`]+)`/g)) codes.push({ code: m[1], line: i + 1 });
  }
  return codes;
}

export function failures(documented: DocumentedCode[], emitted: Emission[], skipped: Record<string, string>): string[] {
  const out: string[] = [];
  const emittedCodes = new Set(emitted.map((e) => e.code));
  const documentedCodes = new Set(documented.map((d) => d.code));
  for (const d of documented) {
    if (!emittedCodes.has(d.code)) out.push(`${ERRORS_DOC}:${d.line}  \`${d.code}\` is documented, but nothing in scope emits it`);
  }
  const reported = new Set<string>();
  for (const e of emitted) {
    if (documentedCodes.has(e.code) || e.code in skipped || reported.has(e.code)) continue;
    reported.add(e.code);
    out.push(`${e.where}  \`${e.code}\` is emitted, but missing from the Codes table in ${ERRORS_DOC}`);
  }
  for (const [code, reason] of Object.entries(skipped)) {
    if (typeof reason !== 'string' || reason.trim().length === 0) out.push(`SKIPPED ${code} — needs a reason`);
    if (!emittedCodes.has(code)) out.push(`SKIPPED ${code} — nothing in scope emits it any more; remove it`);
    if (documentedCodes.has(code)) out.push(`SKIPPED ${code} — it is documented in ${ERRORS_DOC}; remove it from SKIPPED`);
  }
  return out;
}

export function collectEmitted(root: string = REPO_ROOT): Emission[] {
  const files = SOURCE_ROOTS.flatMap((dir) => javaFiles(join(root, dir))).map((f) => ({ file: relative(root, f), text: readFileSync(f, 'utf8') }));
  return extractEmittedCodes(readSources(files));
}

export function check(root: string = REPO_ROOT): string[] {
  return failures(parseDocumentedCodes(readFileSync(join(root, ERRORS_DOC), 'utf8')), collectEmitted(root), SKIPPED);
}

if (import.meta.main) {
  const found = check();
  for (const f of found) console.error(f);
  process.exit(found.length > 0 ? 1 : 0);
}
