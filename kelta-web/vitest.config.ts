import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@kelta/sdk': resolve(__dirname, 'packages/sdk/src'),
      '@kelta/components': resolve(__dirname, 'packages/components/src'),
      '@kelta/plugin-sdk': resolve(__dirname, 'packages/plugin-sdk/src'),
      '@kelta/formula': resolve(__dirname, 'packages/formula/src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    // The Build-and-Deploy workflow runs this suite on the same runner as three
    // concurrent GraalVM native image builds. Under that contention a test BODY
    // (not just a waitFor) can exceed vitest's 5s default — a userEvent.type of
    // ~15 chars is ~15 React renders. That surfaced as a deterministic-looking
    // "Test timed out in 5000ms" on main that blocked every image build and
    // therefore every deploy. Same rationale as asyncUtilTimeout in
    // vitest.setup.ts; kept well below the job timeout so a genuine hang still
    // fails fast.
    testTimeout: 20_000,
    hookTimeout: 20_000,
    include: [
      'packages/**/*.{test,spec}.{ts,tsx}',
      'packages/**/*.property.test.{ts,tsx}',
      'scripts/**/*.test.ts',
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['packages/*/src/**/*.{ts,tsx}'],
      exclude: [
        'packages/*/src/**/*.test.{ts,tsx}',
        'packages/*/src/**/*.spec.{ts,tsx}',
        'packages/*/src/**/*.property.test.{ts,tsx}',
        'packages/*/src/**/index.ts',
      ],
      // A ratchet, not a target: each value is the measured total rounded down,
      // so CI passes today and fails on any regression. Raise them when coverage
      // improves; never lower them. These must sit directly under `thresholds` —
      // vitest 1.x silently ignores a nested `global` key, which is how an
      // advertised 80% gate enforced nothing while lines sat at ~76%.
      thresholds: {
        branches: 83,
        functions: 55,
        lines: 75,
        statements: 75,
      },
    },
  },
});
