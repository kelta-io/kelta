/// <reference types="vitest" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['node_modules', 'dist'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      exclude: [
        'node_modules/',
        'dist/',
        '**/*.d.ts',
        '**/*.test.{ts,tsx}',
        '**/*.spec.{ts,tsx}',
        'vitest.config.ts',
        'vitest.setup.ts',
      ],
    },
    // Property-based testing configuration
    testTimeout: 30000, // Longer timeout for property tests
    hookTimeout: 30000,
  },
  resolve: {
    alias: {
      '@': resolve(import.meta.dirname, './src'),
      // Ensure all packages (including symlinked @kelta/sdk) resolve the same
      // axios instance so that vi.mock('axios') in vitest.setup.ts intercepts
      // all Axios usage, including KeltaClient's internal axios.create().
      axios: resolve(import.meta.dirname, 'node_modules/axios'),
      // The @kelta/components dist references @kelta/formula as an external
      // import. Vite needs the alias so its dependency resolver can locate
      // the workspace package; npm's symlink alone isn't always enough when
      // the import is hoisted across packages.
      '@kelta/formula': resolve(import.meta.dirname, 'node_modules/@kelta/formula'),
      // Force a single React instance across all workspace packages. Without
      // this, @kelta/components uses its own (hoisted) React copy and hooks
      // like useRef return null because hook dispatcher state lives in a
      // different React module instance than the test renderer's React.
      react: resolve(import.meta.dirname, 'node_modules/react'),
      'react-dom': resolve(import.meta.dirname, 'node_modules/react-dom'),
      // Same singleton requirement for react-router (mirrors vite.config.ts'
      // dedupe): @kelta/components' Navigation calls router hooks
      // (useNavigate/useLocation). A duplicate router copy reads a different
      // (null) NavigationContext via @kelta/components' hoisted React and
      // throws "Cannot read properties of null (reading 'useContext')". Forcing
      // the app's copy keeps one router context for the Navigation widget tests.
      'react-router-dom': resolve(import.meta.dirname, 'node_modules/react-router-dom'),
      'react-router': resolve(import.meta.dirname, 'node_modules/react-router'),
    },
  },
})
