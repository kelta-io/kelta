import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { externalMeta } from '../src/docs/externalMeta';
import { SECTIONS, SECTION_IDS } from '../src/docs/sidebar';

const repoRoot = join(import.meta.dirname, '..', '..');
const marketingRoot = join(repoRoot, 'kelta-marketing');
const contentRoot = join(marketingRoot, 'src', 'content', 'docs');
const read = (...parts: string[]) => readFileSync(join(repoRoot, ...parts), 'utf8');

const EXTERNAL_SOURCES: Record<string, string> = {
  'authoring/jsonapi': 'docs/authoring/jsonapi.md',
  'authoring/api-access': 'docs/authoring/api-access.md',
  'authoring/page-layouts': 'docs/authoring/page-layouts.md',
  'authoring/list-views': 'docs/authoring/list-views.md',
  'authoring/dashboards': 'docs/authoring/dashboards.md',
  'authoring/ui-pages': 'docs/authoring/ui-pages.md',
  'authoring/ui-menus': 'docs/authoring/ui-menus.md',
  'cli/commands': 'kelta-web/packages/cli/COMMANDS.md',
  'cli/agents': 'kelta-web/packages/cli/AGENTS.md',
};

interface LocalDoc {
  file: string;
  id: string;
  frontmatter: Record<string, string>;
  body: string;
}

function parseFrontmatter(source: string): { frontmatter: Record<string, string>; body: string } {
  const match = source.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) return { frontmatter: {}, body: source };
  const frontmatter: Record<string, string> = {};
  for (const line of match[1].split('\n')) {
    const kv = line.match(/^([A-Za-z_]+):\s*(.*)$/);
    if (kv) frontmatter[kv[1]] = kv[2].replace(/^['"]|['"]$/g, '');
  }
  return { frontmatter, body: match[2] };
}

function localDocs(): LocalDoc[] {
  return readdirSync(contentRoot, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile() && e.name.endsWith('.md'))
    .map((e) => {
      const file = join(e.parentPath, e.name);
      const id = file.slice(contentRoot.length + 1).replace(/\.md$/, '').replace(/\/index$/, '');
      return { file, id, ...parseFrontmatter(readFileSync(file, 'utf8')) };
    });
}

/** First `# ` heading outside fenced code blocks (a bash `# comment` inside a fence is not a heading). */
function firstH1(source: string): string | undefined {
  return source.replace(/^```[\s\S]*?^```/gm, '').match(/^# (.+)$/m)?.[1];
}

describe('docs engine sources', () => {
  it('reads the canonical authoring docs and generated CLI docs in place', () => {
    const config = read('kelta-marketing', 'src', 'content.config.ts');
    expect(config).toContain("base: '../docs/authoring'");
    expect(config).toContain("base: '../kelta-web/packages/cli'");
    for (const source of Object.values(EXTERNAL_SOURCES)) {
      expect(existsSync(join(repoRoot, source)), `${source} missing`).toBe(true);
    }
  });

  it('has sidecar metadata for every external doc, and no orphan metadata', () => {
    const authoring = readdirSync(join(repoRoot, 'docs', 'authoring')).filter((f) => f.endsWith('.md'));
    for (const file of authoring) {
      expect(externalMeta, `externalMeta lacks authoring/${file}`).toHaveProperty(`authoring/${file.replace(/\.md$/, '')}`);
    }
    for (const key of Object.keys(externalMeta)) {
      expect(EXTERNAL_SOURCES, `${key} has metadata but no known source`).toHaveProperty(key);
    }
  });

  it('keeps external titles equal to the source h1 so the sidebar never drifts from the canonical doc', () => {
    for (const [key, source] of Object.entries(EXTERNAL_SOURCES)) {
      expect(externalMeta[key as keyof typeof externalMeta].title, key).toBe(firstH1(read(source)));
    }
  });
});

describe('docs content', () => {
  const docs = localDocs();

  it('has at least one page per registered section', () => {
    const sections = new Set([...docs.map((d) => d.frontmatter.section), ...Object.values(externalMeta).map((m) => m.section)]);
    for (const id of SECTION_IDS) {
      expect(sections.has(id), `section ${id} has no pages`).toBe(true);
    }
  });

  it('every page has complete frontmatter and no h1 in the body', () => {
    expect(docs.length).toBeGreaterThan(0);
    for (const doc of docs) {
      const { frontmatter, body } = doc;
      expect(frontmatter.title, `${doc.id}: title`).toBeTruthy();
      expect(frontmatter.description, `${doc.id}: description`).toBeTruthy();
      expect(SECTION_IDS, `${doc.id}: section ${frontmatter.section}`).toContain(frontmatter.section);
      expect(frontmatter.order, `${doc.id}: order`).toMatch(/^\d+$/);
      expect(firstH1(body), `${doc.id}: body must not contain an h1 (the layout renders the title)`).toBeUndefined();
    }
  });

  it('places pages in the directory named after their section', () => {
    for (const doc of docs) {
      expect(doc.id.split('/')[0], doc.id).toBe(doc.frontmatter.section);
    }
  });

  it('uses unique sidebar orders within each section (local + external)', () => {
    const seen = new Map<string, string>();
    const entries = [
      ...docs.map((d) => ({ key: `${d.frontmatter.section}:${d.frontmatter.order}`, id: d.id })),
      ...Object.entries(externalMeta).map(([id, m]) => ({ key: `${m.section}:${m.order}`, id })),
    ];
    for (const { key, id } of entries) {
      expect(seen.has(key), `${id} shares sidebar slot ${key} with ${seen.get(key)}`).toBe(false);
      seen.set(key, id);
    }
  });

  it('registers sections with unique ids and orders', () => {
    expect(new Set(SECTIONS.map((s) => s.id)).size).toBe(SECTIONS.length);
    expect(new Set(SECTIONS.map((s) => s.order)).size).toBe(SECTIONS.length);
  });

  it('never publishes a local page at a URL owned by an external doc', () => {
    for (const doc of docs) {
      expect(doc.id.startsWith('reference/'), `${doc.id} would shadow an authoring page`).toBe(false);
      expect(['cli/commands', 'cli/agents']).not.toContain(doc.id);
    }
  });

  // The repo is public. Nothing tenant-, customer-, or homelab-specific may
  // reach the docs, and removed features must not be documented as existing.
  it('contains no internal hostnames, tenant names, or removed features', () => {
    const forbidden = [/rzware/i, /harbor\./i, /192\.168\./, /homelab/i, /permission[- ]sets?/i, /mcp\.kelta\.io/i, /spotopened/i, /couchpicks/i];
    for (const doc of docs) {
      const source = readFileSync(doc.file, 'utf8');
      for (const pattern of forbidden) {
        expect(source, `${doc.id} matches ${pattern}`).not.toMatch(pattern);
      }
    }
  });

  it('links only to docs pages that exist', () => {
    const hrefs = new Set<string>([
      '/docs/',
      ...docs.map((d) => `/docs/${d.id}/`),
      ...Object.keys(externalMeta).map((k) => (k.startsWith('authoring/') ? `/docs/reference/${k.slice('authoring/'.length)}/` : `/docs/${k}/`)),
    ]);
    for (const doc of docs) {
      const source = readFileSync(doc.file, 'utf8');
      for (const match of source.matchAll(/\]\((\/docs\/[^)#\s]*)(#[^)\s]*)?\)/g)) {
        expect(hrefs.has(match[1]), `${doc.id} links to missing page ${match[1]}`).toBe(true);
      }
    }
  });
});

describe('SCIM discovery contract', () => {
  it('serves the documentationUri that ScimDiscoveryController advertises', () => {
    const controller = read('kelta-worker', 'src', 'main', 'java', 'io', 'kelta', 'worker', 'scim', 'controller', 'ScimDiscoveryController.java');
    const uri = controller.match(/https:\/\/kelta\.io(\/docs\/[a-z/-]+)/)?.[1];
    expect(uri).toBeDefined();
    const config = read('kelta-marketing', 'astro.config.mjs');
    expect(config).toContain(`'${uri}': '/docs/security/scim/'`);
    expect(existsSync(join(contentRoot, 'security', 'scim.md'))).toBe(true);
  });
});

describe('docs build wiring', () => {
  it('builds the image from the repo root and copies the external doc sources', () => {
    const dockerfile = read('kelta-marketing', 'Dockerfile');
    expect(dockerfile).toContain('COPY kelta-marketing/package.json kelta-marketing/package-lock.json ./');
    expect(dockerfile).toContain('COPY docs/authoring /app/docs/authoring');
    expect(dockerfile).toContain('COPY kelta-web/packages/cli/COMMANDS.md kelta-web/packages/cli/AGENTS.md');

    const ignore = read('kelta-marketing', 'Dockerfile.dockerignore');
    expect(ignore).toContain('!docs/authoring/');
    expect(ignore).toContain('!kelta-web/packages/cli/COMMANDS.md');
    expect(existsSync(join(marketingRoot, '.dockerignore'))).toBe(false);

    const workflow = read('.github', 'workflows', 'build-and-publish-containers.yml');
    expect(workflow).not.toContain('context: kelta-marketing');
    expect(workflow).not.toContain('matrix.context'); // no entry defines it — actionlint rejects the reference
  });

  it('rebuilds the site when the external doc sources change', () => {
    const filters = read('.github', 'path-filters.yml');
    const marketing = filters.slice(filters.indexOf('\nmarketing:'));
    const block = marketing.slice(0, marketing.indexOf('\n\n'));
    expect(block).toContain("- 'docs/authoring/**'");
    expect(block).toContain("- 'kelta-web/packages/cli/COMMANDS.md'");
    expect(block).toContain("- 'kelta-web/packages/cli/AGENTS.md'");
  });

  it('indexes the docs with Pagefind after the build and guards the dev-mode loader', () => {
    const pkg = JSON.parse(read('kelta-marketing', 'package.json'));
    expect(pkg.scripts.postbuild).toBe('pagefind --site dist');
    expect(read('kelta-marketing', 'src', 'layouts', 'DocsLayout.astro')).toContain('data-pagefind-body');
    expect(read('kelta-marketing', 'src', 'components', 'docs', 'DocsSearch.astro')).toContain('onerror');
    expect(read('kelta-marketing', 'nginx.conf')).toContain('location ^~ /pagefind/');
  });
});
