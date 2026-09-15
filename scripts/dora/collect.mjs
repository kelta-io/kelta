#!/usr/bin/env node
// DORA metrics collector for the Kelta platform repo.
//
// Sources (all read-only):
//   - GitHub GraphQL   merged PRs + their commits           (GITHUB_TOKEN)
//   - this repo        first-parent history of main         (git)
//   - ArgoCD repo      image-tag bumps + rollback reverts   (git clone at --argo-dir)
//   - Loki             Grafana alert state history          (--loki-url, optional)
//   - Mimir            Prometheus-rule ALERTS series        (--mimir-url, optional)
//
// Sinks (all optional, all idempotent):
//   --out <file>       JSON report (deployments, changes, incidents, summaries)
//   --push-loki        deploy/rollback/incident/snapshot lines → Loki push API
//   --push-otlp        dora_* gauges → OTLP/HTTP JSON (Alloy → Mimir)
//   --push-kelta       upsert into dora-* collections via the kelta CLI (KELTA_* env)
//
// Zero dependencies; Node ≥ 18 (global fetch). Pure logic lives in lib.mjs.

import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  WINDOWS, parseArgoLog, buildDeployments, attachChanges, pairStateTransitions,
  runsFromAlertSeries, summarize, stableStringify, normalizePr, filterIncidents,
} from './lib.mjs';

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(`Usage: node scripts/dora/collect.mjs [options]
  --repo <owner/name>     GitHub repo (default: $GITHUB_REPOSITORY or kelta-io/kelta)
  --repo-dir <path>       local clone of the repo (default: cwd)
  --argo-dir <path>       local clone of the ArgoCD manifests repo (default: ../homelab-argo)
  --argo-path <subdir>    manifest subdir to scan (default: emf/)
  --days <n>              history to load (default: 120; must cover the longest window + slack)
  --now <iso>             fix the clock (tests / backfill)
  --loki-url <url>        Loki base URL for alert state history (e.g. http://loki:3100)
  --mimir-url <url>       Mimir base URL for ALERTS series (e.g. http://mimir:8080)
  --incident-filter <re>  only alerts whose name matches count as incidents (default: ^(EMF|Kelta); '' = all)
  --out <file>            write the JSON report here
  --push-loki             push events to --loki-url (last --push-days days)
  --push-otlp <url>       push gauges to an OTLP/HTTP endpoint (e.g. http://alloy:4318)
  --push-kelta            upsert into Kelta dora-* collections (kelta CLI + KELTA_* env)
  --push-days <n>         event lookback for the Loki/Kelta pushes (default: 6, inside Loki's 7d old-sample limit)
  --quiet                 no summary table on stderr`);
  process.exit(0);
}

const repo = args.repo || process.env.GITHUB_REPOSITORY || 'kelta-io/kelta';
const repoDir = resolve(args['repo-dir'] || '.');
const argoDir = resolve(args['argo-dir'] || '../homelab-argo');
const argoPath = args['argo-path'] || 'emf/';
const days = Number(args.days || 120);
const pushDays = Number(args['push-days'] || 6); // < Loki's 7d reject_old_samples_max_age
const now = args.now || new Date().toISOString();
const since = new Date(Date.parse(now) - days * 86400 * 1000).toISOString();

// ---------------------------------------------------------------------------
// Sources
// ---------------------------------------------------------------------------

function git(dir, ...argv) {
  return execFileSync('git', ['-C', dir, ...argv], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

// Prefer the remote-tracking ref so a stale local checkout does not hide recent
// merges/bumps (CI checkouts have no local main at all).
function mainRef(dir) {
  for (const ref of ['origin/main', 'main', 'HEAD']) {
    try { git(dir, 'rev-parse', '--verify', '-q', ref); return ref; } catch { /* next */ }
  }
  return 'HEAD';
}

function loadArgoEvents() {
  const text = git(argoDir, 'log', mainRef(argoDir), '--format=%H%x09%ct%x09%an%x09%s', `--since=${since}`, '--', argoPath);
  return parseArgoLog(text);
}

function loadMainHistory() {
  const text = git(repoDir, 'log', '--first-parent', '--format=%H', `--since=${since}`, mainRef(repoDir));
  return text.split('\n').filter(Boolean).reverse(); // oldest first
}

async function loadMergedPrs() {
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (!token) throw new Error('GITHUB_TOKEN (or GH_TOKEN) is required to read merged PRs');
  const [owner, name] = repo.split('/');
  const q = `repo:${owner}/${name} is:pr is:merged merged:>=${since.slice(0, 10)}`;
  const query = `query($q: String!, $after: String) {
    search(query: $q, type: ISSUE, first: 100, after: $after) {
      pageInfo { hasNextPage endCursor }
      nodes { ... on PullRequest {
        number title createdAt mergedAt
        author { __typename login }
        mergeCommit { oid }
        commits(first: 100) { totalCount nodes { commit { authoredDate committedDate } } }
      } }
    } }`;
  const prs = [];
  let after = null;
  for (let page = 0; page < 50; page++) {
    const res = await fetch('https://api.github.com/graphql', {
      method: 'POST',
      headers: { authorization: `bearer ${token}`, 'content-type': 'application/json', 'user-agent': 'kelta-dora-collector' },
      body: JSON.stringify({ query, variables: { q, after } }),
    });
    if (!res.ok) throw new Error(`GitHub GraphQL ${res.status}: ${await res.text()}`);
    const body = await res.json();
    if (body.errors) throw new Error(`GitHub GraphQL: ${JSON.stringify(body.errors)}`);
    const search = body.data.search;
    for (const node of search.nodes) if (node && node.number) prs.push(normalizePr(node));
    if (!search.pageInfo.hasNextPage) break;
    after = search.pageInfo.endCursor;
  }
  return prs;
}

// Alert sources are bounded by observability retention (30d) and per-query limits
// (Loki: 30d range; Mimir: 11k points per series), so incidents are loaded in
// 7-day chunks over the last ALERT_LOOKBACK_DAYS regardless of --days. The Kelta
// sink keeps the long history.
const ALERT_LOOKBACK_DAYS = 30;
const ALERT_CHUNK_DAYS = 7;

function alertChunks() {
  const endMs = Date.parse(now);
  const startMs = Math.max(Date.parse(since), endMs - ALERT_LOOKBACK_DAYS * 86400 * 1000);
  const chunks = [];
  for (let a = startMs; a < endMs; a += ALERT_CHUNK_DAYS * 86400 * 1000) {
    chunks.push([a, Math.min(a + ALERT_CHUNK_DAYS * 86400 * 1000, endMs)]);
  }
  return chunks;
}

async function loadGrafanaStateHistory(lokiUrl) {
  // Grafana writes one JSON line per state transition when
  // [unified_alerting.state_history] backend=loki is enabled.
  const entries = [];
  for (const [a, b] of alertChunks()) {
    const url = new URL('/loki/api/v1/query_range', lokiUrl);
    url.searchParams.set('query', '{from="state-history"}');
    url.searchParams.set('start', `${a}000000`);
    url.searchParams.set('end', `${b}000000`);
    url.searchParams.set('limit', '5000');
    url.searchParams.set('direction', 'forward');
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Loki ${res.status}: ${await res.text()}`);
    const body = await res.json();
    for (const stream of body.data?.result || []) {
      for (const [ns, line] of stream.values || []) {
        let j; try { j = JSON.parse(line); } catch { continue; }
        entries.push({
          ts: new Date(Number(ns) / 1e6).toISOString(),
          previous: j.previous, current: j.current,
          alertname: j.labels?.alertname || j.ruleTitle, ruleTitle: j.ruleTitle,
          fingerprint: j.fingerprint, ruleUID: j.ruleUID,
        });
      }
    }
  }
  return pairStateTransitions(entries, now);
}

async function loadPrometheusAlerts(mimirUrl) {
  const step = 60; // 7d × 60s = 10,080 points — under Mimir's 11k/series cap
  // Merge chunks per series so a run spanning a chunk boundary stays one incident.
  const bySeries = new Map();
  for (const [a, b] of alertChunks()) {
    const url = new URL('/prometheus/api/v1/query_range', mimirUrl);
    url.searchParams.set('query', 'ALERTS{alertstate="firing"}');
    url.searchParams.set('start', String(Math.floor(a / 1000)));
    url.searchParams.set('end', String(Math.floor(b / 1000)));
    url.searchParams.set('step', String(step));
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Mimir ${res.status}: ${await res.text()}`);
    const body = await res.json();
    for (const series of body.data?.result || []) {
      const key = JSON.stringify(series.metric || {});
      if (!bySeries.has(key)) bySeries.set(key, { metric: series.metric, values: [] });
      bySeries.get(key).values.push(...(series.values || []));
    }
  }
  return runsFromAlertSeries([...bySeries.values()], step, now);
}

// ---------------------------------------------------------------------------
// Sinks
// ---------------------------------------------------------------------------

function eventsSince(report, cutoffIso) {
  const cut = Date.parse(cutoffIso);
  const out = [];
  for (const d of report.deployments) {
    if (Date.parse(d.deployedAt) >= cut) out.push({ ts: d.deployedAt, event: 'deploy', body: deployLine(d) });
    if (d.rollback && Date.parse(d.rollback.at) >= cut) {
      out.push({ ts: d.rollback.at, event: 'rollback', body: { sha: d.sha, argoCommit: d.argoCommit, ...d.rollback } });
    }
  }
  for (const i of report.incidents) {
    if (i.resolvedAt && Date.parse(i.resolvedAt) >= cut) {
      out.push({ ts: i.resolvedAt, event: 'incident', body: { alertName: i.alertName, source: i.source, firedAt: i.firedAt, resolvedAt: i.resolvedAt, ttrSec: i.ttrSec } });
    }
  }
  return out;
}

function deployLine(d) {
  const changes = d.changes || [];
  return {
    sha: d.sha, argoCommit: d.argoCommit, deployedAt: d.deployedAt, status: d.status, redeploy: d.redeploy,
    changes: changes.length,
    prs: changes.map((c) => c.number),
    leadTimeP50Sec: median(changes.map((c) => c.leadTimeSec)),
    mergeToProdP50Sec: median(changes.map((c) => c.mergeToProdSec)),
    botChanges: changes.filter((c) => c.authorIsBot).length,
  };
}

function median(xs) {
  const v = xs.filter((x) => typeof x === 'number').sort((a, b) => a - b);
  return v.length ? v[Math.floor((v.length - 1) / 2)] : null;
}

async function pushLoki(lokiUrl, report) {
  const cutoff = new Date(Date.parse(now) - pushDays * 86400 * 1000).toISOString();
  const streams = new Map();
  const add = (labels, tsIso, body) => {
    const key = stableStringify(labels);
    if (!streams.has(key)) streams.set(key, { stream: labels, values: [] });
    // Deterministic line + exact timestamp: Loki drops byte-identical duplicates, so a
    // re-run (or a manual backfill) does not double-count.
    streams.get(key).values.push([`${Date.parse(tsIso)}000000`, stableStringify(body)]);
  };
  for (const e of eventsSince(report, cutoff)) add({ job: 'dora', repo, event: e.event }, e.ts, e.body);
  for (const s of report.summaries) add({ job: 'dora', repo, event: 'snapshot', window: s.window, segment: s.segment }, now, s);
  const res = await fetch(new URL('/loki/api/v1/push', lokiUrl), {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ streams: [...streams.values()] }),
  });
  if (!res.ok && res.status !== 204) throw new Error(`Loki push ${res.status}: ${await res.text()}`);
  return [...streams.values()].reduce((n, s) => n + s.values.length, 0);
}

async function pushOtlp(otlpUrl, report) {
  const nowNs = `${Date.parse(now)}000000`;
  const gauges = [];
  // No unit on counts/ratios: the OTLP→Prometheus translator appends `_ratio` to a
  // unit-"1" gauge, and `_per_day` is already in the name. Seconds keep "s" — the
  // name already carries `_seconds`, so the translator leaves it alone.
  const gauge = (name, value, attrs, unit = '') => {
    if (value === null || value === undefined) return;
    gauges.push({
      name, unit,
      gauge: { dataPoints: [{ asDouble: value, timeUnixNano: nowNs, attributes: kv(attrs) }] },
    });
  };
  for (const s of report.summaries) {
    const a = { window: s.window, segment: s.segment, repo };
    gauge('dora_deployments_total', s.deployments, a);
    gauge('dora_changes_total', s.changes, a);
    gauge('dora_rollbacks_total', s.rollbacks, a);
    gauge('dora_incidents_total', s.incidents, a);
    gauge('dora_deploy_frequency_per_day', s.deploysPerDay, a);
    gauge('dora_lead_time_seconds', s.leadTimeP50Sec, { ...a, quantile: '0.5' }, 's');
    gauge('dora_lead_time_seconds', s.leadTimeP90Sec, { ...a, quantile: '0.9' }, 's');
    gauge('dora_merge_to_prod_seconds', s.mergeToProdP50Sec, { ...a, quantile: '0.5' }, 's');
    gauge('dora_change_failure_rate', s.changeFailureRate, a);
    gauge('dora_time_to_restore_seconds', s.mttrP50Sec, { ...a, quantile: '0.5' }, 's');
    gauge('dora_time_to_restore_seconds', s.mttrP90Sec, { ...a, quantile: '0.9' }, 's');
  }
  gauge('dora_collector_last_run_timestamp_seconds', Date.parse(now) / 1000, { repo }, 's');
  const payload = {
    resourceMetrics: [{
      resource: { attributes: kv({ 'service.name': 'dora', 'service.namespace': 'ci' }) },
      scopeMetrics: [{ scope: { name: 'kelta.dora.collector' }, metrics: gauges }],
    }],
  };
  const res = await fetch(new URL('/v1/metrics', otlpUrl), {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`OTLP push ${res.status}: ${await res.text()}`);
  return gauges.length;
}

function kv(obj) {
  return Object.entries(obj).map(([key, value]) => ({ key, value: { stringValue: String(value) } }));
}

// Kelta sink — the durable, queryable store (Loki/Mimir retention is 30d; DORA wants
// 90d+). Uses the kelta CLI with env auth so CI needs no profile file.
function kelta(...argv) {
  const out = execFileSync('kelta', [...argv, '--output', 'json'], { encoding: 'utf8', env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
  return out.trim() ? JSON.parse(out) : null;
}

function upsert(collection, keyField, key, attrs) {
  const existing = kelta('records', 'list', collection, '--filter', `${keyField}=${key}`, '--size', '1');
  const rows = Array.isArray(existing) ? existing : existing?.data || [];
  if (rows.length) {
    kelta('records', 'update', collection, rows[0].id, '--data', JSON.stringify(attrs));
    return 'updated';
  }
  kelta('records', 'create', collection, '--data', JSON.stringify({ [keyField]: key, ...attrs }));
  return 'created';
}

function pushKelta(report) {
  const cutoff = Date.parse(now) - pushDays * 86400 * 1000;
  const counts = { created: 0, updated: 0 };
  const tally = (r) => { counts[r]++; };

  for (const d of report.deployments) {
    if (Date.parse(d.deployedAt) < cutoff && !(d.rollback && Date.parse(d.rollback.at) >= cutoff)) continue;
    const line = deployLine(d);
    tally(upsert('dora-deployments', 'sha', d.sha, {
      argoCommit: d.argoCommit, deployedAt: d.deployedAt, deployedDate: d.deployedAt.slice(0, 10),
      status: d.status, redeploy: !!d.redeploy,
      changeCount: line.changes, botChangeCount: line.botChanges, prNumbers: line.prs.join(','),
      leadTimeP50Sec: line.leadTimeP50Sec, mergeToProdP50Sec: line.mergeToProdP50Sec,
      rolledBackAt: d.rollback?.at || null, rollbackAutomatic: d.rollback ? !!d.rollback.automatic : null,
      rollbackReason: d.rollback?.reason || null, rollbackRunId: d.rollback?.runId || null,
    }));
    for (const c of d.changes || []) {
      tally(upsert('dora-changes', 'prNumber', String(c.number), {
        title: (c.title || '').slice(0, 200), author: c.author, authorType: c.authorIsBot ? 'bot' : 'human',
        firstCommitAt: c.firstCommitAt, mergedAt: c.mergedAt, deployedAt: c.deployedAt, deployedDate: c.deployedAt.slice(0, 10),
        deploymentSha: d.sha, leadTimeSec: c.leadTimeSec, leadTimeHours: hours(c.leadTimeSec), mergeToProdSec: c.mergeToProdSec,
      }));
    }
  }
  for (const i of report.incidents) {
    if (Date.parse(i.firedAt) < cutoff && !(i.resolvedAt && Date.parse(i.resolvedAt) >= cutoff)) continue;
    tally(upsert('dora-incidents', 'key', i.key, {
      alertName: i.alertName, source: i.source, firedAt: i.firedAt, firedDate: i.firedAt.slice(0, 10),
      resolvedAt: i.resolvedAt, ttrSec: i.ttrSec, ongoing: !!i.ongoing,
    }));
  }
  // Two rows per (window, segment): `kind=latest` is overwritten every run so a
  // dashboard tile can filter to it without knowing today's date; `kind=history`
  // is keyed by day and accumulates for trend charts.
  const today = now.slice(0, 10);
  for (const s of report.summaries) {
    const attrs = {
      snapshotDate: today, window: s.window, segment: s.segment,
      deployments: s.deployments, changes: s.changes, rollbacks: s.rollbacks, incidents: s.incidents,
      deploysPerDay: s.deploysPerDay, leadTimeP50Sec: s.leadTimeP50Sec, leadTimeP90Sec: s.leadTimeP90Sec,
      leadTimeP50Hours: hours(s.leadTimeP50Sec), mergeToProdP50Sec: s.mergeToProdP50Sec,
      changeFailureRate: s.changeFailureRate, changeFailurePct: s.changeFailureRate === null ? null : Math.round(s.changeFailureRate * 1000) / 10,
      mttrP50Sec: s.mttrP50Sec, mttrP50Minutes: s.mttrP50Sec === null ? null : Math.round(s.mttrP50Sec / 6) / 10,
      bandDeployFrequency: s.bands.deployFrequency, bandLeadTime: s.bands.leadTime,
      bandChangeFailureRate: s.bands.changeFailureRate, bandTimeToRestore: s.bands.timeToRestore,
    };
    tally(upsert('dora-snapshots', 'key', `latest:${s.window}:${s.segment}`, { ...attrs, kind: 'latest', latest: true }));
    tally(upsert('dora-snapshots', 'key', `${today}:${s.window}:${s.segment}`, { ...attrs, kind: 'history', latest: false }));
  }
  return counts;
}

function hours(sec) { return sec === null || sec === undefined ? null : Math.round(sec / 360) / 10; }

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith('--')) { out[key] = next; i++; } else { out[key] = true; }
  }
  return out;
}

function fmt(sec) {
  if (sec === null || sec === undefined) return '—';
  if (sec < 3600) return `${Math.round(sec / 60)}m`;
  if (sec < 86400) return `${(sec / 3600).toFixed(1)}h`;
  return `${(sec / 86400).toFixed(1)}d`;
}

async function main() {
  const log = (...m) => { if (!args.quiet) console.error(...m); };

  const argoEvents = loadArgoEvents();
  const deployments = buildDeployments(argoEvents);
  const mainHistory = loadMainHistory();
  const prs = await loadMergedPrs();
  const { unshipped } = attachChanges(deployments, prs, mainHistory);

  let incidents = [];
  const sourceErrors = [];
  if (args['loki-url']) {
    try { incidents.push(...await loadGrafanaStateHistory(args['loki-url'])); } catch (e) { sourceErrors.push(`loki: ${e.message}`); }
  }
  if (args['mimir-url']) {
    try { incidents.push(...await loadPrometheusAlerts(args['mimir-url'])); } catch (e) { sourceErrors.push(`mimir: ${e.message}`); }
  }

  const incidentFilter = args['incident-filter'] === undefined ? '^(EMF|Kelta)' : args['incident-filter'];
  const allIncidents = incidents;
  incidents = filterIncidents(incidents, incidentFilter === true ? '' : incidentFilter);

  const summaries = [];
  for (const w of WINDOWS) for (const segment of ['all', 'bot', 'human']) {
    summaries.push(summarize({ deployments, incidents, windowDays: w, now, segment }));
  }

  const report = {
    generatedAt: now, repo, since,
    sources: { argoDir, argoPath, mainCommits: mainHistory.length, mergedPrs: prs.length, incidentFilter, alertsSeen: allIncidents.length, sourceErrors },
    summaries, deployments, unshipped, incidents,
  };

  if (args.out) { writeFileSync(args.out, JSON.stringify(report, null, 2)); log(`wrote ${args.out}`); }

  log(`\nDORA — ${repo} (as of ${now})`);
  log('window   seg    deploys  /day    lead p50   merge→prod   CFR     MTTR p50   incidents');
  for (const s of summaries.filter((x) => x.segment === 'all' || x.changes > 0)) {
    log(`${s.window.padEnd(8)} ${s.segment.padEnd(6)} ${String(s.deployments).padStart(7)}  ${String(s.deploysPerDay).padEnd(6)}  ${fmt(s.leadTimeP50Sec).padEnd(10)} ${fmt(s.mergeToProdP50Sec).padEnd(12)} ${s.changeFailureRate === null ? '—' : `${(s.changeFailureRate * 100).toFixed(0)}%`.padEnd(6)}  ${fmt(s.mttrP50Sec).padEnd(10)} ${s.incidents}`);
  }
  if (unshipped.length) log(`unshipped PRs: ${unshipped.map((p) => `#${p.number}`).join(' ')}`);
  for (const e of sourceErrors) log(`WARN ${e}`);

  if (args['push-loki'] && !args['loki-url']) throw new Error('--push-loki requires --loki-url');
  if (args['push-loki']) log(`loki: pushed ${await pushLoki(args['loki-url'], report)} lines`);
  if (args['push-otlp']) log(`otlp: pushed ${await pushOtlp(args['push-otlp'], report)} gauges`);
  if (args['push-kelta']) log(`kelta: ${JSON.stringify(pushKelta(report))}`);
}

main().catch((e) => { console.error(e.stack || String(e)); process.exit(1); });
