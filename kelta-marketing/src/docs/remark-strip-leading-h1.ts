import type { Root } from 'mdast';
import { toString } from 'mdast-util-to-string';

/**
 * Every docs page renders its title from metadata (frontmatter for local pages,
 * `externalMeta.ts` for the authoring/CLI docs), so a leading `# Title` in the
 * body would render twice. Drop it — and a leading `<!-- GENERATED -->` comment —
 * and stash the text on `frontmatter.h1` so tests can assert it matches.
 */
export function remarkStripLeadingH1() {
  return (tree: Root, file: { data: { astro?: { frontmatter?: Record<string, unknown> } } }) => {
    while (tree.children.length > 0) {
      const first = tree.children[0];
      if (first.type === 'html' && /^\s*<!--/.test(first.value)) {
        tree.children.shift();
        continue;
      }
      if (first.type === 'heading' && first.depth === 1) {
        const frontmatter = (file.data.astro ??= {}).frontmatter ??= {};
        frontmatter.h1 = toString(first);
        tree.children.shift();
      }
      break;
    }
  };
}
