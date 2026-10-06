import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createMarkdownProcessor } from '@astrojs/markdown-remark';
import { describe, expect, it } from 'vitest';
import astroConfig from '../astro.config.mjs';
import { remarkInclude } from '../src/docs/remark-include';
import { extractSection, QUICKSTART_ANCHORS, readmeQuickstart, rewriteAnchors } from '../src/docs/readme-section';

const repoRoot = join(import.meta.dirname, '..', '..');
const readmePath = join(repoRoot, 'README.md');
const fixturePath = join(import.meta.dirname, 'fixtures', 'README.md');
const pagePath = join(repoRoot, 'kelta-marketing', 'src', 'content', 'docs', 'getting-started', 'quickstart.md');
const MARKER = '<!-- include: readme-quickstart -->';

const pageBody = () => readFileSync(pagePath, 'utf8').replace(/^---\n[\s\S]*?\n---\n/, '');

/** Lines of every fenced code block in a markdown source. */
function fencedLines(markdown: string): string[] {
  return [...markdown.matchAll(/^```[^\n]*\n([\s\S]*?)^```/gm)].flatMap((m) => m[1].split('\n').filter((l) => l.trim()));
}

/** Text of every <pre> block in rendered HTML, one entry per line. */
function renderedCodeLines(html: string): string[] {
  return [...html.matchAll(/<pre[^>]*>([\s\S]*?)<\/pre>/g)].flatMap((m) =>
    m[1]
      .replace(/<[^>]+>/g, '')
      .replace(/&#x3C;/g, '<')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&#x27;|&#39;/g, "'")
      .replace(/&quot;/g, '"')
      .replace(/&amp;/g, '&')
      .split('\n'),
  );
}

async function render(markdown: string, sources: Record<string, string>): Promise<string> {
  const processor = await createMarkdownProcessor({ ...astroConfig.markdown, remarkPlugins: [[remarkInclude, { sources }]] });
  return (await processor.render(markdown)).code;
}

describe('README quickstart section', () => {
  it('extracts the section body up to the next h2, ignoring headings inside fences', () => {
    const section = extractSection(readFileSync(fixturePath, 'utf8'), 'Quickstart');
    expect(section).toContain('make up-fixture');
    expect(section).toContain('## not a heading inside a fence');
    expect(section).not.toContain('make not-included');
  });

  it('rewrites README anchors to docs anchors and rejects unmapped ones', () => {
    expect(rewriteAnchors('[x](#native-vs-jvm-images)', QUICKSTART_ANCHORS)).toBe('[x](#native-images)');
    expect(() => rewriteAnchors('[x](#nowhere)', QUICKSTART_ANCHORS)).toThrow(/#nowhere/);
  });

  it('maps every README quickstart link to a heading on the docs page', () => {
    const headings = new Set([...pageBody().matchAll(/^#{2,3} (.+)$/gm)].map((m) => `#${m[1].toLowerCase().replace(/[^a-z0-9 -]/g, '').replace(/ /g, '-')}`));
    for (const target of Object.values(QUICKSTART_ANCHORS)) {
      expect(headings, target).toContain(target);
    }
    expect(() => readmeQuickstart(readmePath)).not.toThrow();
  });

  // K-3: the quickstart a newcomer reads must stay short.
  it('stays at 30 lines or fewer in README.md', () => {
    const lines = readFileSync(readmePath, 'utf8').split('\n');
    const start = lines.indexOf('## Quickstart');
    const next = lines.findIndex((l, i) => i > start && /^## /.test(l));
    let end = next;
    while (!lines[end - 1].trim()) end--;
    expect(start).toBeGreaterThanOrEqual(0);
    expect(end - start).toBeLessThanOrEqual(30);
  });
});

describe('docs quickstart page', () => {
  it('is wired to README.md through astro.config.mjs', () => {
    const plugins = astroConfig.markdown?.remarkPlugins ?? [];
    const include = plugins.find((p) => Array.isArray(p) && p[0] === remarkInclude) as [unknown, { sources: Record<string, string> }];
    expect(include, 'remarkInclude is not registered').toBeDefined();
    expect(include[1].sources['readme-quickstart']).toBe(readmeQuickstart(readmePath));
  });

  it('includes the README block once and keeps every other section after it', () => {
    const body = pageBody();
    expect(body.split(MARKER)).toHaveLength(2);
    const marker = body.indexOf(MARKER);
    for (const heading of ['## Prerequisites', '## Native images', '## Sign in', '## Troubleshooting']) {
      expect(body.indexOf(heading), heading).toBeGreaterThan(marker);
    }
  });

  it('keeps no hand-written copy of the README commands that could drift', () => {
    const readmeCommands = new Set(fencedLines(readmeQuickstart(readmePath)));
    for (const line of fencedLines(pageBody())) {
      expect(readmeCommands.has(line), `quickstart.md repeats README line "${line}"`).toBe(false);
    }
  });

  it('renders README.md quickstart commands verbatim', async () => {
    const html = await render(pageBody(), { 'readme-quickstart': readmeQuickstart(readmePath) });
    const rendered = renderedCodeLines(html);
    const commands = fencedLines(readmeQuickstart(readmePath));
    expect(commands.length).toBeGreaterThan(0);
    for (const line of commands) {
      expect(rendered, line).toContain(line);
    }
    expect(html).not.toContain(MARKER);
    expect(html).toContain('href="#native-images"');
  });

  it('changes when the README command changes, with no edit to the page', async () => {
    const html = await render(pageBody(), { 'readme-quickstart': readmeQuickstart(fixturePath) });
    const rendered = renderedCodeLines(html);
    expect(rendered).toContain('make up-fixture');
    expect(rendered).not.toContain('make not-included');
  });
});
