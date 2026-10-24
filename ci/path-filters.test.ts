// node --test ci/path-filters.test.ts   (Node >= 24; no dependencies)
//
// Dry run of .github/path-filters.yml against the job triggers in .github/workflows/ci.yml:
// each test job must run when its module's own build file, or a shared build input it reads,
// changes. kelta#1928 bumped Testcontainers in kelta-test-harness/pom.xml, which no filter
// listed, so Integration Tests skipped and the break only showed on main.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, matchesGlob } from 'node:path';
import { describe, it } from 'node:test';

const ROOT = join(import.meta.dirname, '..');

// The subset of YAML path-filters.yml uses: top-level `name:` / `name: &anchor` keys holding
// lists of quoted globs and `*alias` splices (dorny/paths-filter flattens one level).
function parseFilters(text: string): Map<string, string[]> {
  const filters = new Map<string, string[]>();
  const anchors = new Map<string, string[]>();
  let current: string[] | undefined;
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\s+#.*$/, '').trimEnd();
    if (line === '' || line.startsWith('#')) continue;
    const key = /^([\w-]+):(?:\s+&([\w-]+))?$/.exec(line);
    if (key) {
      current = [];
      filters.set(key[1], current);
      if (key[2]) anchors.set(key[2], current);
      continue;
    }
    const item = /^\s+-\s+(.+)$/.exec(line);
    assert.ok(item && current, `unparsed line in path-filters.yml: ${raw}`);
    const value = item[1];
    if (value.startsWith('*')) {
      const spliced = anchors.get(value.slice(1));
      assert.ok(spliced, `unknown alias ${value}`);
      current.push(...spliced);
    } else {
      current.push(value.replace(/^'(.*)'$/, '$1').replace(/^"(.*)"$/, '$1'));
    }
  }
  return filters;
}

const OUTPUT_REF = /needs\.changes\.outputs\.(\w+)\s*==\s*'true'/g;

interface Job {
  outputs: string[];
  matrix: Map<string, string[]>;
}

// Each job's trigger is the OR of the `needs.changes.outputs.<x> == 'true'` terms in its
// job-level `if:`; test-java's matrix entries add their own `changed:` terms.
function parseJobs(text: string): { outputToFilter: Map<string, string>; jobs: Map<string, Job> } {
  const lines = text.split('\n');
  const outputToFilter = new Map<string, string>();
  const jobs = new Map<string, Job>();
  let inJobs = false;
  let job: Job | undefined;
  let jobName = '';
  let inIf = false;
  let service: string | undefined;
  for (const line of lines) {
    if (/^jobs:\s*$/.test(line)) {
      inJobs = true;
      continue;
    }
    if (!inJobs) continue;
    const jobKey = /^ {2}([\w-]+):\s*$/.exec(line);
    if (jobKey) {
      jobName = jobKey[1];
      job = { outputs: [], matrix: new Map() };
      jobs.set(jobName, job);
      inIf = false;
      continue;
    }
    if (!job) continue;
    const output = /^ {6}(\w+): \$\{\{ steps\.filter\.outputs\.(\w+) \}\}/.exec(line);
    if (jobName === 'changes' && output) outputToFilter.set(output[1], output[2]);
    const condition = /^ {4}if:\s*(.*)$/.exec(line);
    if (condition) {
      inIf = true;
      job.outputs.push(...[...condition[1].matchAll(OUTPUT_REF)].map((m) => m[1]));
      continue;
    }
    if (inIf && /^ {6,}\S/.test(line)) {
      job.outputs.push(...[...line.matchAll(OUTPUT_REF)].map((m) => m[1]));
      continue;
    }
    inIf = false;
    const entry = /^ {10}- service: (\S+)/.exec(line);
    if (entry) service = entry[1];
    const changed = /^ {12}changed: (.*)$/.exec(line);
    if (changed && service) {
      job.matrix.set(service, [...changed[1].matchAll(/needs\.changes\.outputs\.(\w+)/g)].map((m) => m[1]));
    }
  }
  return { outputToFilter, jobs };
}

const filters = parseFilters(readFileSync(join(ROOT, '.github/path-filters.yml'), 'utf8'));
const { outputToFilter, jobs } = parseJobs(readFileSync(join(ROOT, '.github/workflows/ci.yml'), 'utf8'));

function filterMatches(filter: string, path: string): boolean {
  const globs = filters.get(filter);
  assert.ok(globs, `no filter named ${filter} in path-filters.yml`);
  return globs.some((glob) => matchesGlob(path, glob));
}

function outputsMatch(outputs: string[], path: string): string[] {
  return outputs.filter((output) => {
    const filter = outputToFilter.get(output);
    assert.ok(filter, `ci.yml reads needs.changes.outputs.${output}, which the changes job does not set`);
    return filterMatches(filter, path);
  });
}

// `workflows` matches only workflow plumbing; a module's build file must trigger its job on its own.
function triggeredBy(jobName: string, path: string): string[] {
  const job = jobs.get(jobName);
  assert.ok(job, `no job ${jobName} in ci.yml`);
  return outputsMatch(job.outputs.filter((o) => o !== 'workflows'), path);
}

function assertTriggers(jobName: string, path: string) {
  assert.ok(triggeredBy(jobName, path).length > 0, `${path} does not trigger ${jobName} (if: ${jobs.get(jobName)?.outputs.join(' || ')})`);
}

function assertMatrixTriggers(service: string, path: string) {
  assertTriggers('test-java', path);
  const outputs = jobs.get('test-java')?.matrix.get(service);
  assert.ok(outputs, `no test-java matrix entry for ${service}`);
  assert.ok(outputsMatch(outputs, path).length > 0, `${path} does not set test-java matrix ${service}.changed`);
}

const modulePoms = readdirSync(join(ROOT, 'kelta-modules'), { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => `kelta-modules/${d.name}/pom.xml`);

describe('parsing', () => {
  it('reads the filters and every job trigger', () => {
    assert.deepEqual(filters.get('gateway'), ['kelta-platform/**', 'docs/authoring/system-collections.md', 'kelta-gateway/**']);
    assert.deepEqual(jobs.get('test-java')?.outputs, ['integration', 'workflows']);
    assert.deepEqual([...(jobs.get('test-java')?.matrix.keys() ?? [])], ['gateway', 'worker', 'auth', 'ai', 'mcp']);
    assert.ok(jobs.get('e2e')?.outputs.includes('e2e'));
  });

  it('every filter output the changes job sets names a filter', () => {
    for (const [output, filter] of outputToFilter) {
      assert.ok(filters.has(filter), `changes output ${output} reads unknown filter ${filter}`);
    }
  });
});

describe('integration tests', () => {
  for (const path of ['kelta-test-harness/pom.xml', 'kelta-test-harness/src/test/java/io/kelta/harness/AnyTest.java', 'ci/maven-settings.xml']) {
    it(`${path} matches integration and starts both integration-tests and the test-java job it needs`, () => {
      assert.ok(filterMatches('integration', path));
      assert.ok(jobs.get('integration-tests')?.outputs.includes('integration'));
      assertTriggers('integration-tests', path);
      assertTriggers('test-java', path);
    });
  }

  it('integration matches everything any_java matches', () => {
    for (const glob of filters.get('any_java') ?? []) {
      assert.ok(filters.get('integration')?.includes(glob), `integration is missing any_java's ${glob}`);
    }
  });

  it('any_java (which also publishes images) does not match the harness', () => {
    assert.equal(filterMatches('any_java', 'kelta-test-harness/pom.xml'), false);
    assert.equal(filterMatches('any_java', 'ci/maven-settings.xml'), false);
  });
});

describe('each Java module pom triggers the job that tests it', () => {
  it('kelta-platform/pom.xml → test-runtime, test-runtime-modules and every test-java service', () => {
    assertTriggers('test-runtime', 'kelta-platform/pom.xml');
    assertTriggers('test-runtime-modules', 'kelta-platform/pom.xml');
    assertTriggers('integration-tests', 'kelta-platform/pom.xml');
    for (const service of ['gateway', 'worker', 'auth', 'ai', 'mcp']) assertMatrixTriggers(service, 'kelta-platform/pom.xml');
  });

  for (const service of ['gateway', 'worker', 'auth', 'ai', 'mcp']) {
    it(`kelta-${service}/pom.xml → test-java ${service} and integration-tests`, () => {
      assertMatrixTriggers(service, `kelta-${service}/pom.xml`);
      assertTriggers('integration-tests', `kelta-${service}/pom.xml`);
    });
  }

  for (const pom of [...modulePoms, 'kelta-modules/not-yet-written/pom.xml']) {
    it(`${pom} → test-runtime-modules`, () => assertTriggers('test-runtime-modules', pom));
  }
});

describe('ci/maven-settings.xml triggers every Maven job', () => {
  for (const job of ['test-runtime', 'test-runtime-modules', 'integration-tests']) {
    it(job, () => assertTriggers(job, 'ci/maven-settings.xml'));
  }
  for (const service of ['gateway', 'worker', 'auth', 'ai', 'mcp']) {
    it(`test-java ${service}`, () => assertMatrixTriggers(service, 'ci/maven-settings.xml'));
  }
});

describe('frontend and node build files', () => {
  it('kelta-web/package.json matches web and triggers test-frontend', () => {
    assert.ok(filterMatches('web', 'kelta-web/package.json'));
    assertTriggers('test-frontend', 'kelta-web/package.json');
  });

  const cases: [string, string][] = [
    ['test-frontend', 'kelta-web/package-lock.json'],
    ['test-frontend', 'kelta-ui/app/package.json'],
    ['test-frontend', 'kelta-ui/app/package-lock.json'],
    ['e2e', 'e2e-tests/package.json'],
    ['e2e', 'e2e-tests/package-lock.json'],
    ['test-marketing', 'kelta-marketing/package.json'],
    ['test-marketing', 'kelta-marketing/package-lock.json'],
    ['test-dora', 'scripts/dora/lib.mjs'],
    ['test-ci-scripts', 'ci/path-filters.test.ts'],
    ['test-ci-scripts', '.github/path-filters.yml'],
  ];
  for (const [job, path] of cases) {
    it(`${path} → ${job}`, () => assertTriggers(job, path));
  }
});

describe('unrelated changes stay cheap', () => {
  it('a docs-only change starts neither test-java nor integration-tests', () => {
    assert.deepEqual(triggeredBy('test-java', '.claude/docs/testing.md'), []);
    assert.deepEqual(triggeredBy('integration-tests', '.claude/docs/testing.md'), []);
  });
});
