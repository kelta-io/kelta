// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Command } from 'commander';
import { addGlobalOptions } from '../registry/bind.js';
import { buildManifest } from '../registry/manifest.js';
import { allCommands } from '../registry/registry.js';
import {
  applyAllowlist,
  buildCommandIndex,
  checkDocLines,
  checkInvocation,
  extractFromMarkdown,
  extractFromShell,
  tokenize,
  type AllowlistEntry,
  type DocLine,
} from './docLines.js';

const REPO_ROOT = fileURLToPath(new URL('../../../../../', import.meta.url));
const SKIP_DIRS = new Set(['node_modules', 'dist', '.git', 'target', 'build', 'coverage']);

function walk(dir: string, keep: (path: string) => boolean): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(entry.name)) continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(path, keep));
    else if (keep(path)) out.push(path);
  }
  return out;
}

/** The doc sources a reader copies `kelta …` lines from. COMMANDS.md is rendered from the registry. */
function docFiles(): string[] {
  const at = (path: string) => join(REPO_ROOT, path);
  const files = new Set<string>([
    ...walk(at('kelta-marketing/src/content/docs'), (path) => path.endsWith('.md')),
    ...walk(at('docs'), (path) => path.endsWith('.md')),
    ...walk(at('examples'), (path) => path.endsWith('.md') || path.endsWith('.sh')),
    ...walk(REPO_ROOT, (path) => path.endsWith('/README.md')),
    at('kelta-web/packages/cli/AGENTS.md'),
  ]);
  return [...files].sort();
}

function extractAll(): DocLine[] {
  return docFiles().flatMap((path) => {
    const file = relative(REPO_ROOT, path);
    const content = readFileSync(path, 'utf-8');
    return path.endsWith('.sh')
      ? extractFromShell(file, content)
      : extractFromMarkdown(file, content);
  });
}

/** Root-program globals come from the same helper that registers them on every leaf. */
const GLOBAL_FLAGS = [
  ...addGlobalOptions(new Command()).options.map((option) => option.flags),
  // commander's built-in help, accepted by every command
  '-h, --help',
];
const ROOT_FLAGS = ['-V, --version'];

const index = buildCommandIndex(buildManifest(allCommands), GLOBAL_FLAGS, ROOT_FLAGS);

const allowlist = JSON.parse(
  readFileSync(fileURLToPath(new URL('./docLines.allowlist.json', import.meta.url)), 'utf-8')
) as AllowlistEntry[];

const format = (line: DocLine & { reason: string }) =>
  `${line.file}:${line.line}: ${line.reason}\n    ${line.text}`;

describe('kelta lines in docs', () => {
  const lines = extractAll();
  const problems = checkDocLines(lines, index);
  const { unexpected, stale } = applyAllowlist(lines, problems, allowlist);

  it('finds invocations in every doc source', () => {
    expect(lines.length).toBeGreaterThan(300);
    for (const prefix of [
      'kelta-marketing/',
      'docs/',
      'examples/',
      'kelta-web/packages/cli/AGENTS.md',
    ]) {
      expect(
        lines.some((line) => line.file.startsWith(prefix)),
        prefix
      ).toBe(true);
    }
  });

  it('every `kelta …` line matches the command registry (fix the doc, or allowlist it when the CLI is wrong)', () => {
    expect(unexpected.map(format).join('\n')).toBe('');
  });

  it('every allowlist entry still exists and still fails — remove the ones that do not', () => {
    expect(stale.map(({ entry, why }) => `${format(entry)}\n    (${why})`).join('\n')).toBe('');
  });

  it('every allowlist entry carries a reason', () => {
    for (const entry of allowlist) {
      expect(Object.keys(entry).sort()).toEqual(['file', 'line', 'reason', 'text']);
      expect(entry.reason.trim()).not.toBe('');
    }
  });
});

describe('doc-line checker', () => {
  const check = (text: string) => checkInvocation(text, index);

  it('reports an unknown command', () => {
    expect(check('kelta records frobnicate invoices')).toBe('unknown command "records frobnicate"');
    expect(check('kelta frobnicate')).toBe('unknown command "frobnicate"');
  });

  it('reports an undeclared flag', () => {
    expect(check('kelta records list invoices --colour red')).toBe('unknown flag --colour');
  });

  it('reports too many positionals', () => {
    expect(check('kelta records list invoices extra')).toMatch(
      /^too many positionals for "records list"/
    );
  });

  it('reports missing required positionals', () => {
    expect(check('kelta records get invoices')).toMatch(/^missing positionals for "records get"/);
  });

  it('accepts a valid line', () => {
    expect(check('kelta records list invoices --filter status=open --sort -createdAt')).toBeNull();
  });

  it('counts <placeholder> tokens as positionals', () => {
    expect(check('kelta records get <collection> <id>')).toBeNull();
    expect(check('kelta records get <collection> <id> <extra>')).toMatch(/^too many positionals/);
  });

  it('accepts root-program global options on every command, before or after the path', () => {
    expect(
      check('kelta records list invoices --output json --profile prod --raw --quiet --yes')
    ).toBeNull();
    expect(check('kelta --profile prod collections list')).toBeNull();
    expect(check('kelta collections list --help')).toBeNull();
  });

  it('accepts --flag=value, short flags and declared --no-x negations', () => {
    expect(check('kelta records list invoices --size=5')).toBeNull();
    expect(check('kelta metadata export -o out.json')).toBeNull();
    expect(check('kelta auth login --no-browser')).toBeNull();
    expect(check('kelta auth login --no-wait')).toBe('unknown flag --no-wait');
  });

  it('accepts bare group references and --version on the root only', () => {
    expect(check('kelta records')).toBeNull();
    expect(check('kelta mcp --help')).toBeNull();
    expect(check('kelta --version')).toBeNull();
    expect(check('kelta collections list --version')).toBe('unknown flag --version');
  });

  it('reads [optional] brackets as given and skips arity on --help', () => {
    expect(check('kelta manifest [--group records]')).toBeNull();
    expect(check('kelta manifest [--colour red]')).toBe('unknown flag --colour');
    expect(check('kelta fields add --help')).toBeNull();
  });

  it('accepts generic templates and checks every alternative of a command family', () => {
    expect(check('kelta <group> <command> --help')).toBeNull();
    expect(check('kelta flows cancel|retry')).toBeNull();
    expect(check('kelta flows cancel|frobnicate')).toBe('unknown command "flows frobnicate"');
  });

  it('treats a bare command path in an inline span as a reference, but checks it in a code block', () => {
    expect(checkInvocation('kelta metadata apply', index, { inline: true })).toBeNull();
    expect(check('kelta metadata apply')).toMatch(/^missing positionals/);
    expect(checkInvocation('kelta fields add --type TEXT', index, { inline: true })).toMatch(
      /^missing positionals/
    );
  });

  it('treats an elided argument list as satisfying the arity', () => {
    expect(check('kelta records get …')).toBeNull();
    expect(check('kelta records get ...')).toBeNull();
  });

  it('tokenises quoted JSON as one token and stops at pipes, comments and redirects', () => {
    expect(tokenize(`kelta flows execute f1 --input '{"a": "b c"}' | jq .`)).toEqual([
      'kelta',
      'flows',
      'execute',
      'f1',
      '--input',
      '{"a": "b c"}',
    ]);
    expect(tokenize('kelta records list "my items" # comment')).toEqual([
      'kelta',
      'records',
      'list',
      'my items',
    ]);
    expect(tokenize('kelta collections list 2>/dev/null && echo ok')).toEqual([
      'kelta',
      'collections',
      'list',
    ]);
    expect(tokenize('kelta records list <collection name> ; ls')).toEqual([
      'kelta',
      'records',
      'list',
      '<collection name>',
    ]);
  });

  it('extracts fenced lines (with prompts and continuations) and inline spans', () => {
    const markdown = [
      'Run `kelta records list invoices` or `npx kelta` here.',
      '```bash',
      '$ kelta records create invoices \\',
      "  --data '{}'",
      'echo kelta records',
      '```',
      'kelta outside a fence is prose',
    ].join('\n');
    expect(extractFromMarkdown('doc.md', markdown)).toEqual([
      { file: 'doc.md', line: 1, text: 'kelta records list invoices', inline: true },
      { file: 'doc.md', line: 3, text: "kelta records create invoices --data '{}'", inline: false },
    ]);
  });

  it('reports each failure kind with file and line, and passes a valid line', () => {
    const fixture = [
      '```sh',
      'kelta records frobnicate invoices',
      'kelta records list invoices --colour red',
      'kelta records list invoices extra',
      'kelta records list invoices',
      '```',
    ].join('\n');
    const problems = checkDocLines(extractFromMarkdown('fixture.md', fixture), index);
    expect(problems.map(({ file, line, reason }) => `${file}:${line} ${reason}`)).toEqual([
      'fixture.md:2 unknown command "records frobnicate"',
      'fixture.md:3 unknown flag --colour',
      'fixture.md:4 too many positionals for "records list": 2 given, at most 1',
    ]);
  });

  it('flags allowlist entries that went stale', () => {
    const lines = [
      { file: 'a.md', line: 1, text: 'kelta records list invoices' },
      { file: 'a.md', line: 2, text: 'kelta frobnicate' },
    ];
    const problems = checkDocLines(lines, index);
    const entry = (line: DocLine) => ({ ...line, reason: 'cli is wrong' });
    const { unexpected, stale } = applyAllowlist(lines, problems, [
      entry(lines[1]),
      entry(lines[0]),
      entry({ file: 'a.md', line: 9, text: 'kelta gone' }),
    ]);
    expect(unexpected).toEqual([]);
    expect(stale.map(({ entry, why }) => `${entry.line} ${why}`)).toEqual([
      '1 line now passes',
      '9 line no longer exists',
    ]);
  });
});
