// node --test ci/first-app-runnable.test.ts   (Node >= 24; needs bash, curl, jq)
//
// Drives ci/first-app-runnable.sh: --extract on fixture markdown, --run with a stub `kelta`
// first on PATH, and --in-container against a local stand-in for kelta-auth's direct login and
// the gateway's environments and collections endpoints.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { after, before, beforeEach, describe, it } from 'node:test';

const SCRIPT = join(import.meta.dirname, 'first-app-runnable.sh');
const DOC = join(import.meta.dirname, '..', 'kelta-marketing/src/content/docs/getting-started/first-app.md');
const PASSWORD = 'admin-secret';
const TOKEN = 'token-123';
const SANDBOX_SLUG = 'default--first-app';
const SANDBOX_ADMIN = `${SANDBOX_SLUG}-admin`;
const SANDBOX_PASSWORD = 'one-time-secret';
const SANDBOX_TOKEN = 'sandbox-token-456';
const FENCE = '```';

let root: string;
let stubBin: string;
let server: Server;
let baseUrl: string;
let environmentRequests: unknown[];
let sandboxStatus: string;

/**
 * A stand-in for the kelta CLI: logs "<args> | <cwd name> | <tenant> | <token>" to KELTA_LOG,
 * exits 2 on a flag it does not know (as commander does on an unknown option) and otherwise
 * prints APPLY_REPLY (default: an all-zero layout-tree report).
 */
const STUB = [
  '#!/usr/bin/env bash',
  'echo "$* | $(basename "$PWD") | $KELTA_TENANT | $KELTA_TOKEN" >>"$KELTA_LOG"',
  'for arg in "$@"; do',
  '  case "$arg" in',
  '    --name|--file|--output|--type|--required) ;;',
  '    --*) echo "error: unknown option \'$arg\'" >&2; exit 2 ;;',
  '  esac',
  'done',
  'default=\'{"layoutId":"l1","created":0,"updated":0,"deleted":0,"unchanged":3}\'',
  'printf "%s\\n" "${APPLY_REPLY:-$default}"',
  '',
].join('\n');

function writeDoc(markdown: string): string {
  const dir = mkdtempSync(join(root, 'doc-'));
  const file = join(dir, 'first-app.md');
  writeFileSync(file, markdown);
  return file;
}

function run(args: string[], env: Record<string, string> = {}): Promise<{ code: number; out: string; stdout: string }> {
  return new Promise((resolve) => {
    execFile(
      'bash',
      [SCRIPT, ...args],
      {
        env: {
          PATH: `${stubBin}:${process.env.PATH ?? ''}`,
          KELTA_LOG: join(root, 'kelta-log'),
          KELTA_URL: 'http://gateway.test',
          KELTA_TENANT: 'acme',
          KELTA_TOKEN: 'tok',
          ...env,
        },
      },
      (error, stdout, stderr) => {
        resolve({ code: error ? Number(error.code) : 0, out: stdout + stderr, stdout });
      }
    );
  });
}

const keltaLog = () => (existsSync(join(root, 'kelta-log')) ? readFileSync(join(root, 'kelta-log'), 'utf8') : '');

const FIXTURE = [
  '# Fixture',
  '',
  `${FENCE}bash`,
  'echo not-marked-1',
  FENCE,
  '',
  `${FENCE}bash runnable`,
  'echo first-marked',
  'echo first-marked-2',
  FENCE,
  '',
  `${FENCE}json`,
  '{ "runnable": true }',
  FENCE,
  '',
  `${FENCE}sh`,
  'echo not-marked-2',
  FENCE,
  '',
  `${FENCE}bash runnable`,
  'echo second-marked',
  FENCE,
  '',
].join('\n');

before(async () => {
  root = mkdtempSync(join(tmpdir(), 'first-app-runnable-'));
  stubBin = join(root, 'bin');
  mkdirSync(stubBin);
  writeFileSync(join(stubBin, 'kelta'), STUB, { mode: 0o755 });
  server = createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const url = new URL(req.url ?? '/', 'http://localhost');
      if (req.method === 'POST' && url.pathname === '/auth/direct-login') {
        const login = JSON.parse(body);
        const parent = login.password === PASSWORD && login.tenantSlug === 'default';
        const sandbox =
          login.username === SANDBOX_ADMIN && login.password === SANDBOX_PASSWORD && login.tenantSlug === SANDBOX_SLUG;
        const token = parent ? TOKEN : sandbox ? SANDBOX_TOKEN : undefined;
        res.writeHead(token ? 200 : 401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(token ? { access_token: token } : { error: 'bad credentials' }));
        return;
      }
      if (req.method === 'POST' && url.pathname === '/default/api/environments') {
        if (req.headers.authorization !== `Bearer ${TOKEN}`) {
          res.writeHead(401).end();
          return;
        }
        environmentRequests.push(JSON.parse(body));
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            data: {
              id: 'env-1',
              attributes: {
                status: sandboxStatus,
                clone_mode: 'EMPTY',
                sandboxSlug: SANDBOX_SLUG,
                adminUsername: SANDBOX_ADMIN,
                adminInitialPassword: SANDBOX_PASSWORD,
              },
            },
          })
        );
        return;
      }
      if (req.method === 'GET' && url.pathname === `/${SANDBOX_SLUG}/api/collections`) {
        res.writeHead(req.headers.authorization === `Bearer ${SANDBOX_TOKEN}` ? 200 : 401).end('{"data":[]}');
        return;
      }
      res.writeHead(404).end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(() => {
  server.close();
  rmSync(root, { recursive: true, force: true });
});

beforeEach(() => {
  rmSync(join(root, 'kelta-log'), { force: true });
  environmentRequests = [];
  sandboxStatus = 'ACTIVE';
});

describe('first-app-runnable.sh --extract', () => {
  it('prints only the blocks fenced `runnable`, in document order', async () => {
    const result = await run(['--extract', writeDoc(FIXTURE)]);

    assert.equal(result.code, 0, result.out);
    assert.equal(
      result.stdout,
      '### runnable block 1\necho first-marked\necho first-marked-2\n### runnable block 2\necho second-marked\n'
    );
    assert.doesNotMatch(result.stdout, /not-marked|"runnable": true/);
  });

  it('keeps a fence line inside a longer fence as content', async () => {
    const doc = writeDoc(['````bash runnable', 'cat <<EOF', FENCE, 'EOF', '````', ''].join('\n'));

    const result = await run(['--extract', doc]);

    assert.equal(result.stdout, `### runnable block 1\ncat <<EOF\n${FENCE}\nEOF\n`);
  });

  it('finds the two Part 2 shell blocks of first-app.md and nothing else', async () => {
    const result = await run(['--extract', DOC]);

    assert.equal(result.code, 0, result.out);
    const blocks = result.stdout.split(/^### runnable block \d+\n/m).slice(1);
    assert.equal(blocks.length, 2);
    assert.match(blocks[0], /^kelta collections create --name customers /);
    assert.match(blocks[1], /^cat > invoices-layout\.json <<'JSON'\n/);
    assert.match(blocks[1], /kelta menus apply --file finance-menu\.json\n$/);
    assert.doesNotMatch(result.stdout, /schemaVersion/);
  });
});

describe('first-app-runnable.sh --run', () => {
  it('runs the marked blocks in order in one shell in a temp directory, echoing each first line', async () => {
    const doc = writeDoc(
      [
        `${FENCE}bash`,
        'kelta never --name unmarked',
        FENCE,
        `${FENCE}bash runnable`,
        'shared=from-block-1',
        'echo \'{"name": "Main"}\' > layout.json',
        'kelta collections create --name customers',
        FENCE,
        `${FENCE}bash runnable`,
        'kelta fields add customers --name "$shared" --type text --required',
        'kelta layouts apply customers \\',
        '  --file layout.json',
        FENCE,
        '',
      ].join('\n')
    );

    const result = await run(['--run', doc]);

    assert.equal(result.code, 0, result.out);
    assert.match(result.out, /==> block 1\/2: shared=from-block-1\n/);
    assert.match(result.out, /==> block 2\/2: kelta fields add customers --name "\$shared"/);
    const lines = keltaLog().trim().split('\n');
    const workdir = lines[0].split(' | ')[1];
    assert.match(workdir, /^tmp\./);
    assert.notEqual(workdir, basename(process.cwd()));
    assert.deepEqual(lines, [
      `collections create --name customers | ${workdir} | acme | tok`,
      `fields add customers --name from-block-1 --type text --required | ${workdir} | acme | tok`,
      `layouts apply customers --file layout.json | ${workdir} | acme | tok`,
      // the second run of every apply command, with --output json
      `layouts apply customers --file layout.json --output json | ${workdir} | acme | tok`,
    ]);
    assert.match(result.out, /Every apply command \(1\) reported unchanged on its second run/);
  });

  it('exits non-zero at a runnable block whose kelta call exits 2 on an unknown flag', async () => {
    const doc = writeDoc(
      [
        `${FENCE}bash runnable`,
        'kelta collections create --name customers --bogus-flag x',
        'echo after-the-failure >&2',
        FENCE,
        `${FENCE}bash runnable`,
        'kelta collections create --name invoices',
        FENCE,
        '',
      ].join('\n')
    );

    const result = await run(['--run', doc]);

    assert.notEqual(result.code, 0, result.out);
    assert.match(result.out, /error: unknown option '--bogus-flag'/);
    assert.match(result.out, /::error::a runnable block of .* failed \(exit 2\)/);
    assert.doesNotMatch(result.out, /after-the-failure|==> block 2\/2/);
    assert.equal(keltaLog().trim().split('\n').length, 1);
  });

  it('fails when an apply command does not report unchanged on its second run', async () => {
    const doc = writeDoc(
      [`${FENCE}bash runnable`, 'kelta list-views apply invoices --name "By status"', FENCE, ''].join('\n')
    );

    const result = await run(['--run', doc], { APPLY_REPLY: '{"action":"updated","id":"lv1","changed":["columns"]}' });

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::second run of `kelta list-views apply invoices --name "By status"` was not unchanged: \{"action":"updated"/);
  });

  it('fails when the document has no runnable block', async () => {
    const result = await run(['--run', writeDoc(`${FENCE}bash\nkelta x\n${FENCE}\n`)]);

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::no ```bash runnable block/);
    assert.equal(keltaLog(), '');
  });
});

describe('first-app-runnable.sh --in-container', () => {
  const env = () => ({ AUTH_URL: baseUrl, GATEWAY_URL: baseUrl, ADMIN_PASSWORD: PASSWORD, READY_ATTEMPTS: '2' });

  it('creates an empty sandbox and runs the blocks as its admin', async () => {
    const doc = writeDoc([`${FENCE}bash runnable`, 'kelta collections create --name customers', FENCE, ''].join('\n'));

    const result = await run(['--in-container', doc], env());

    assert.equal(result.code, 0, result.out);
    assert.deepEqual(environmentRequests, [
      { data: { type: 'environments', attributes: { name: 'first-app', type: 'SANDBOX', cloneMetadata: false } } },
    ]);
    assert.match(result.out, new RegExp(`created the empty sandbox ${SANDBOX_SLUG}`));
    assert.match(keltaLog(), new RegExp(`^collections create --name customers \\| tmp\\.\\w+ \\| ${SANDBOX_SLUG} \\| ${SANDBOX_TOKEN}\n$`));
    assert.match(result.out, new RegExp(`against tenant ${SANDBOX_SLUG}`));
  });

  it('fails before running anything when the sandbox is not ACTIVE', async () => {
    sandboxStatus = 'PROVISIONING';
    const doc = writeDoc([`${FENCE}bash runnable`, 'kelta collections create --name customers', FENCE, ''].join('\n'));

    const result = await run(['--in-container', doc], env());

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::sandbox first-app: status is PROVISIONING, expected ACTIVE/);
    assert.equal(keltaLog(), '');
  });

  it('fails when the platform admin cannot sign in', async () => {
    const doc = writeDoc([`${FENCE}bash runnable`, 'true', FENCE, ''].join('\n'));

    const result = await run(['--in-container', doc], { ...env(), ADMIN_PASSWORD: 'wrong' });

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::direct login of admin@kelta\.local to default/);
    assert.deepEqual(environmentRequests, []);
  });
});
