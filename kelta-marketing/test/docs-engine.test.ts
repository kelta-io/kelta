import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createMarkdownProcessor } from '@astrojs/markdown-remark';
import type { Code } from 'mdast';
import { fromMarkdown } from 'mdast-util-from-markdown';
import { gfmFromMarkdown } from 'mdast-util-gfm';
import { gfm } from 'micromark-extension-gfm';
import { visit } from 'unist-util-visit';
import { describe, expect, it } from 'vitest';
import astroConfig from '../astro.config.mjs';
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
  'authoring/flows': 'docs/authoring/flows.md',
  'authoring/packages': 'docs/authoring/packages.md',
  'authoring/mcp-admin-coverage': 'docs/authoring/mcp-admin-coverage.md',
  'authoring/endpoints': 'docs/authoring/endpoints.md',
  'authoring/system-collections': 'docs/authoring/system-collections.md',
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

/** A page as the links and JSON checks see it: where it is published and the markdown Astro renders. */
interface CheckedPage {
  label: string;
  href: string;
  markdown: string;
}

const hrefOf = (externalKey: string) =>
  externalKey.startsWith('authoring/') ? `/docs/reference/${externalKey.slice('authoring/'.length)}/` : `/docs/${externalKey}/`;

/** Frontmatter blanked line-for-line, so mdast positions are file line numbers and `---` cannot read as a setext heading. */
const blankFrontmatter = (source: string) => source.replace(/^---\n[\s\S]*?\n---\n/, (fm) => fm.replace(/[^\n]/g, ''));

function authoringPages(): CheckedPage[] {
  return readdirSync(join(repoRoot, 'docs', 'authoring'))
    .filter((f) => f.endsWith('.md'))
    .map((f) => ({
      label: `docs/authoring/${f}`,
      href: hrefOf(`authoring/${f.replace(/\.md$/, '')}`),
      markdown: read('docs', 'authoring', f),
    }));
}

const decodeAttr = (value: string) => value.replace(/&#x26;|&amp;/g, '&').replace(/&#x22;|&quot;/g, '"');

/** Every `id` and `href` in a page as Astro renders it — heading slugs come from Astro's own rehypeHeadingIds. */
async function renderedAnchors(pages: CheckedPage[]): Promise<Map<string, { ids: Set<string>; hrefs: string[] }>> {
  const processor = await createMarkdownProcessor(astroConfig.markdown);
  const rendered = new Map<string, { ids: Set<string>; hrefs: string[] }>();
  for (const page of pages) {
    const { code } = await processor.render(page.markdown);
    rendered.set(page.href, {
      ids: new Set([...code.matchAll(/\sid="([^"]*)"/g)].map((m) => decodeAttr(m[1]))),
      hrefs: [...code.matchAll(/\shref="([^"]*)"/g)].map((m) => decodeAttr(m[1])),
    });
  }
  return rendered;
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

  const localPages: CheckedPage[] = docs.map((d) => ({
    label: d.file.slice(repoRoot.length + 1),
    href: `/docs/${d.id}/`,
    markdown: blankFrontmatter(readFileSync(d.file, 'utf8')),
  }));
  const sourcePages = [...localPages, ...authoringPages()];

  // Links in local pages and docs/authoring/*.md must reach a page that exists and, when they
  // carry a #fragment (same-page or /docs/...#x), an id that page renders.
  it('links only to docs pages and anchors that exist', async () => {
    const cliPages = Object.keys(externalMeta)
      .filter((k) => !k.startsWith('authoring/'))
      .map((k) => ({ label: EXTERNAL_SOURCES[k], href: hrefOf(k), markdown: read(EXTERNAL_SOURCES[k]) }));
    const rendered = await renderedAnchors([...sourcePages, ...cliPages]);
    const pages = new Set(['/docs/', ...rendered.keys(), ...Object.keys(externalMeta).map(hrefOf)]);
    const broken: string[] = [];

    for (const page of sourcePages) {
      for (const href of rendered.get(page.href)!.hrefs) {
        if (!href.startsWith('#') && !href.startsWith('/docs/')) continue;
        const [path, fragment] = href.startsWith('#') ? [page.href, href.slice(1)] : [href.split('#')[0], href.split('#')[1]];
        if (!pages.has(path)) {
          broken.push(`${page.label} links to missing page ${path}`);
        } else if (fragment !== undefined && !rendered.get(path)?.ids.has(decodeURIComponent(fragment))) {
          broken.push(`${page.label} links to missing anchor ${href}`);
        }
      }
    }
    expect(broken).toEqual([]);
  });

  it('has only valid JSON in ```json blocks (use ```jsonc for annotated examples)', () => {
    const invalid: string[] = [];
    for (const page of sourcePages) {
      const blocks: Code[] = [];
      visit(fromMarkdown(page.markdown, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] }), 'code', (node: Code) => {
        if (node.lang === 'json') blocks.push(node);
      });
      for (const block of blocks) {
        try {
          JSON.parse(block.value);
        } catch (e) {
          invalid.push(`${page.label}:${block.position?.start.line} json block: ${(e as Error).message}`);
        }
      }
    }
    expect(invalid).toEqual([]);
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

  // Run 35576779660: the docs probes ran while the Service still fronted the previous
  // image, 404'd, and the auto-rollback reverted every service. The smoke must wait for
  // ArgoCD to roll the pushed tag before asserting docs content.
  it('smoke-tests the docs only after the pushed image has rolled out', () => {
    const workflow = read('.github', 'workflows', 'build-and-publish-containers.yml');
    const marketing = workflow.slice(workflow.indexOf('DEPLOY=emf-marketing'));
    const smoke = marketing.slice(0, marketing.indexOf('Marketing smoke OK'));
    const docsProbe = smoke.indexOf('for path in /docs/ /docs/reference/jsonapi/');
    expect(docsProbe).toBeGreaterThan(0);
    const gate = smoke.indexOf('rollout status deploy/"${DEPLOY}"');
    expect(gate).toBeGreaterThan(0);
    expect(gate).toBeLessThan(docsProbe);
    expect(smoke.slice(0, gate)).toContain('PUSHED_TAG="main-$(echo "${GITHUB_SHA}" | cut -c1-7)"');
  });

  it('rebuilds the site when the external doc sources change', () => {
    const filters = read('.github', 'path-filters.yml');
    const marketing = filters.slice(filters.indexOf('\nmarketing:'));
    const block = marketing.slice(0, marketing.indexOf('\n\n'));
    expect(block).toContain("- 'docs/authoring/**'");
    expect(block).toContain("- 'kelta-web/packages/cli/COMMANDS.md'");
    expect(block).toContain("- 'kelta-web/packages/cli/AGENTS.md'");
  });

  // The docs quickstart page splices in README.md's Quickstart at build time.
  it('ships README.md into the image and rebuilds the site when it changes', () => {
    expect(read('kelta-marketing', 'Dockerfile')).toContain('COPY README.md /app/README.md');
    expect(read('kelta-marketing', 'Dockerfile.dockerignore').split('\n')).toContain('!README.md');

    const filters = read('.github', 'path-filters.yml');
    const marketing = filters.slice(filters.indexOf('\nmarketing:'));
    expect(marketing.slice(0, marketing.indexOf('\n\n'))).toContain("- 'README.md'");

    const workflow = read('.github', 'workflows', 'build-and-publish-containers.yml');
    const pushPaths = workflow.slice(workflow.indexOf('paths:'), workflow.indexOf('workflow_dispatch:'));
    expect(pushPaths).toContain("- 'README.md'");
  });

  it('indexes the docs with Pagefind after the build and guards the dev-mode loader', () => {
    const pkg = JSON.parse(read('kelta-marketing', 'package.json'));
    expect(pkg.scripts.postbuild).toBe('pagefind --site dist');
    expect(read('kelta-marketing', 'src', 'layouts', 'DocsLayout.astro')).toContain('data-pagefind-body');
    expect(read('kelta-marketing', 'src', 'components', 'docs', 'DocsSearch.astro')).toContain('onerror');
    expect(read('kelta-marketing', 'nginx.conf')).toContain('location ^~ /pagefind/');
  });
});
