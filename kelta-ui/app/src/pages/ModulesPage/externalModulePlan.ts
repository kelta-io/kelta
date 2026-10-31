/**
 * Types and API helpers for external modules (`specs/module-platform/9-external-modules.md`):
 * HTTPS services installed by URL after the admin reviews a plan of what they will be granted.
 *
 * The plan is the worker's `module-install-plans` document. Its `planHash` covers everything in
 * it, so the admin consents to exactly what is shown: an install or upgrade sends that hash back,
 * and the worker refuses (409) if anything changed in between.
 */
import type { ApiClient } from '../../services/apiClient'
import { ApiError } from '../../services/apiClient'

interface PlanBlocker {
  code: string
  message: string
}

export interface ObjectGrant {
  collection: string
  create: boolean
  read: boolean
  edit: boolean
  delete: boolean
}

export interface FieldGrant {
  collection: string
  field: string
  visibility: string
}

export interface PlanDiff {
  added: string[]
  removed: string[]
}

interface PermissionDiff extends PlanDiff {
  /** The upgrade asks for access the admin has not approved yet. */
  widened: boolean
}

export interface MetadataPlanItem {
  type: string
  naturalKey: string
  action: string
}

interface UiBundlePlan {
  bundlePath: string | null
  /** The hash the manifest declares. */
  sha256: string | null
  /** The hash of what the platform fetched; null when it could not fetch it. */
  fetchedSha256: string | null
  sizeBytes: number | null
  /** The bundle pinned today (upgrade only). */
  installedSha256: string | null
  /** An upgrade replaces (or removes) the pinned bundle. */
  changed: boolean
}

export interface InstallPlan {
  baseUrl: string
  moduleId: string | null
  name?: string
  description?: string | null
  author?: string | null
  mode: 'INSTALL' | 'UPGRADE' | 'NO_OP' | 'BLOCKED'
  fromVersion?: string | null
  toVersion?: string
  manifestSha256: string
  platformVersion?: string
  grants?: {
    ownedCollections: string[]
    objects: ObjectGrant[]
    fields: FieldGrant[]
    system: string[]
    credentials: string[]
  }
  capabilities?: {
    actions: { key: string; name: string; timeoutMs: number }[]
    hooks: { collection: string; events: string[]; order: number; failurePolicy: string }[]
    events: { collection: string; types: string[] }[]
    services: { port: string; cacheTtlSeconds: number }[]
    routes: { path: string; methods: string[]; permission: string | null }[]
    schedules: { action: string; cron: string }[]
    webhook: boolean
    ui: { bundlePath: string; sha256: string } | null
  }
  uiBundle?: UiBundlePlan | null
  metadata?: MetadataPlanItem[]
  changes?: {
    actions: PlanDiff
    routes: PlanDiff
    services: PlanDiff
    hooks: PlanDiff
    events: PlanDiff
    schedules: PlanDiff
    permissions?: PermissionDiff
  }
  orphanedByUpgrade?: { type: string; naturalKey: string }[]
  blockers: PlanBlocker[]
  planHash: string
}

export interface UninstallPlanEntry {
  resourceType: string
  naturalKey: string
  ownership: string
  decision: string
}

export interface UninstallPlan {
  moduleId: string
  installedVersion: string
  purgeData: boolean
  entries: UninstallPlanEntry[]
  planHash: string
}

export interface ExternalModuleHealth {
  status?: string
  moduleHealth?: { status?: string; error?: string; [key: string]: unknown } | null
  breakerState?: string
  consecutiveFailures?: number
  breakerRetryAt?: string | null
  lastFailure?: string | null
  podName?: string
}

interface JsonApiDocument<T> {
  data?: { attributes?: T }
}

function attributesOf<T>(document: JsonApiDocument<T>): T {
  if (!document?.data?.attributes) {
    throw new Error('The server answered without a plan')
  }
  return document.data.attributes
}

/** `POST /api/modules/plan`: what installing (or upgrading) the module at `baseUrl` would do. */
export async function fetchInstallPlan(
  apiClient: ApiClient,
  baseUrl: string
): Promise<InstallPlan> {
  return attributesOf(
    await apiClient.post<JsonApiDocument<InstallPlan>>('/api/modules/plan', { baseUrl })
  )
}

/** `GET /api/modules/{id}/uninstall-plan`. */
export async function fetchUninstallPlan(
  apiClient: ApiClient,
  moduleId: string,
  purgeData: boolean
): Promise<UninstallPlan> {
  return attributesOf(
    await apiClient.get<JsonApiDocument<UninstallPlan>>(
      `/api/modules/${encodeURIComponent(moduleId)}/uninstall-plan?purgeData=${purgeData}`
    )
  )
}

/** True for the worker's `409`: the plan changed since it was reviewed, so it must be re-read. */
export function isPlanChanged(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

/** Counts of the metadata plan by action (`CREATE`, `ADOPT`, `KEEP`, ...). */
export function metadataCounts(items: MetadataPlanItem[] | undefined): Record<string, number> {
  const counts: Record<string, number> = {}
  for (const item of items ?? []) {
    counts[item.action] = (counts[item.action] ?? 0) + 1
  }
  return counts
}

/** The permission lines an admin reads: `orders: read, edit` and so on. */
export function objectGrantLabel(grant: ObjectGrant): string {
  const ops = (['create', 'read', 'edit', 'delete'] as const).filter((op) => grant[op])
  return ops.length === 0 ? 'no access' : ops.join(', ')
}
