import axios from 'axios';
import { z } from 'zod';
import { CliError, EXIT } from '../errors.js';

export const DEFAULT_UPDATE_URL = 'https://downloads.kelta.io';

const TargetSchema = z.object({
  /** Path relative to the downloads base (mirror/override-friendly). */
  path: z.string().min(1),
  /** Convenience absolute URL against the canonical host. */
  url: z.string().min(1),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  size: z.number().int().positive(),
});

export const ReleaseManifestSchema = z.object({
  manifestVersion: z.literal(1),
  version: z.string().regex(/^\d+\.\d+\.\d+$/),
  gitSha: z.string().min(1),
  builtAt: z.string().min(1),
  targets: z.record(z.string(), TargetSchema),
});

export type ReleaseManifest = z.infer<typeof ReleaseManifestSchema>;

/** Downloads base: env > profile-independent default (release builds embed it). */
export function updateBaseUrl(): string {
  return (process.env.KELTA_UPDATE_URL ?? DEFAULT_UPDATE_URL).replace(/\/$/, '');
}

export async function fetchManifest(baseUrl: string, timeoutMs = 10_000): Promise<ReleaseManifest> {
  const response = await axios.get<unknown>(`${baseUrl}/cli/manifest.json`, {
    timeout: timeoutMs,
  });
  const parsed = ReleaseManifestSchema.safeParse(response.data);
  if (!parsed.success) {
    throw new CliError(`Malformed release manifest at ${baseUrl}/cli/manifest.json`, {
      code: 'MALFORMED_MANIFEST',
      exitCode: EXIT.API,
    });
  }
  return parsed.data;
}

/** Compare dotted-triple versions: negative when a < b, 0 equal, positive when a > b. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

/** `<os>-<arch>` key for this machine, matching the manifest target keys. */
export function currentTargetKey(): string {
  const os = process.platform === 'win32' ? 'windows' : process.platform;
  return `${os}-${process.arch}`;
}
