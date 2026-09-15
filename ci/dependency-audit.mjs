#!/usr/bin/env node
/**
 * Fails CI on NEW high/critical advisories in production npm dependencies.
 *
 * Why a baseline instead of a plain `npm audit --audit-level=high`:
 * the frontend currently carries a backlog (2 critical + 5 high in kelta-ui/app
 * production deps alone), so a plain threshold gate would fail on day one and
 * red main is a deploy outage, not a signal. This gates the delta — every newly
 * introduced advisory blocks, while the known backlog is listed explicitly in
 * ci/npm-audit-baseline.json so it is visible and burn-down-able rather than
 * silently tolerated.
 *
 * devDependencies are excluded (--omit=dev): build tooling does not ship to a
 * browser, and including it triples the count with noise that cannot be acted on
 * the same way.
 *
 * Usage:
 *   node ci/dependency-audit.mjs                 # check (CI)
 *   node ci/dependency-audit.mjs --update        # regenerate the baseline
 */
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const BASELINE = join(ROOT, 'ci', 'npm-audit-baseline.json')
const PACKAGES = ['kelta-web', 'kelta-ui/app']
const BLOCKING = new Set(['high', 'critical'])
const update = process.argv.includes('--update')

/** GHSA ids at/above the blocking severity, keyed by advisory id. */
function auditPackage(pkgDir) {
  const cwd = join(ROOT, pkgDir)
  if (!existsSync(join(cwd, 'package.json'))) {
    throw new Error(`No package.json in ${pkgDir}`)
  }
  let raw
  try {
    // npm audit exits non-zero when findings exist — that is expected, read stdout anyway.
    raw = execFileSync('npm', ['audit', '--omit=dev', '--json'], {
      cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'],
    })
  } catch (e) {
    raw = e.stdout
    if (!raw) throw new Error(`npm audit produced no output in ${pkgDir}: ${e.message}`)
  }
  const parsed = JSON.parse(raw)
  const found = {}
  for (const [name, v] of Object.entries(parsed.vulnerabilities ?? {})) {
    for (const via of v.via ?? []) {
      if (typeof via !== 'object') continue
      if (!BLOCKING.has(via.severity)) continue
      const id = (via.url ?? '').split('/').pop()
      if (!id) continue
      found[id] = `${via.severity} | ${name} | ${String(via.title ?? '').slice(0, 100)}`
    }
  }
  return found
}

const results = Object.fromEntries(PACKAGES.map((p) => [p, auditPackage(p)]))

if (update) {
  writeFileSync(
    BASELINE,
    JSON.stringify(
      {
        _comment:
          'Known high/critical advisories in PRODUCTION npm deps, accepted as a backlog so CI ' +
          'can gate NEW ones. This is debt to burn down, not an allowlist to grow. Regenerate ' +
          'with: node ci/dependency-audit.mjs --update',
        _generated: new Date().toISOString().slice(0, 10),
        packages: results,
      },
      null,
      2
    ) + '\n'
  )
  console.log(`Baseline written: ${BASELINE}`)
  for (const [pkg, adv] of Object.entries(results)) {
    console.log(`  ${pkg}: ${Object.keys(adv).length} advisories`)
  }
  process.exit(0)
}

if (!existsSync(BASELINE)) {
  console.error(`Baseline missing: ${BASELINE}\nRun: node ci/dependency-audit.mjs --update`)
  process.exit(1)
}
const baseline = JSON.parse(readFileSync(BASELINE, 'utf8')).packages ?? {}

let newCount = 0
let fixedCount = 0
for (const pkg of PACKAGES) {
  const known = baseline[pkg] ?? {}
  const current = results[pkg]
  const added = Object.keys(current).filter((id) => !(id in known))
  const gone = Object.keys(known).filter((id) => !(id in current))
  console.log(`\n${pkg}: ${Object.keys(current).length} high/critical (baseline ${Object.keys(known).length})`)
  for (const id of added) {
    newCount++
    console.error(`  NEW      ${id}  ${current[id]}`)
  }
  for (const id of gone) {
    fixedCount++
    console.log(`  resolved ${id}  (was: ${known[id]})`)
  }
}

if (fixedCount) {
  console.log(
    `\n${fixedCount} advisory(ies) no longer present — refresh the baseline:\n` +
      '  node ci/dependency-audit.mjs --update'
  )
}
if (newCount) {
  console.error(
    `\nFAIL: ${newCount} new high/critical advisory(ies) in production dependencies.\n` +
      'Upgrade the dependency, or — only with a written reason — add it to\n' +
      'ci/npm-audit-baseline.json via: node ci/dependency-audit.mjs --update'
  )
  process.exit(1)
}
console.log('\nOK: no new high/critical advisories in production dependencies.')
