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
const SANDBOX_SLUG = 'default--templates-empty';
const SANDBOX_ADMIN = `${SANDBOX_SLUG}-admin`;
const SANDBOX_PASSWORD = 'one-time-secret';
const SANDBOX_TOKEN = 'sandbox-token-456';
/** A fake install.sh line that tells the stand-in its tenant now holds the template. */
const MARK_INSTALLED = 'curl -fsS -X POST "$KELTA_URL/$KELTA_TENANT/__installed"';
/** What the stub `kelta metadata apply` prints when nothing changed. */
const NO_OP_APPLY = {
  success: true,
  created: 0,
  updated: 0,
  skipped: 2,
  failed: 0,
  items: [
    { type: 'COLLECTION', naturalKey: 'accounts', action: 'SKIPPED' },
    { type: 'FIELD', naturalKey: 'accounts.name', action: 'SKIPPED' },
  ],
};

let server: Server;
let baseUrl: string;
let counts: Record<string, number>;
let root: string;
let sandboxAttributes: Record<string, unknown>;
let sandboxInstalled: boolean;
let environmentRequests: unknown[];
let createError: string | undefined;
let stubBin: string;

interface Template {
  install: string;
  seeds?: Record<string, unknown[]>;
}

function writeTemplates(templates: Record<string, Template>): string {
  const dir = mkdtempSync(join(root, 'templates-'));
  for (const [name, t] of Object.entries(templates)) {
    mkdirSync(join(dir, name, 'seeds'), { recursive: true });
    writeFileSync(join(dir, name, 'install.sh'), `set -euo pipefail\n${t.install}\n`);
    writeFileSync(join(dir, name, 'metadata.json'), '{"items": []}');
    for (const [collection, rows] of Object.entries(t.seeds ?? {})) {
      writeFileSync(join(dir, name, 'seeds', `${collection}.json`), JSON.stringify(rows));
    }
  }
  return dir;
}

/**
 * Runs the script with a stub `kelta` first on PATH. The stub logs each call (args, cwd and
 * tenant) to KELTA_LOG and prints APPLY_REPLY, or APPLY_REPLY_<tenant with - as _> when set,
 * exiting APPLY_EXIT.
 */
function run(dir: string, env: Record<string, string> = {}): Promise<{ code: number; out: string }> {
  return new Promise((resolve) => {
    execFile(
      'bash',
      [SCRIPT, '--in-container', dir],
      {
        env: {
          PATH: `${stubBin}:${process.env.PATH ?? ''}`,
          AUTH_URL: baseUrl,
          GATEWAY_URL: baseUrl,
          ADMIN_PASSWORD: PASSWORD,
          LOG: join(dir, 'log'),
          KELTA_LOG: join(dir, 'kelta-log'),
          APPLY_REPLY: JSON.stringify(NO_OP_APPLY),
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
  stubBin = join(root, 'bin');
  mkdirSync(stubBin);
  writeFileSync(
    join(stubBin, 'kelta'),
    [
      '#!/usr/bin/env bash',
      'echo "$* | $(basename "$PWD") | $KELTA_TENANT" >>"$KELTA_LOG"',
      'reply_var="APPLY_REPLY_${KELTA_TENANT//-/_}"',
      'printf "%s\\n" "${!reply_var:-$APPLY_REPLY}"',
      'exit "${APPLY_EXIT:-0}"',
      '',
    ].join('\n'),
    { mode: 0o755 }
  );
  server = createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const url = new URL(req.url ?? '/', 'http://localhost');
      if (req.method === 'POST' && url.pathname === '/auth/direct-login') {
        const login = JSON.parse(body);
        const parent = login.password === PASSWORD && login.tenantSlug === 'default';
        const sandbox =
          login.username === SANDBOX_ADMIN &&
          login.password === SANDBOX_PASSWORD &&
          login.tenantSlug === SANDBOX_SLUG;
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
        if (createError) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ errors: [{ detail: createError }] }));
          return;
        }
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ data: { id: 'env-1', attributes: sandboxAttributes } }));
        return;
      }
      if (req.method === 'POST' && url.pathname === `/${SANDBOX_SLUG}/__installed`) {
        sandboxInstalled = true;
        res.writeHead(204).end();
        return;
      }
      if (req.method === 'POST' && url.pathname === '/default/__installed') {
        res.writeHead(204).end();
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
      const sandboxMatch = url.pathname.match(new RegExp(`^/${SANDBOX_SLUG}/api/([^/]+)$`));
      if (req.method === 'GET' && sandboxMatch) {
        if (req.headers.authorization !== `Bearer ${SANDBOX_TOKEN}`) {
          res.writeHead(401).end();
          return;
        }
        if (sandboxMatch[1] === 'collections') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ data: [] }));
          return;
        }
        if (!sandboxInstalled) {
          res.writeHead(404).end();
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ data: [], meta: { totalCount: counts[sandboxMatch[1]] ?? 0 } }));
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
  sandboxInstalled = false;
  environmentRequests = [];
  createError = undefined;
  sandboxAttributes = {
    status: 'ACTIVE',
    clone_mode: 'EMPTY',
    sandboxSlug: SANDBOX_SLUG,
    adminUsername: SANDBOX_ADMIN,
    adminInitialPassword: SANDBOX_PASSWORD,
  };
});

describe('templates-install-check.sh --in-container', () => {
  it('installs every template in name order with the CLI env set and stdin closed', async () => {
    counts = { accounts: 2, sites: 1 };
    const dir = writeTemplates({
      b: { install: 'echo "b $KELTA_URL $KELTA_TENANT $KELTA_TOKEN" >>"$LOG"; ! read -r _', seeds: { sites: [{}] } },
      a: {
        install: `echo "a $KELTA_URL $KELTA_TENANT $KELTA_TOKEN" >>"$LOG"; ${MARK_INSTALLED}`,
        seeds: { accounts: [{}, {}] },
      },
    });

    const result = await run(dir);

    assert.equal(result.code, 0, result.out);
    assert.match(result.out, /a\/accounts: expected 2, found 2/);
    assert.match(result.out, /b\/sites: expected 1, found 1/);
    assert.match(result.out, /All 2 template\(s\) installed/);
    assert.equal(
      readFileSync(join(dir, 'log'), 'utf8'),
      `a ${baseUrl} default ${TOKEN}\nb ${baseUrl} default ${TOKEN}\n` +
        `a ${baseUrl} ${SANDBOX_SLUG} ${SANDBOX_TOKEN}\n`
    );
  });

  it('applies metadata.json a second time after each install.sh and passes when it changes nothing', async () => {
    counts = { accounts: 2 };
    const dir = writeTemplates({
      a: { install: MARK_INSTALLED, seeds: { accounts: [{}, {}] } },
      b: { install: 'true' },
    });

    const result = await run(dir);

    assert.equal(result.code, 0, result.out);
    assert.equal(
      readFileSync(join(dir, 'kelta-log'), 'utf8'),
      'metadata apply metadata.json --yes --output json | a | default\n' +
        'metadata apply metadata.json --yes --output json | b | default\n' +
        `metadata apply metadata.json --yes --output json | a | ${SANDBOX_SLUG}\n`
    );
    assert.match(result.out, /a: second apply of metadata\.json changed nothing: \{"created":0,"updated":0,"skipped":2,"failed":0\}/);
    assert.match(result.out, /Every second apply of metadata\.json changed nothing/);
  });

  it('fails, naming the template and the items, when the second apply creates an item', async () => {
    const dir = writeTemplates({ a: { install: MARK_INSTALLED }, b: { install: 'true' } });
    const reply = {
      ...NO_OP_APPLY,
      created: 1,
      skipped: 1,
      items: [
        { type: 'COLLECTION', naturalKey: 'accounts', action: 'SKIPPED' },
        { type: 'PICKLIST_VALUE', naturalKey: 'accounts.industry:SOFTWARE', action: 'CREATED' },
      ],
    };

    const result = await run(dir, { APPLY_REPLY_default: JSON.stringify(reply) });

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::a: the second apply of metadata\.json was not a no-op \(exit 0\): \{"created":1,/);
    assert.match(result.out, /a: items the second apply did not skip:\n {2}PICKLIST_VALUE accounts\.industry:SOFTWARE: CREATED\n/);
    assert.doesNotMatch(result.out, /COLLECTION accounts: SKIPPED/);
    // Every template and the sandbox still install, so one run reports every re-apply.
    assert.match(result.out, /::error::b: the second apply/);
    assert.match(result.out, /a installed into the empty sandbox/);
    assert.match(result.out, /::error::a second apply of metadata\.json changed or failed items in: a b \(see/);
  });

  it('fails when the second apply reports a failed item', async () => {
    const dir = writeTemplates({ a: { install: MARK_INSTALLED } });
    const reply = {
      ...NO_OP_APPLY,
      success: false,
      skipped: 1,
      failed: 1,
      items: [
        { type: 'COLLECTION', naturalKey: 'accounts', action: 'SKIPPED' },
        { type: 'FLOW', naturalKey: 'Stamp closed deals', action: 'FAILED', error: 'duplicate key' },
      ],
    };

    const result = await run(dir, { APPLY_REPLY_default: JSON.stringify(reply) });

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::a: the second apply of metadata\.json was not a no-op \(exit 0\): \{"created":0,"updated":0,"skipped":1,"failed":1\}/);
    assert.match(result.out, / {2}FLOW Stamp closed deals: FAILED — duplicate key/);
    assert.match(result.out, /changed or failed items in: a \(see/);
  });

  it('fails when the second apply exits non-zero, even with a zero report', async () => {
    const dir = writeTemplates({ a: { install: MARK_INSTALLED } });

    const result = await run(dir, { APPLY_EXIT: '1' });

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::a: the second apply of metadata\.json was not a no-op \(exit 1\)/);
  });

  it('installs the first template into an empty sandbox created with cloneMetadata: false', async () => {
    counts = { accounts: 2 };
    const dir = writeTemplates({
      a: { install: MARK_INSTALLED, seeds: { accounts: [{}, {}] } },
    });

    const result = await run(dir);

    assert.equal(result.code, 0, result.out);
    assert.deepEqual(environmentRequests, [
      {
        data: {
          type: 'environments',
          attributes: { name: 'templates-empty', type: 'SANDBOX', cloneMetadata: false },
        },
      },
    ]);
    assert.match(result.out, new RegExp(`sandbox ${SANDBOX_SLUG} holds none of a's collections`));
    assert.match(result.out, new RegExp(`a@${SANDBOX_SLUG}/accounts: expected 2, found 2`));
    assert.match(result.out, /a installed into the empty sandbox .* with no deletions/);
  });

  it('fails when the sandbox was cloned instead of created empty', async () => {
    sandboxAttributes.clone_mode = 'CLONED';
    const dir = writeTemplates({ a: { install: MARK_INSTALLED } });

    const result = await run(dir);

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::sandbox templates-empty: clone_mode is CLONED, expected EMPTY/);
  });

  it('fails with the status and body when the sandbox create is refused', async () => {
    createError = 'cloneMetadata must be a boolean';
    const dir = writeTemplates({ a: { install: MARK_INSTALLED } });

    const result = await run(dir);

    assert.equal(result.code, 1);
    assert.match(
      result.out,
      /::error::creating the empty sandbox templates-empty answered HTTP 400: .*cloneMetadata must be a boolean/
    );
  });

  it('fails when the sandbox already holds a template collection before the install', async () => {
    counts = { accounts: 1 };
    sandboxInstalled = true;
    const dir = writeTemplates({ a: { install: 'true', seeds: { accounts: [{}] } } });

    const result = await run(dir);

    assert.equal(result.code, 1);
    assert.match(result.out, /::error::sandbox .* already holds accounts before the install/);
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
