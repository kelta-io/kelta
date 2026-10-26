// node --test ci/java-dependency-audit.test.ts   (Node >= 24; no dependencies)
//
// Drives ci/java-dependency-audit.mjs against a throwaway tree (JAVA_AUDIT_ROOT) with a fake
// osv-scanner that replays canned JSON, so the gate's decisions are checked without Maven or
// network: new advisories fail, baselined ones pass, a label-only HIGH blocks, an empty SBOM or
// a scanner error fails, and --update keeps the hand-written _reasons.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

const SCRIPT = join(import.meta.dirname, 'java-dependency-audit.mjs');
const SERVICES = ['gateway', 'worker', 'auth', 'ai', 'mcp'];

type Advisory = { id: string; score?: string; label?: string; pkg?: string };

/** A tree with one SBOM per service and a fake osv-scanner answering per service. */
function tree(perService: Record<string, Advisory[]>, opts: { components?: number; scannerExit?: number } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'java-audit-'));
  mkdirSync(join(root, 'ci'));
  for (const svc of SERVICES) {
    mkdirSync(join(root, `kelta-${svc}`, 'target'), { recursive: true });
    const components = Array.from({ length: opts.components ?? 3 }, (_, i) => ({ purl: `pkg:maven/x/y${i}@1` }));
    writeFileSync(join(root, `kelta-${svc}`, 'target', `${svc}.cdx.json`), JSON.stringify({ components }));
    const advisories = perService[svc] ?? [];
    const packages = advisories.map((a) => ({
      package: { name: a.pkg ?? 'org.example:lib', version: '1.0' },
      vulnerabilities: [{ id: a.id, summary: `summary of ${a.id}`, database_specific: a.label ? { severity: a.label } : {} }],
      groups: [{ ids: [a.id], max_severity: a.score ?? '' }],
    }));
    writeFileSync(join(root, `osv-${svc}.json`), JSON.stringify({ results: packages.length ? [{ packages }] : [] }));
  }
  const fake = join(root, 'fake-osv.sh');
  // The script calls: osv-scanner scan source -L <root>/kelta-<svc>/target/<svc>.cdx.json --format json
  writeFileSync(
    fake,
    `#!/bin/sh\nsvc=$(basename "$4" .cdx.json)\n` +
      (opts.scannerExit && opts.scannerExit !== 1
        ? `echo "boom" >&2\nexit ${opts.scannerExit}\n`
        : `cat "${root}/osv-$svc.json"\n[ -s "${root}/osv-$svc.json" ] && grep -q '"packages"' "${root}/osv-$svc.json" && exit 1\nexit 0\n`)
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

function baseline(root: string, services: Record<string, Record<string, string>>, reasons: Record<string, string> = {}) {
  writeFileSync(join(root, 'ci', 'java-audit-baseline.json'), JSON.stringify({ _reasons: reasons, services }));
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
    const root = tree({ worker: [{ id: 'GHSA-new1', score: '7.5', pkg: 'org.example:http' }] });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /NEW\s+GHSA-new1\s+high \| org\.example:http@1\.0/);
  });

  it('passes when the advisory is in the baseline for that service', () => {
    const root = tree({ auth: [{ id: 'GHSA-known', score: '9.3' }] });
    baseline(root, { auth: { 'GHSA-known': 'critical | x' } });
    assert.equal(run(root).status, 0);
  });

  it('a baseline entry for another service does not cover this one', () => {
    const root = tree({ worker: [{ id: 'GHSA-known', score: '9.3' }] });
    baseline(root, { auth: { 'GHSA-known': 'critical | x' } });
    assert.equal(run(root).status, 1);
  });

  it('ignores medium advisories', () => {
    const root = tree({ gateway: [{ id: 'GHSA-medium', score: '5.3' }] });
    baseline(root, {});
    assert.equal(run(root).status, 0);
  });

  it('blocks a label-only HIGH with no CVSS score', () => {
    const root = tree({ ai: [{ id: 'GHSA-label', label: 'HIGH' }] });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /NEW\s+GHSA-label\s+high/);
  });

  it('reports a baselined advisory that is gone as resolved', () => {
    const root = tree({});
    baseline(root, { mcp: { 'GHSA-gone': 'high | x' } });
    const r = run(root);
    assert.equal(r.status, 0);
    assert.match(r.out, /resolved GHSA-gone/);
  });

  it('fails on an SBOM with no components instead of passing vacuously', () => {
    const root = tree({}, { components: 0 });
    baseline(root, {});
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.out, /read no packages/);
  });

  it('fails when osv-scanner errors (exit other than 0/1)', () => {
    const root = tree({}, { scannerExit: 127 });
    baseline(root, {});
    const r = run(root);
    assert.notEqual(r.status, 0);
    assert.match(r.out, /osv-scanner failed/);
  });

  it('--update writes current advisories and keeps _reasons', () => {
    const root = tree({ auth: [{ id: 'GHSA-keep', score: '8.7' }] });
    baseline(root, {}, { 'GHSA-keep': 'why we accept it' });
    assert.equal(run(root, '--update').status, 0);
    const written = JSON.parse(readFileSync(join(root, 'ci', 'java-audit-baseline.json'), 'utf8'));
    assert.equal(written._reasons['GHSA-keep'], 'why we accept it');
    assert.match(written.services.auth['GHSA-keep'], /^high \| /);
  });
});
