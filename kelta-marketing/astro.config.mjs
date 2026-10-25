import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';
import { rehypeHeadingIds, unified } from '@astrojs/markdown-remark';
import tailwindcss from '@tailwindcss/vite';
import rehypeAutolinkHeadings from 'rehype-autolink-headings';
import { fileURLToPath } from 'node:url';
import { remarkStripLeadingH1 } from './src/docs/remark-strip-leading-h1.ts';
import { remarkInclude } from './src/docs/remark-include.ts';
import { readmeQuickstart } from './src/docs/readme-section.ts';

export default defineConfig({
  site: 'https://www.kelta.io',
  integrations: [sitemap()],
  output: 'static',
  // Astro 7 defaults to 'jsx', which drops whitespace between inline elements; keep v6 output.
  compressHTML: true,
  // ScimDiscoveryController advertises https://kelta.io/docs/scim as the SCIM
  // documentationUri; the page lives under /docs/security/scim/.
  redirects: {
    '/docs/scim': '/docs/security/scim/',
  },
  // Tailwind v4 through its Vite plugin; theme and base styles live in
  // src/styles/global.css (BaseLayout). v4 adds vendor prefixes itself.
  vite: {
    plugins: [tailwindcss()],
  },
  markdown: {
    shikiConfig: { theme: 'github-dark', wrap: false },
    // The remark/rehype plugins need the unified processor; Astro 7 defaults to Sätteri.
    processor: unified({
      // README.md's Quickstart is canonical (ci/quickstart-check.sh exercises it);
      // the docs quickstart page includes it rather than keeping a copy.
      remarkPlugins: [
        [remarkInclude, { sources: { 'readme-quickstart': readmeQuickstart(fileURLToPath(new URL('../README.md', import.meta.url))) } }],
        remarkStripLeadingH1,
      ],
      // Astro assigns heading ids after user rehype plugins run, so the id
      // plugin must be listed explicitly ahead of the autolinker.
      rehypePlugins: [
        rehypeHeadingIds,
        [
          rehypeAutolinkHeadings,
          {
            behavior: 'append',
            properties: { class: 'heading-anchor', 'aria-label': 'Link to this section' },
            content: { type: 'text', value: '#' },
          },
        ],
      ],
    }),
  },
});
