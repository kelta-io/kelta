// Pure helpers for .github/workflows/release.yml. No I/O here: release.ts does the
// git/compose calls and lib.test.ts covers the decisions (run with Node >= 24, which
// strips the type annotations natively — no build step, no dependency).

export type Image = { service: string; dockerfile: string; args: Record<string, string> };

export type Plan = {
  publish: boolean;
  tag: string;
  version: string;
  prerelease: boolean;
  latest: boolean;
};

export type RunContext = {
  event: string;
  refType: string;
  refName: string;
  dryRunInput: string;
  sha: string;
};

const SEMVER_TAG = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$/;

// The trigger glob `v*.*.*` also matches tags like `v1.2.3.4` or `v1.2.3+build`, and a
// `+` is not even legal in an image tag, so the tag is validated here, not trusted.
export function parseTag(tag: string): { version: string; prerelease: boolean } {
  const m = SEMVER_TAG.exec(tag);
  if (!m) {
    throw new Error(`tag "${tag}" is not vMAJOR.MINOR.PATCH[-prerelease]`);
  }
  return { version: tag.slice(1), prerelease: m[4] !== undefined };
}

export function resolvePlan(ctx: RunContext): Plan {
  const publish =
    ctx.event === 'push' || (ctx.event === 'workflow_dispatch' && ctx.dryRunInput === 'false');
  if (publish && ctx.refType !== 'tag') {
    throw new Error(
      `publishing needs a v*.*.* tag ref, got ${ctx.refType} "${ctx.refName}" — dispatch on a tag or keep dry_run`,
    );
  }
  if (ctx.refType === 'tag') {
    const { version, prerelease } = parseTag(ctx.refName);
    return { publish, tag: ctx.refName, version, prerelease, latest: !prerelease };
  }
  // Dry run off a branch or PR: a version-shaped placeholder that is never pushed.
  return {
    publish: false,
    tag: '',
    version: `0.0.0-dryrun.${ctx.sha.slice(0, 7)}`,
    prerelease: true,
    latest: false,
  };
}

type ComposeService = {
  image?: string;
  build?: { dockerfile?: string; args?: Record<string, string | null> };
  profiles?: string[];
};

type ComposeConfig = { services: Record<string, ComposeService> };

const inDefaultStack = (s: ComposeService): boolean => !(s.profiles && s.profiles.length > 0);

// Services the default (profile-less) stack builds from source, as compose resolves them.
// Read from the from-source file set (docker-compose.yml + docker-compose.build.yml +
// docker-compose.jvm.yml): the base file alone builds nothing.
export function composeBuiltImages(config: ComposeConfig): Image[] {
  return Object.entries(config.services)
    .filter(([, s]) => s.build && inDefaultStack(s))
    .map(([service, s]) => ({
      service,
      dockerfile: s.build?.dockerfile ?? 'Dockerfile',
      args: Object.fromEntries(
        Object.entries(s.build?.args ?? {}).map(([k, v]) => [k, v ?? '']),
      ),
    }))
    .sort((a, b) => a.service.localeCompare(b.service));
}

// The base docker-compose.yml is the self-hoster's quickstart: every released service must
// be pulled from `<registry>/<service>:<tag>` there, nothing in the default stack may be
// built, and nothing pulled from the registry may be missing from the release.
export function pinDrift(release: Image[], base: ComposeConfig, registry: string): string[] {
  const errors: string[] = [];
  const released = new Set(release.map((i) => i.service));
  for (const { service } of release) {
    const s = base.services[service];
    if (!s?.image?.startsWith(`${registry}/${service}:`)) {
      errors.push(`${service}: base compose runs ${s?.image ?? '(no image)'}, not the released ${registry}/${service}:<version>`);
    }
  }
  for (const [service, s] of Object.entries(base.services)) {
    if (!inDefaultStack(s)) continue;
    if (s.build) {
      errors.push(`${service}: base compose builds from source; move its build: to docker-compose.build.yml`);
    }
    if (s.image?.startsWith(`${registry}/`) && !released.has(service)) {
      errors.push(`${service}: base compose pulls ${s.image}, which the release does not publish`);
    }
  }
  return errors;
}

// Every compose service must be released from the same Dockerfile with the same build
// args. The release list may add args compose does not pass (kelta-ui's BASE_REGISTRY),
// never drop or change one.
export function imageDrift(release: Image[], compose: Image[]): string[] {
  const errors: string[] = [];
  const byName = new Map(release.map((i) => [i.service, i]));
  const composeNames = new Set(compose.map((i) => i.service));
  for (const want of compose) {
    const have = byName.get(want.service);
    if (!have) {
      errors.push(`${want.service}: built by the quickstart stack but not released`);
      continue;
    }
    if (have.dockerfile !== want.dockerfile) {
      errors.push(`${want.service}: released from ${have.dockerfile}, compose builds ${want.dockerfile}`);
    }
    for (const [k, v] of Object.entries(want.args)) {
      if (have.args[k] !== v) {
        errors.push(`${want.service}: build arg ${k}=${have.args[k] ?? '(unset)'}, compose passes ${k}=${v}`);
      }
    }
  }
  for (const have of release) {
    if (!composeNames.has(have.service)) {
      errors.push(`${have.service}: released but not built by the quickstart stack`);
    }
  }
  return errors;
}

export type Commit = { sha: string; subject: string };

const CONVENTIONAL = /^(\w+)(\([^)]*\))?(!)?:\s*(.+)$/;

const SECTIONS = [
  { key: 'feat', title: 'Features' },
  { key: 'fix', title: 'Bug fixes' },
  { key: 'other', title: 'Other changes' },
] as const;

// GitHub rejects a release body over 125,000 characters; the first release covers the
// whole history, so each section is capped and the rest is left to the compare link.
export const MAX_PER_SECTION = 250;

export function renderNotes(opts: {
  commits: Commit[];
  repo: string;
  previousTag: string;
  ref: string;
}): string {
  const groups: Record<string, string[]> = { feat: [], fix: [], other: [] };
  for (const c of opts.commits) {
    const m = CONVENTIONAL.exec(c.subject);
    const type = m?.[1]?.toLowerCase();
    const key = type === 'feat' || type === 'fix' ? type : 'other';
    const breaking = m?.[3] ? '**BREAKING** ' : '';
    groups[key].push(`- ${breaking}${c.subject} (${c.sha.slice(0, 7)})`);
  }

  const range = opts.previousTag ? `since ${opts.previousTag}` : 'since the start of the history';
  const n = opts.commits.length;
  const lines = [`${n} ${n === 1 ? 'commit' : 'commits'} ${range}.`, ''];
  for (const { key, title } of SECTIONS) {
    const entries = groups[key];
    if (entries.length === 0) continue;
    lines.push(`## ${title}`, '', ...entries.slice(0, MAX_PER_SECTION));
    if (entries.length > MAX_PER_SECTION) {
      lines.push(`- …and ${entries.length - MAX_PER_SECTION} more`);
    }
    lines.push('');
  }
  if (opts.previousTag) {
    lines.push(`**Full changelog**: https://github.com/${opts.repo}/compare/${opts.previousTag}...${opts.ref}`);
  } else {
    lines.push(`**Full history**: https://github.com/${opts.repo}/commits/${opts.ref}`);
  }
  return lines.join('\n') + '\n';
}
