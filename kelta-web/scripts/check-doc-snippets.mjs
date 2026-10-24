// Type-checks every ```ts / ```tsx block in the SDK docs against the kelta-web
// sources. Run: npm run check:doc-snippets. Each block becomes its own module in a
// temp project whose tsconfig extends ../tsconfig.json, so @kelta/* resolve to
// packages/*/src with no build. A block is skipped only when the line directly above
// its fence is `<!-- not-compiled: <reason> -->` with a non-empty reason.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = resolve(webRoot, '..');

const OPT_OUT = /^<!--\s*not-compiled:(.*?)-->\s*$/;
const FENCE_OPEN = /^```(\w+)?/;
const COMPILED_LANGS = new Set(['ts', 'tsx']);

function sdkPages() {
  const pages = [];
  const marketingSdk = join(repoRoot, 'kelta-marketing', 'src', 'content', 'docs', 'sdk');
  if (existsSync(marketingSdk)) {
    pages.push(...readdirSync(marketingSdk).filter((f) => f.endsWith('.md')).map((f) => join(marketingSdk, f)));
  }
  const docsRoot = join(repoRoot, 'docs');
  if (existsSync(docsRoot)) {
    for (const entry of readdirSync(docsRoot, { recursive: true, withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('.md') && basename(entry.parentPath) === 'sdk') {
        pages.push(join(entry.parentPath, entry.name));
      }
    }
  }
  return pages.sort();
}

/** Every fenced block in a page: language, the 1-based line of its opening fence, body lines. */
function fencedBlocks(source) {
  const lines = source.split('\n');
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    const open = lines[i].match(FENCE_OPEN);
    if (!open) continue;
    let end = i + 1;
    while (end < lines.length && !/^```\s*$/.test(lines[end])) end++;
    blocks.push({ lang: open[1] ?? '', fenceLine: i + 1, above: i > 0 ? lines[i - 1] : '', body: lines.slice(i + 1, end) });
    i = end;
  }
  return blocks;
}

const problems = [];
const snippets = [];
let optedOut = 0;

for (const page of sdkPages()) {
  const rel = relative(repoRoot, page);
  for (const block of fencedBlocks(readFileSync(page, 'utf8'))) {
    if (!COMPILED_LANGS.has(block.lang)) continue;
    const optOut = block.above.match(OPT_OUT);
    if (optOut) {
      if (optOut[1].trim()) {
        optedOut++;
      } else {
        problems.push(`${rel}:${block.fenceLine - 1}: not-compiled marker needs a reason`);
      }
      continue;
    }
    snippets.push({ rel, ...block });
  }
}

const tmp = mkdtempSync(join(webRoot, '.doc-snippets-'));
let tscFailed = false;
try {
  const byFile = new Map();
  snippets.forEach((snippet, n) => {
    const file = `snippet-${n}.${snippet.lang}`;
    byFile.set(file, snippet);
    writeFileSync(join(tmp, file), `${snippet.body.join('\n')}\nexport {};\n`);
  });

  if (snippets.length > 0) {
    writeFileSync(
      join(tmp, 'tsconfig.json'),
      JSON.stringify({
        extends: '../tsconfig.json',
        compilerOptions: {
          noEmit: true,
          noUnusedLocals: false,
          noUnusedParameters: false,
          declaration: false,
          declarationMap: false,
          sourceMap: false,
        },
        include: [...byFile.keys()],
      }),
    );
    const tsc = spawnSync(join(webRoot, 'node_modules', '.bin', 'tsc'), ['--noEmit', '--pretty', 'false', '-p', join(tmp, 'tsconfig.json')], {
      cwd: webRoot,
      encoding: 'utf8',
    });
    if (tsc.error) throw tsc.error;
    tscFailed = tsc.status !== 0;
    for (const line of `${tsc.stdout}${tsc.stderr}`.split('\n')) {
      if (!line.trim()) continue;
      const diag = line.match(/^(.+?)\((\d+),(\d+)\): (.*)$/);
      const snippet = diag && byFile.get(basename(diag[1]));
      if (snippet && resolve(webRoot, diag[1]).startsWith(tmp)) {
        problems.push(`${snippet.rel}:${snippet.fenceLine + Number(diag[2])}:${diag[3]}: ${diag[4]}`);
      } else {
        problems.push(line);
      }
    }
  }
} finally {
  rmSync(tmp, { recursive: true, force: true });
}

for (const problem of problems) console.error(problem);
console.log(`Doc snippets: ${snippets.length} compiled, ${optedOut} opted out`);
if (problems.length > 0 || tscFailed) process.exit(1);
