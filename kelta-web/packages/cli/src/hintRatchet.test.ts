// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * `new CliError(` sites under src/ whose arguments carry no `hint`. Lower this
 * when you add hints; never raise it — give the new error a hint instead.
 */
const RECORDED_SITES_WITHOUT_HINT = 73;

const SRC = fileURLToPath(new URL('.', import.meta.url));

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return path.endsWith('.ts') && !path.endsWith('.test.ts') && !path.endsWith('.d.ts')
      ? [path]
      : [];
  });
}

/** Source from just after `(` to its matching `)`, skipping brackets inside string literals. */
function argumentSpan(source: string, start: number): string {
  let depth = 1;
  let quote: string | undefined;
  for (let i = start; i < source.length; i++) {
    const char = source[i];
    if (quote) {
      if (char === '\\') i++;
      else if (char === quote) quote = undefined;
      continue;
    }
    if (char === "'" || char === '"' || char === '`') quote = char;
    else if (char === '(') depth++;
    else if (char === ')' && --depth === 0) return source.slice(start, i);
  }
  throw new Error(`unbalanced new CliError( at offset ${String(start)}`);
}

function sitesWithoutHint(source: string): number {
  let count = 0;
  for (const match of source.matchAll(/new CliError\(/g)) {
    const args = argumentSpan(source, (match.index ?? 0) + match[0].length);
    if (!/\bhint\s*[:,}\n]/.test(args)) count++;
  }
  return count;
}

describe('CliError hint ratchet', () => {
  it('counts only sites whose options lack a hint', () => {
    expect(
      sitesWithoutHint(
        "new CliError(`a (${x})`, { code: 'A', exitCode: 1 });\n" +
          "new CliError('b', { code: 'B', exitCode: 1, hint: 'do x' });\n" +
          "new CliError('c', { code: 'C', exitCode: 1, hint });"
      )
    ).toBe(1);
  });

  it(`has at most ${String(RECORDED_SITES_WITHOUT_HINT)} new CliError( sites without a hint`, () => {
    const perFile = sourceFiles(SRC)
      .map((path) => ({
        file: relative(SRC, path),
        count: sitesWithoutHint(readFileSync(path, 'utf-8')),
      }))
      .filter((entry) => entry.count > 0);
    const total = perFile.reduce((sum, entry) => sum + entry.count, 0);
    expect(
      total,
      `CliError sites without a hint rose above the ratchet; add a hint:\n${perFile
        .map((entry) => `  ${entry.file}: ${String(entry.count)}`)
        .join('\n')}`
    ).toBeLessThanOrEqual(RECORDED_SITES_WITHOUT_HINT);
  });
});
