import type { Manifest } from '../registry/manifest.js';

/**
 * Doc-line checker: finds every `kelta …` invocation in prose docs (fenced
 * shell blocks and inline code spans) and checks it against the command
 * manifest — command path, declared flags and positional arity. Pure: the
 * file walk lives in docLines.test.ts.
 */

export interface DocLine {
  /** Repo-relative path. */
  file: string;
  /** 1-based line the invocation starts on. */
  line: number;
  /** The invocation as written (continuation lines joined with a space). */
  text: string;
}

export interface ExtractedLine extends DocLine {
  /**
   * An inline code span rather than a code-block line. Prose names commands
   * this way (`kelta metadata apply`), so a bare command path there is a
   * reference and its arity is not checked.
   */
  inline: boolean;
}

export interface DocLineProblem extends DocLine {
  reason: string;
}

interface FlagSpec {
  takesValue: boolean;
  optionalValue: boolean;
}

interface CommandShape {
  path: string;
  flags: Map<string, FlagSpec>;
  required: number;
  max: number;
}

export interface CommandIndex {
  commands: Map<string, CommandShape>;
  groups: Set<string>;
  globals: Map<string, FlagSpec>;
  rootOnly: Map<string, FlagSpec>;
}

const FENCE = /^\s*(```+|~~~+)/;
const PROMPT = /^\$\s+/;
const INLINE_CODE = /(`+)(?!`)(.+?)(?<!`)\1(?!`)/g;

function startsInvocation(text: string): boolean {
  return /^kelta(\s|$)/.test(text);
}

/**
 * Every `kelta …` invocation in a markdown file: lines inside fenced blocks
 * (optionally after a `$ ` prompt, `\` continuations joined) and inline code
 * spans that start with `kelta `.
 */
export function extractFromMarkdown(file: string, content: string): ExtractedLine[] {
  const lines = content.split('\n');
  const found: ExtractedLine[] = [];
  let fence: string | null = null;
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    const fenceMatch = FENCE.exec(raw);
    if (fenceMatch) {
      const marker = fenceMatch[1];
      if (fence === null) fence = marker;
      else if (marker[0] === fence[0] && marker.length >= fence.length) fence = null;
      continue;
    }
    if (fence !== null) {
      const joined = joinContinuation(lines, i);
      if (joined) {
        found.push({ file, line: i + 1, text: joined.text, inline: false });
        i = joined.last;
      }
      continue;
    }
    for (const match of raw.matchAll(INLINE_CODE)) {
      const span = match[2].trim();
      if (startsInvocation(span)) found.push({ file, line: i + 1, text: span, inline: true });
    }
  }
  return found;
}

/** Shell scripts: every line that starts with `kelta ` (continuations joined). */
export function extractFromShell(file: string, content: string): ExtractedLine[] {
  const lines = content.split('\n');
  const found: ExtractedLine[] = [];
  for (let i = 0; i < lines.length; i++) {
    const joined = joinContinuation(lines, i);
    if (joined) {
      found.push({ file, line: i + 1, text: joined.text, inline: false });
      i = joined.last;
    }
  }
  return found;
}

function joinContinuation(lines: string[], start: number): { text: string; last: number } | null {
  const first = lines[start].trim().replace(PROMPT, '');
  if (!startsInvocation(first)) return null;
  const parts = [first];
  let last = start;
  while (parts[parts.length - 1].endsWith('\\') && last + 1 < lines.length) {
    parts[parts.length - 1] = parts[parts.length - 1].slice(0, -1).trimEnd();
    last++;
    parts.push(lines[last].trim());
  }
  return { text: parts.join(' '), last };
}

/**
 * Shell-quote-aware split of one invocation. Quoted strings are one token;
 * `<placeholder>` is one token even with spaces inside; tokenising stops at
 * an unquoted `#` comment, `|` pipe, `&&`, `;` or `>` redirect.
 */
export function tokenize(text: string): string[] {
  const tokens: string[] = [];
  let current = '';
  let inToken = false;
  let i = 0;
  const push = () => {
    if (inToken) tokens.push(current);
    current = '';
    inToken = false;
  };
  while (i < text.length) {
    const ch = text[i];
    if (/\s/.test(ch)) {
      push();
      i++;
    } else if (ch === "'") {
      const end = text.indexOf("'", i + 1);
      const stop = end === -1 ? text.length : end;
      current += text.slice(i + 1, stop);
      inToken = true;
      i = stop + 1;
    } else if (ch === '"') {
      i++;
      while (i < text.length && text[i] !== '"') {
        if (text[i] === '\\' && i + 1 < text.length) i++;
        current += text[i];
        i++;
      }
      inToken = true;
      i++;
    } else if (ch === '\\' && i + 1 < text.length) {
      current += text[i + 1];
      inToken = true;
      i += 2;
    } else if (ch === '<' && text.indexOf('>', i) !== -1) {
      const end = text.indexOf('>', i);
      current += text.slice(i, end + 1);
      inToken = true;
      i = end + 1;
    } else if (ch === '#' && !inToken) {
      break;
    } else if (ch === '|' && inToken && /\S/.test(text[i + 1] ?? ' ')) {
      // `list|get` inside a word is the docs' alternation shorthand, not a pipe
      current += ch;
      i++;
    } else if (ch === '|' || ch === ';' || ch === '>' || text.startsWith('&&', i)) {
      // `2>/dev/null`: the fd number belongs to the redirect, not the command
      if (ch === '>' && /^\d+$/.test(current)) {
        current = '';
        inToken = false;
      }
      break;
    } else {
      current += ch;
      inToken = true;
      i++;
    }
  }
  push();
  return tokens;
}

/** Parse a commander flag spec (`-n, --name <name>`) into its spellings. */
export function parseFlag(spec: string): { names: string[]; flag: FlagSpec } {
  const names = spec
    .split(/[\s,]+/)
    .filter((part) => part.startsWith('-'))
    .map((part) => part.trim());
  return {
    names,
    flag: { takesValue: /[<[]/.test(spec), optionalValue: /\[/.test(spec) },
  };
}

function flagMap(specs: string[]): Map<string, FlagSpec> {
  const map = new Map<string, FlagSpec>();
  for (const spec of specs) {
    const { names, flag } = parseFlag(spec);
    for (const name of names) map.set(name, flag);
  }
  return map;
}

/**
 * @param globalFlags flags every leaf command accepts (the root program's
 *   global options plus commander's built-in help)
 * @param rootFlags flags only the bare `kelta` program accepts (`--version`)
 */
export function buildCommandIndex(
  manifest: Manifest,
  globalFlags: string[],
  rootFlags: string[]
): CommandIndex {
  const commands = new Map<string, CommandShape>();
  const groups = new Set<string>();
  for (const command of manifest.commands) {
    const path = [command.group, command.name].filter(Boolean).join(' ');
    if (command.group) groups.add(command.group);
    commands.set(path, {
      path,
      flags: flagMap(command.options.map((option) => option.flag)),
      required: command.positionals.filter((positional) => positional.required).length,
      max: command.positionals.length,
    });
  }
  return { commands, groups, globals: flagMap(globalFlags), rootOnly: flagMap(rootFlags) };
}

function isPlaceholder(token: string): boolean {
  return /^<[^>]+>$/.test(token);
}

function isElision(token: string): boolean {
  return token === '...' || token === '…';
}

function isFlag(token: string): boolean {
  return token.startsWith('-') && token !== '-' && !/^-\d/.test(token) && !isPlaceholder(token);
}

/**
 * Consume one flag token at `tokens[i]`. Returns the index of the last token
 * consumed, or a reason when the flag is not declared.
 */
function consumeFlag(
  tokens: string[],
  i: number,
  flags: Map<string, FlagSpec>
): { next: number } | { reason: string } {
  const token = tokens[i];
  if (token.startsWith('--')) {
    const eq = token.indexOf('=');
    const name = eq === -1 ? token : token.slice(0, eq);
    const spec = flags.get(name);
    if (!spec) return { reason: `unknown flag ${name}` };
    if (eq !== -1) {
      return spec.takesValue ? { next: i } : { reason: `flag ${name} takes no value` };
    }
    return { next: takesNext(tokens, i, spec) ? i + 1 : i };
  }
  const name = token.slice(0, 2);
  const spec = flags.get(name);
  if (!spec) return { reason: `unknown flag ${name}` };
  if (token.length > 2) {
    return spec.takesValue ? { next: i } : { reason: `unknown flag ${token}` };
  }
  return { next: takesNext(tokens, i, spec) ? i + 1 : i };
}

function takesNext(tokens: string[], i: number, spec: FlagSpec): boolean {
  if (!spec.takesValue || i + 1 >= tokens.length) return false;
  return !spec.optionalValue || !isFlag(tokens[i + 1]);
}

/**
 * Check one invocation; returns the reason it fails, or null when it is valid.
 * `[…]` optional-syntax brackets are read as if the bracketed part were
 * given; `--help` skips the arity check (commander prints help first).
 */
export function checkInvocation(
  text: string,
  index: CommandIndex,
  { inline = false }: { inline?: boolean } = {}
): string | null {
  const tokens = tokenize(text)
    .slice(1)
    .map((token) => token.replace(/^\[/, '').replace(/\]$/, ''))
    .filter((token) => token !== '');

  // root-level flags may precede the command path (`kelta --profile prod records list`)
  const rootFlags = new Map([...index.globals, ...index.rootOnly]);
  let i = 0;
  while (i < tokens.length && isFlag(tokens[i])) {
    const consumed = consumeFlag(tokens, i, rootFlags);
    if ('reason' in consumed) return consumed.reason;
    i = consumed.next + 1;
  }
  const words = tokens.slice(i);
  if (words.length === 0) return null;
  // `kelta help …` and generic templates such as `kelta <group> --help`
  if (words[0] === 'help' || isPlaceholder(words[0])) return null;

  if (words.slice(0, 2).some((word) => word.includes('|'))) return checkAlternation(words, index);

  const pathLength = [2, 1].find((n) => index.commands.has(words.slice(0, n).join(' ')));
  if (pathLength === undefined) {
    if (index.groups.has(words[0])) {
      if (words.length === 1 || isFlag(words[1])) {
        // a bare group reference (`kelta records`, `kelta mcp --help`)
        return checkFlagsOnly(words.slice(1), index.globals);
      }
      return `unknown command "${words[0]} ${words[1]}"`;
    }
    return `unknown command "${words[0]}"`;
  }

  const command = index.commands.get(words.slice(0, pathLength).join(' '))!;
  const flags = new Map([...index.globals, ...command.flags]);
  const rest = words.slice(pathLength);
  let positionals = 0;
  let elided = false;
  let help = false;
  for (let j = 0; j < rest.length; j++) {
    const token = rest[j];
    if (token === '--') {
      positionals += rest.length - j - 1;
      break;
    }
    if (isElision(token)) {
      elided = true;
    } else if (isFlag(token)) {
      const consumed = consumeFlag(rest, j, flags);
      if ('reason' in consumed) return consumed.reason;
      if (token === '--help' || token === '-h') help = true;
      j = consumed.next;
    } else {
      positionals++;
    }
  }
  if (help || (inline && rest.length === 0)) return null;
  if (positionals > command.max) {
    return `too many positionals for "${command.path}": ${positionals} given, at most ${command.max}`;
  }
  if (positionals < command.required && !elided) {
    return `missing positionals for "${command.path}": ${positionals} given, ${command.required} required`;
  }
  return null;
}

/**
 * `kelta flows cancel|retry` names a family of commands: every alternative
 * must exist; arguments after it are a summary and are not checked.
 */
function checkAlternation(words: string[], index: CommandIndex): string | null {
  const [first, second = ''] = words;
  for (const group of first.split('|')) {
    for (const name of second.split('|')) {
      const path = [group, name].filter(Boolean).join(' ');
      if (!index.commands.has(path) && !index.commands.has(group)) {
        return `unknown command "${path}"`;
      }
    }
  }
  return null;
}

function checkFlagsOnly(tokens: string[], flags: Map<string, FlagSpec>): string | null {
  for (let i = 0; i < tokens.length; i++) {
    if (!isFlag(tokens[i])) return `unexpected argument "${tokens[i]}"`;
    const consumed = consumeFlag(tokens, i, flags);
    if ('reason' in consumed) return consumed.reason;
    i = consumed.next;
  }
  return null;
}

export function checkDocLines(lines: ExtractedLine[], index: CommandIndex): DocLineProblem[] {
  const problems: DocLineProblem[] = [];
  for (const { inline, ...line } of lines) {
    const reason = checkInvocation(line.text, index, { inline });
    if (reason !== null) problems.push({ ...line, reason });
  }
  return problems;
}

export interface AllowlistEntry extends DocLine {
  reason: string;
}

/**
 * Split problems into allowlisted and new ones, and report allowlist entries
 * that went stale (the line no longer exists, or it now passes).
 */
export function applyAllowlist(
  lines: DocLine[],
  problems: DocLineProblem[],
  allowlist: AllowlistEntry[]
): { unexpected: DocLineProblem[]; stale: { entry: AllowlistEntry; why: string }[] } {
  const key = (line: DocLine) => `${line.file}:${line.line}:${line.text}`;
  const allowed = new Set(allowlist.map(key));
  const present = new Set(lines.map(key));
  const failing = new Set(problems.map(key));
  return {
    unexpected: problems.filter((problem) => !allowed.has(key(problem))),
    stale: allowlist.flatMap((entry) => {
      if (!present.has(key(entry))) return [{ entry, why: 'line no longer exists' }];
      if (!failing.has(key(entry))) return [{ entry, why: 'line now passes' }];
      return [];
    }),
  };
}
