// node --test ci/path-filters.test.ts   (Node >= 24; no dependencies)
//
// A dry run of .github/path-filters.yml against the ci.yml jobs it gates: each test job must be
// triggered by its module's own build file and by the shared build inputs it reads. kelta#1928
// bumped Testcontainers in kelta-test-harness/pom.xml only, no filter listed the harness, and the
// Integration Tests job skipped and merged a broken harness.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, matchesGlob } from 'node:path';
import { describe, it } from 'node:test';

const ROOT = join(import.meta.dirname, '..');
const FILTERS_FILE = join(ROOT, '.github/path-filters.yml');
const CI_FILE = join(ROOT, '.github/workflows/ci.yml');

/**
 * The subset of YAML the filters file uses: top-level `name:` / `name: &anchor` keys, list items
 * that are a quoted or bare glob, and `- *alias` items that splice an anchored list in place.
 */
export function parseFilters(text: string): Map<string, string[]> {
  const filters = new Map<string, string[]>();
  const anchors = new Map<string, string[]>();
  let current: string[] | undefined;
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\s+#.*$/, '').trimEnd();
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue;
    const key = /^([\w-]+):\s*(?:&([\w-]+))?$/.exec(line);
    if (key) {
      current = [];
      filters.set(key[1], current);
      if (key[2]) anchors.set(key[2], current);
      continue;
    }
    const item = /^\s+-\s+(.+)$/.exec(line);
    assert.ok(item && current, `unparsed line in path-filters.yml: ${raw}`);
    const value = item[1].trim();
    if (value.startsWith('*')) {
      const spliced = anchors.get(value.slice(1));
      assert.ok(spliced, `unknown alias ${value}`);
      current.push(...spliced);
    } else {
      current.push(value.replace(/^(['"])(.*)\1$/, '$2'));
    }
  }
  return filters;
}

/** The `needs.changes.outputs.<filter>` names in each job's own (4-space) `if:`. */
export function jobGates(text: string): Map<string, string[]> {
  const gates = new Map<string, string[]>();
  const lines = text.split('\n');
  const start = lines.indexOf('jobs:');
  let job: string | undefined;
  let inIf = false;
  for (const line of lines.slice(start + 1)) {
    const jobKey = /^ {2}([\w-]+):\s*$/.exec(line);
    if (jobKey) {
      job = jobKey[1];
      gates.set(job, []);
      inIf = false;
      continue;
    }
    if (!job) continue;
    if (/^ {4}if:/.test(line)) inIf = true;
    else if (/^ {0,4}\S/.test(line)) inIf = false;
    if (inIf) {
      for (const m of line.matchAll(/needs\.changes\.outputs\.(\w+)/g)) gates.get(job)!.push(m[1]);
    }
  }
  return gates;
}

/** The filters behind each test-java matrix entry's `changed:`. */
export function matrixGates(text: string): Map<string, string[]> {
  const gates = new Map<string, string[]>();
  for (const m of text.matchAll(/- service: (\w+)\n\s+changed: (.+)\n/g)) {
    gates.set(m[1], [...m[2].matchAll(/needs\.changes\.outputs\.(\w+)/g)].map((o) => o[1]));
  }
  return gates;
}

const filters = parseFilters(readFileSync(FILTERS_FILE, 'utf8'));
const ciText = readFileSync(CI_FILE, 'utf8');
const gates = jobGates(ciText);
const services = matrixGates(ciText);

function matchingFilters(path: string): string[] {
  return [...filters].filter(([, globs]) => globs.some((g) => matchesGlob(path, g))).map(([name]) => name);
}

function matches(path: string, filter: string): boolean {
  const globs = filters.get(filter);
  assert.ok(globs, `no filter named ${filter}`);
  return globs.some((g) => matchesGlob(path, g));
}

/** Whether a PR touching only `path` runs `job`, not counting the `workflows` catch-all. */
function triggers(path: string, job: string): boolean {
  const names = gates.get(job);
  assert.ok(names?.length, `job ${job} has no path-filter gate in ci.yml`);
  return names.filter((n) => n !== 'workflows').some((n) => matches(path, n));
}

describe('parseFilters', () => {
  it('splices anchored lists and strips quotes', () => {
    const parsed = parseFilters(
      ["# c", "a: &a", "  - 'x/**'", 'b:', '  - *a', '  - "y.txt"  # trailing', ''].join('\n'),
    );
    assert.deepEqual(parsed.get('a'), ['x/**']);
    assert.deepEqual(parsed.get('b'), ['x/**', 'y.txt']);
  });
});

describe('integration-tests', () => {
  for (const path of ['kelta-test-harness/pom.xml', 'kelta-test-harness/src/test/java/io/kelta/X.java', 'ci/maven-settings.xml']) {
    it(`${path} matches the integration filter and runs integration-tests and the test-java job it needs`, () => {
      assert.ok(matches(path, 'integration'), `${path} matches: ${matchingFilters(path).join(', ')}`);
      assert.deepEqual(gates.get('integration-tests')!.filter((n) => n !== 'workflows'), ['integration']);
      assert.ok(triggers(path, 'integration-tests'));
      assert.ok(triggers(path, 'test-java'), 'integration-tests needs test-java; a skipped test-java skips it');
    });
  }

  it('matches everything any_java matches', () => {
    const integration = new Set(filters.get('integration'));
    for (const glob of filters.get('any_java')!) assert.ok(integration.has(glob), `integration lacks ${glob}`);
  });

  it('leaves the harness out of any_java, which gates image builds', () => {
    assert.ok(!matches('kelta-test-harness/pom.xml', 'any_java'));
    assert.ok(!matches('ci/maven-settings.xml', 'any_java'));
  });
});

describe('each Java module build file triggers the job that tests it', () => {
  for (const service of ['gateway', 'worker', 'auth', 'ai', 'mcp']) {
    for (const path of [`kelta-${service}/pom.xml`, 'kelta-platform/pom.xml', 'ci/maven-settings.xml']) {
      it(`${path} runs test-java's ${service} entry`, () => {
        assert.ok(triggers(path, 'test-java'));
        const entry = services.get(service);
        assert.ok(entry, `no test-java matrix entry for ${service}`);
        assert.ok(entry.some((n) => matches(path, n)), `${service} entry gated on ${entry.join(', ')}`);
      });
    }
    it(`kelta-${service}/pom.xml runs integration-tests`, () => {
      assert.ok(triggers(`kelta-${service}/pom.xml`, 'integration-tests'));
    });
  }

  for (const path of ['kelta-platform/pom.xml', 'kelta-platform/runtime/runtime-core/pom.xml', 'ci/maven-settings.xml']) {
    it(`${path} runs test-runtime`, () => assert.ok(triggers(path, 'test-runtime')));
  }

  for (const path of ['kelta-modules/billing/pom.xml', 'kelta-modules/some-new-module/pom.xml', 'kelta-platform/pom.xml', 'ci/maven-settings.xml']) {
    it(`${path} runs test-runtime-modules`, () => assert.ok(triggers(path, 'test-runtime-modules')));
  }
});

describe('frontend and other test jobs', () => {
  it('kelta-web/package.json matches web and runs test-frontend', () => {
    assert.ok(matches('kelta-web/package.json', 'web'));
    assert.ok(triggers('kelta-web/package.json', 'test-frontend'));
    assert.ok(triggers('kelta-web/package-lock.json', 'test-frontend'));
  });

  it('kelta-ui/app/package.json runs test-frontend', () => {
    assert.ok(triggers('kelta-ui/app/package.json', 'test-frontend'));
  });

  for (const job of ['templates', 'mcp-replay']) {
    for (const path of ['kelta-web/package.json', 'kelta-web/package-lock.json', 'kelta-web/packages/cli/package.json']) {
      it(`${path} runs ${job}, which builds the kelta CLI it drives`, () => assert.ok(triggers(path, job)));
    }
  }

  const ownBuildFiles: Array<[string, string]> = [
    ['e2e', 'e2e-tests/package.json'],
    ['e2e', 'kelta-worker/pom.xml'],
    ['quickstart', 'docker-compose.yml'],
    ['test-marketing', 'kelta-marketing/package.json'],
    ['test-dora', 'scripts/dora/lib.mjs'],
    ['test-ci-scripts', 'ci/path-filters.test.ts'],
    ['test-ci-scripts', '.github/path-filters.yml'],
    // The Java dependency audit resolves every service's shipped classpath: a pom change anywhere
    // in it, the shared Maven settings, or its own script/baseline must re-run it.
    ['dependency-audit-java', 'kelta-worker/pom.xml'],
    ['dependency-audit-java', 'kelta-auth/pom.xml'],
    ['dependency-audit-java', 'kelta-platform/runtime/runtime-core/pom.xml'],
    ['dependency-audit-java', 'ci/maven-settings.xml'],
    ['dependency-audit-java', 'ci/java-dependency-audit.mjs'],
    ['dependency-audit-java', 'ci/java-audit-baseline.json'],
  ];
  for (const [job, path] of ownBuildFiles) {
    it(`${path} runs ${job}`, () => assert.ok(triggers(path, job)));
  }
});
