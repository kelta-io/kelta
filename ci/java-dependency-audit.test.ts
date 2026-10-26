// node --test ci/java-dependency-audit.test.ts   (Node >= 24; no dependencies)
//
// Drives ci/java-dependency-audit.mjs against a throwaway tree (JAVA_AUDIT_ROOT) with a fake
// osv-scanner that replays canned JSON, so the gate's decisions are checked without Maven or
// network: new advisories fail, baselined ones pass (by any of their ids), labels without a score
// still block, every baselined id needs a reason, an empty SBOM or a scanner error fails, the
// OpenTelemetry agent pinned in a Dockerfile is scanned, and --update keeps only live reasons.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

const SCRIPT = join(import.meta.dirname, 'java-dependency-audit.mjs');
const SERVICES = ['gateway', 'worker', 'auth', 'ai', 'mcp'];

/** One advisory group: ids[0] is the primary id; label goes on labelOn (default ids[0]). */
type Advisory = { ids: string[]; score?: string; label?: string; labelOn?: string; pkg?: string };

/**
 * A tree with one SBOM per service and a fake osv-scanner answering per target (the SBOM file's
 * basename). `agent` writes a Dockerfile pinning that OpenTelemetry agent version into kelta-ai.
 */
function tree(
  perTarget: Record<string, Advisory[]>,
  opts: { components?: number; scannerExit?: number; agent?: string; aiDockerfile?: string } = {}
) {
  const root = mkdtempSync(join(tmpdir(), 'java-audit-'));
  mkdirSync(join(root, 'ci'));
  for (const svc of SERVICES) {
    mkdirSync(join(root, `kelta-${svc}`, 'target'), { recursive: true });
    const components = Array.from({ length: opts.components ?? 3 }, (_, i) => ({ purl: `pkg:maven/x/y${i}@1` }));
    writeFileSync(join(root, `kelta-${svc}`, 'target', `${svc}.cdx.json`), JSON.stringify({ components }));
  }
  if (opts.aiDockerfile !== undefined) {
    writeFileSync(join(root, 'kelta-ai', 'Dockerfile'), opts.aiDockerfile);
  } else if (opts.agent) {
    writeFileSync(
      join(root, 'kelta-ai', 'Dockerfile'),
      `RUN curl -fL -o opentelemetry-javaagent.jar \\\n    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${opts.agent}/opentelemetry-javaagent.jar\n`
    );
  }
  for (const target of [...SERVICES, 'otel-javaagent']) {
    const packages = (perTarget[target] ?? []).map((a) => ({
      package: { name: a.pkg ?? 'org.example:lib', version: '1.0' },
      vulnerabilities: a.ids.map((id) => ({
        id,
        summary: `summary of ${id}`,
        database_specific: a.label && id === (a.labelOn ?? a.ids[0]) ? { severity: a.label } : {},
      })),
      groups: [{ ids: a.ids, max_severity: a.score ?? '' }],
    }));
    writeFileSync(join(root, `osv-${target}.json`), JSON.stringify({ results: packages.length ? [{ packages }] : [] }));
  }
  const fake = join(root, 'fake-osv.sh');
  // The script calls: osv-scanner scan source -L <sbom path> --format json
  writeFileSync(
    fake,
    `#!/bin/sh\ntarget=$(basename "$4" .cdx.json)\n` +
      (opts.scannerExit && opts.scannerExit !== 1
        ? `echo "boom" >&2\nexit ${opts.scannerExit}\n`
        : `cat "${root}/osv-$target.json"\ngrep -q '"packages"' "${root}/osv-$target.json" && exit 1\nexit 0\n`)
  );
  chmodSync(fake, 0o755);
  return root;
}

function run(root: string, ...args: string[]) {
  const r = spawnSync(process.execPath, [SCRIPT, '--no-sbom', ...args], {
    env: { ...process.env, JAVA_AUDIT_ROOT: root, OSV_SCANNER: join(root, 'fake-osv.sh') },
    encoding: 'utf8',
  });
  return { status: r.status, out: `${r.stdout}${r.stderr}` };
}

/** Writes a baseline; every listed id gets a reason unless `reasons` is given explicitly. */
function baseline(root: string, services: Record<string, Record<string, string>>, reasons?: Record<string, string>) {
  const auto = Object.fromEntries(Object.values(services).flatMap((m) => Object.keys(m)).map((id) => [id, 'reason']));
  writeFileSync(join(root, 'ci', 'java-audit-baseline.json'), JSON.stringify({ _reasons: reasons ?? auto, services }));
}

function readBaseline(root: string) {
  return JSON.parse(readFileSync(join(root, 'ci', 'java-audit-baseline.json'), 'utf8'));
}

describe('java-dependency-audit', () => {
  it('fails when the baseline is missing', () => {
    const r = run(tree({}));
    assert.equal(r.status, 1);
    assert.match(r.out, /Baseline missing/);
  });

  it('passes with no advisories', () => {
    const root = tree({});
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 0, r.out);
    assert.match(r.out, /OK: no new high\/critical/);
  });

  it('fails on a new high advisory and names it', () => {
    const root = tree({ worker: [{ ids: ['GHSA-new1'], score: '7.5', pkg: 'org.example:http' }] });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /NEW\s+GHSA-new1\s+high \| org\.example:http@1\.0/);
  });

  it('passes when the advisory is in the baseline for that target', () => {
    const root = tree({ auth: [{ ids: ['GHSA-known'], score: '9.3' }] });
    baseline(root, { auth: { 'GHSA-known': 'critical | x' } });
    assert.equal(run(root).status, 0);
  });

  it('a baseline entry for another target does not cover this one', () => {
    const root = tree({ worker: [{ ids: ['GHSA-known'], score: '9.3' }] });
    baseline(root, { auth: { 'GHSA-known': 'critical | x' } });
    assert.equal(run(root).status, 1);
  });

  it('matches a baselined advisory by any of its ids (a new alias sorting first is not "new")', () => {
    const root = tree({ worker: [{ ids: ['CVE-2026-1', 'GHSA-alias'], score: '7.5' }] });
    baseline(root, { worker: { 'GHSA-alias': 'high | x' } });
    const r = run(root);
    assert.equal(r.status, 0, r.out);
    assert.doesNotMatch(r.out, /resolved GHSA-alias/);
  });

  it('ignores medium advisories', () => {
    const root = tree({ gateway: [{ ids: ['GHSA-medium'], score: '5.3' }] });
    baseline(root, {});
    assert.equal(run(root).status, 0);
  });

  it('blocks a label-only HIGH with no CVSS score', () => {
    const root = tree({ ai: [{ ids: ['GHSA-label'], label: 'HIGH' }] });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /NEW\s+GHSA-label\s+high/);
  });

  it('reads a HIGH label on a non-first id of the group', () => {
    const root = tree({ ai: [{ ids: ['CVE-2026-2', 'GHSA-second'], label: 'HIGH', labelOn: 'GHSA-second' }] });
    baseline(root, {});
    assert.match(run(root).out, /NEW\s+CVE-2026-2\s+high/);
  });

  it('a CRITICAL label wins over a lower score', () => {
    const root = tree({ ai: [{ ids: ['GHSA-crit'], score: '7.1', label: 'CRITICAL' }] });
    baseline(root, {});
    assert.match(run(root).out, /NEW\s+GHSA-crit\s+critical/);
  });

  it('reports a baselined advisory that is gone as resolved', () => {
    const root = tree({});
    baseline(root, { mcp: { 'GHSA-gone': 'high | x' } });
    const r = run(root);
    assert.equal(r.status, 0);
    assert.match(r.out, /resolved GHSA-gone/);
  });

  it('fails when a baselined id has no line in _reasons', () => {
    const root = tree({ auth: [{ ids: ['GHSA-silent'], score: '8.1' }] });
    baseline(root, { auth: { 'GHSA-silent': 'high | x' } }, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /NO REASON GHSA-silent/);
  });

  it('fails on an SBOM with no components instead of passing vacuously', () => {
    const root = tree({}, { components: 0 });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /no packages in/);
  });

  for (const exit of [127, 128]) {
    it(`fails when osv-scanner exits ${exit}`, () => {
      const root = tree({}, { scannerExit: exit });
      baseline(root, {});
      const r = run(root);
      assert.notEqual(r.status, 0);
      assert.match(r.out, new RegExp(`osv-scanner failed .*exit ${exit}`));
    });
  }

  it('scans the OpenTelemetry agent version pinned in a Dockerfile as its own target', () => {
    const root = tree({ 'otel-javaagent': [{ ids: ['GHSA-agent'], score: '9.8', pkg: 'io.opentelemetry.javaagent:opentelemetry-javaagent' }] }, { agent: '2.25.0' });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /otel-javaagent \(Dockerfile -javaagent\): 1 packages/);
    assert.match(r.out, /NEW\s+GHSA-agent\s+critical \| io\.opentelemetry\.javaagent:opentelemetry-javaagent@1\.0/);
    const sbom = JSON.parse(readFileSync(join(root, 'target', 'java-audit', 'otel-javaagent.cdx.json'), 'utf8'));
    assert.equal(sbom.components[0].purl, 'pkg:maven/io.opentelemetry.javaagent/opentelemetry-javaagent@2.25.0');
  });

  const AGENT_URL = 'https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download';

  it('resolves an ARG-style agent version through the ARG default', () => {
    const root = tree({}, { aiDockerfile: `ARG OTEL_AGENT_VERSION=2.26.1\nRUN curl -fL -o a.jar ${AGENT_URL}/v\${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar\n` });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 0, r.out);
    const sbom = JSON.parse(readFileSync(join(root, 'target', 'java-audit', 'otel-javaagent.cdx.json'), 'utf8'));
    assert.equal(sbom.components[0].version, '2.26.1');
  });

  it('fails when an ARG-style agent version has no default', () => {
    const root = tree({}, { aiDockerfile: `RUN curl -fL -o a.jar ${AGENT_URL}/v$OTEL_AGENT_VERSION/opentelemetry-javaagent.jar\n` });
    baseline(root, {});
    const r = run(root);
    assert.notEqual(r.status, 0);
    assert.match(r.out, /no "ARG OTEL_AGENT_VERSION=<version>" default/);
  });

  it('fails when a Dockerfile uses the agent in a form it cannot read, instead of dropping the target', () => {
    const root = tree({}, { aiDockerfile: 'COPY vendor/opentelemetry-javaagent.jar /app/\nENV JAVA_OPTS="-javaagent:/app/opentelemetry-javaagent.jar"\n' });
    baseline(root, {});
    const r = run(root);
    assert.notEqual(r.status, 0);
    assert.match(r.out, /references the OpenTelemetry agent but no pinned version/);
  });

  it('ignores a commented-out agent reference', () => {
    const root = tree({}, { aiDockerfile: '# no -javaagent:/app/opentelemetry-javaagent.jar here on purpose\n' });
    baseline(root, {});
    assert.equal(run(root).status, 0);
  });

  it('reports the advisories of a target no longer scanned as resolved', () => {
    const root = tree({});
    baseline(root, { 'otel-javaagent': { 'GHSA-agent': 'critical | x' } });
    const r = run(root);
    assert.equal(r.status, 0, r.out);
    assert.match(r.out, /resolved GHSA-agent\s+\(otel-javaagent is no longer scanned/);
  });

  it('counts a missing reason once per id, not once per target', () => {
    const root = tree({ worker: [{ ids: ['GHSA-both'], score: '7.5' }], auth: [{ ids: ['GHSA-both'], score: '7.5' }] });
    baseline(root, { worker: { 'GHSA-both': 'high | x' }, auth: { 'GHSA-both': 'high | x' } }, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.equal(r.out.match(/NO REASON GHSA-both/g)?.length, 1);
    assert.match(r.out, /FAIL: 1 baselined advisory/);
  });

  it('skips the agent target when no Dockerfile pins an agent', () => {
    const root = tree({});
    baseline(root, {});
    assert.doesNotMatch(run(root).out, /otel-javaagent/);
  });

  it('--update writes current advisories, keeps live reasons and drops stale ones', () => {
    const root = tree({ auth: [{ ids: ['GHSA-keep'], score: '8.7' }] });
    baseline(root, {}, { 'GHSA-keep': 'why we accept it', 'GHSA-stale': 'no longer present' });
    const r = run(root, '--update');
    assert.equal(r.status, 0, r.out);
    const written = readBaseline(root);
    assert.deepEqual(written._reasons, { 'GHSA-keep': 'why we accept it' });
    assert.match(written.services.auth['GHSA-keep'], /^high \| /);
  });

  it('--update names the ids that still need a reason', () => {
    const root = tree({ worker: [{ ids: ['GHSA-needs'], score: '7.0' }] });
    baseline(root, {});
    assert.match(run(root, '--update').out, /Add a line to _reasons for: GHSA-needs/);
  });
});
