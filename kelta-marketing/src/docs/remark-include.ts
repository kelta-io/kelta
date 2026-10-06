import type { Root, RootContent } from 'mdast';
import { fromMarkdown } from 'mdast-util-from-markdown';
import { gfmFromMarkdown } from 'mdast-util-gfm';
import { gfm } from 'micromark-extension-gfm';
import { visit } from 'unist-util-visit';

const INCLUDE = /^<!--\s*include:\s*([\w-]+)\s*-->$/;

export interface IncludeOptions {
  /** Markdown keyed by include name, spliced in place of `<!-- include: <name> -->`. */
  sources: Record<string, string>;
}

/**
 * Replace each top-level `<!-- include: <name> -->` comment with the parsed
 * markdown registered under that name. The sources are passed as plugin options
 * (read in astro.config.mjs) rather than read here, so a source edit changes
 * Astro's config digest and invalidates the cached render of the including page.
 */
export function remarkInclude({ sources }: IncludeOptions) {
  return (tree: Root) => {
    tree.children = tree.children.flatMap((node): RootContent[] => {
      const name = node.type === 'html' ? node.value.trim().match(INCLUDE)?.[1] : undefined;
      if (!name) return [node];
      const source = sources[name];
      if (source === undefined) throw new Error(`remarkInclude: no source registered for "${name}"`);
      const included = fromMarkdown(source, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] });
      visit(included, (child) => {
        delete child.position;
      });
      return included.children;
    });
  };
}
