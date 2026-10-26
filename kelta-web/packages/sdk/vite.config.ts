import { defineConfig } from 'vite';
import { resolve } from 'path';
import dts from 'vite-plugin-dts';

export default defineConfig({
  plugins: [
    dts({
      include: ['src/**/*.ts'],
      exclude: ['**/*.test.ts', '**/*.spec.ts', '**/*.property.test.ts'],
      bundleTypes: false, // Keep separate .d.ts files for multiple entry points
    }),
  ],
  build: {
    lib: {
      entry: {
        index: resolve(import.meta.dirname, 'src/index.ts'),
        'cli/index': resolve(import.meta.dirname, 'src/cli/index.ts'),
        'cli/generate-types': resolve(import.meta.dirname, 'src/cli/generate-types.ts'),
      },
      name: 'KeltaSdk',
      formats: ['es', 'cjs'],
      fileName: (format, entryName) => `${entryName}.${format === 'es' ? 'js' : 'cjs'}`,
    },
    rolldownOptions: {
      external: ['axios', 'zod', 'fs/promises', 'path'],
      output: {
        globals: {
          axios: 'axios',
          zod: 'zod',
        },
      },
    },
    sourcemap: true,
    minify: false,
  },
});
