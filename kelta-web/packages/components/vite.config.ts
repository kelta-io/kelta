import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';
import dts from 'vite-plugin-dts';

export default defineConfig({
  plugins: [
    react(),
    dts({
      include: ['src/**/*.ts', 'src/**/*.tsx'],
      exclude: [
        '**/*.test.ts',
        '**/*.test.tsx',
        '**/*.spec.ts',
        '**/*.spec.tsx',
        '**/*.property.test.ts',
        '**/*.property.test.tsx',
      ],
      bundleTypes: true,
    }),
  ],
  build: {
    lib: {
      // Two entries: the main barrel and the `./video` subpath. The video entry
      // is kept separate (not re-exported from index) so LiveKit stays out of the
      // base bundle — the app eagerly imports the barrel but lazy-imports `./video`.
      entry: {
        index: resolve(import.meta.dirname, 'src/index.ts'),
        video: resolve(import.meta.dirname, 'src/video.ts'),
      },
      name: 'KeltaComponents',
      formats: ['es', 'cjs'],
      fileName: (format, entryName) => `${entryName}.${format === 'es' ? 'js' : 'cjs'}`,
    },
    rolldownOptions: {
      external: [
        'react',
        'react-dom',
        'react/jsx-runtime',
        'react-router-dom',
        'react-hook-form',
        '@tanstack/react-query',
        '@kelta/sdk',
        '@kelta/formula',
        'clsx',
        'lucide-react',
        'class-variance-authority',
        'radix-ui',
        'maplibre-gl',
        // LiveKit is a peer dep of the `./video` entry — never bundle it into the
        // components dist. The app declares these directly and code-splits them.
        '@livekit/components-react',
        '@livekit/components-styles',
        'livekit-client',
      ],
      output: {
        globals: {
          react: 'React',
          'react-dom': 'ReactDOM',
          'react/jsx-runtime': 'jsxRuntime',
          'react-router-dom': 'ReactRouterDOM',
          'react-hook-form': 'ReactHookForm',
          '@tanstack/react-query': 'ReactQuery',
          '@kelta/sdk': 'KeltaSdk',
          '@kelta/formula': 'KeltaFormula',
          clsx: 'clsx',
          'lucide-react': 'LucideReact',
          'class-variance-authority': 'cva',
          'radix-ui': 'RadixUI',
          'maplibre-gl': 'maplibregl',
          '@livekit/components-react': 'LiveKitComponentsReact',
          '@livekit/components-styles': 'LiveKitComponentsStyles',
          'livekit-client': 'LivekitClient',
        },
      },
    },
    sourcemap: true,
    minify: false,
  },
});
