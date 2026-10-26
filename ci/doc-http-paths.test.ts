// node --test ci/doc-http-paths.test.ts   (Node >= 24; no dependencies)
//
// Every `METHOD /api/...` the docs show against the controller mappings ci/endpoint-index.ts
// reads, the exclusion list's reasons, and the extraction and matching rules on inline fixtures.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { REPO_ROOT, collect, loadGatewayTable, type Endpoint } from './endpoint-index.ts';
import {
  EXCLUSIONS_PATH,
  apiPath,
  check,
  docFiles,
  extractOccurrences,
  failures,
  normalisePath,
  pathMatches,
  readExclusions,
  resolves,
  type Exclusion,
  type Occurrence,
} from './doc-http-paths.ts';

const FENCE = '```';
const TABLE = { staticRoutes: [{ id: 'admin', prefix: '/api/admin' }, { id: 'flows', prefix: '/api/flows' }], systemCollections: new Set(['flows']) };
const ENDPOINTS: Array<Pick<Endpoint, 'method' | 'path'>> = [
  { method: 'GET', path: '/api/admin/widgets/{widgetId}' },
  { method: 'POST', path: '/api/admin/widgets' },
  { method: 'GET', path: '/api/files/**' },
  { method: 'GET', path: '/api/{collectionName}' },
  { method: 'GET', path: '/api/{collectionName}/{id}' },
  { method: 'ANY', path: '/api/hooks/{id}' },
];

/** The globs of the `ci_scripts` filter in .github/path-filters.yml. */
function ciScriptsFilter(): string[] {
  const yml = readFileSync(join(REPO_ROOT, '.github/path-filters.yml'), 'utf8');
  const block = yml.slice(yml.indexOf('\nci_scripts:\n')).split('\n\n')[0];
  return [...block.matchAll(/^\s+- '([^']+)'$/gm)].map((m) => m[1]);
}

describe('the committed docs', () => {
  it('show only METHOD /api/... paths a controller mapping serves, with every exclusion reasoned and used', () => {
    assert.deepEqual(check(REPO_ROOT), []);
  });

  it('are read from the marketing docs, docs/** and the module READMEs', () => {
    const files = docFiles(REPO_ROOT);
    assert.ok(files.includes('kelta-marketing/src/content/docs/api/errors.md'));
    assert.ok(files.includes('docs/authoring/api-access.md'));
    for (const readme of ['README.md', 'kelta-worker/README.md', 'kelta-gateway/README.md', 'kelta-platform/runtime/runtime-core/README.md']) {
      assert.ok(files.includes(readme), readme);
    }
    assert.ok(!files.some((f) => f.includes('node_modules')));
  });

  it('re-runs in CI when the docs, a README or a mapping change', () => {
    const filter = ciScriptsFilter();
    for (const glob of ['kelta-marketing/src/content/docs/**', 'docs/**', '**/README.md', 'kelta-*/src/main/java/**/*Controller.java']) {
      assert.ok(filter.includes(glob), glob);
    }
  });

  it('keeps a reason on every exclusion', () => {
    for (const x of readExclusions(REPO_ROOT)) assert.ok(x.reason.trim().length > 0, `${x.file} ${x.method} ${x.path}`);
  });

  it('takes its mappings from ci/endpoint-index.ts and parses no Java itself', () => {
    const src = readFileSync(join(import.meta.dirname, 'doc-http-paths.ts'), 'utf8');
    assert.match(src, /from '\.\/endpoint-index\.ts'/);
    assert.doesNotMatch(src, /Mapping\\b|maskJava|\.java\b/);
  });

  it('matches /api/{collection} and /api/{collection}/{id} to the DynamicCollectionRouter routes', () => {
    const endpoints = collect(REPO_ROOT).endpoints;
    const table = loadGatewayTable(REPO_ROOT);
    for (const [method, path] of [
      ['GET', '/api/{collection}'],
      ['POST', '/api/{collection}'],
      ['GET', '/api/{collection}/{id}'],
      ['PATCH', '/api/{collection}/{id}'],
      ['DELETE', '/api/accounts/{id}'],
    ]) {
      const served = endpoints.filter((e) => e.method === method && pathMatches(path, e.path, table));
      assert.ok(served.some((e) => e.sources.some((s) => s.startsWith('DynamicCollectionRouter#'))), `${method} ${path}`);
    }
  });
});

describe('extractOccurrences', () => {
  it('reads inline code, fenced request lines, kelta api calls and curl commands', () => {
    const md = [
      'Call `GET /api/admin/widgets/{id}` or `GET|PUT /api/ai/config`.',
      '',
      `${FENCE}http`,
      'POST /api/admin/widgets   (JSON body)',
      'kelta api DELETE /api/me/tokens/<id> --yes',
      FENCE,
      '',
      `${FENCE}bash`,
      'curl -X PATCH -H "Authorization: Bearer $T" \\',
      '     https://api.example.com/acme/api/accounts/42 \\',
      "     -d '{}'",
      'curl https://acme.<host>/api/products?page[size]=1',
      'curl -d x=1 "$KELTA_URL/api/products"',
      FENCE,
      '',
      'An inline span that wraps: `POST',
      '/api/operations` mid-sentence, and prose GET /api/not-code is ignored.',
    ].join('\n');
    assert.deepEqual(
      extractOccurrences(md, 'doc.md').map((o) => `${o.line} ${o.method} ${o.path}`),
      [
        '1 GET /api/admin/widgets/{id}',
        '1 GET /api/ai/config',
        '1 PUT /api/ai/config',
        '4 POST /api/admin/widgets',
        '5 DELETE /api/me/tokens/<id>',
        '9 PATCH /acme/api/accounts/42',
        '12 GET /api/products',
        '13 POST /api/products',
        '16 POST /api/operations',
      ],
    );
  });

  it('ignores paths outside /api and fenced blocks it cannot read as a request', () => {
    const md = ['`GET /scim/v2/Users`', '', `${FENCE}json`, '{ "url": "/api/x" }', FENCE].join('\n');
    assert.deepEqual(extractOccurrences(md, 'doc.md'), []);
  });
});

describe('apiPath and normalisePath', () => {
  it('drops scheme, host, query and trailing punctuation', () => {
    assert.equal(apiPath('https://api.kelta.io/acme/api/flows/1/execute?x=1'), '/acme/api/flows/1/execute');
    assert.equal(apiPath('…/api/collections,'), '/api/collections');
    assert.equal(apiPath('/api/x/'), '/api/x');
    assert.equal(apiPath('https://downloads.kelta.io/cli/install.sh'), null);
  });

  it('treats {id}, :id, <id> and $ID as the same path variable', () => {
    const variants = ['/api/admin/widgets/{id}', '/api/admin/widgets/:id', '/api/admin/widgets/<widget-id>', '/api/admin/widgets/$WIDGET_ID'];
    assert.equal(new Set(variants.map(normalisePath)).size, 1);
    for (const path of variants) assert.ok(resolves({ method: 'GET', path }, ENDPOINTS, TABLE), path);
  });
});

describe('resolves', () => {
  const ok = (method: string, path: string) => resolves({ method, path }, ENDPOINTS, TABLE);

  it('matches a concrete id to a mapping variable, and the method', () => {
    assert.ok(ok('GET', '/api/admin/widgets/0b6c1f4e-1111-2222-3333-444455556666'));
    assert.ok(!ok('DELETE', '/api/admin/widgets/1'));
    assert.ok(ok('DELETE', '/api/hooks/1'), 'an ANY mapping serves every method');
  });

  it('matches a catch-all mapping and everything below it', () => {
    assert.ok(ok('GET', '/api/files/a/b/c'));
  });

  it('strips a one-segment tenant slug before /api', () => {
    assert.ok(ok('POST', '/acme/api/admin/widgets'));
    assert.ok(ok('GET', '/{tenantSlug}/api/admin/widgets/{id}'));
  });

  it('serves any tenant collection through the collection routes', () => {
    assert.ok(ok('GET', '/api/accounts'));
    assert.ok(ok('GET', '/api/{collection}/{id}'));
  });

  it('does not let a collection route swallow a statically routed path, unless it names a system collection', () => {
    assert.ok(!ok('GET', '/api/admin/typo'));
    assert.ok(ok('GET', '/api/flows/1'));
  });
});

describe('failures', () => {
  const at = (path: string, line = 7): Occurrence => ({ file: 'docs/x.md', line, method: 'GET', path });

  it('names file:line and the method and path of a planted bad path', () => {
    assert.deepEqual(failures([at('/api/admin/widgets/{id}'), at('/api/admin/wigdets/{id}', 12)], ENDPOINTS, TABLE, []), [
      'docs/x.md:12  GET /api/admin/wigdets/{id}  — no controller mapping serves this path',
    ]);
  });

  it('lets a reasoned exclusion through, in any path-variable spelling', () => {
    const x: Exclusion = { file: 'docs/x.md', method: 'GET', path: '/api/admin/gone/:id', reason: 'shows a removed endpoint on purpose' };
    assert.deepEqual(failures([at('/api/admin/gone/{id}')], ENDPOINTS, TABLE, [x]), []);
  });

  it('fails an exclusion without a reason', () => {
    const x: Exclusion = { file: 'docs/x.md', method: 'GET', path: '/api/admin/gone', reason: ' ' };
    assert.deepEqual(failures([at('/api/admin/gone')], ENDPOINTS, TABLE, [x]), [
      `${EXCLUSIONS_PATH}: docs/x.md GET /api/admin/gone — needs a reason`,
    ]);
  });

  it('fails an exclusion that no longer matches an occurrence', () => {
    const x: Exclusion = { file: 'docs/x.md', method: 'GET', path: '/api/admin/gone', reason: 'shows a removed endpoint on purpose' };
    assert.deepEqual(failures([], ENDPOINTS, TABLE, [x]), [
      `${EXCLUSIONS_PATH}: docs/x.md GET /api/admin/gone — matches no occurrence in the docs; remove it`,
    ]);
  });
});
