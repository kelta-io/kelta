// node --test ci/templates-roundtrip-check.test.ts   (Node >= 24; needs bash, curl, jq, diff)
//
// Drives ci/templates-roundtrip-check.sh: its --compare half (ci/templates-roundtrip-normalise.jq
// plus the exclusions file) on hand-written exports, and its --in-container half against fake
// templates, a stub `kelta` and a local stand-in for kelta-auth's direct login and the gateway's
// sandbox create.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, beforeEach, describe, it } from 'node:test';

const SCRIPT = join(import.meta.dirname, 'templates-roundtrip-check.sh');
const PASSWORD = 'admin-secret';
const TOKEN = 'token-123';
const SANDBOX_PASSWORD = 'one-time-secret';
const SANDBOX_TOKEN = 'sandbox-token-456';

type Item = { type: string; data: Record<string, unknown> };

/** An export of a two-collection template, with every id and timestamp derived from `tenant`. */
function exportOf(tenant: string, overrides: (items: Item[]) => void = () => {}): object {
  const items: Item[] = [
    {
      type: 'COLLECTION',
      data: {
        id: `${tenant}-accounts`,
        name: 'accounts',
        display_field_id: `${tenant}-f-name`,
        display_field_name: 'name',
        created_at: `${tenant}-2026-01-01`,
        updated_at: `${tenant}-2026-01-02`,
      },
    },
    {
      type: 'FIELD',
      data: {
        id: `${tenant}-f-name`,
        collection_id: `${tenant}-accounts`,
        collection_name: 'accounts',
        name: 'name',
        type: 'STRING',
        required: true,
        field_type_config: { globalPicklistId: `${tenant}-gp`, maxLength: 80 },
        global_picklist_name: 'sizes',
      },
    },
    {
      type: 'LIST_VIEW',
      data: { id: `${tenant}-lv`, collection_id: `${tenant}-accounts`, collection_name: 'accounts', name: 'All' },
    },
  ];
  overrides(items);
  return {
    formatVersion: 2,
    name: 'a',
    version: '1.0.0',
    exportedAt: `${tenant}-now`,
    source: { instanceId: `${tenant}-instance`, tenantId: `${tenant}-id`, tenantSlug: tenant },
    // Rows come back in a different order from another tenant.
    items: tenant === 'default' ? items : [...items].reverse(),
  };
}

let root: string;
let stubBin: string;

function runScript(args: string[], env: Record<string, string> = {}): Promise<{ code: number; out: string }> {
  return new Promise((resolve) => {
    execFile(
      'bash',
      [SCRIPT, ...args],
      { env: { PATH: `${stubBin}:${process.env.PATH ?? ''}`, ...env } },
      (error, stdout, stderr) => {
        resolve({ code: error ? Number(error.code) : 0, out: stdout + stderr });
      }
    );
  });
}

function compare(a: object, b: object, exclusions?: string): Promise<{ code: number; out: string }> {
  const dir = mkdtempSync(join(root, 'compare-'));
  writeFileSync(join(dir, 'a.json'), JSON.stringify(a));
  writeFileSync(join(dir, 'b.json'), JSON.stringify(b));
  const env: Record<string, string> = {};
  if (exclusions !== undefined) {
    writeFileSync(join(dir, 'exclusions.txt'), exclusions);
    env.EXCLUSIONS = join(dir, 'exclusions.txt');
  }
  return runScript(['--compare', join(dir, 'a.json'), join(dir, 'b.json'), 'tpl'], env);
}

before(() => {
  root = mkdtempSync(join(tmpdir(), 'templates-roundtrip-check-'));
  stubBin = join(root, 'bin');
  mkdirSync(stubBin);
  // `kelta metadata export ... --name <n> --out <f>` copies $EXPORTS/<tenant>/<n>.json to <f>
  // (a missing one is an error on stderr, exit 1); `kelta metadata apply <f>` prints
  // $APPLY_REPLY (exit $APPLY_EXIT). Like the real CLI, a `--version` anywhere prints the CLI
  // version and exits 0 without running the command. Every call is logged.
  writeFileSync(
    join(stubBin, 'kelta'),
    [
      '#!/usr/bin/env bash',
      'set -euo pipefail',
      'echo "$KELTA_TENANT $KELTA_TOKEN | $*" >>"$KELTA_LOG"',
      'for arg in "$@"; do [ "$arg" != --version ] || { echo 0.0.0-stub; exit 0; }; done',
      'if [ "$2" = export ]; then',
      '  args=("$@"); out=""; name=""',
      '  for ((i = 0; i < ${#args[@]}; i++)); do',
      '    case "${args[$i]}" in --out) out="${args[$((i + 1))]}" ;; --name) name="${args[$((i + 1))]}" ;; esac',
      '  done',
      '  [ -f "$EXPORTS/$KELTA_TENANT/$name.json" ] || { echo "Unknown collection(s) in collectionNames" >&2; exit 1; }',
      '  cp "$EXPORTS/$KELTA_TENANT/$name.json" "$out"',
      '  echo "{\\"file\\": \\"$out\\"}"',
      'else',
      '  printf "%s\\n" "$APPLY_REPLY"',
      '  exit "${APPLY_EXIT:-0}"',
      'fi',
      '',
    ].join('\n'),
    { mode: 0o755 }
  );
});

after(() => {
  rmSync(root, { recursive: true, force: true });
});

describe('templates-roundtrip-check.sh --compare', () => {
  it('passes two exports that differ only in ids, timestamps, provenance and item order', async () => {
    const result = await compare(exportOf('default'), exportOf('default--roundtrip-a'));

    assert.equal(result.code, 0, result.out);
    assert.match(result.out, /tpl: the re-export matches the export \(3 item\(s\) compared\)/);
  });

  it('fails on a changed field value and prints the diff', async () => {
    const changed = exportOf('default--roundtrip-a', (items) => {
      items[1].data.required = false;
    });

    const result = await compare(exportOf('default'), changed);

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::tpl: the re-export differs from the export/);
    assert.match(result.out, /^-\s+"required": true,?$/m);
    assert.match(result.out, /^\+\s+"required": false,?$/m);
  });

  it('fails on a value inside a JSON column that is not an id', async () => {
    const changed = exportOf('default--roundtrip-a', (items) => {
      items[1].data.field_type_config = { globalPicklistId: 'other', maxLength: 40 };
    });

    const result = await compare(exportOf('default'), changed);

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /^\+\s+"maxLength": 40$/m);
  });

  it('fails when the re-export lost an item', async () => {
    const lost = exportOf('default--roundtrip-a', (items) => {
      items.splice(2, 1);
    });

    const result = await compare(exportOf('default'), lost);

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /^-\s+"type": "LIST_VIEW"$/m);
  });

  it('leaves an excluded type out of the diff and says so, with its task and counts', async () => {
    const lost = exportOf('default--roundtrip-a', (items) => {
      items.splice(2, 1);
    });

    const result = await compare(exportOf('default'), lost, '# comment\n\nLIST_VIEW KLT-999 not imported yet\n');

    assert.equal(result.code, 0, result.out);
    assert.match(
      result.out,
      /tpl: LIST_VIEW excluded from the diff \(KLT-999: not imported yet\) — 1 item\(s\) in the export, 0 in the re-export/
    );
    assert.match(result.out, /2 item\(s\) compared/);
  });

  it('refuses an exclusion line that names no task', async () => {
    const result = await compare(exportOf('default'), exportOf('default'), 'LIST_VIEW not imported yet\n');

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::.*:1: LIST_VIEW names no task that removes it/);
  });

  it('reads the committed exclusions file and reports when it excludes nothing', async () => {
    const committed = readFileSync(join(import.meta.dirname, 'templates-roundtrip-exclusions.txt'), 'utf8');
    const result = await compare(exportOf('default'), exportOf('default--roundtrip-a'));

    assert.equal(result.code, 0, result.out);
    if (!committed.split('\n').some((line) => line.trim() && !line.trim().startsWith('#'))) {
      assert.match(result.out, /tpl: no package types excluded from the diff/);
    }
  });
});

describe('templates-roundtrip-check.sh --in-container', () => {
  let server: Server;
  let baseUrl: string;
  let environmentRequests: { data: { attributes: Record<string, unknown> } }[];

  before(async () => {
    server = createServer((req, res) => {
      let body = '';
      req.on('data', (chunk) => (body += chunk));
      req.on('end', () => {
        const url = new URL(req.url ?? '/', 'http://localhost');
        if (req.method === 'POST' && url.pathname === '/auth/direct-login') {
          const login = JSON.parse(body);
          const parent = login.password === PASSWORD && login.tenantSlug === 'default';
          const sandbox =
            login.username === `${login.tenantSlug}-admin` && login.password === SANDBOX_PASSWORD;
          const token = parent ? TOKEN : sandbox ? SANDBOX_TOKEN : undefined;
          res.writeHead(token ? 200 : 401, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(token ? { access_token: token } : { error: 'bad credentials' }));
          return;
        }
        if (req.method === 'POST' && url.pathname === '/default/api/environments') {
          const request = JSON.parse(body);
          environmentRequests.push(request);
          const slug = `default--${request.data.attributes.name}`;
          res.writeHead(202, { 'Content-Type': 'application/json' });
          res.end(
            JSON.stringify({
              data: {
                attributes: {
                  status: 'ACTIVE',
                  clone_mode: 'EMPTY',
                  sandboxSlug: slug,
                  adminUsername: `${slug}-admin`,
                  adminInitialPassword: SANDBOX_PASSWORD,
                },
              },
            })
          );
          return;
        }
        if (
          req.method === 'GET' &&
          url.pathname.endsWith('/api/collections') &&
          req.headers.authorization === `Bearer ${SANDBOX_TOKEN}`
        ) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ data: [] }));
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
  });

  beforeEach(() => {
    environmentRequests = [];
  });

  /** Fake templates, each with a metadata.json naming `collections`, and the exports the stub serves. */
  function setUp(exports: Record<string, Record<string, object>>, collections = ['accounts', 'contacts']) {
    const dir = mkdtempSync(join(root, 'run-'));
    for (const [tenant, byTemplate] of Object.entries(exports)) {
      mkdirSync(join(dir, 'exports', tenant), { recursive: true });
      for (const [name, pkg] of Object.entries(byTemplate)) {
        writeFileSync(join(dir, 'exports', tenant, `${name}.json`), JSON.stringify(pkg));
        mkdirSync(join(dir, 'templates', name), { recursive: true });
        writeFileSync(
          join(dir, 'templates', name, 'metadata.json'),
          JSON.stringify({
            items: [
              ...collections.map((c) => ({ type: 'COLLECTION', data: { name: c } })),
              { type: 'FIELD', data: { name: 'x', collection_name: collections[0] } },
            ],
          })
        );
      }
    }
    return dir;
  }

  function run(dir: string, env: Record<string, string> = {}) {
    return runScript(['--in-container', join(dir, 'templates')], {
      AUTH_URL: baseUrl,
      GATEWAY_URL: baseUrl,
      ADMIN_PASSWORD: PASSWORD,
      EXPORTS: join(dir, 'exports'),
      KELTA_LOG: join(dir, 'kelta-log'),
      READY_ATTEMPTS: '2',
      APPLY_REPLY: JSON.stringify({ success: true, created: 3, updated: 0, skipped: 0, failed: 0, items: [] }),
      ...env,
    });
  }

  it("exports the template's collections, applies them to an empty sandbox and re-exports", async () => {
    const dir = setUp({
      default: { a: exportOf('default') },
      'default--roundtrip-a': { a: exportOf('default--roundtrip-a') },
    });

    const result = await run(dir);

    assert.equal(result.code, 0, result.out);
    assert.deepEqual(
      environmentRequests.map((r) => r.data.attributes),
      [{ name: 'roundtrip-a', type: 'SANDBOX', cloneMetadata: false }]
    );
    const log = readFileSync(join(dir, 'kelta-log'), 'utf8').trim().split('\n');
    assert.equal(log.length, 3, log.join('\n'));
    assert.match(log[0], new RegExp(`^default ${TOKEN} \\| metadata export --collections accounts,contacts --name a --out \\S+/export\\.json --output json$`));
    assert.match(log[1], new RegExp(`^default--roundtrip-a ${SANDBOX_TOKEN} \\| metadata apply \\S+/export\\.json --yes --output json$`));
    assert.match(log[2], new RegExp(`^default--roundtrip-a ${SANDBOX_TOKEN} \\| metadata export --collections accounts,contacts --name a .*re-export\\.json`));
    assert.match(result.out, /a: the re-export matches the export/);
    assert.match(result.out, /All 1 template\(s\) round-trip/);
  });

  it('checks every template, then fails naming the ones whose re-export differs', async () => {
    const dir = setUp({
      default: { a: exportOf('default'), b: exportOf('default') },
      'default--roundtrip-a': {
        a: exportOf('default--roundtrip-a', (items) => {
          items[0].data.display_field_name = null;
        }),
      },
      'default--roundtrip-b': { b: exportOf('default--roundtrip-b') },
    });

    const result = await run(dir);

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::a: the re-export differs from the export/);
    assert.match(result.out, /^\+\s+"display_field_name": null,?$/m);
    assert.match(result.out, /b: the re-export matches the export/);
    assert.match(result.out, /::error::export → apply → re-export did not round-trip for: a \(diffs above\)/);
  });

  it("fails naming the template and printing the CLI's error when an export fails", async () => {
    const dir = setUp({ default: { a: exportOf('default') } });
    rmSync(join(dir, 'exports', 'default', 'a.json'));

    const result = await run(dir);

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /kelta metadata export exited 1: Unknown collection\(s\) in collectionNames/);
    assert.match(result.out, /::error::a: kelta metadata export from default failed/);
  });

  it('reports the items a failed apply could not import and still diffs', async () => {
    const dir = setUp({
      default: { a: exportOf('default') },
      'default--roundtrip-a': {
        a: exportOf('default--roundtrip-a', (items) => {
          items.splice(2, 1);
        }),
      },
    });
    const reply = {
      success: false,
      created: 2,
      updated: 0,
      skipped: 0,
      failed: 1,
      items: [{ type: 'LIST_VIEW', naturalKey: 'accounts:All', action: 'FAILED', error: 'no such column' }],
    };

    const result = await run(dir, { APPLY_REPLY: JSON.stringify(reply) });

    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /::error::a: applying the export to default--roundtrip-a failed \(exit 0\)/);
    assert.match(result.out, / {2}LIST_VIEW accounts:All: no such column/);
    assert.match(result.out, /^-\s+"type": "LIST_VIEW"$/m);
  });
});
