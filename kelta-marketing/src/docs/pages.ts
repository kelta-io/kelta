import { getCollection, type CollectionEntry } from 'astro:content';
import { externalMeta } from './externalMeta';
import { repoPathFor } from './github';
import type { DocCollection, DocPage } from './sidebar';

export type AnyEntry = CollectionEntry<'docs'> | CollectionEntry<'authoring'> | CollectionEntry<'cli'>;

export interface LoadedDocPage extends DocPage {
  entry: AnyEntry;
}

const HREF_PREFIX: Record<DocCollection, string> = {
  docs: '/docs/',
  authoring: '/docs/reference/',
  cli: '/docs/cli/',
};

let cache: Promise<LoadedDocPage[]> | undefined;

/**
 * Merge the three content collections into one normalised page list. Throws on
 * anything that would otherwise ship a silently incomplete site: an empty
 * collection (a wrong Dockerfile COPY leaves the glob loader warning, not
 * failing), an external file with no sidecar metadata, or two pages that would
 * publish at the same URL.
 */
export function loadDocsPages(): Promise<LoadedDocPage[]> {
  return (cache ??= load());
}

async function load(): Promise<LoadedDocPage[]> {
  const isProd = import.meta.env.PROD;
  const [docs, authoring, cli] = await Promise.all([
    getCollection('docs', (e) => !(isProd && e.data.draft)),
    getCollection('authoring'),
    getCollection('cli'),
  ]);

  if (docs.length === 0) throw new Error('docs collection is empty — src/content/docs has no pages');
  if (authoring.length === 0) throw new Error('authoring collection is empty — is ../docs/authoring present?');
  if (cli.length === 0) throw new Error('cli collection is empty — is ../kelta-web/packages/cli present?');

  const pages: LoadedDocPage[] = [];

  for (const entry of docs) {
    pages.push({
      collection: 'docs',
      id: entry.id,
      href: `${HREF_PREFIX.docs}${entry.id}/`,
      title: entry.data.title,
      description: entry.data.description,
      section: entry.data.section,
      order: entry.data.order,
      tocDepth: entry.data.tocDepth,
      status: entry.data.status,
      repoPath: repoPathFor(entry.filePath),
      entry,
    });
  }

  const externals: Array<[DocCollection, AnyEntry[]]> = [
    ['authoring', authoring],
    ['cli', cli],
  ];
  for (const [collection, entries] of externals) {
    for (const entry of entries) {
      const key = `${collection}/${entry.id}` as keyof typeof externalMeta;
      const meta = externalMeta[key];
      if (!meta) throw new Error(`No externalMeta entry for ${key} — add one in src/docs/externalMeta.ts`);
      pages.push({
        collection,
        id: entry.id,
        href: `${HREF_PREFIX[collection]}${entry.id}/`,
        title: meta.title,
        description: meta.description,
        section: meta.section,
        order: meta.order,
        tocDepth: meta.tocDepth ?? 3,
        repoPath: repoPathFor(entry.filePath),
        entry,
      });
    }
  }

  for (const key of Object.keys(externalMeta)) {
    if (!pages.some((p) => `${p.collection}/${p.id}` === key)) {
      throw new Error(`externalMeta has ${key} but no such file was loaded`);
    }
  }

  const seen = new Map<string, LoadedDocPage>();
  for (const page of pages) {
    const other = seen.get(page.href);
    if (other) {
      throw new Error(`Duplicate docs URL ${page.href}: ${other.repoPath} and ${page.repoPath}`);
    }
    seen.set(page.href, page);
  }

  return pages;
}
