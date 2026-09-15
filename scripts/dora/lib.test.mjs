import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  classifyArgoCommit, parseArgoLog, buildDeployments, attachChanges, percentile,
  pairStateTransitions, runsFromAlertSeries, band, summarize, stableStringify, normalizePr, filterIncidents,
} from './lib.mjs';

const T0 = 1_757_800_000; // 2025-09-13T21:46:40Z-ish; only relative order matters
const line = (hash, offsetSec, author, subject) => `${hash}\t${T0 + offsetSec}\t${author}\t${subject}`;
const iso = (offsetSec) => new Date((T0 + offsetSec) * 1000).toISOString();

test('classifyArgoCommit: bump, auto rollback, manual revert, re-apply, noise', () => {
  assert.deepEqual(
    classifyArgoCommit(line('a1', 0, 'github-actions[bot]', 'chore: update Kelta images to main-f55dd48')),
    { hash: 'a1', at: iso(0), author: 'github-actions[bot]', subject: 'chore: update Kelta images to main-f55dd48', kind: 'bump', sha: 'f55dd48', redeploy: false },
  );
  const auto = classifyArgoCommit(line('a2', 10, 'github-actions[bot]', 'revert: roll back image bump (smoke-test failed in run 33519396092)'));
  assert.equal(auto.kind, 'rollback');
  assert.equal(auto.automatic, true);
  assert.equal(auto.runId, '33519396092');

  const manual = classifyArgoCommit(line('a3', 20, 'Craig', 'revert(emf): roll gateway and auth back to main-74a8b86'));
  assert.equal(manual.kind, 'rollback');
  assert.equal(manual.automatic, false);

  const reapply = classifyArgoCommit(line('a4', 30, 'Craig', 'revert: re-apply the main-ad72c6c image bump'));
  assert.equal(reapply.kind, 'bump');
  assert.equal(reapply.redeploy, true);
  assert.equal(reapply.sha, 'ad72c6c');

  assert.equal(classifyArgoCommit(line('a5', 40, 'Craig', 'fix(emf): worker keeps service.namespace (#297)')), null);
  assert.equal(classifyArgoCommit(''), null);
});

test('parseArgoLog reverses newest-first git output into oldest-first events', () => {
  const text = [
    line('c', 20, 'github-actions[bot]', 'chore: update Kelta images to main-ccccccc'),
    line('b', 10, 'github-actions[bot]', 'chore: update Kelta images to main-bbbbbbb'),
    line('a', 0, 'github-actions[bot]', 'chore: update Kelta images to main-aaaaaaa'),
  ].join('\n');
  assert.deepEqual(parseArgoLog(text).map((e) => e.sha), ['aaaaaaa', 'bbbbbbb', 'ccccccc']);
});

test('buildDeployments attaches a rollback to the latest un-rolled-back bump only', () => {
  const events = parseArgoLog([
    line('r2', 50, 'Craig', 'revert: manual'),
    line('d3', 40, 'github-actions[bot]', 'chore: update Kelta images to main-3333333'),
    line('r1', 30, 'github-actions[bot]', 'revert: roll back image bump (smoke-test failed in run 1)'),
    line('d2', 20, 'github-actions[bot]', 'chore: update Kelta images to main-2222222'),
    line('d1', 10, 'github-actions[bot]', 'chore: update Kelta images to main-1111111'),
  ].join('\n'));
  const deps = buildDeployments(events);
  assert.deepEqual(deps.map((d) => [d.sha, d.status]), [
    ['1111111', 'deployed'], ['2222222', 'rolled_back'], ['3333333', 'rolled_back'],
  ]);
  assert.equal(deps[1].rollback.automatic, true);
  assert.equal(deps[2].rollback.automatic, false);
});

test('attachChanges ships each PR in the first deployment at/after its merge commit', () => {
  const main = ['1111111aaaa', '2222222bbbb', '3333333cccc', '4444444dddd', '5555555eeee'];
  const deployments = buildDeployments(parseArgoLog([
    line('d2', 400, 'github-actions[bot]', 'chore: update Kelta images to main-4444444'),
    line('d1', 200, 'github-actions[bot]', 'chore: update Kelta images to main-2222222'),
  ].join('\n')));
  const prs = [
    { number: 1, mergeSha: '1111111aaaa', mergedAt: iso(100), firstCommitAt: iso(0) },
    { number: 2, mergeSha: '2222222bbbb', mergedAt: iso(150), firstCommitAt: iso(120) },
    { number: 3, mergeSha: '3333333cccc', mergedAt: iso(250), firstCommitAt: iso(50) },   // cancelled build → rides d2
    { number: 5, mergeSha: '5555555eeee', mergedAt: iso(500), firstCommitAt: iso(450) },  // not deployed yet
    { number: 9, mergeSha: 'ffffffffff', mergedAt: iso(500), firstCommitAt: iso(450) },   // not on main
  ];
  const { unshipped } = attachChanges(deployments, prs, main);
  assert.deepEqual(deployments[0].changes.map((c) => c.number), [1, 2]);
  assert.deepEqual(deployments[1].changes.map((c) => c.number), [3]);
  assert.equal(deployments[0].changes[0].leadTimeSec, 200);
  assert.equal(deployments[0].changes[0].mergeToProdSec, 100);
  assert.equal(deployments[1].changes[0].leadTimeSec, 350);
  assert.deepEqual(unshipped.map((p) => p.number).sort(), [5, 9]);
});

test('attachChanges skips re-apply bumps as change carriers', () => {
  const main = ['1111111aaaa'];
  const deployments = buildDeployments(parseArgoLog([
    line('d2', 400, 'Craig', 'revert: re-apply the main-1111111 image bump'),
    line('d1', 200, 'github-actions[bot]', 'chore: update Kelta images to main-1111111'),
  ].join('\n')));
  attachChanges(deployments, [{ number: 1, mergeSha: '1111111aaaa', mergedAt: iso(100), firstCommitAt: iso(0) }], main);
  assert.equal(deployments[0].changes.length, 1);
  assert.equal(deployments[1].changes.length, 0);
});

test('percentile is nearest-rank and ignores junk', () => {
  assert.equal(percentile([], 50), null);
  assert.equal(percentile([5], 90), 5);
  assert.equal(percentile([1, 2, 3, 4, 5], 50), 3);
  assert.equal(percentile([1, 2, 3, 4, 5], 90), 5);
  assert.equal(percentile([1, 2, 3, 4], 50), 2);
  assert.equal(percentile([3, null, 1, NaN, 2], 100), 3);
});

test('pairStateTransitions pairs Alerting→Normal per fingerprint and reports ongoing', () => {
  const now = iso(1000);
  const entries = [
    { ts: iso(100), previous: 'Normal', current: 'Alerting', alertname: 'A', fingerprint: 'f1' },
    { ts: iso(110), previous: 'Normal', current: 'Alerting', alertname: 'B', fingerprint: 'f2' },
    { ts: iso(400), previous: 'Alerting', current: 'Normal', alertname: 'A', fingerprint: 'f1' },
    { ts: iso(410), previous: 'Normal', current: 'Pending', alertname: 'C', fingerprint: 'f3' }, // never fires
  ];
  const incs = pairStateTransitions(entries, now);
  const a = incs.find((i) => i.alertName === 'A');
  const b = incs.find((i) => i.alertName === 'B');
  assert.equal(a.ttrSec, 300);
  assert.equal(a.ongoing, undefined);
  assert.equal(b.resolvedAt, null);
  assert.equal(b.ongoing, true);
  assert.equal(b.ttrSec, 890);
  assert.equal(incs.length, 2);
});

test('runsFromAlertSeries splits contiguous firing samples into incidents', () => {
  const step = 60;
  const t = (n) => T0 + n * step;
  const result = [{
    metric: { alertname: 'KeltaHikariPoolExhausted' },
    values: [[t(0), '1'], [t(1), '1'], [t(2), '1'], /* gap */ [t(10), '1'], [t(11), '1']],
  }];
  const incs = runsFromAlertSeries(result, step, iso(20 * step));
  assert.equal(incs.length, 2);
  assert.equal(incs[0].ttrSec, 3 * step);
  assert.equal(incs[0].ongoing, false);
  assert.equal(incs[1].ttrSec, 2 * step);

  const ongoing = runsFromAlertSeries(result, step, iso(12 * step));
  assert.equal(ongoing[1].ongoing, true);
  assert.equal(ongoing[1].resolvedAt, null);
});

test('band thresholds', () => {
  assert.equal(band('deploysPerDay', 2), 'elite');
  assert.equal(band('deploysPerDay', 0.2), 'high');
  assert.equal(band('deploysPerDay', 0.05), 'medium');
  assert.equal(band('deploysPerDay', 0.01), 'low');
  assert.equal(band('leadTimeSec', 3600), 'elite');
  assert.equal(band('leadTimeSec', 3 * 86400), 'high');
  assert.equal(band('changeFailureRate', 0.05), 'elite');
  assert.equal(band('changeFailureRate', 0.2), 'low');
  assert.equal(band('mttrSec', 1800), 'elite');
  assert.equal(band('mttrSec', 10 * 86400), 'low');
  assert.equal(band('mttrSec', null), null);
});

test('summarize computes the four numbers for a window and segments by author', () => {
  const now = iso(8 * 86400); // window 7d → starts day 1 (inclusive)
  const deployments = [
    { sha: 'a', deployedAt: iso(1 * 86400), status: 'deployed', changes: [
      { leadTimeSec: 1000, mergeToProdSec: 500, authorIsBot: true },
      { leadTimeSec: 3000, mergeToProdSec: 700, authorIsBot: false },
    ] },
    { sha: 'b', deployedAt: iso(5 * 86400), status: 'rolled_back', rollback: { automatic: true }, changes: [
      { leadTimeSec: 2000, mergeToProdSec: 600, authorIsBot: true },
    ] },
    { sha: 'old', deployedAt: iso(-5 * 86400), status: 'rolled_back', rollback: { automatic: false }, changes: [] },
  ];
  const incidents = [
    { firedAt: iso(2 * 86400), ttrSec: 600 },
    { firedAt: iso(3 * 86400), ttrSec: 1200 },
    { firedAt: iso(-20 * 86400), ttrSec: 99999 },
    { firedAt: iso(4 * 86400), ttrSec: null },
    { firedAt: iso(4 * 86400), ttrSec: 50, ongoing: true }, // provisional — not averaged
  ];
  const s = summarize({ deployments, incidents, windowDays: 7, now });
  assert.equal(s.deployments, 2);
  assert.equal(s.changes, 3);
  assert.equal(s.rollbacks, 1);
  assert.equal(s.autoRollbacks, 1);
  assert.equal(s.incidents, 2);
  assert.equal(s.deploysPerDay, 0.286);
  assert.equal(s.leadTimeP50Sec, 2000);
  assert.equal(s.changeFailureRate, 0.5);
  assert.equal(s.mttrP50Sec, 600);
  assert.equal(s.bands.leadTime, 'elite');
  assert.equal(s.bands.changeFailureRate, 'low');

  const bots = summarize({ deployments, incidents, windowDays: 7, now, segment: 'bot' });
  assert.equal(bots.changes, 2);
  assert.equal(bots.leadTimeP50Sec, 1000);
  const empty = summarize({ deployments: [], incidents: [], windowDays: 7, now });
  assert.equal(empty.changeFailureRate, null);
  assert.equal(empty.bands.changeFailureRate, null);
});

test('filterIncidents keeps platform alerts only', () => {
  const incs = [
    { alertName: 'EMF Gateway — SLO fast burn (14.4x)' },
    { alertName: 'KeltaHikariPoolExhausted' },
    { alertName: 'Autopilot — median task duration > 8 min' },
    { alertName: 'SpotOpened pollers — upstream 429s elevated' },
  ];
  assert.deepEqual(filterIncidents(incs, '^(EMF|Kelta)').map((i) => i.alertName.slice(0, 5)), ['EMF G', 'Kelta']);
  assert.equal(filterIncidents(incs, '').length, 4);
  assert.equal(filterIncidents(incs, /Autopilot/).length, 1);
});

test('stableStringify sorts keys recursively and drops undefined', () => {
  assert.equal(stableStringify({ b: 1, a: { d: undefined, c: [ { z: 1, y: 2 } ] } }), '{"a":{"c":[{"y":2,"z":1}]},"b":1}');
});

test('normalizePr picks first authored commit and flags bots', () => {
  const pr = normalizePr({
    number: 7, title: 't', mergedAt: iso(300), createdAt: iso(50),
    author: { login: 'rzware-developer', __typename: 'Bot' },
    mergeCommit: { oid: 'abc' },
    commits: { totalCount: 2, nodes: [{ commit: { authoredDate: iso(200) } }, { commit: { authoredDate: iso(100) } }] },
  });
  assert.equal(pr.firstCommitAt, iso(100));
  assert.equal(pr.authorIsBot, true);
  assert.equal(pr.mergeSha, 'abc');
  assert.equal(normalizePr({ number: 1, author: { login: 'cklinker', __typename: 'User' }, commits: { nodes: [] }, createdAt: iso(1) }).authorIsBot, false);
  assert.equal(normalizePr({ number: 1, author: { login: 'foo[bot]' }, commits: { nodes: [] }, createdAt: iso(1) }).authorIsBot, true);
});
