import { readFileSync, writeFileSync } from 'node:fs';
import type { AxiosInstance, AxiosResponse } from 'axios';
import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

export interface ExportOptions {
  name?: string;
  version?: string;
  output?: string;
}

export type ConflictMode = 'skip' | 'overwrite';

/**
 * Export this tenant's metadata as a package file (GitOps-friendly).
 * POST /api/packages/export → write the returned package JSON to disk.
 * Name and version are sent only when given; an empty body means "the whole
 * tenant, named after it" and is the default. Returns the path written.
 */
export async function runExport(client: AxiosInstance, opts: ExportOptions): Promise<string> {
  const body: Record<string, string> = {};
  if (opts.name) body.name = opts.name;
  if (opts.version) body.version = opts.version;
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
    { flag: '-o, --out <file>', description: 'Output file (default: the name the server returns)' },
  ],
  input: z.object({
    name: z.string().min(1).optional(),
    version: z.string().min(1).optional(),
    out: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const path = await runExport(ctx.client.getAxiosInstance(), {
      name: input.name,
      version: input.version,
      output: input.out,
    });
    return { data: { file: path }, message: `Exported package to ${path}` };
  },
});

const diff = defineCommand({
  group: 'metadata',
  name: 'diff',
  summary: 'Preview the changes a package file would make (no writes)',
  positionals: [{ name: 'file', description: 'Package file', required: true }],
  input: z.object({ file: z.string().min(1) }),
  handler: async (ctx, input) => {
    const result = await runDiff(ctx.client.getAxiosInstance(), input.file);
    return { data: result };
  },
});

const apply = defineCommand({
  group: 'metadata',
  name: 'apply',
  summary: 'Apply a package file to this tenant',
  dangerous: (input) => !input.dryRun,
  positionals: [{ name: 'file', description: 'Package file', required: true }],
  options: [
    { flag: '--dry-run', description: 'Validate without writing' },
    {
      flag: '--conflict <mode>',
      description: 'On an item that already exists: skip (default) or overwrite',
      default: 'skip',
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
    return { data: result };
  },
});

export const metadataCommands: RegisteredCommand[] = [exportCommand, diff, apply];
