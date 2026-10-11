export interface RoadmapColumn {
  title: string;
  accent: string;
  items: string[];
}

export interface Roadmap {
  updated: string;
  columns: RoadmapColumn[];
}

export const roadmap: Roadmap = {
  updated: '2026-10-11',
  columns: [
    {
      title: 'Shipped',
      accent: 'border-t-cyan',
      items: [
        'Runtime data platform — collections, fields, relationships, validation, and an auto-generated JSON:API REST API',
        'Visual workflow automation with a drag-and-drop flow canvas and a library of built-in action handlers',
        'Enterprise security — role-based access control, field-level security and data masking, row-level multi-tenancy',
        'Owner-scoped collections — limit members to the records they own, enforced in the query layer',
        'Realtime collaboration — presence, live record updates, in-app chat and video',
        'Consumer alerting — availability watches, matching, and push notifications for end users',
        'AI integration — in-app assistant and an MCP server so AI agents can operate the platform directly',
        'Cross-platform CLI with self-updating binaries',
        'Versioned releases — tagged GitHub Releases with multi-arch container images on ghcr.io/kelta-io, and a quickstart pinned to a release with KELTA_VERSION',
        'Runtime-installable modules, so capabilities can be added without redeploying the platform',
        'Installable application templates — CRM and site inspections, each installed into an empty tenant with one command',
        'Sandboxes and promotion — clone a tenant into a sandbox, export/diff/apply metadata, and promote changes between environments from the CLI',
        'Generated, typed SDK artifacts for your tenant\'s collections from a single CLI command',
        'A documentation site at kelta.io/docs, rendered from the same Markdown the CLI and MCP server ship',
        'Worked examples — build a lending library from the CLI, automate it with a record flow and an inbound webhook, and let an AI agent operate it over MCP',
      ],
    },
    {
      title: 'In progress',
      accent: 'border-t-blue',
      items: [
        'A first-class module marketplace — provenance, permissions, and consent for installed modules',
        'Public, SEO-friendly pages generated from tenant data for consumer-facing use cases',
      ],
    },
    {
      title: 'Exploring',
      accent: 'border-t-navy',
      items: [
        'A fully managed, hosted version of Kelta — see the waitlist below',
      ],
    },
  ],
};
