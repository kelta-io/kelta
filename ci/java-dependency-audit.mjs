#!/usr/bin/env node
/**
 * Fails CI on NEW high/critical advisories in the services' shipped Java dependencies.
 *
 * The Java tree had no vulnerability scanning. OWASP dependency-check (#1471) needs an NVD API
 * key and never ran; this needs none:
 *   1. Maven resolves each service the way it ships (Spring Boot dependency management, the
 *      unpublished io.kelta runtime libs from the local repo) and writes a CycloneDX SBOM of its
 *      runtime classpath — compile + runtime scope; test/provided/system do not ship.
 *      (osv-scanner's own pom resolver cannot do this: it fails on the io.kelta SNAPSHOTs.)
 *   2. osv-scanner checks every component of each SBOM against osv.dev (GitHub advisories,
 *      Maven ecosystem).
 *
 * Gated like ci/dependency-audit.mjs (npm): a delta against ci/java-audit-baseline.json, so the
 * known backlog is visible and burn-down-able while every newly introduced high/critical blocks.
 * Blocking = CVSS >= 7.0, or the advisory database labels it HIGH/CRITICAL (some GHSA entries
 * carry a label but no score). `_reasons` in the baseline is hand-written and kept by --update.
 *
 * Prerequisites: mvn and Java 25 on PATH, the runtime libs installed in the local repository
 * (CLAUDE.md → Build), osv-scanner v2 on PATH or in $OSV_SCANNER.
 *
 * Usage:
 *   node ci/java-dependency-audit.mjs            # check (CI)
 *   node ci/java-dependency-audit.mjs --update   # regenerate the baseline
 *   node ci/java-dependency-audit.mjs --no-sbom  # reuse kelta-<svc>/target/<svc>.cdx.json
 */
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// JAVA_AUDIT_ROOT: tests point the script at a throwaway tree (ci/java-dependency-audit.test.ts).
const ROOT = process.env.JAVA_AUDIT_ROOT || resolve(dirname(fileURLToPath(import.meta.url)), '..')
const BASELINE = join(ROOT, 'ci', 'java-audit-baseline.json')
const SERVICES = ['gateway', 'worker', 'auth', 'ai', 'mcp']
const CYCLONEDX = 'org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom'
const OSV = process.env.OSV_SCANNER || 'osv-scanner'
const update = process.argv.includes('--update')
const skipSbom = process.argv.includes('--no-sbom')

function sbomPath(svc) {
  return join(ROOT, `kelta-${svc}`, 'target', `${svc}.cdx.json`)
}

function writeSbom(svc) {
  execFileSync(
    'mvn',
    [
      '-q', '-B', '-f', join(ROOT, `kelta-${svc}`, 'pom.xml'), CYCLONEDX,
      '-DoutputFormat=json', `-DoutputName=${svc}.cdx`,
      '-DincludeTestScope=false', '-DincludeProvidedScope=false', '-DincludeSystemScope=false',
    ],
    { cwd: ROOT, stdio: ['ignore', 'inherit', 'inherit'] }
  )
  if (!existsSync(sbomPath(svc))) throw new Error(`No SBOM written for ${svc}: ${sbomPath(svc)}`)
}

/** Advisory id → its database severity label ("HIGH", …), from a scan result. */
function labels(pkg) {
  const out = {}
  for (const v of pkg.vulnerabilities ?? []) {
    const label = v.database_specific?.severity
    if (label) out[v.id] = String(label).toUpperCase()
  }
  return out
}

/** Blocking advisories for one service, keyed by the group's primary id. */
function scan(svc) {
  let raw
  try {
    raw = execFileSync(OSV, ['scan', 'source', '-L', sbomPath(svc), '--format', 'json'], {
      cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    })
  } catch (e) {
    // Exit 1 means "vulnerabilities found" — the JSON is on stdout. Anything else is an error.
    if (e.status !== 1 || !e.stdout) {
      throw new Error(`osv-scanner failed for ${svc} (exit ${e.status}): ${String(e.stderr ?? e.message).trim()}`)
    }
    raw = e.stdout
  }
  const parsed = JSON.parse(raw)
  const found = {}
  for (const result of parsed.results ?? []) {
    for (const pkg of result.packages ?? []) {
      const byId = labels(pkg)
      for (const group of pkg.groups ?? []) {
        const score = Number.parseFloat(group.max_severity)
        const label = group.ids.map((id) => byId[id]).find((l) => l === 'CRITICAL' || l === 'HIGH')
        const band = score >= 9 || label === 'CRITICAL' ? 'critical'
          : score >= 7 || label === 'HIGH' ? 'high' : null
        if (!band) continue
        const v = pkg.vulnerabilities?.find((x) => x.id === group.ids[0])
        found[group.ids[0]] =
          `${band} | ${pkg.package.name}@${pkg.package.version} | ${String(v?.summary ?? '').slice(0, 100)}`
      }
    }
  }
  // osv-scanner reports only packages that HAVE advisories; count what was scanned from the SBOM.
  const packages = (JSON.parse(readFileSync(sbomPath(svc), 'utf8')).components ?? []).length
  return { found, packages }
}

for (const svc of SERVICES) {
  if (skipSbom) {
    if (!existsSync(sbomPath(svc))) throw new Error(`--no-sbom, but ${sbomPath(svc)} is missing`)
  } else {
    console.log(`Resolving kelta-${svc} → ${sbomPath(svc)}`)
    writeSbom(svc)
  }
}
const scans = Object.fromEntries(SERVICES.map((svc) => [svc, scan(svc)]))
const results = Object.fromEntries(SERVICES.map((svc) => [svc, scans[svc].found]))
for (const svc of SERVICES) {
  if (scans[svc].packages === 0) {
    // An empty SBOM would pass vacuously — that is a broken scan, not a clean one.
    console.error(`FAIL: osv-scanner read no packages from ${sbomPath(svc)}`)
    process.exit(1)
  }
}

if (update) {
  const previous = existsSync(BASELINE) ? JSON.parse(readFileSync(BASELINE, 'utf8')) : {}
  writeFileSync(
    BASELINE,
    JSON.stringify(
      {
        _comment:
          'Known high/critical advisories in the services\' SHIPPED Java dependencies, accepted as a ' +
          'backlog so CI can gate NEW ones. Debt to burn down, not an allowlist to grow: every entry ' +
          'needs a line in _reasons. Regenerate with: node ci/java-dependency-audit.mjs --update',
        _generated: new Date().toISOString().slice(0, 10),
        _reasons: previous._reasons ?? {},
        services: results,
      },
      null,
      2
    ) + '\n'
  )
  console.log(`Baseline written: ${BASELINE}`)
  for (const [svc, adv] of Object.entries(results)) {
    console.log(`  kelta-${svc}: ${Object.keys(adv).length} advisories (${scans[svc].packages} packages)`)
  }
  process.exit(0)
}

if (!existsSync(BASELINE)) {
  console.error(`Baseline missing: ${BASELINE}\nRun: node ci/java-dependency-audit.mjs --update`)
  process.exit(1)
}
const baseline = JSON.parse(readFileSync(BASELINE, 'utf8')).services ?? {}

let newCount = 0
let fixedCount = 0
for (const svc of SERVICES) {
  const known = baseline[svc] ?? {}
  const current = results[svc]
  const added = Object.keys(current).filter((id) => !(id in known))
  const gone = Object.keys(known).filter((id) => !(id in current))
  console.log(
    `\nkelta-${svc}: ${scans[svc].packages} packages, ${Object.keys(current).length} high/critical ` +
      `(baseline ${Object.keys(known).length})`
  )
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
      '  node ci/java-dependency-audit.mjs --update'
  )
}
if (newCount) {
  console.error(
    `\nFAIL: ${newCount} new high/critical advisory(ies) in shipped Java dependencies.\n` +
      'Upgrade the dependency, or — only with a written reason in _reasons — add it to\n' +
      'ci/java-audit-baseline.json via: node ci/java-dependency-audit.mjs --update'
  )
  process.exit(1)
}
console.log('\nOK: no new high/critical advisories in shipped Java dependencies.')
