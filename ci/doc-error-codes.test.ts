// node --test ci/doc-error-codes.test.ts   (Node >= 24; no dependencies)
//
// The Code column of api/errors.md both ways against the codes the platform emits, the skip
// list's reasons, and the emitter-shape extractor on inline Java fixtures.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { REPO_ROOT } from './endpoint-index.ts';
import {
  ERRORS_DOC,
  SKIPPED,
  SOURCE_ROOTS,
  check,
  collectEmitted,
  extractEmittedCodes,
  failures,
  parseDocumentedCodes,
  readSources,
} from './doc-error-codes.ts';

const codesOf = (files: Record<string, string>) =>
  [...new Set(extractEmittedCodes(readSources(Object.entries(files).map(([file, text]) => ({ file, text })))).map((e) => e.code))].sort();

/** The globs of the `ci_scripts` filter in .github/path-filters.yml. */
function ciScriptsFilter(): string[] {
  const yml = readFileSync(join(REPO_ROOT, '.github/path-filters.yml'), 'utf8');
  const block = yml.slice(yml.indexOf('\nci_scripts:\n')).split('\n\n')[0];
  return [...block.matchAll(/^\s+- '([^']+)'$/gm)].map((m) => m[1]);
}

describe('the committed errors.md', () => {
  it('documents every emitted code and only emitted codes', () => {
    assert.deepEqual(check(REPO_ROOT), []);
  });

  it('is read from its Code column, several codes per cell, no code for a — cell', () => {
    const codes = parseDocumentedCodes(readFileSync(join(REPO_ROOT, ERRORS_DOC), 'utf8')).map((c) => c.code);
    for (const code of ['VALIDATION_FAILED', 'reference', 'nullable', 'RATE_LIMIT_EXCEEDED', 'FLOW_INACTIVE']) assert.ok(codes.includes(code), code);
    assert.ok(!codes.includes('—'));
    assert.ok(!codes.some((c) => /^\d/.test(c)), 'no status or member names');
  });

  it('extracts the codes the brief found missing from the table', () => {
    const emitted = new Set(collectEmitted(REPO_ROOT).map((e) => e.code));
    for (const code of ['FORBIDDEN', 'MISSING_TENANT', 'AI_PROVIDER_ERROR', 'TOKEN_BUDGET_EXCEEDED', 'INVALID_PROPOSAL', 'PROPOSAL_ALREADY_APPLIED', 'APPLY_FAILED', 'RATE_LIMIT_EXCEEDED', 'reference']) {
      assert.ok(emitted.has(code), code);
    }
  });

  it('re-runs in CI when any source root it reads, or the docs, change', () => {
    const filter = ciScriptsFilter();
    for (const root of SOURCE_ROOTS) assert.ok(filter.includes(`${root}/**`), root);
    assert.ok(filter.includes('kelta-marketing/src/content/docs/**'));
  });

  it('gives every skipped code a reason', () => {
    for (const [code, reason] of Object.entries(SKIPPED)) assert.ok(reason.trim().length >= 20, code);
  });

  it('documents its scope and emitter shapes in the header comment', () => {
    const header = readFileSync(join(import.meta.dirname, 'doc-error-codes.ts'), 'utf8').split('\nimport ')[0];
    for (const root of SOURCE_ROOTS) assert.ok(header.includes(root.split('/src/')[0].split('/').pop()!), root);
    for (const shape of ['"code"', 'parameter named `code`', 'SKIPPED', 'Not recognised']) assert.ok(header.includes(shape), shape);
  });
});

describe('failures', () => {
  const doc = ['## Codes', '', '| Code | Status | When |', '|---|---|---|', '| `NOT_FOUND` | 404 | x |', '| `GHOST_CODE` | 400 | y |', '| — | 409 | z |', ''].join('\n');

  it('fails on a documented code nothing emits and on an emitted code the table lacks', () => {
    const emitted = [
      { code: 'NOT_FOUND', where: 'A.java:3' },
      { code: 'NEW_CODE', where: 'B.java:9' },
      { code: 'NEW_CODE', where: 'C.java:1' },
    ];
    assert.deepEqual(failures(parseDocumentedCodes(doc), emitted, {}), [
      `${ERRORS_DOC}:6  \`GHOST_CODE\` is documented, but nothing in scope emits it`,
      `B.java:9  \`NEW_CODE\` is emitted, but missing from the Codes table in ${ERRORS_DOC}`,
    ]);
  });

  it('lets a skipped code through, and fails a skip without a reason, a stale skip and a documented skip', () => {
    const emitted = [
      { code: 'NOT_FOUND', where: 'A.java:3' },
      { code: 'GHOST_CODE', where: 'A.java:4' },
      { code: 'INTERNAL_ONLY', where: 'A.java:5' },
      { code: 'NO_REASON', where: 'A.java:6' },
    ];
    assert.deepEqual(failures(parseDocumentedCodes(doc), emitted, { INTERNAL_ONLY: 'tool result, never an HTTP body', NO_REASON: '', GONE: 'was removed', NOT_FOUND: 'clashes' }), [
      'SKIPPED NO_REASON — needs a reason',
      'SKIPPED GONE — nothing in scope emits it any more; remove it',
      `SKIPPED NOT_FOUND — it is documented in ${ERRORS_DOC}; remove it from SKIPPED`,
    ]);
  });

  it('throws when the table is missing', () => {
    assert.throws(() => parseDocumentedCodes('# Errors\n'), /no "## Codes" section/);
  });
});

describe('extractEmittedCodes', () => {
  it('reads "code" map keys, JSON bodies in string literals and text blocks', () => {
    const src = `class A {
      void a() {
        error.put("code", "PUT_CODE");
        Map.of("status", "400", "code", "MAP_CODE", "title", "t");
        Map.entry("code", blank ? "TERNARY_CODE" : other.code());
        String s = "{\\"errors\\":[{\\"code\\":\\"ESCAPED_JSON\\"}]}";
        String t = """
            {"errors":[{"status":"403","code":"TEXT_BLOCK"}]}
            """;
        // error.put("code", "IN_A_COMMENT");
        schema.put("code", Map.of("type", "string"));
        Set.of("pre", "code", "ul");
      }
    }`;
    assert.deepEqual(codesOf({ 'A.java': src }), ['ESCAPED_JSON', 'MAP_CODE', 'PUT_CODE', 'TERNARY_CODE', 'TEXT_BLOCK']);
  });

  it('reads arguments passed to a `code` parameter, resolving constants', () => {
    const builder = `public class JsonApiResponseBuilder {
      public static Map<String, Object> error(String status, String code, String title, String detail) { return null; }
      public static Map<String, Object> error(String status, String title, String detail) { return null; }
    }`;
    const result = `public class BeforeSaveResult {
      public static final String SHARED = "SHARED_CONSTANT";
      public static BeforeSaveResult error(String field, String message, String code) { return null; }
      public record ValidationError(String field, String message, String code) {}
    }`;
    const controller = `class C {
      private static final String LOCAL = "LOCAL_CONSTANT";
      Object a() {
        JsonApiResponseBuilder.error("409", "FOUR_ARG", "Conflict", "d");
        JsonApiResponseBuilder.error("400", "Derived Title", "not a code");
        BeforeSaveResult.error("name", "message", LOCAL);
        new BeforeSaveResult.ValidationError("f", "m", BeforeSaveResult.SHARED);
        return errorResponse("400", "HELPER_CODE", "Bad Request", "detail");
      }
      private Map<String, Object> errorResponse(String status, String code, String title, String detail) { return null; }
    }`;
    const other = `class D {
      Object b() { return errorResponse("x", "OTHER_FILE", "y", "z"); }
      private Object errorResponse(String a, String b, String c, String d) { return null; }
    }`;
    assert.deepEqual(codesOf({ 'JsonApiResponseBuilder.java': builder, 'BeforeSaveResult.java': result, 'C.java': controller, 'D.java': other }), [
      'FOUR_ARG',
      'HELPER_CODE',
      'LOCAL_CONSTANT',
      'SHARED_CONSTANT',
    ]);
  });

  it("reads a FieldError's constraint, the parameter the exception handlers emit as the code", () => {
    const fieldError = `public record FieldError(String fieldName, String message, String constraint, Map<String, Object> meta) {
      public FieldError(String fieldName, String message, String constraint) { this(fieldName, message, constraint, null); }
      public static FieldError nullable(String f) { return new FieldError(f, "Field is required", "nullable"); }
      public static FieldError reference(String f, Map<String, Object> meta) { return new FieldError(f, "missing", "reference", meta); }
    }`;
    assert.deepEqual(codesOf({ 'FieldError.java': fieldError }), ['nullable', 'reference']);
  });
});
