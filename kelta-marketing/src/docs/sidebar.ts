/**
 * Section registry for the documentation sidebar.
 *
 * Pure TypeScript (no `astro:*` imports) so the vitest suite in `test/` can
 * import it directly and assert the registry against the content on disk.
 */

export const SECTIONS = [
  { id: 'getting-started', label: 'Getting started', order: 10 },
  { id: 'concepts', label: 'Concepts', order: 20 },
  { id: 'data-model', label: 'Data model', order: 30 },
  { id: 'security', label: 'Security & identity', order: 40 },
  { id: 'automation', label: 'Automation', order: 50 },
  { id: 'api', label: 'REST API', order: 60 },
  { id: 'console', label: 'Console & app', order: 70 },
  { id: 'platform', label: 'Platform operations', order: 80 },
  { id: 'deploy', label: 'Deploy & self-host', order: 90 },
  { id: 'cli', label: 'CLI', order: 100 },
  { id: 'mcp', label: 'MCP', order: 110 },
  { id: 'sdk', label: 'SDK', order: 120 },
  { id: 'reference', label: 'Authoring reference', order: 130 },
] as const;

export type SectionId = (typeof SECTIONS)[number]['id'];

export const SECTION_IDS = SECTIONS.map((s) => s.id) as [SectionId, ...SectionId[]];

export type DocCollection = 'docs' | 'authoring' | 'cli';

export interface DocPage {
  collection: DocCollection;
  id: string;
  href: string;
  title: string;
  description: string;
  section: SectionId;
  order: number;
  tocDepth: 2 | 3;
  status?: 'partial';
  /** Path of the source file relative to the repository root. */
  repoPath: string;
}

export interface SidebarSection {
  id: SectionId;
  label: string;
  order: number;
  pages: DocPage[];
}

export function sectionLabel(id: SectionId): string {
  const section = SECTIONS.find((s) => s.id === id);
  if (!section) throw new Error(`Unknown docs section: ${id}`);
  return section.label;
}

/** Sections in display order, each with its pages sorted by `order` then title. Empty sections are dropped. */
export function buildSidebar<T extends DocPage>(pages: T[]): Array<Omit<SidebarSection, 'pages'> & { pages: T[] }> {
  return [...SECTIONS]
    .sort((a, b) => a.order - b.order)
    .map((s) => ({
      id: s.id,
      label: s.label,
      order: s.order,
      pages: pages
        .filter((p) => p.section === s.id)
        .sort((a, b) => a.order - b.order || a.title.localeCompare(b.title)),
    }))
    .filter((s) => s.pages.length > 0);
}

/** Flat reading order — drives prev/next links. */
export function flatten<T extends DocPage>(sidebar: Array<{ pages: T[] }>): T[] {
  return sidebar.flatMap((s) => s.pages);
}
