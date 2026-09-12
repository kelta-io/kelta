import { webcrypto } from 'node:crypto'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'path'

// vite-plugin-pwa (Workbox) hashes precache entries with the Web Crypto API.
// Node 18 — the version the kelta-ui Docker image builds with — does not expose
// `crypto` as a global, so `npx vite build` throws "ReferenceError: crypto is not
// defined" once the PWA plugin runs. Polyfill it for the build process.
if (!globalThis.crypto) {
  ;(globalThis as { crypto?: unknown }).crypto = webcrypto
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    // Progressive Web App: installable end-user shell + offline app-shell precache.
    // Read-only first (Rec 2A) — API requests stay network-only (no runtimeCaching for
    // /api): offline *data* (IndexedDB replica + outbox) is the separate Rec 2B slice.
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: [
        'favicon.svg',
        'logo.svg',
        'apple-touch-icon-180x180.png',
        'maskable-icon-192x192.png',
      ],
      // The manifest is NOT generated here — it is the static public/manifest.webmanifest, and
      // the <link rel="manifest"> is injected at runtime as /{tenantSlug}/manifest.webmanifest
      // (see useTenantManifest in App.tsx). Relative URLs in a manifest resolve against the
      // manifest's own URL, so one file yields a tenant-correct start_url for every tenant.
      // A generated manifest with start_url '/' launched every Home Screen icon at the
      // slug-less root — and a Home Screen app has its own storage container, so nothing but
      // the URL can carry the tenant across.
      manifest: false,
      workbox: {
        // generateSW owns /sw.js, so push handling cannot live in a hand-written worker — a
        // public/sw.js is silently overwritten by the build. The handlers are imported into
        // the generated worker instead. See public/push-handlers.js.
        importScripts: ['push-handlers.js'],
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // Admin-only page chunks are emitted under assets/admin/ (see build.rollupOptions
        // below) and kept OUT of the precache — the installable end-user PWA shouldn't
        // download the admin/builder code up front. They're runtime-cached on demand instead
        // (see runtimeCaching), so an admin who visits those routes still gets a warm cache.
        globIgnores: ['**/assets/admin/**'],
        maximumFileSizeToCacheInBytes: 4 * 1024 * 1024,
        // SPA fallback to index.html for client routes, but never for backend paths.
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [
          /^\/api/,
          /^\/control/,
          /^\/internal/,
          /^\/actuator/,
          /^\/openapi/,
        ],
        // Admin chunks aren't precached — cache them on demand so repeat admin visits are
        // fast (and offline-capable) without bloating the install.
        runtimeCaching: [
          {
            urlPattern: /\/assets\/admin\/.*\.js$/,
            handler: 'StaleWhileRevalidate',
            options: { cacheName: 'admin-chunks', expiration: { maxEntries: 100 } },
          },
        ],
      },
    }),
  ],
  build: {
    rollupOptions: {
      output: {
        // Emit admin-only page chunks under assets/admin/ so the service worker can keep
        // them out of the precache (workbox.globIgnores). A lazy page chunk's facadeModuleId
        // is its own module path — src/pages/* is admin, src/pages/app/* is the end-user
        // runtime (stays in the default assets/ + precache). Shared chunks (facadeModuleId
        // null) also keep the default path.
        chunkFileNames: (chunkInfo) => {
          const id = chunkInfo.facadeModuleId
          if (id && id.includes('/src/pages/') && !id.includes('/src/pages/app/')) {
            return 'assets/admin/[name]-[hash].js'
          }
          return 'assets/[name]-[hash].js'
        },
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
    // Force a single instance of React AND every React-context-bearing
    // singleton across the app and the file:-linked @kelta/components /
    // @kelta/formula workspace packages. Without this, npm installs a
    // second copy inside packages/components/node_modules (each declares
    // these as peer/dev deps) while the app uses its own — a hook from the
    // components bundle then reads a different context provider and throws.
    //
    // react/react-dom: "Cannot read properties of null (reading 'useRef')".
    // react-router-dom: the detail-page components migrated to
    //   @kelta/components in #903–#907 (Crumb, Navigation) call <Link> /
    //   router hooks; a duplicate copy made useContext(NavigationContext)
    //   null → "Cannot destructure property 'basename' of useContext(...)
    //   as it is null", crashing the whole record-detail page.
    // @tanstack/react-query: a duplicate QueryClientProvider context means
    //   useQuery in the shared components never sees the app's client, so
    //   data lists silently never load.
    // react-hook-form: duplicate FormProvider context breaks shared
    //   form-field components the same way.
    dedupe: [
      'react',
      'react-dom',
      'react/jsx-runtime',
      'react-router-dom',
      '@tanstack/react-query',
      'react-hook-form',
    ],
  },
  server: {
    port: 5174,
    proxy: {
      // All control plane endpoints are under /control
      '/control': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/internal': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/openapi': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
