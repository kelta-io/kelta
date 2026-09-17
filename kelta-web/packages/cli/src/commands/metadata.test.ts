import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { AxiosInstance } from 'axios';
import { runApply, runDiff, runExport } from './metadata.js';

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
