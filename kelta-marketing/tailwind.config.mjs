/** @type {import('tailwindcss').Config} */
import typography from '@tailwindcss/typography';

export default {
  content: ['./src/**/*.{astro,html,js,jsx,md,mdx,svelte,ts,tsx,vue}'],
  theme: {
    extend: {
      colors: {
        kelta: {
          50: '#F8FAFC',   // Slate 50 — off-white BG
          100: '#e0f2fe',
          200: '#bae6fd',
          300: '#7dd3fc',
          400: '#38bdf8',
          500: '#06B6D4',   // Cyan — primary accent
          600: '#3B82F6',   // Blue — secondary accent
          700: '#334155',   // Slate 700 — secondary text
          800: '#1e293b',
          900: '#0F172A',   // Navy — primary dark
          950: '#020617',
        },
        navy: '#0F172A',
        cyan: '#06B6D4',
        blue: '#3B82F6',
        success: '#10B981',
        warning: '#F59E0B',
        error: '#EF4444',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
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
  plugins: [typography],
};
