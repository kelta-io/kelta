import { readFileSync, writeFileSync } from 'node:fs';
import type { AxiosInstance, AxiosResponse } from 'axios';
import { z } from 'zod';
import { parseList } from '../query.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

export interface ExportOptions {
  name?: string;
  version?: string;
  /** Collection API names; the server adds their rules, layouts, triggered flows and picklists. */
  collections?: string[];
  /** Package item types to leave out, e.g. ['LIST_VIEW', 'DASHBOARD']. */
  exclude?: string[];
  output?: string;
}

export type ConflictMode = 'skip' | 'overwrite';

/**
 * Export this tenant's metadata as a package file (GitOps-friendly).
 * POST /api/packages/export → write the returned package JSON to disk.
 * Name, version, collections and excluded types are sent only when given; an empty
 * body means "the whole tenant, named after it" (list views and dashboards included,
 * seed records never) and is the default. Returns the path written.
 */
export async function runExport(client: AxiosInstance, opts: ExportOptions): Promise<string> {
  const body: Record<string, unknown> = {};
  if (opts.name) body.name = opts.name;
  if (opts.version) body.version = opts.version;
  if (opts.collections?.length) body.collectionNames = opts.collections;
  if (opts.exclude?.length) body.excludeTypes = opts.exclude;
  const res = await client.post('/api/packages/export', body, { responseType: 'arraybuffer' });
  if (res.status !== 200) {
    throw new Error(`Export failed (status ${String(res.status)})`);
  }
  const file = opts.output ?? attachmentFilename(res) ?? 'metadata.json';
  writeFileSync(file, Buffer.from(res.data as ArrayBuffer));
  return file;
}

/** The server names the package file in Content-Disposition — use it when the caller didn't. */
function attachmentFilename(res: AxiosResponse): string | undefined {
  const header = res.headers?.['content-disposition'] as string | undefined;
  return /filename="?([^";]+)"?/.exec(header ?? '')?.[1];
}

/** Preview the changes a package file would make — POST /api/packages/import/preview (no writes). */
export async function runDiff(client: AxiosInstance, file: string): Promise<unknown> {
  const res = await uploadPackage(client, '/api/packages/import/preview', file);
  return res.data as unknown;
}

interface DiffEntry {
  type?: string;
  name?: string;
  changes?: string[];
}

interface DiffConflict {
  item?: DiffEntry;
  error?: string;
}

function entries<T>(value: unknown): T[] {
  return (Array.isArray(value) ? value : []) as T[];
}

/**
 * Human rendering of an import preview: every create, every update with its changed
 * attributes, unchanged counts per type, and every conflict with its error, e.g.
 *
 * ```
 * creates (1):
 *   + LIST_VIEW orders:Open
 * updates (1):
 *   ~ REPORT Open orders: group_by
 * unchanged (3): COLLECTION 2, FIELD 1
 * conflicts (0)
 * ```
 */
export function renderDiff(preview: unknown): string {
  const body = (preview ?? {}) as Record<string, unknown>;
  const label = (e: DiffEntry | undefined) => `${e?.type ?? '?'} ${e?.name ?? '?'}`;
  const creates = entries<DiffEntry>(body.creates);
  const updates = entries<DiffEntry>(body.updates);
  const unchanged = entries<DiffEntry>(body.unchanged);
  const conflicts = entries<DiffConflict>(body.conflicts);

  const lines: string[] = [];
  const section = (title: string, rows: string[]) => {
    lines.push(rows.length > 0 ? `${title} (${String(rows.length)}):` : `${title} (0)`);
    lines.push(...rows.map((row) => `  ${row}`));
  };
  section(
    'creates',
    creates.map((e) => `+ ${label(e)}`)
  );
  section(
    'updates',
    updates.map((e) => `~ ${label(e)}: ${(e.changes ?? []).join(', ')}`)
  );
  const perType = new Map<string, number>();
  for (const e of unchanged) {
    const type = e.type ?? '?';
    perType.set(type, (perType.get(type) ?? 0) + 1);
  }
  lines.push(
    `unchanged (${String(unchanged.length)})` +
      (perType.size > 0
        ? ': ' +
          [...perType.entries()].map(([type, count]) => `${type} ${String(count)}`).join(', ')
        : '')
  );
  section(
    'conflicts',
    conflicts.map((c) => `! ${label(c.item)}${c.error ? `: ${c.error}` : ''}`)
  );
  return lines.join('\n') + '\n';
}

/** Apply a package file — POST /api/packages/import (with optional dryRun + conflict mode). */
export async function runApply(
  client: AxiosInstance,
  file: string,
  opts: { dryRun?: boolean; conflict?: ConflictMode }
): Promise<unknown> {
  const params = new URLSearchParams();
  if (opts.dryRun) params.set('dryRun', 'true');
  if (opts.conflict) params.set('conflictMode', opts.conflict);
  const query = params.toString();
  const res = await uploadPackage(client, `/api/packages/import${query ? `?${query}` : ''}`, file);
  return res.data as unknown;
}

/** `list-views` / `dashboard` / `page_layout` → the server's package type name (LIST_VIEW, …). */
export function toPackageType(name: string): string {
  return name.trim().toUpperCase().replace(/-/g, '_').replace(/S$/, '');
}

interface ApplyItem {
  type?: string;
  action?: string;
}

/**
 * One-line apply summary with per-type counts, e.g.
 * `created=3 updated=0 skipped=2 failed=0 — COLLECTION: 1 created; RECORD: 2 created, 2 skipped`.
 */
export function summarizeApply(result: unknown): string {
  const body = (result ?? {}) as Record<string, unknown>;
  const totals = ['created', 'updated', 'skipped', 'failed']
    .map((key) => `${key}=${String(body[key] ?? 0)}`)
    .join(' ');
  const byType = new Map<string, Map<string, number>>();
  for (const item of (Array.isArray(body.items) ? body.items : []) as ApplyItem[]) {
    if (!item.type || !item.action) continue;
    const actions = byType.get(item.type) ?? new Map<string, number>();
    actions.set(item.action, (actions.get(item.action) ?? 0) + 1);
    byType.set(item.type, actions);
  }
  if (byType.size === 0) return totals;
  const perType = [...byType.entries()]
    .map(
      ([type, actions]) =>
        `${type}: ` +
        [...actions.entries()]
          .map(([action, count]) => `${String(count)} ${action.toLowerCase()}`)
          .join(', ')
    )
    .join('; ');
  return `${totals} — ${perType}`;
}

/** Upload a package file as multipart {@code file=...} and return the response. */
async function uploadPackage(client: AxiosInstance, url: string, file: string) {
  const buffer = readFileSync(file);
  const form = new FormData();
  const name = file.split('/').pop() ?? 'package.json';
  form.append('file', new Blob([new Uint8Array(buffer)], { type: 'application/json' }), name);
  // The client defaults to Content-Type: application/json; left in place, axios's
  // transformRequest sees that as the request's content type and JSON-stringifies this
  // FormData into `{"file":{}}` instead of streaming a multipart body (axios only picks
  // its own multipart/form-data + boundary header when no Content-Type is preset).
  const res = await client.post(url, form, { headers: { 'Content-Type': undefined } });
  if (res.status !== 200) {
    throw new Error(`Request failed (status ${String(res.status)}): ${JSON.stringify(res.data)}`);
  }
  return res;
}

const exportCommand = defineCommand({
  group: 'metadata',
  name: 'export',
  summary: "Export this tenant's metadata as a package file",
  options: [
    { flag: '-n, --name <name>', description: 'Package name (default: the tenant slug)' },
    { flag: '-v, --version <version>', description: 'Package version (default: 1.0.0)' },
    {
      flag: '--collections <list>',
      description:
        'Collection names, comma-separated: export only these, with their validation rules, ' +
        'page layouts, list views, record-triggered flows, global picklists and the dashboards ' +
        'with a widget on them (default: the whole tenant)',
    },
    {
      flag: '--exclude <types>',
      description:
        'Item types to leave out, comma-separated: list-view, dashboard, flow, page-layout, ' +
        'validation-rule, ui-page, ui-menu (list views and dashboards are exported by default; ' +
        'seed records never are)',
    },
    { flag: '-o, --out <file>', description: 'Output file (default: the name the server returns)' },
  ],
  examples: [
    { cmd: 'kelta metadata export --out metadata.json', description: 'Export the whole tenant' },
    {
      cmd: 'kelta metadata export --collections customers,orders --exclude list-view,dashboard --name sales -v 1.2.0',
      description: 'Export two collections without their list views and dashboards',
    },
  ],
  input: z.object({
    name: z.string().min(1).optional(),
    version: z.string().min(1).optional(),
    collections: z.string().min(1).optional(),
    exclude: z.string().min(1).optional(),
    out: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const path = await runExport(ctx.client.getAxiosInstance(), {
      name: input.name,
      version: input.version,
      collections: input.collections ? parseList(input.collections) : undefined,
      exclude: input.exclude ? parseList(input.exclude).map(toPackageType) : undefined,
      output: input.out,
    });
    return { data: { file: path }, message: `Exported package to ${path}` };
  },
});

const diff = defineCommand({
  group: 'metadata',
  name: 'diff',
  summary:
    'Preview a package file against this tenant (dry run, no writes): creates, updates with ' +
    'their changed attributes, unchanged counts, conflicts',
  positionals: [{ name: 'file', description: 'Package file', required: true }],
  examples: [{ cmd: 'kelta metadata diff metadata.json' }],
  input: z.object({ file: z.string().min(1) }),
  handler: async (ctx, input) => {
    const result = await runDiff(ctx.client.getAxiosInstance(), input.file);
    return { data: result, human: renderDiff(result) };
  },
});

const apply = defineCommand({
  group: 'metadata',
  name: 'apply',
  summary: 'Apply a package file to this tenant (metadata items, then its seed records section)',
  dangerous: (input) => !input.dryRun,
  positionals: [{ name: 'file', description: 'Package file', required: true }],
  options: [
    { flag: '--dry-run', description: 'Validate without writing' },
    {
      flag: '--conflict <mode>',
      description:
        'On an item that already exists: skip (default) or overwrite (seed records are always skipped)',
      default: 'skip',
    },
  ],
  examples: [
    {
      cmd: 'kelta metadata apply metadata.json --dry-run',
      description: 'Validate without writing',
    },
    {
      cmd: 'kelta metadata apply metadata.json --conflict overwrite --yes',
      description: 'Apply, overwriting items that already exist',
    },
  ],
  input: z.object({
    file: z.string().min(1),
    dryRun: z.boolean().default(false),
    conflict: z.enum(['skip', 'overwrite']).default('skip'),
  }),
  handler: async (ctx, input) => {
    const result = await runApply(ctx.client.getAxiosInstance(), input.file, {
      dryRun: input.dryRun,
      conflict: input.conflict,
    });
    return { data: result, message: summarizeApply(result) };
  },
});

export const metadataCommands: RegisteredCommand[] = [exportCommand, diff, apply];
