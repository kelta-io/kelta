import { readFileSync } from 'node:fs';

/**
 * The body of a `## <heading>` section of a markdown file: everything after the
 * heading line up to the next `## ` (or higher) heading outside a code fence.
 */
export function extractSection(markdown: string, heading: string): string {
  const lines = markdown.split('\n');
  const start = lines.findIndex((line) => line.trim() === `## ${heading}`);
  if (start < 0) throw new Error(`no "## ${heading}" section`);
  let inFence = false;
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (/^(```|~~~)/.test(lines[i])) inFence = !inFence;
    if (!inFence && /^#{1,2} /.test(lines[i])) {
      end = i;
      break;
    }
  }
  return lines.slice(start + 1, end).join('\n').trim() + '\n';
}

/**
 * Point the section's in-page links (`](#anchor)`) at anchors that exist on the
 * page it is included into. An unmapped anchor throws, so a new README link
 * fails the build instead of shipping a dead link.
 */
export function rewriteAnchors(markdown: string, anchors: Record<string, string>): string {
  return markdown.replace(/\]\(#([^)\s]+)\)/g, (_, anchor: string) => {
    const target = anchors[anchor];
    if (!target) throw new Error(`no docs anchor mapped for README link #${anchor}`);
    return `](${target})`;
  });
}

/** README links → their counterparts on /docs/getting-started/quickstart/. */
export const QUICKSTART_ANCHORS: Record<string, string> = {
  'native-vs-jvm-images': '#native-images',
  'local-development': '#service-ports',
};

/** README.md's `## Quickstart` body, as rendered on the docs quickstart page. */
export function readmeQuickstart(readmePath: string): string {
  return rewriteAnchors(extractSection(readFileSync(readmePath, 'utf8'), 'Quickstart'), QUICKSTART_ANCHORS);
}
