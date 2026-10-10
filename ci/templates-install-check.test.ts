// node --test ci/templates-install-check.test.ts   (Node >= 24; needs bash, curl, jq, timeout)
//
// Drives the --in-container half of ci/templates-install-check.sh against fake templates and a
// local stand-in for kelta-auth's direct login and the gateway's record lists.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, beforeEach, describe, it } from 'node:test';

const SCRIPT = join(import.meta.dirname, 'templates-install-check.sh');
const PASSWORD = 'admin-secret';
const TOKEN = 'token-123';

let server: Server;
let baseUrl: string;
let counts: Record<string, number>;
let root: string;

interface Template {
  install: string;
  seeds?: Record<string, unknown[]>;
}

function writeTemplates(templates: Record<string, Template>): string {
  const dir = mkdtempSync(join(root, 'templates-'));
  for (const [name, t] of Object.entries(templates)) {
    mkdirSync(join(dir, name, 'seeds'), { recursive: true });
    writeFileSync(join(dir, name, 'install.sh'), `set -euo pipefail\n${t.install}\n`);
    for (const [collection, rows] of Object.entries(t.seeds ?? {})) {
      writeFileSync(join(dir, name, 'seeds', `${collection}.json`), JSON.stringify(rows));
    }
  }
  return dir;
}

function run(dir: string, env: Record<string, string> = {}): Promise<{ code: number; out: string }> {
  return new Promise((resolve) => {
    execFile(
      'bash',
      [SCRIPT, '--in-container', dir],
      {
        env: {
          PATH: process.env.PATH ?? '',
          AUTH_URL: baseUrl,
          GATEWAY_URL: baseUrl,
          ADMIN_PASSWORD: PASSWORD,
          LOG: join(dir, 'log'),
          ...env,
        },
      },
      (error, stdout, stderr) => {
        resolve({ code: error ? Number(error.code) : 0, out: stdout + stderr });
      }
    );
  });
}

before(async () => {
  root = mkdtempSync(join(tmpdir(), 'templates-install-check-'));
  server = createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const url = new URL(req.url ?? '/', 'http://localhost');
      if (req.method === 'POST' && url.pathname === '/auth/direct-login') {
        const login = JSON.parse(body);
        const ok = login.password === PASSWORD && login.tenantSlug === 'default';
        res.writeHead(ok ? 200 : 401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(ok ? { access_token: TOKEN } : { error: 'bad credentials' }));
        return;
      }
      const match = url.pathname.match(/^\/default\/api\/([^/]+)$/);
      if (req.method === 'GET' && match && url.searchParams.get('page[size]') === '1') {
        if (req.headers.authorization !== `Bearer ${TOKEN}`) {
          res.writeHead(401).end();
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ data: [], meta: { totalCount: counts[match[1]] ?? 0 } }));
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
  counts = {};
});

describe('templates-install-check.sh --in-container', () => {
  it('installs every template in name order with the CLI env set and stdin closed', async () => {
    counts = { accounts: 2, sites: 1 };
    const dir = writeTemplates({
      b: { install: 'echo "b $KELTA_URL $KELTA_TENANT $KELTA_TOKEN" >>"$LOG"; ! read -r _', seeds: { sites: [{}] } },
      a: { install: 'echo "a $KELTA_URL $KELTA_TENANT $KELTA_TOKEN" >>"$LOG"', seeds: { accounts: [{}, {}] } },
    });

    const result = await run(dir);

    assert.equal(result.code, 0, result.out);
    assert.match(result.out, /a\/accounts: expected 2, found 2/);
    assert.match(result.out, /b\/sites: expected 1, found 1/);
    assert.match(result.out, /All 2 template\(s\) installed/);
    assert.equal(
      readFileSync(join(dir, 'log'), 'utf8'),
      `a ${baseUrl} default ${TOKEN}\nb ${baseUrl} default ${TOKEN}\n`
    );
  });

  it('stops at the first install.sh that exits non-zero', async () => {
    const dir = writeTemplates({
      a: { install: 'echo a >>"$LOG"; exit 3' },
      b: { install: 'echo b >>"$LOG"' },
    });

    const result = await run(dir);

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::a: install\.sh exited 3/);
    assert.equal(readFileSync(join(dir, 'log'), 'utf8'), 'a\n');
  });

  it('fails when a collection holds a different number of records than its seed file', async () => {
    counts = { accounts: 1 };
    const dir = writeTemplates({ a: { install: 'true', seeds: { accounts: [{}, {}, {}] } } });

    const result = await run(dir);

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::a: accounts holds 1 records, seeds\/accounts\.json has 3/);
  });

  it('fails an install that outlives the timeout', async () => {
    const dir = writeTemplates({ a: { install: 'sleep 30' } });

    const started = Date.now();
    const result = await run(dir, { INSTALL_TIMEOUT: '1' });

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::a: install\.sh did not finish within 1s/);
    assert.ok(Date.now() - started < 10_000);
  });

  it('fails before installing when the direct login returns no token', async () => {
    const dir = writeTemplates({ a: { install: 'echo a >>"$LOG"' } });

    const result = await run(dir, { ADMIN_PASSWORD: 'wrong' });

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::direct login/);
    assert.equal(existsSync(join(dir, 'log')), false);
  });

  it('fails when there is no template to install', async () => {
    const result = await run(mkdtempSync(join(root, 'empty-')));

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::no templates under/);
  });
});
