import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { AxiosInstance } from 'axios';
import type { CommandContext } from '../registry/types.js';
import {
  metadataCommands,
  renderDiff,
  runApply,
  runDiff,
  runExport,
  summarizeApply,
  toPackageType,
} from './metadata.js';

let dir: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'kelta-cli-meta-'));
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

function client(post: ReturnType<typeof vi.fn>): AxiosInstance {
  return { post } as unknown as AxiosInstance;
}

describe('runExport', () => {
  it('posts options and writes the package file', async () => {
    const post = vi
      .fn()
      .mockResolvedValue({ status: 200, data: new TextEncoder().encode('{"name":"app"}').buffer });
    const out = join(dir, 'out.json');
    const path = await runExport(client(post), { name: 'app', version: '1.0', output: out });
    expect(path).toBe(out);
    expect(post).toHaveBeenCalledWith(
      '/api/packages/export',
      { name: 'app', version: '1.0' },
      expect.objectContaining({ responseType: 'arraybuffer' })
    );
    expect(readFileSync(out, 'utf-8')).toBe('{"name":"app"}');
  });

  it('sends an empty body when no name or version is given (whole tenant)', async () => {
    const post = vi
      .fn()
      .mockResolvedValue({ status: 200, data: new Uint8Array([123]).buffer, headers: {} });
    const out = join(dir, 'all.json');
    await runExport(client(post), { output: out });
    expect(post).toHaveBeenCalledWith(
      '/api/packages/export',
      {},
      expect.objectContaining({ responseType: 'arraybuffer' })
    );
  });

  it('`kelta metadata export --collections sites,inspections` posts collectionNames', async () => {
    const post = vi
      .fn()
      .mockResolvedValue({ status: 200, data: new Uint8Array([123]).buffer, headers: {} });
    const out = join(dir, 'inspections.json');
    const def = metadataCommands.find((c) => c.name === 'export');
    if (!def) throw new Error('no export command');
    const ctx = { client: { getAxiosInstance: () => client(post) } } as unknown as CommandContext;

    await def.handler(ctx, def.input.parse({ collections: 'sites,inspections', out }) as never);

    expect(post).toHaveBeenCalledWith(
      '/api/packages/export',
      { collectionNames: ['sites', 'inspections'] },
      expect.objectContaining({ responseType: 'arraybuffer' })
    );
  });

  it('`kelta metadata export --exclude list-views,dashboards` posts excludeTypes', async () => {
    const post = vi
      .fn()
      .mockResolvedValue({ status: 200, data: new Uint8Array([123]).buffer, headers: {} });
    const out = join(dir, 'no-views.json');
    const def = metadataCommands.find((c) => c.name === 'export');
    if (!def) throw new Error('no export command');
    const ctx = { client: { getAxiosInstance: () => client(post) } } as unknown as CommandContext;

    await def.handler(ctx, def.input.parse({ exclude: 'list-views,dashboards', out }) as never);

    expect(post).toHaveBeenCalledWith(
      '/api/packages/export',
      { excludeTypes: ['LIST_VIEW', 'DASHBOARD'] },
      expect.objectContaining({ responseType: 'arraybuffer' })
    );
  });

  it('maps CLI type names onto package item types', () => {
    expect(toPackageType('list-view')).toBe('LIST_VIEW');
    expect(toPackageType(' Dashboards ')).toBe('DASHBOARD');
    expect(toPackageType('page_layout')).toBe('PAGE_LAYOUT');
    expect(toPackageType('validation-rules')).toBe('VALIDATION_RULE');
  });

  it('names the file from the response Content-Disposition', async () => {
    // can't chdir in a vitest worker — accept the default-relative write, then clean up
    const post = vi.fn().mockResolvedValue({
      status: 200,
      data: new Uint8Array([123]).buffer,
      headers: { 'content-disposition': 'attachment; filename="kelta-cli-test-app-2.json"' },
    });
    try {
      expect(await runExport(client(post), {})).toBe('kelta-cli-test-app-2.json');
    } finally {
      rmSync('kelta-cli-test-app-2.json', { force: true });
    }
  });

  it('falls back to metadata.json when the server names nothing', async () => {
    const post = vi
      .fn()
      .mockResolvedValue({ status: 200, data: new Uint8Array([123]).buffer, headers: {} });
    try {
      expect(await runExport(client(post), {})).toBe('metadata.json');
    } finally {
      rmSync('metadata.json', { force: true });
    }
  });

  it('throws on a non-200 export', async () => {
    const post = vi.fn().mockResolvedValue({ status: 500, data: {} });
    await expect(runExport(client(post), { name: 'app', version: '1.0' })).rejects.toThrow(
      /Export failed/
    );
  });
});

describe('runDiff / runApply', () => {
  it('uploads the file as multipart and returns the preview', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{"name":"app"}');
    const post = vi.fn().mockResolvedValue({ status: 200, data: { changes: [] } });
    expect(await runDiff(client(post), file)).toEqual({ changes: [] });
    expect(post).toHaveBeenCalledWith('/api/packages/import/preview', expect.any(FormData), {
      headers: { 'Content-Type': undefined },
    });
  });

  it('passes dryRun as a query param', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{}');
    const post = vi.fn().mockResolvedValue({ status: 200, data: { applied: false } });
    await runApply(client(post), file, { dryRun: true });
    expect(post).toHaveBeenCalledWith('/api/packages/import?dryRun=true', expect.any(FormData), {
      headers: { 'Content-Type': undefined },
    });
  });

  it('passes the conflict mode as conflictMode', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{}');
    const post = vi.fn().mockResolvedValue({ status: 200, data: { updated: 1 } });
    await runApply(client(post), file, { conflict: 'overwrite' });
    expect(post).toHaveBeenCalledWith(
      '/api/packages/import?conflictMode=overwrite',
      expect.any(FormData),
      { headers: { 'Content-Type': undefined } }
    );
  });

  it('combines dryRun and conflictMode', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{}');
    const post = vi.fn().mockResolvedValue({ status: 200, data: {} });
    await runApply(client(post), file, { dryRun: true, conflict: 'skip' });
    expect(post).toHaveBeenCalledWith(
      '/api/packages/import?dryRun=true&conflictMode=skip',
      expect.any(FormData),
      { headers: { 'Content-Type': undefined } }
    );
  });

  it('sends no conflictMode when none is given, leaving the server default', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{}');
    const post = vi.fn().mockResolvedValue({ status: 200, data: {} });
    await runApply(client(post), file, {});
    expect(post).toHaveBeenCalledWith('/api/packages/import', expect.any(FormData), {
      headers: { 'Content-Type': undefined },
    });
  });

  it('throws with the response body on failure', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{}');
    const post = vi.fn().mockResolvedValue({ status: 400, data: { error: 'bad package' } });
    await expect(runApply(client(post), file, {})).rejects.toThrow(/bad package/);
  });
});

describe('kelta metadata diff output', () => {
  const preview = {
    creates: [{ type: 'LIST_VIEW', name: 'orders:Open' }],
    updates: [
      { type: 'UNIQUE_CONSTRAINT', name: 'orders:code,region', changes: ['field_names'] },
      { type: 'REPORT', name: 'Open orders', changes: ['group_by', 'columns'] },
      { type: 'APPROVAL_PROCESS', name: 'orders:Discount', changes: ['steps[2].approver_id'] },
    ],
    unchanged: [
      { type: 'COLLECTION', name: 'orders' },
      { type: 'FIELD', name: 'orders.code' },
      { type: 'FIELD', name: 'orders.region' },
    ],
    conflicts: [
      {
        item: { type: 'APPROVAL_PROCESS', name: 'orders:Other' },
        error: "approver user 'ghost@acme.io' not found in target",
      },
    ],
  };

  it('names each changed unique constraint, report and approval step with its changed attributes', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{}');
    const post = vi.fn().mockResolvedValue({ status: 200, data: preview });
    const def = metadataCommands.find((c) => c.name === 'diff');
    if (!def) throw new Error('no diff command');
    const ctx = { client: { getAxiosInstance: () => client(post) } } as unknown as CommandContext;

    const out = await def.handler(ctx, def.input.parse({ file }) as never);

    expect(out.data).toEqual(preview);
    expect(out.human).toContain('~ UNIQUE_CONSTRAINT orders:code,region: field_names');
    expect(out.human).toContain('~ REPORT Open orders: group_by, columns');
    expect(out.human).toContain('~ APPROVAL_PROCESS orders:Discount: steps[2].approver_id');
  });

  it('renders creates, updates, unchanged counts per type and conflicts', () => {
    expect(renderDiff(preview)).toBe(
      [
        'creates (1):',
        '  + LIST_VIEW orders:Open',
        'updates (3):',
        '  ~ UNIQUE_CONSTRAINT orders:code,region: field_names',
        '  ~ REPORT Open orders: group_by, columns',
        '  ~ APPROVAL_PROCESS orders:Discount: steps[2].approver_id',
        'unchanged (3): COLLECTION 1, FIELD 2',
        'conflicts (1):',
        "  ! APPROVAL_PROCESS orders:Other: approver user 'ghost@acme.io' not found in target",
        '',
      ].join('\n')
    );
  });

  it('renders an empty preview as zero counts', () => {
    expect(renderDiff({})).toBe('creates (0)\nupdates (0)\nunchanged (0)\nconflicts (0)\n');
  });
});

describe('kelta metadata apply summary', () => {
  it('counts list views, dashboards and seed records per type', async () => {
    const file = join(dir, 'pkg.json');
    writeFileSync(file, '{}');
    const result = {
      success: true,
      created: 3,
      updated: 0,
      skipped: 3,
      failed: 0,
      items: [
        { type: 'COLLECTION', naturalKey: 'accounts', action: 'SKIPPED' },
        { type: 'LIST_VIEW', naturalKey: 'accounts:All accounts', action: 'CREATED' },
        { type: 'DASHBOARD', naturalKey: 'Sales', action: 'CREATED' },
        { type: 'RECORD', naturalKey: 'accounts:name=Acme', action: 'CREATED' },
        { type: 'RECORD', naturalKey: 'accounts:name=Globex', action: 'SKIPPED' },
        { type: 'RECORD', naturalKey: 'accounts:name=Initech', action: 'SKIPPED' },
      ],
    };
    const post = vi.fn().mockResolvedValue({ status: 200, data: result });
    const def = metadataCommands.find((c) => c.name === 'apply');
    if (!def) throw new Error('no apply command');
    const ctx = { client: { getAxiosInstance: () => client(post) } } as unknown as CommandContext;

    const out = await def.handler(ctx, def.input.parse({ file }) as never);

    expect(out.data).toEqual(result);
    expect(out.message).toBe(
      'created=3 updated=0 skipped=3 failed=0 — COLLECTION: 1 skipped; LIST_VIEW: 1 created; ' +
        'DASHBOARD: 1 created; RECORD: 1 created, 2 skipped'
    );
  });

  it('falls back to the totals when the response carries no items', () => {
    expect(summarizeApply({ created: 1 })).toBe('created=1 updated=0 skipped=0 failed=0');
  });
});
