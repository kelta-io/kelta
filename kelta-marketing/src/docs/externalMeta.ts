import type { SectionId } from './sidebar';

/**
 * Sidecar metadata for documents that carry no frontmatter of their own:
 * the canonical authoring docs in `docs/authoring/*.md` (tri-published — the CLI
 * embeds them and the MCP server serves them as `kelta://docs/<topic>`) and the
 * generated CLI docs in `kelta-web/packages/cli/`.
 *
 * `title` MUST equal the file's first `# ` heading; `test/docs-engine.test.ts`
 * fails the build if the two drift.
 */
export interface ExternalMeta {
  title: string;
  description: string;
  section: SectionId;
  order: number;
  tocDepth?: 2 | 3;
}

export const externalMeta: Record<`authoring/${string}` | `cli/${string}`, ExternalMeta> = {
  'authoring/jsonapi': {
    title: 'JSON:API Conventions',
    description: 'How Kelta records are shaped on the wire: attributes vs relationships, filter grammar, pagination and the batch operations extension.',
    section: 'reference',
    order: 10,
  },
  'authoring/api-access': {
    title: 'PAT-Based Read-Only API Access',
    description: 'Recipe for a service account that reads one collection through the REST API with a personal access token.',
    section: 'reference',
    order: 20,
  },
  'authoring/page-layouts': {
    title: 'Authoring Page Layouts',
    description: 'The page-layout tree (layouts, sections, fields, rules, related lists) and the one-call apply endpoint.',
    section: 'reference',
    order: 30,
  },
  'authoring/list-views': {
    title: 'Authoring List Views',
    description: 'The saved-query model behind list views: filters, sort, columns, limits and deep links.',
    section: 'reference',
    order: 40,
  },
  'authoring/dashboards': {
    title: 'Authoring Dashboards',
    description: 'The dashboards and dashboard-components grid model.',
    section: 'reference',
    order: 50,
  },
  'authoring/ui-pages': {
    title: 'Authoring Custom UI Pages',
    description: 'The page-builder config JSON: components, variables, data sources, access and the home page.',
    section: 'reference',
    order: 60,
  },
  'authoring/ui-menus': {
    title: 'Authoring UI Menus',
    description: 'Apps (ui-menus) and their items: the nav path grammar and default-menu resolution.',
    section: 'reference',
    order: 70,
  },
  'cli/commands': {
    title: 'kelta CLI — Command Reference',
    description: 'Every kelta command group, command, flag and default — generated from the CLI command registry.',
    section: 'cli',
    order: 30,
    tocDepth: 2,
  },
  'cli/agents': {
    title: 'kelta CLI — Agent Guide',
    description: 'How an AI agent should drive the CLI: discovery, auth precedence, output contract, exit codes and gotchas.',
    section: 'cli',
    order: 40,
  },
};
