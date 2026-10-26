#!/usr/bin/env node
/**
 * Fails CI on NEW high/critical advisories in the Java the services ship.
 *
 * The Java tree had no vulnerability scanning. OWASP dependency-check (#1471) needs an NVD API
 * key and never ran; this needs none:
 *   1. Maven resolves each service the way it ships (Spring Boot dependency management, the
 *      unpublished io.kelta runtime libs from the local repo) and writes a CycloneDX SBOM of its
 *      runtime classpath — compile + runtime scope; test/provided/system do not ship.
 *      (osv-scanner's own pom resolver cannot do this: it fails on the io.kelta SNAPSHOTs.)
 *   2. The OpenTelemetry Java agent, which some images download in their Dockerfile and run
 *      in-process with -javaagent, is outside every Maven classpath. Every version pinned in a
 *      service Dockerfile is written into one more SBOM, target "otel-javaagent".
 *   3. osv-scanner checks every component of each SBOM against osv.dev (Maven ecosystem).
 * Out of scope: base images, the JRE and OS packages.
 *
 * Gated like ci/dependency-audit.mjs (npm): a delta against ci/java-audit-baseline.json, so the
 * known backlog is visible and burn-down-able while every newly introduced high/critical blocks.
 * Blocking = CVSS >= 7.0, or the advisory database labels it HIGH/CRITICAL (some GHSA entries
 * carry a label but no score). An advisory counts as baselined when ANY of its ids (GHSA, CVE
 * aliases) is listed for that target, so a new alias sorting first cannot turn it "new".
 * Every baselined id needs a hand-written line in `_reasons`; the check fails without one.
 * --update rewrites the advisory lists, keeps reasons for ids still present and drops the rest.
 *
 * Prerequisites: mvn and Java 25 on PATH, the runtime libs installed in the local repository
 * (CLAUDE.md → Build), osv-scanner v2 on PATH or in $OSV_SCANNER.
 *
 * Usage:
 *   node ci/java-dependency-audit.mjs            # check (CI)
 *   node ci/java-dependency-audit.mjs --update   # regenerate the baseline
 *   node ci/java-dependency-audit.mjs --no-sbom  # reuse existing service SBOMs (not regenerated)
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// JAVA_AUDIT_ROOT: tests point the script at a throwaway tree (ci/java-dependency-audit.test.ts).
const ROOT = process.env.JAVA_AUDIT_ROOT || resolve(dirname(fileURLToPath(import.meta.url)), '..')
const BASELINE = join(ROOT, 'ci', 'java-audit-baseline.json')
const SERVICES = ['gateway', 'worker', 'auth', 'ai', 'mcp']
const AGENT = 'otel-javaagent'
const CYCLONEDX = 'org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom'
const OSV = process.env.OSV_SCANNER || 'osv-scanner'
const update = process.argv.includes('--update')
const skipSbom = process.argv.includes('--no-sbom')

function sbomPath(target) {
  return target === AGENT
    ? join(ROOT, 'target', 'java-audit', `${AGENT}.cdx.json`)
    : join(ROOT, `kelta-${target}`, 'target', `${target}.cdx.json`)
}

function writeServiceSbom(svc) {
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

const AGENT_URL = /opentelemetry-java-instrumentation\/releases\/download\/v([0-9][^/\s]*)\/opentelemetry-javaagent\.jar/g

/** Every OpenTelemetry agent version a service Dockerfile downloads, as a synthetic SBOM. */
function writeAgentSbom() {
  const versions = new Set()
  for (const svc of SERVICES) {
    for (const name of ['Dockerfile', 'Dockerfile.jvm']) {
      const file = join(ROOT, `kelta-${svc}`, name)
      if (!existsSync(file)) continue
      for (const m of readFileSync(file, 'utf8').matchAll(AGENT_URL)) versions.add(m[1])
    }
  }
  const components = [...versions].sort().map((v) => ({
    type: 'library',
    group: 'io.opentelemetry.javaagent',
    name: 'opentelemetry-javaagent',
    version: v,
    purl: `pkg:maven/io.opentelemetry.javaagent/opentelemetry-javaagent@${v}`,
  }))
  mkdirSync(dirname(sbomPath(AGENT)), { recursive: true })
  writeFileSync(
    sbomPath(AGENT),
    JSON.stringify({ bomFormat: 'CycloneDX', specVersion: '1.5', version: 1, components }, null, 2)
  )
  return components.length
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

/** Blocking advisories for one target: primary id → { ids, text }. */
function scan(target) {
  let raw
  try {
    raw = execFileSync(OSV, ['scan', 'source', '-L', sbomPath(target), '--format', 'json'], {
      cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    })
  } catch (e) {
    // Exit 1 means "vulnerabilities found" — the JSON is on stdout. Anything else (127 missing
    // file, 128 no packages, …) is an error.
    if (e.status !== 1 || !e.stdout) {
      throw new Error(`osv-scanner failed for ${target} (exit ${e.status}): ${String(e.stderr ?? e.message).trim()}`)
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
        const groupLabels = group.ids.map((id) => byId[id])
        const band = score >= 9 || groupLabels.includes('CRITICAL') ? 'critical'
          : score >= 7 || groupLabels.includes('HIGH') ? 'high' : null
        if (!band) continue
        const v = pkg.vulnerabilities?.find((x) => group.ids.includes(x.id) && x.summary)
        found[group.ids[0]] = {
          ids: group.ids,
          text: `${band} | ${pkg.package.name}@${pkg.package.version} | ${String(v?.summary ?? '').slice(0, 100)}`,
        }
      }
    }
  }
  // osv-scanner reports only packages that HAVE advisories; count what was scanned from the SBOM.
  const packages = (JSON.parse(readFileSync(sbomPath(target), 'utf8')).components ?? []).length
  return { found, packages }
}

for (const svc of SERVICES) {
  if (skipSbom) {
    if (!existsSync(sbomPath(svc))) throw new Error(`--no-sbom, but ${sbomPath(svc)} is missing`)
  } else {
    console.log(`Resolving kelta-${svc} → ${sbomPath(svc)}`)
    writeServiceSbom(svc)
  }
}
const agentVersions = writeAgentSbom()
// No pinned agent (it was removed from every image) means nothing to scan for that target.
const TARGETS = agentVersions > 0 ? [...SERVICES, AGENT] : SERVICES

const scans = Object.fromEntries(TARGETS.map((t) => [t, scan(t)]))
for (const t of TARGETS) {
  if (scans[t].packages === 0) {
    // An empty SBOM would pass vacuously — that is a broken scan, not a clean one.
    console.error(`FAIL: no packages in ${sbomPath(t)}`)
    process.exit(1)
  }
}
const results = Object.fromEntries(
  TARGETS.map((t) => [t, Object.fromEntries(Object.entries(scans[t].found).map(([id, f]) => [id, f.text]))])
)
const label = (t) => (t === AGENT ? `${AGENT} (Dockerfile -javaagent)` : `kelta-${t}`)

if (update) {
  const previous = existsSync(BASELINE) ? JSON.parse(readFileSync(BASELINE, 'utf8')) : {}
  const present = new Set(TARGETS.flatMap((t) => Object.keys(results[t])))
  const reasons = Object.fromEntries(Object.entries(previous._reasons ?? {}).filter(([id]) => present.has(id)))
  writeFileSync(
    BASELINE,
    JSON.stringify(
      {
        _comment:
          'Known high/critical advisories in the Java the services ship, accepted as a backlog so ' +
          'CI can gate NEW ones. Debt to burn down, not an allowlist to grow. Every id needs a ' +
          'written line in _reasons (the check fails without one). Regenerate with: ' +
          'node ci/java-dependency-audit.mjs --update',
        _generated: new Date().toISOString().slice(0, 10),
        _reasons: reasons,
        services: results,
      },
      null,
      2
    ) + '\n'
  )
  console.log(`Baseline written: ${BASELINE}`)
  for (const t of TARGETS) {
    console.log(`  ${label(t)}: ${Object.keys(results[t]).length} advisories (${scans[t].packages} packages)`)
  }
  const missing = [...present].filter((id) => !(id in reasons))
  if (missing.length) {
    console.log(`\nAdd a line to _reasons for: ${missing.join(', ')} — the check fails until you do.`)
  }
  process.exit(0)
}

if (!existsSync(BASELINE)) {
  console.error(`Baseline missing: ${BASELINE}\nRun: node ci/java-dependency-audit.mjs --update`)
  process.exit(1)
}
const baselineFile = JSON.parse(readFileSync(BASELINE, 'utf8'))
const baseline = baselineFile.services ?? {}
const reasons = baselineFile._reasons ?? {}

let newCount = 0
let fixedCount = 0
let unexplained = 0
for (const t of TARGETS) {
  const known = baseline[t] ?? {}
  const current = scans[t].found
  const isKnown = (f) => f.ids.some((id) => id in known)
  const added = Object.entries(current).filter(([, f]) => !isKnown(f))
  const currentIds = new Set(Object.values(current).flatMap((f) => f.ids))
  const gone = Object.keys(known).filter((id) => !currentIds.has(id))
  console.log(
    `\n${label(t)}: ${scans[t].packages} packages, ${Object.keys(current).length} high/critical ` +
      `(baseline ${Object.keys(known).length})`
  )
  for (const [id, f] of added) {
    newCount++
    console.error(`  NEW      ${id}  ${f.text}`)
  }
  for (const id of gone) {
    fixedCount++
    console.log(`  resolved ${id}  (was: ${known[id]})`)
  }
  for (const id of Object.keys(known)) {
    if (!reasons[id]) {
      unexplained++
      console.error(`  NO REASON ${id} is baselined without a line in _reasons`)
    }
  }
}

if (fixedCount) {
  console.log(
    `\n${fixedCount} advisory(ies) no longer present — refresh the baseline:\n` +
      '  node ci/java-dependency-audit.mjs --update'
  )
}
if (newCount || unexplained) {
  if (newCount) {
    console.error(
      `\nFAIL: ${newCount} new high/critical advisory(ies) in shipped Java.\n` +
        'Upgrade the dependency, or — only with a written reason in _reasons — add it to\n' +
        'ci/java-audit-baseline.json via: node ci/java-dependency-audit.mjs --update\n' +
        '(On a PR that does not touch dependencies, this means an advisory was just published.)'
    )
  }
  if (unexplained) {
    console.error(`\nFAIL: ${unexplained} baselined advisory(ies) have no line in _reasons.`)
  }
  process.exit(1)
}
console.log('\nOK: no new high/critical advisories in shipped Java.')
