// Tailwind v4 reads the theme (colours, fonts) from src/styles/global.css `@theme`.
// Only the typography overrides stay here, loaded through `@config`: written as CSS they
// would land after `.prose-slate` and change its link colour.
/** @type {import('tailwindcss').Config} */
export default {
  theme: {
    extend: {
      // Docs prose (the /docs pages render Markdown through @tailwindcss/typography).
      typography: {
        DEFAULT: {
          css: {
            '--tw-prose-headings': '#0F172A',
            '--tw-prose-links': '#06B6D4',
            '--tw-prose-code': '#0F172A',
            a: { textDecoration: 'none', fontWeight: '500', '&:hover': { color: '#3B82F6', textDecoration: 'underline' } },
            'h2, h3, h4': { scrollMarginTop: '6rem' },
            code: {
              fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
              fontWeight: '400',
              fontSize: '0.875em',
              backgroundColor: '#F1F5F9',
              borderRadius: '0.25rem',
              padding: '0.125rem 0.375rem',
            },
            'code::before': { content: 'none' },
            'code::after': { content: 'none' },
            // Shiki paints its own background on <pre>; keep the wrapper neutral.
            pre: { backgroundColor: 'transparent', padding: '0', borderRadius: '0.75rem' },
            'pre code': { backgroundColor: 'transparent', padding: '0', fontSize: '0.875em' },
            'thead th': { color: '#0F172A' },
          },
        },
      },
    },
  },
};
