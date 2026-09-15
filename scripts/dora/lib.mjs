// Pure DORA computation. No I/O here — everything in this file is unit-testable
// with `node --test scripts/dora/`. collect.mjs does the fetching and wires these
// together.
//
// Vocabulary:
//   deployment — one image-tag bump commit in the ArgoCD repo (`chore: update Kelta
//                images to main-<sha>`). That is the unit ArgoCD rolls out, so it is
//                the unit DORA counts.
//   change     — one merged PR. A deployment ships every PR merged since the previous
//                deployment (the Build-and-Deploy concurrency group cancels in-flight
//                builds, so one bump routinely carries several merges).
//   incident   — one firing→resolved interval of a production alert.

export const WINDOWS = [7, 30, 90];

const BUMP_RE = /update Kelta images to main-([0-9a-f]{7,40})/;
const REAPPLY_RE = /re-apply.*main-([0-9a-f]{7,40})/i;
const AUTO_ROLLBACK_RE = /^revert: roll back image bump \((.*)\)/;
const RUN_ID_RE = /run (\d+)/;

/**
 * Classify one line of `git log --format=%H%x09%ct%x09%an%x09%s -- emf/` from the
 * ArgoCD repo. Returns null for commits that are not deploy-related (manifest
 * edits, resource tweaks).
 */
export function classifyArgoCommit(line) {
  const [hash, ts, author, ...rest] = line.split('\t');
  const subject = rest.join('\t');
  if (!hash || !ts) return null;
  const at = new Date(Number(ts) * 1000).toISOString();

  const reapply = subject.match(REAPPLY_RE);
  if (reapply) {
    return { hash, at, author, subject, kind: 'bump', sha: reapply[1], redeploy: true };
  }
  const bump = subject.match(BUMP_RE);
  if (bump) {
    return { hash, at, author, subject, kind: 'bump', sha: bump[1], redeploy: false };
  }
  const auto = subject.match(AUTO_ROLLBACK_RE);
  if (auto) {
    const run = auto[1].match(RUN_ID_RE);
    return {
      hash, at, author, subject, kind: 'rollback', automatic: true,
      reason: auto[1], runId: run ? run[1] : null,
    };
  }
  if (/^revert(\(|:)/i.test(subject) || /\broll(s|ed)? back\b/i.test(subject)) {
    return { hash, at, author, subject, kind: 'rollback', automatic: false, reason: subject, runId: null };
  }
  return null;
}

/** Oldest-first list of deploy-related ArgoCD events. Input is newest-first git log. */
export function parseArgoLog(text) {
  return text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map(classifyArgoCommit)
    .filter(Boolean)
    .reverse();
}

/**
 * Build deployments from the ordered ArgoCD events. A rollback attaches to the most
 * recent preceding bump that has not already been rolled back.
 */
export function buildDeployments(events) {
  const deployments = [];
  for (const ev of events) {
    if (ev.kind === 'bump') {
      deployments.push({
        sha: ev.sha,
        argoCommit: ev.hash,
        deployedAt: ev.at,
        redeploy: ev.redeploy,
        status: 'deployed',
        rollback: null,
      });
      continue;
    }
    // rollback
    for (let i = deployments.length - 1; i >= 0; i--) {
      if (deployments[i].status === 'deployed') {
        deployments[i].status = 'rolled_back';
        deployments[i].rollback = {
          at: ev.at, automatic: ev.automatic, reason: ev.reason, runId: ev.runId,
        };
        break;
      }
    }
  }
  return deployments;
}

/**
 * Attach merged PRs to the deployment that shipped them.
 *
 * mainHistory: oldest-first array of full SHAs on `main` (first-parent).
 * prs: [{ number, title, author, authorIsBot, mergedAt, mergeSha, firstCommitAt }]
 *
 * A PR ships in the first deployment whose SHA is at or after the PR's merge commit
 * on main. PRs after the last deployment are "unshipped" (returned separately).
 */
export function attachChanges(deployments, prs, mainHistory) {
  const index = new Map();
  mainHistory.forEach((sha, i) => {
    index.set(sha, i);
    index.set(sha.slice(0, 7), i);
  });

  const deploysByPos = deployments
    .filter((d) => !d.redeploy)
    .map((d) => ({ d, pos: index.get(d.sha.slice(0, 7)) }))
    .filter((x) => x.pos !== undefined)
    .sort((a, b) => a.pos - b.pos);

  for (const d of deployments) d.changes = [];
  const unshipped = [];

  for (const pr of prs) {
    const pos = index.get(pr.mergeSha) ?? index.get((pr.mergeSha || '').slice(0, 7));
    if (pos === undefined) {
      unshipped.push({ ...pr, reason: 'merge commit not on main first-parent history' });
      continue;
    }
    const target = deploysByPos.find((x) => x.pos >= pos);
    if (!target) {
      unshipped.push({ ...pr, reason: 'no deployment after merge yet' });
      continue;
    }
    const change = {
      ...pr,
      deploymentSha: target.d.sha,
      deployedAt: target.d.deployedAt,
      leadTimeSec: secondsBetween(pr.firstCommitAt, target.d.deployedAt),
      mergeToProdSec: secondsBetween(pr.mergedAt, target.d.deployedAt),
    };
    target.d.changes.push(change);
  }
  return { deployments, unshipped };
}

export function secondsBetween(fromIso, toIso) {
  if (!fromIso || !toIso) return null;
  const s = Math.round((Date.parse(toIso) - Date.parse(fromIso)) / 1000);
  return Number.isFinite(s) ? s : null;
}

/** Nearest-rank percentile on a numeric array. Returns null on empty input. */
export function percentile(values, p) {
  const xs = values.filter((v) => typeof v === 'number' && Number.isFinite(v)).sort((a, b) => a - b);
  if (xs.length === 0) return null;
  const rank = Math.ceil((p / 100) * xs.length);
  return xs[Math.max(0, Math.min(xs.length - 1, rank - 1))];
}

/**
 * Turn Grafana alert state-history entries (Loki `{from="state-history"}`) into
 * incidents. Each entry: { ts (ISO), previous, current, alertname, fingerprint, ruleUID }.
 * An incident opens on a transition into Alerting and closes on the next transition
 * out of it (Normal / NoData / Error) for the same fingerprint.
 */
export function pairStateTransitions(entries, now) {
  const byKey = new Map();
  const sorted = [...entries].sort((a, b) => Date.parse(a.ts) - Date.parse(b.ts));
  const incidents = [];
  for (const e of sorted) {
    const key = e.fingerprint || `${e.ruleUID || ''}:${e.alertname || ''}`;
    const firing = e.current === 'Alerting';
    const open = byKey.get(key);
    if (firing && !open) {
      byKey.set(key, { key, alertName: e.alertname || e.ruleTitle || 'unknown', firedAt: e.ts, source: 'grafana' });
    } else if (!firing && open && e.previous === 'Alerting') {
      incidents.push({ ...open, resolvedAt: e.ts, ttrSec: secondsBetween(open.firedAt, e.ts) });
      byKey.delete(key);
    }
  }
  for (const open of byKey.values()) {
    incidents.push({ ...open, resolvedAt: null, ttrSec: now ? secondsBetween(open.firedAt, now) : null, ongoing: true });
  }
  return incidents;
}

/**
 * Turn a Prometheus range-vector result for `ALERTS{alertstate="firing"}` into
 * incidents: one per contiguous run of samples on the same series. `stepSec` is the
 * query step; a gap larger than 2 steps closes the run.
 */
export function runsFromAlertSeries(result, stepSec, now) {
  const incidents = [];
  for (const series of result) {
    const name = series.metric?.alertname || 'unknown';
    const key = JSON.stringify(series.metric || {});
    let runStart = null;
    let last = null;
    for (const [t] of series.values || []) {
      const ts = Number(t);
      if (runStart === null) {
        runStart = ts; last = ts; continue;
      }
      if (ts - last > stepSec * 2) {
        incidents.push(mk(name, key, runStart, last + stepSec, false));
        runStart = ts;
      }
      last = ts;
    }
    if (runStart !== null) {
      const endsNow = now && (Date.parse(now) / 1000 - last) <= stepSec * 2;
      incidents.push(mk(name, key, runStart, endsNow ? null : last + stepSec, !!endsNow, now));
    }
  }
  return incidents;

  function mk(alertName, key, start, end, ongoing, nowIso) {
    const firedAt = new Date(start * 1000).toISOString();
    const resolvedAt = end ? new Date(end * 1000).toISOString() : null;
    return {
      key: `${alertName}:${firedAt}`, alertName, source: 'prometheus', firedAt, resolvedAt,
      ttrSec: secondsBetween(firedAt, resolvedAt || nowIso), ongoing, seriesKey: key,
    };
  }
}

/**
 * Keep only incidents whose alert name matches `pattern` (RegExp or string). Grafana
 * hosts alerts for more than the platform (fleet ops, other tenants' pollers) — DORA
 * time-to-restore is about the service, so the collector filters by name.
 */
export function filterIncidents(incidents, pattern) {
  if (!pattern) return incidents;
  const re = pattern instanceof RegExp ? pattern : new RegExp(pattern);
  return incidents.filter((i) => re.test(i.alertName || ''));
}

/** Google DORA bands (2023/2024 report thresholds). */
export function band(metric, value) {
  if (value === null || value === undefined) return null;
  switch (metric) {
    case 'deploysPerDay': // elite: on demand (>=1/day); high: weekly-monthly; medium: monthly-6mo
      return value >= 1 ? 'elite' : value >= 1 / 7 ? 'high' : value >= 1 / 30 ? 'medium' : 'low';
    case 'leadTimeSec': // elite <1d; high 1d-1w; medium 1w-1mo
      return value < 86400 ? 'elite' : value < 7 * 86400 ? 'high' : value < 30 * 86400 ? 'medium' : 'low';
    case 'changeFailureRate': // elite <=5%; high <=10%; medium <=15%
      return value <= 0.05 ? 'elite' : value <= 0.10 ? 'high' : value <= 0.15 ? 'medium' : 'low';
    case 'mttrSec': // elite <1h; high <1d; medium <1w
      return value < 3600 ? 'elite' : value < 86400 ? 'high' : value < 7 * 86400 ? 'medium' : 'low';
    default:
      return null;
  }
}

function inWindow(iso, startIso, endIso) {
  const t = Date.parse(iso);
  return t >= Date.parse(startIso) && t <= Date.parse(endIso);
}

/**
 * The four numbers for one trailing window ending at `now`.
 */
export function summarize({ deployments, incidents, windowDays, now, segment = 'all' }) {
  const end = now;
  const start = new Date(Date.parse(now) - windowDays * 86400 * 1000).toISOString();

  const deps = deployments.filter((d) => inWindow(d.deployedAt, start, end));
  const changes = deps.flatMap((d) => d.changes || []).filter((c) => segmentMatches(c, segment));
  const rolledBack = deps.filter((d) => d.status === 'rolled_back');
  // Ongoing incidents have a provisional ttrSec (measured to now) — reported, not averaged.
  const incs = incidents.filter((i) => inWindow(i.firedAt, start, end) && i.ttrSec !== null && !i.ongoing);

  const leadTimes = changes.map((c) => c.leadTimeSec).filter((v) => v !== null);
  const mergeToProd = changes.map((c) => c.mergeToProdSec).filter((v) => v !== null);
  const ttrs = incs.map((i) => i.ttrSec);

  const deploysPerDay = deps.length / windowDays;
  const leadTimeP50Sec = percentile(leadTimes, 50);
  const changeFailureRate = deps.length ? rolledBack.length / deps.length : null;
  const mttrP50Sec = percentile(ttrs, 50);

  return {
    window: `${windowDays}d`,
    windowDays,
    segment,
    from: start,
    to: end,
    deployments: deps.length,
    changes: changes.length,
    rollbacks: rolledBack.length,
    autoRollbacks: rolledBack.filter((d) => d.rollback?.automatic).length,
    incidents: incs.length,
    deploysPerDay: round(deploysPerDay, 3),
    leadTimeP50Sec,
    leadTimeP90Sec: percentile(leadTimes, 90),
    mergeToProdP50Sec: percentile(mergeToProd, 50),
    changeFailureRate: changeFailureRate === null ? null : round(changeFailureRate, 4),
    mttrP50Sec,
    mttrP90Sec: percentile(ttrs, 90),
    bands: {
      deployFrequency: band('deploysPerDay', deploysPerDay),
      leadTime: band('leadTimeSec', leadTimeP50Sec),
      changeFailureRate: band('changeFailureRate', changeFailureRate),
      timeToRestore: band('mttrSec', mttrP50Sec),
    },
  };
}

function segmentMatches(change, segment) {
  if (segment === 'all') return true;
  if (segment === 'bot') return change.authorIsBot === true;
  if (segment === 'human') return change.authorIsBot !== true;
  return true;
}

export function round(v, places) {
  if (v === null || v === undefined) return v;
  const f = 10 ** places;
  return Math.round(v * f) / f;
}

/** Stable, key-sorted JSON so re-pushed Loki lines dedupe byte-for-byte. */
export function stableStringify(obj) {
  return JSON.stringify(sortKeys(obj));
}

function sortKeys(v) {
  if (Array.isArray(v)) return v.map(sortKeys);
  if (v && typeof v === 'object') {
    return Object.keys(v).sort().reduce((acc, k) => {
      if (v[k] !== undefined) acc[k] = sortKeys(v[k]);
      return acc;
    }, {});
  }
  return v;
}

/** Map a GitHub GraphQL search node to the PR shape attachChanges wants. */
export function normalizePr(node) {
  const dates = (node.commits?.nodes || [])
    .map((n) => n.commit?.authoredDate || n.commit?.committedDate)
    .filter(Boolean)
    .sort();
  const login = node.author?.login || 'unknown';
  const isBot = node.author?.__typename === 'Bot' || /\[bot\]$/.test(login) || /-bot$/.test(login);
  return {
    number: node.number,
    title: node.title,
    author: login,
    authorIsBot: isBot,
    mergedAt: node.mergedAt,
    mergeSha: node.mergeCommit?.oid || null,
    firstCommitAt: dates[0] || node.createdAt,
    commitCount: node.commits?.totalCount ?? dates.length,
  };
}
