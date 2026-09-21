import path from 'node:path';

export const REPO_BLOB_BASE = 'https://github.com/kelta-io/kelta/blob/main/';

/**
 * `entry.filePath` is relative to the Astro project root (`kelta-marketing/`),
 * e.g. `../docs/authoring/jsonapi.md` or `src/content/docs/api/errors.md`.
 * Normalise it against the repository root for the "edit this page" link.
 */
export function repoPathFor(filePath: string | undefined): string {
  if (!filePath) throw new Error('Content entry has no filePath');
  return path.posix.normalize(path.posix.join('kelta-marketing', filePath));
}

export function editUrlFor(repoPath: string): string {
  return REPO_BLOB_BASE + repoPath;
}
