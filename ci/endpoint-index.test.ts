// node --test ci/endpoint-index.test.ts   (Node >= 24; no dependencies)
//
// The public endpoint index (docs/authoring/endpoints.md) against a fresh render, the
// classification's reasons and docs links, the unclassified-count ratchet, and the Java and
// gateway-table parsers on inline fixtures.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import {
  BASELINE_PATH,
  PAGE_PATH,
  REPO_ROOT,
  WRITE_COMMAND,
  brokenDocsLinks,
  buildEndpoints,
  classificationProblems,
  classify,
  collect,
  docsPages,
  loadGatewayTable,
  parseAuthPermitAll,
  parseCollectionChecks,
  parseFilterWriteRules,
  parseJavaMappings,
  parseStaticRoutes,
  parseYamlList,
  ratchet,
  readBaseline,
  readClassification,
  render,
  stalePage,
  unclassifiedCount,
  type Classification,
  type GatewayTable,
  type Model,
  type SourceMapping,
} from './endpoint-index.ts';

const PERMISSIONS = new Set(['API_ACCESS', 'MANAGE_DATA', 'MANAGE_WORKFLOWS', 'VIEW_ANALYTICS']);

describe('the committed index', () => {
  const entries = readClassification(REPO_ROOT);
  const model = collect(REPO_ROOT, entries);

  it(`equals a fresh render — run \`${WRITE_COMMAND}\` when this fails`, () => {
    const committed = readFileSync(join(REPO_ROOT, PAGE_PATH), 'utf8');
    assert.equal(stalePage(committed, render(model)), null);
  });

  it('has a reason for every internal entry and no malformed or unused entry', () => {
    assert.deepEqual(classificationProblems(model, entries), []);
  });

  it('links only to docs pages and headings the site publishes', () => {
    assert.deepEqual(brokenDocsLinks(entries, docsPages(REPO_ROOT)), []);
  });

  it(`keeps the unclassified count at ${BASELINE_PATH}`, () => {
    assert.equal(ratchet(unclassifiedCount(model), readBaseline(REPO_ROOT)), null);
  });

  it('keeps the baseline at or below 80', () => {
    assert.ok(readBaseline(REPO_ROOT) <= 80, `baseline is ${readBaseline(REPO_ROOT)}`);
  });

  it('states its definition of public and has one row per public endpoint', () => {
    const page = render(model);
    assert.match(page, /## What counts as public/);
    const rows = page.split('\n').filter((l) => /^\| (GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY) \|/.test(l));
    assert.equal(rows.length, model.endpoints.length);
    assert.equal(new Set(rows.map((r) => r.split('|').slice(1, 3).join('|'))).size, rows.length);
  });

  it('reads the real gateway table', () => {
    const table = loadGatewayTable(REPO_ROOT);
    assert.ok(table.staticRoutes.some((r) => r.prefix === '/api/flows'));
    assert.ok(table.staticRoutes.some((r) => r.prefix === '/api/ai'), 'RouteInitializer static-ai route');
    assert.equal(table.checks.get('flows')?.writeOverride, 'MANAGE_WORKFLOWS');
    assert.ok(table.checks.get('flows')?.exempt?.test('/api/flows/x/execute'));
    assert.ok(table.unauthenticatedPaths.includes('/api/webhooks'));
    assert.ok(table.publicPaths.includes('/api/ui-pages'));
    assert.ok(table.systemCollections.has('picklist-values'));
    assert.deepEqual(
      table.filterWriteRules.map((r) => r.routeId).sort(),
      ['modules', 'tenants'],
      'the static-route write rules hard-coded in RouteAuthorizationFilter',
    );
  });

  it('covers every service and excludes the internal worker endpoints', () => {
    const services = new Set(model.endpoints.map((e) => e.service));
    assert.deepEqual([...services].sort(), ['kelta-ai', 'kelta-auth', 'kelta-mcp', 'kelta-worker']);
    assert.ok(model.endpoints.some((e) => e.method === 'GET' && e.path === '/api/{collectionName}'), 'generic collection router');
    assert.ok(model.unrouted.some((u) => u.path === '/internal/bootstrap'));
    assert.ok(!model.endpoints.some((e) => e.service === 'kelta-worker' && e.path.startsWith('/internal/')));
  });
});

describe('stalePage', () => {
  it('fails on any difference and names the regenerate command', () => {
    assert.equal(stalePage('a\nb\n', 'a\nb\n'), null);
    const message = stalePage('a\nb\n', 'a\nc\n');
    assert.ok(message?.includes(WRITE_COMMAND), message ?? '');
    assert.ok(message?.includes('line 2'), message ?? '');
  });
});

function fixtureModel(classifications: Array<Classification | null>): Model {
  return {
    endpoints: classifications.map((value, i) => ({
      service: 'kelta-worker',
      method: 'GET',
      path: `/api/things/${i}`,
      permission: 'API_ACCESS',
      sources: [`ThingController#get${i}`],
      classification: value ? { key: `GET /api/things/${i}`, value } : null,
    })),
    unrouted: [],
  };
}

describe('ratchet', () => {
  const model = fixtureModel([{ docs: '/docs/api/overview/' }, null, null]);

  it('passes when the unclassified count equals the baseline', () => {
    assert.equal(unclassifiedCount(model), 2);
    assert.equal(ratchet(unclassifiedCount(model), 2), null);
  });

  it('fails when the count is above the baseline', () => {
    const message = ratchet(unclassifiedCount(model), 1);
    assert.ok(message?.includes('above the baseline of 1'), message ?? '');
  });

  it('fails with "lower the baseline to N" when the count is below it', () => {
    const message = ratchet(unclassifiedCount(model), 5);
    assert.ok(message?.includes('lower the baseline to 2'), message ?? '');
  });
});

describe('classification checks', () => {
  it('rejects an internal entry without a reason', () => {
    const entries: Record<string, Classification> = { 'GET /api/things/0': { internal: '' } };
    const problems = classificationProblems(fixtureModel([entries['GET /api/things/0']]), entries);
    assert.ok(problems.some((p) => p.includes('needs a reason')), problems.join('\n'));
  });

  it('rejects an entry with both docs and internal, and an unused entry', () => {
    const entries = {
      'GET /api/things/0': { docs: '/docs/api/overview/', internal: 'both at once' },
      'GET /api/nothing': { docs: '/docs/api/overview/' },
    } as unknown as Record<string, Classification>;
    const problems = classificationProblems(fixtureModel([entries['GET /api/things/0']]), entries);
    assert.ok(problems.some((p) => p.includes('exactly one')), problems.join('\n'));
    assert.ok(problems.some((p) => p.startsWith('GET /api/nothing') && p.includes('matches no public endpoint')), problems.join('\n'));
  });

  it('rejects a docs link to a page or heading the site does not publish', () => {
    const pages = new Map([['/docs/api/overview/', new Set(['batch-operations'])]]);
    const broken = brokenDocsLinks(
      {
        'GET /a': { docs: '/docs/api/overview/#batch-operations' },
        'GET /b': { docs: '/docs/api/nowhere/' },
        'GET /c': { docs: '/docs/api/overview/#no-such-heading' },
      },
      pages,
    );
    assert.equal(broken.length, 2);
    assert.ok(broken[0].includes('no such docs page'));
    assert.ok(broken[1].includes('no such heading'));
  });

  it('prefers an exact key, then the longest pattern', () => {
    const entries: Record<string, Classification> = {
      '* /api/flows/**': { docs: '/docs/automation/flows/' },
      '* /api/flows/{id}/versions/**': { docs: '/docs/automation/flow-runs/#versions' },
      'GET /api/flows/{id}/versions': { internal: 'exact key for the fixture' },
    };
    assert.equal(classify({ method: 'GET', path: '/api/flows/{id}/versions' }, entries)?.key, 'GET /api/flows/{id}/versions');
    assert.equal(classify({ method: 'GET', path: '/api/flows/{id}/versions/{n}' }, entries)?.key, '* /api/flows/{id}/versions/**');
    assert.equal(classify({ method: 'POST', path: '/api/flows/{id}/publish' }, entries)?.key, '* /api/flows/**');
    assert.equal(classify({ method: 'POST', path: '/api/flowsx' }, entries), null);
  });
});

describe('parseJavaMappings', () => {
  const JAVA = `
package io.example;

/** Mentions @GetMapping("/javadoc") and is not a mapping. */
@RestController
@RequestMapping("/api/widgets")
public class WidgetController {
    private static final String PERMISSION = "MANAGE_DATA";
    static final String SUB = "/sub";

    // @PostMapping("/commented-out")
    @GetMapping
    public List<Widget> list() { return List.of(); }

    @GetMapping({"/{id:[0-9]+}", "/by-name/{name}"})
    public Widget get(@PathVariable String id) {
        String s = "@DeleteMapping(\\"/in-a-string\\")";
        return null;
    }

    @RequestMapping(value = "/{id}/touch", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public void touch(@PathVariable String id) {
        requireManage();
    }

    @PostMapping(path = SUB + "/run", consumes = "application/json")
    public void run() {
        if (holdsWorkflows()) { }
    }

    @RequestMapping(value = {"/x/{p1}", "/x/{p1}/{p2}", "/x/**"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public void dispatch() { }

    private void requireManage() {
        if (!has(PERMISSION)) throw new IllegalStateException();
    }

    private boolean holdsWorkflows() { return has("MANAGE_WORKFLOWS"); }

    @RestController
    @RequestMapping("/api/inner")
    static class Inner {
        @DeleteMapping("/{id}")
        public void remove() { }
    }
}

@RestController
class Other {
    @RequestMapping("\${spring.web.error.path:/error}")
    public void error() { }
}
`;
  const rows = parseJavaMappings(JAVA, 'Widget.java', 'kelta-worker', PERMISSIONS);
  const keys = rows.map((r) => `${r.method} ${r.path}`);

  it('joins class and method paths, reads arrays, method = and constants', () => {
    assert.deepEqual(keys.sort(), [
      'ANY /error',
      'DELETE /api/inner/{id}',
      'GET /api/widgets',
      'GET /api/widgets/by-name/{name}',
      'GET /api/widgets/x/**',
      'GET /api/widgets/{id}',
      'PATCH /api/widgets/{id}/touch',
      'POST /api/widgets/sub/run',
      'POST /api/widgets/x/**',
      'PUT /api/widgets/{id}/touch',
    ]);
  });

  it('ignores mappings in comments and string literals', () => {
    assert.ok(!keys.some((k) => /javadoc|commented-out|in-a-string/.test(k)));
  });

  it('finds permissions in the handler and its require… helpers only', () => {
    const touch = rows.find((r) => r.path === '/api/widgets/{id}/touch');
    assert.deepEqual(touch?.handlerPermissions, ['MANAGE_DATA']);
    const run = rows.find((r) => r.path === '/api/widgets/sub/run');
    assert.deepEqual(run?.handlerPermissions, [], 'holdsWorkflows is a query, not a gate');
    assert.equal(touch?.controller, 'WidgetController');
    assert.equal(touch?.handler, 'touch');
  });

  it('throws on a mapping value it cannot resolve', () => {
    assert.throws(
      () => parseJavaMappings('@RestController class A { @GetMapping(Paths.UNKNOWN) void a() {} }', 'A.java', 'kelta-worker', PERMISSIONS),
      /cannot resolve mapping value/,
    );
  });
});

describe('gateway table parsers', () => {
  const ROUTES = `
    static final String[][] STATIC_ROUTES = {
                {"flows", "/api/flows/**", "flows"},
                {"me", "/api/me/**", "me"},
                {"webhooks", "/api/webhooks/**", "webhooks"},
                {"ui-pages", "/api/ui-pages/**", "ui-pages"},
        };
    public static final Map<String, StaticCollectionCheck> COLLECTION_CHECKED_STATIC_ROUTES = Map.ofEntries(
            Map.entry("users", new StaticCollectionCheck(true, "MANAGE_USERS")),
            Map.entry("flows", new StaticCollectionCheck(false, "MANAGE_WORKFLOWS",
                    Pattern.compile("^/api/flows/[^/]+/execute$"))));
  `;
  const INIT = `routeRegistry.addRoute(new RouteDefinition(
                    "static-ai", "/api/ai/**", aiServiceUrl, "ai"));`;
  const AUTHZ = `
            if ("static-tenants".equals(collectionId) && StaticCollectionCheck.isWrite(method)) {
                return cerbosService.checkSystemPermission(principal, "MANAGE_TENANTS")`;
  const YML = `
      public-paths: /api/ui-pages,/api/tenants
      unauthenticated-paths: /otel,/api/webhooks   # trailing comment
  `;

  it('parses static routes, collection checks, filter rules and path lists', () => {
    assert.deepEqual(
      parseStaticRoutes(ROUTES, INIT).map((r) => `${r.id}=${r.prefix}`),
      ['flows=/api/flows', 'me=/api/me', 'webhooks=/api/webhooks', 'ui-pages=/api/ui-pages', 'ai=/api/ai'],
    );
    const checks = parseCollectionChecks(ROUTES);
    assert.equal(checks.get('users')?.includeReads, true);
    assert.ok(checks.get('flows')?.exempt?.test('/api/flows/abc/execute'));
    assert.deepEqual(parseFilterWriteRules(AUTHZ), [{ routeId: 'tenants', pathPrefix: null, permission: 'MANAGE_TENANTS' }]);
    assert.deepEqual(parseYamlList(YML, 'unauthenticated-paths'), ['/otel', '/api/webhooks']);
  });

  it('reads permitAll patterns, constants included', () => {
    const src = `
      static final String LOGOUT = "/logout/x";
      http.authorizeHttpRequests(a -> a.requestMatchers("/login", LOGOUT, "/internal/**").permitAll()
          .requestMatchers("/admin").hasRole("A"));`;
    assert.deepEqual(parseAuthPermitAll([src]), ['/login', '/logout/x', '/internal/**']);
  });

  it('decides reachability and the permission per method and path', () => {
    const table: GatewayTable = {
      staticRoutes: parseStaticRoutes(ROUTES, INIT),
      checks: parseCollectionChecks(ROUTES),
      filterWriteRules: [],
      systemCollections: new Set(['picklist-values']),
      unauthenticatedPaths: ['/api/webhooks'],
      publicPaths: ['/api/ui-pages'],
    };
    const mapping = (method: string, path: string, service: SourceMapping['service'] = 'kelta-worker'): SourceMapping => ({
      service,
      controller: 'X',
      handler: `${method}${path}`,
      method,
      path,
      handlerPermissions: path.includes('guarded') ? ['MANAGE_DATA'] : [],
    });
    const { endpoints, unrouted } = buildEndpoints(
      [
        mapping('POST', '/api/flows/{id}/publish'),
        mapping('POST', '/api/flows/{id}/execute'),
        mapping('GET', '/api/flows/{id}/versions'),
        mapping('POST', '/api/webhooks/{flowId}'),
        mapping('GET', '/api/ui-pages/{id}/guarded'),
        mapping('GET', '/api/{collection}/_changes'),
        mapping('POST', '/api/picklist-values'),
        mapping('GET', '/api/ai/usage', 'kelta-ai'),
        mapping('GET', '/internal/bootstrap'),
        mapping('GET', '/api/nothing-routes-this'),
        mapping('GET', '/login', 'kelta-auth'),
        mapping('POST', '/auth/password/change', 'kelta-auth'),
      ],
      table,
      ['/login'],
    );
    const permission = Object.fromEntries(endpoints.map((e) => [`${e.method} ${e.path}`, e.permission]));
    assert.equal(permission['POST /api/flows/{id}/publish'], 'API_ACCESS + flows grant or MANAGE_WORKFLOWS');
    assert.equal(permission['POST /api/flows/{id}/execute'], 'API_ACCESS', 'exempt from the collection check');
    assert.equal(permission['GET /api/flows/{id}/versions'], 'API_ACCESS', 'reads stay API_ACCESS');
    assert.equal(permission['POST /api/webhooks/{flowId}'], 'none (unauthenticated)');
    assert.equal(permission['GET /api/ui-pages/{id}/guarded'], 'none (public read); handler checks MANAGE_DATA');
    assert.equal(permission['GET /api/{collection}/_changes'], 'API_ACCESS + collection grant');
    assert.equal(permission['POST /api/picklist-values'], 'API_ACCESS + collection grant');
    assert.equal(permission['GET /api/ai/usage'], 'API_ACCESS');
    assert.equal(permission['GET /login'], 'none (permitAll)');
    assert.equal(permission['POST /auth/password/change'], 'kelta-auth session');
    assert.deepEqual(unrouted.map((u) => u.path).sort(), ['/api/nothing-routes-this', '/internal/bootstrap']);
  });
});
