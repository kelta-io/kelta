#!/usr/bin/env node
// Create (idempotently) the Kelta metadata the DORA collector writes into and a
// dashboard that reads it. Safe to re-run: every object is looked up by name first.
//
// Any tenant can run this — it is plain tenant metadata created through the CLI,
// nothing platform-side. Auth comes from the kelta CLI (active profile, --profile,
// or KELTA_URL/KELTA_TENANT/KELTA_TOKEN).
//
//   node scripts/dora/bootstrap-kelta.mjs [--profile <name>] [--menu <menu name> [--parent <item label>]]
//
// --menu adds a "DORA" navigation item pointing at the dashboard (optional).

import { execFileSync } from 'node:child_process';

const args = parseArgs(process.argv.slice(2));
const profileArgs = args.profile ? ['--profile', args.profile] : [];
const log = (...m) => console.error(...m);

function kelta(...argv) {
  const out = execFileSync('kelta', [...argv, ...profileArgs, '--output', 'json'], {
    encoding: 'utf8', env: process.env, stdio: ['ignore', 'pipe', 'inherit'],
  });
  return out.trim() ? JSON.parse(out) : null;
}
const rows = (r) => (Array.isArray(r) ? r : r?.data || []);

// ---------------------------------------------------------------------------
// Schema
// ---------------------------------------------------------------------------

const S = (name, extra = {}) => ({ name, type: 'STRING', ...extra });
const I = (name, extra = {}) => ({ name, type: 'INTEGER', ...extra });
const D = (name, extra = {}) => ({ name, type: 'DOUBLE', ...extra });
const DT = (name, extra = {}) => ({ name, type: 'DATETIME', ...extra });
const B = (name, extra = {}) => ({ name, type: 'BOOLEAN', ...extra });

const COLLECTIONS = [
  {
    name: 'dora-deployments', displayName: 'DORA Deployments',
    description: 'One row per production deployment (ArgoCD image-tag bump). Written by scripts/dora/collect.mjs.',
    fields: [
      S('sha', { required: true, unique: true, indexed: true, displayName: 'Commit SHA' }),
      S('argoCommit', { displayName: 'ArgoCD commit' }),
      DT('deployedAt', { indexed: true, displayName: 'Deployed at' }),
      S('deployedDate', { indexed: true, displayName: 'Deployed date' }),
      S('status', { indexed: true }),
      B('redeploy'),
      I('changeCount', { displayName: 'PRs shipped' }),
      I('botChangeCount', { displayName: 'Bot PRs shipped' }),
      { name: 'prNumbers', type: 'TEXT', displayName: 'PR numbers' },
      I('leadTimeP50Sec', { displayName: 'Lead time p50 (s)' }),
      I('mergeToProdP50Sec', { displayName: 'Merge→prod p50 (s)' }),
      DT('rolledBackAt', { displayName: 'Rolled back at' }),
      B('rollbackAutomatic', { displayName: 'Rollback automatic' }),
      S('rollbackReason', { displayName: 'Rollback reason' }),
      S('rollbackRunId', { displayName: 'Rollback run id' }),
    ],
  },
  {
    name: 'dora-changes', displayName: 'DORA Changes',
    description: 'One row per merged PR with the deployment that shipped it and its lead time.',
    fields: [
      S('prNumber', { required: true, unique: true, indexed: true, displayName: 'PR' }),
      S('title'),
      S('author', { indexed: true }),
      S('authorType', { indexed: true, displayName: 'Author type' }),
      DT('firstCommitAt', { displayName: 'First commit at' }),
      DT('mergedAt', { displayName: 'Merged at' }),
      DT('deployedAt', { indexed: true, displayName: 'Deployed at' }),
      S('deployedDate', { indexed: true, displayName: 'Deployed date' }),
      S('deploymentSha', { displayName: 'Deployment SHA' }),
      I('leadTimeSec', { displayName: 'Lead time (s)' }),
      D('leadTimeHours', { displayName: 'Lead time (h)' }),
      I('mergeToProdSec', { displayName: 'Merge→prod (s)' }),
    ],
  },
  {
    name: 'dora-incidents', displayName: 'DORA Incidents',
    description: 'One row per production alert firing→resolved interval (Grafana state history / Prometheus ALERTS).',
    fields: [
      S('key', { required: true, unique: true, indexed: true }),
      S('alertName', { indexed: true, displayName: 'Alert' }),
      S('source'),
      DT('firedAt', { indexed: true, displayName: 'Fired at' }),
      S('firedDate', { displayName: 'Fired date' }),
      DT('resolvedAt', { displayName: 'Resolved at' }),
      I('ttrSec', { displayName: 'Time to restore (s)' }),
      B('ongoing'),
    ],
  },
  {
    name: 'dora-snapshots', displayName: 'DORA Snapshots',
    description: 'Daily rollup per trailing window (7d/30d/90d) and author segment (all/bot/human). kind=latest rows are overwritten each run; kind=history rows accumulate.',
    fields: [
      S('key', { required: true, unique: true, indexed: true }),
      S('kind', { indexed: true }),
      S('snapshotDate', { indexed: true, displayName: 'Date' }),
      S('window', { indexed: true }),
      S('segment', { indexed: true }),
      B('latest'),
      I('deployments'), I('changes'), I('rollbacks'), I('incidents'),
      D('deploysPerDay', { displayName: 'Deploys / day' }),
      I('leadTimeP50Sec', { displayName: 'Lead time p50 (s)' }),
      I('leadTimeP90Sec', { displayName: 'Lead time p90 (s)' }),
      D('leadTimeP50Hours', { displayName: 'Lead time p50 (h)' }),
      I('mergeToProdP50Sec', { displayName: 'Merge→prod p50 (s)' }),
      D('changeFailureRate', { displayName: 'Change failure rate' }),
      D('changeFailurePct', { displayName: 'Change failure %' }),
      I('mttrP50Sec', { displayName: 'MTTR p50 (s)' }),
      D('mttrP50Minutes', { displayName: 'MTTR p50 (min)' }),
      S('bandDeployFrequency', { displayName: 'Band: deploy frequency' }),
      S('bandLeadTime', { displayName: 'Band: lead time' }),
      S('bandChangeFailureRate', { displayName: 'Band: CFR' }),
      S('bandTimeToRestore', { displayName: 'Band: MTTR' }),
    ],
  },
];

function ensureCollection(def) {
  const found = rows(kelta('collections', 'list')).filter((c) => c.name === def.name);
  let col = found[0];
  if (col) {
    log(`  collection ${def.name}: exists (${col.id})`);
  } else {
    col = kelta('collections', 'create', '--name', def.name, '--display-name', def.displayName, '--description', def.description);
    log(`  collection ${def.name}: created (${col.id})`);
  }
  const existing = new Set(rows(kelta('fields', 'list', def.name)).map((f) => f.name));
  for (const f of def.fields) {
    if (existing.has(f.name)) continue;
    const argv = ['fields', 'add', def.name, '--name', f.name, '--type', f.type];
    if (f.displayName) argv.push('--display-name', f.displayName);
    if (f.required) argv.push('--required');
    if (f.unique) argv.push('--unique');
    if (f.indexed) argv.push('--indexed');
    kelta(...argv);
    log(`    + ${f.name} (${f.type})`);
  }
  return col;
}

// ---------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------

const eq = (field, value) => ({ field, operator: 'equals', value });
const latest = (window, segment = 'all') => [eq('kind', 'latest'), eq('window', window), eq('segment', segment)];

function metric(title, label, field, filters, col, row) {
  return {
    componentType: 'metric', title, columnPosition: col, rowPosition: row, columnSpan: 1, rowSpan: 1,
    config: { collectionName: 'dora-snapshots', aggregateFunction: 'MAX', aggregateField: field, label, filters, ignoreTimeRange: true },
  };
}

function widgets() {
  const w = [];
  // Row 1-2: the four numbers, 30d then 7d (latest snapshot, all authors)
  for (const [row, win] of [[1, '30d'], [2, '7d']]) {
    w.push(metric(`Deploys / day (${win})`, 'elite ≥ 1', 'deploysPerDay', latest(win), 1, row));
    w.push(metric(`Lead time p50, hours (${win})`, 'elite < 24h', 'leadTimeP50Hours', latest(win), 2, row));
    w.push(metric(`Change failure % (${win})`, 'elite ≤ 5%', 'changeFailurePct', latest(win), 3, row));
    w.push(metric(`MTTR p50, minutes (${win})`, 'elite < 60m', 'mttrP50Minutes', latest(win), 4, row));
  }
  // Row 3: throughput over time
  w.push({
    componentType: 'chart', title: 'Deployments per day', columnPosition: 1, rowPosition: 3, columnSpan: 2, rowSpan: 1,
    config: { collectionName: 'dora-deployments', chartStyle: 'bar', groupByField: 'deployedDate', aggregateFunction: 'COUNT', timeField: 'deployedAt', timeRange: '30D', maxGroups: 31 },
  });
  w.push({
    componentType: 'chart', title: 'Lead time p50 trend, hours (30d window)', columnPosition: 3, rowPosition: 3, columnSpan: 2, rowSpan: 1,
    config: { collectionName: 'dora-snapshots', chartStyle: 'bar', groupByField: 'snapshotDate', aggregateFunction: 'AVG', aggregateField: 'leadTimeP50Hours', filters: [eq('kind', 'history'), eq('window', '30d'), eq('segment', 'all')], ignoreTimeRange: true, maxGroups: 60 },
  });
  // Row 4: stability + who ships
  w.push({
    componentType: 'chart', title: 'Deployment outcome (30d)', columnPosition: 1, rowPosition: 4, columnSpan: 1, rowSpan: 1,
    config: { collectionName: 'dora-deployments', chartStyle: 'pie', groupByField: 'status', aggregateFunction: 'COUNT', timeField: 'deployedAt', timeRange: '30D' },
  });
  w.push({
    componentType: 'chart', title: 'Changes by author type (30d)', columnPosition: 2, rowPosition: 4, columnSpan: 1, rowSpan: 1,
    config: { collectionName: 'dora-changes', chartStyle: 'pie', groupByField: 'authorType', aggregateFunction: 'COUNT', timeField: 'deployedAt', timeRange: '30D' },
  });
  w.push({
    componentType: 'chart', title: 'Lead time by author, avg hours (30d)', columnPosition: 3, rowPosition: 4, columnSpan: 2, rowSpan: 1,
    config: { collectionName: 'dora-changes', chartStyle: 'bar', groupByField: 'author', aggregateFunction: 'AVG', aggregateField: 'leadTimeHours', timeField: 'deployedAt', timeRange: '30D', maxGroups: 12 },
  });
  // Row 5-6: the receipts
  w.push({
    componentType: 'table', title: 'Recent deployments', columnPosition: 1, rowPosition: 5, columnSpan: 4, rowSpan: 1,
    config: { collectionName: 'dora-deployments', fields: ['deployedAt', 'sha', 'status', 'changeCount', 'prNumbers', 'leadTimeP50Sec', 'rollbackReason'], sortBy: '-deployedAt', ignoreTimeRange: true },
  });
  w.push({
    componentType: 'table', title: 'Recent incidents', columnPosition: 1, rowPosition: 6, columnSpan: 4, rowSpan: 1,
    config: { collectionName: 'dora-incidents', fields: ['firedAt', 'alertName', 'source', 'resolvedAt', 'ttrSec', 'ongoing'], sortBy: '-firedAt', ignoreTimeRange: true },
  });
  return w;
}

function ensureReport(collectionId) {
  const name = 'dora-deployments-all';
  const found = rows(kelta('records', 'list', 'reports', '--filter', `name=${name}`, '--size', '1'));
  if (found[0]) return found[0];
  const rep = kelta('records', 'create', 'reports', '--data', JSON.stringify({
    name, description: 'Every deployment (DORA dashboard source)', reportType: 'TABULAR',
    primaryCollectionId: collectionId, accessLevel: 'PUBLIC', scope: 'ALL_RECORDS',
    columns: [{ fieldName: 'deployedAt', label: 'Deployed' }, { fieldName: 'sha', label: 'SHA' }, { fieldName: 'status', label: 'Status' }],
    filters: [], relatedJoins: [], rowGroupings: [], columnGroupings: [], sortOrder: [{ field: 'deployedAt', direction: 'DESC' }],
  }));
  log(`  report ${name}: created (${rep.id})`);
  return rep;
}

function ensureDashboard(reportId) {
  const name = 'DORA';
  let dash = rows(kelta('records', 'list', 'dashboards', '--filter', `name=${name}`, '--size', '1'))[0];
  if (dash) {
    log(`  dashboard ${name}: exists (${dash.id})`);
  } else {
    dash = kelta('records', 'create', 'dashboards', '--data', JSON.stringify({
      name, description: 'Deployment frequency, lead time, change failure rate, time to restore — computed nightly by scripts/dora/collect.mjs',
      accessLevel: 'PUBLIC', dynamic: false, columnCount: 4,
    }));
    log(`  dashboard ${name}: created (${dash.id})`);
  }
  const existing = rows(kelta('records', 'list', 'dashboard-components', '--filter', `dashboardId=${dash.id}`, '--all'));
  const byTitle = new Map(existing.map((c) => [c.title, c]));
  widgets().forEach((wdg, i) => {
    const body = { ...wdg, dashboardId: dash.id, reportId, sortOrder: i };
    const cur = byTitle.get(wdg.title);
    if (cur) {
      kelta('records', 'update', 'dashboard-components', cur.id, '--data', JSON.stringify(body));
    } else {
      kelta('records', 'create', 'dashboard-components', '--data', JSON.stringify(body));
      log(`    + widget ${wdg.title}`);
    }
  });
  return dash;
}

function ensureMenuItem(dash) {
  if (!args.menu) return;
  const menu = rows(kelta('records', 'list', 'ui-menus', '--filter', `name=${args.menu}`, '--size', '1'))[0];
  if (!menu) { log(`  menu ${args.menu}: not found, skipping nav item`); return; }
  const items = rows(kelta('records', 'list', 'ui-menu-items', '--filter', `menuId=${menu.id}`, '--all'));
  const path = `/dashboards/${dash.id}`;
  if (items.some((i) => i.path === path)) { log('  nav item: exists'); return; }
  const parent = args.parent ? items.find((i) => i.label === args.parent) : null;
  kelta('records', 'create', 'ui-menu-items', '--data', JSON.stringify({
    menuId: menu.id, parentId: parent ? parent.id : null, label: 'DORA', path, icon: 'gauge',
    displayOrder: items.length, active: true,
  }));
  log(`  nav item: created under ${parent ? parent.label : args.menu}`);
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue;
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith('--')) { out[argv[i].slice(2)] = next; i++; } else out[argv[i].slice(2)] = true;
  }
  return out;
}

log('DORA bootstrap');
const ids = {};
for (const def of COLLECTIONS) ids[def.name] = ensureCollection(def).id;
const report = ensureReport(ids['dora-deployments']);
const dash = ensureDashboard(report.id);
ensureMenuItem(dash);
console.log(JSON.stringify({ dashboardId: dash.id, collections: ids }));
