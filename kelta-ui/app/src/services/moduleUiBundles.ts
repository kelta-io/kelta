import { ComponentRegistry } from '@kelta/plugin-sdk'
import type { ApiClient } from './apiClient'

/**
 * Global the host publishes for module bundles to register through.
 *
 * A bundle is evaluated from a blob URL, so it has no module graph of its own and cannot resolve
 * a bare specifier like `@kelta/plugin-sdk` — a global is the only channel available. Kept to a
 * single namespaced key so what a module can reach stays explicit and reviewable.
 */
const REGISTRY_GLOBAL = '__keltaComponentRegistry'

/**
 * Shape of the module rows `GET /api/modules` returns. Only the fields the loader needs.
 */
interface TenantModuleSummary {
  moduleId: string
  status: string
  uiBundlePath?: string | null
}

/** Modules whose bundle has already been evaluated, so a re-render never double-registers. */
const loadedModuleIds = new Set<string>()

/**
 * Statuses whose bundle is served: ACTIVE, and DEGRADED (an external module whose service is not
 * answering still has the bundle pinned at install, so its UI keeps working).
 */
const SERVED_STATUSES = new Set(['ACTIVE', 'DEGRADED'])

/**
 * Loads the browser bundles of the tenant's active runtime modules, so a module can ship its UI
 * in the same single JAR as its backend code — or, for an external module, in the bundle the
 * platform pinned at install.
 *
 * <p>Each bundle's top-level code calls `ComponentRegistry.registerPageComponent(...)` from
 * `@kelta/plugin-sdk`; the caller syncs that registry into the host afterwards.
 *
 * The bundle is fetched through the authenticated `ApiClient` rather than imported straight from
 * its URL, because a bare `import()` cannot attach the session token. The fetched source is then
 * evaluated from a blob URL, which inherits this page's origin.
 *
 * **This executes publisher-authored JavaScript with the admin session's full DOM and cookie
 * access.** The trust anchor is the JAR signature the platform verified at install and re-verified
 * on read, or for an external module the SHA-256 the admin approved in the install plan, which the
 * worker re-checks before serving its pinned copy (never the module's live file) — there is no
 * browser-side isolation. Serving an unverified bundle would make a bad module a stored-XSS vector
 * across the tenant.
 *
 * A module that fails to load is logged and skipped: one broken module must not take the admin UI
 * down with it.
 */
export async function loadModuleUiBundles(apiClient: ApiClient): Promise<string[]> {
  let modules: TenantModuleSummary[]
  try {
    // getList, not get: `get` returns the raw JSON:API envelope (`{ data: [...] }`), which is
    // not iterable. getList unwraps and flattens it into plain rows.
    modules = await apiClient.getList<TenantModuleSummary>('/api/modules')
    if (!Array.isArray(modules)) {
      // Belt and braces — nothing this function does may throw past its caller (see below).
      modules = []
    }
  } catch (err) {
    // A tenant without the modules feature, or without permission to list them, is the normal
    // case — not an error worth surfacing in the UI.
    console.debug('[Module UI] Could not list modules; skipping module bundles', err)
    return []
  }

  // Published before any bundle is evaluated — a bundle registers at import time, so the
  // registry has to be reachable by then.
  ;(globalThis as Record<string, unknown>)[REGISTRY_GLOBAL] = ComponentRegistry

  const loaded: string[] = []
  for (const module of modules ?? []) {
    if (!SERVED_STATUSES.has(module.status) || !module.uiBundlePath) {
      continue
    }
    if (loadedModuleIds.has(module.moduleId)) {
      continue
    }

    let objectUrl: string | undefined
    try {
      const source = await apiClient.get<string>(`/api/modules/${module.moduleId}/ui-bundle.js`)
      objectUrl = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }))
      await import(/* @vite-ignore */ objectUrl)
      loadedModuleIds.add(module.moduleId)
      loaded.push(module.moduleId)
      console.info(`[Module UI] Loaded UI bundle for module: ${module.moduleId}`)
    } catch (err) {
      console.error(`[Module UI] Failed to load UI bundle for module ${module.moduleId}:`, err)
    } finally {
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl)
      }
    }
  }
  return loaded
}

/** Test seam — drops the loaded-module memo so a fresh mount re-evaluates bundles. */
export function resetLoadedModuleUiBundles(): void {
  loadedModuleIds.clear()
}
