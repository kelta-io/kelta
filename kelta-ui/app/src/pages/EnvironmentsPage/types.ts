/**
 * EnvironmentsPage shared types and API functions.
 *
 * Environments and promotions are served by the worker's
 * EnvironmentController / PromotionController. List/detail endpoints return
 * JSON:API envelopes (attributes are snake_case straight from the DB rows);
 * action endpoints (refresh, test, approve, execute, rollback) return plain
 * JSON bodies.
 */

import type { ApiClient } from '../../services/apiClient'

export type EnvironmentType = 'PRODUCTION' | 'SANDBOX' | 'STAGING'

export type EnvironmentStatus = 'CREATING' | 'ACTIVE' | 'REFRESHING' | 'ARCHIVED' | 'FAILED'

/**
 * Environment row as returned by GET /api/environments (snake_case attrs).
 */
export interface Environment {
  id: string
  name: string
  description?: string | null
  type: EnvironmentType
  status: EnvironmentStatus
  source_env_id?: string | null
  sandbox_tenant_id?: string | null
  remote_base_url?: string | null
  remote_tenant_slug?: string | null
  credential_ref?: string | null
  created_by?: string | null
  created_at?: string | null
}

/**
 * Response of POST /api/environments for a local sandbox. Carries the
 * one-time admin credentials — the initial password is shown once and is
 * not retrievable again.
 */
export interface CreatedEnvironment extends Environment {
  sandboxSlug?: string
  adminUsername?: string
  adminEmail?: string
  adminInitialPassword?: string
}

type DiffAction = 'ADD' | 'MODIFY' | 'REMOVE'

export interface DiffChange {
  action: DiffAction
  type: string
  name: string
}

export interface EnvironmentDiff {
  status: string
  environmentId?: string
  changes: DiffChange[]
}

export interface ConnectionTestResult {
  ok: boolean
  status?: number
  error?: string
}

export type PromotionStatus =
  'PENDING' | 'APPROVED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'ROLLED_BACK'

export type PromotionType = 'FULL' | 'SELECTIVE'

export type ConflictMode = 'SKIP' | 'OVERWRITE'

/**
 * Promotion row as returned by GET /api/promotions (snake_case attrs).
 */
export interface Promotion {
  id: string
  status: PromotionStatus
  promotion_type: PromotionType
  conflict_mode: ConflictMode
  items_promoted?: number | null
  items_skipped?: number | null
  items_failed?: number | null
  promoted_by?: string | null
  approved_by?: string | null
  source_env_name?: string | null
  target_env_name?: string | null
  created_at?: string | null
  completed_at?: string | null
  error_message?: string | null
  changes_summary?: unknown
  target_snapshot_id?: string | null
}

/**
 * Promotion item row as returned by GET /api/promotions/{id}/items.
 */
export interface PromotionItem {
  id: string
  item_type: string
  item_name: string
  status: string
  error_message?: string | null
}

export interface CreateEnvironmentRequest {
  name: string
  description?: string
  type: EnvironmentType
  remoteBaseUrl?: string
  remoteTenantSlug?: string
  credentialRef?: string
}

export interface CreatePromotionRequest {
  sourceEnvId: string
  targetEnvId: string
  promotionType: PromotionType
  conflictMode: ConflictMode
  items?: Array<{ itemType: string; itemName: string }>
}

// API functions using apiClient

export async function fetchEnvironments(apiClient: ApiClient): Promise<Environment[]> {
  return apiClient.getList<Environment>('/api/environments')
}

export async function createEnvironment(
  apiClient: ApiClient,
  request: CreateEnvironmentRequest
): Promise<CreatedEnvironment> {
  return apiClient.postResource<CreatedEnvironment>('/api/environments', request)
}

export async function refreshEnvironment(apiClient: ApiClient, envId: string): Promise<unknown> {
  return apiClient.post(`/api/environments/${envId}/refresh`, {})
}

export async function testEnvironmentConnection(
  apiClient: ApiClient,
  envId: string
): Promise<ConnectionTestResult> {
  return apiClient.post<ConnectionTestResult>(`/api/environments/${envId}/test`, {})
}

export async function archiveEnvironment(apiClient: ApiClient, envId: string): Promise<unknown> {
  return apiClient.delete(`/api/environments/${envId}`)
}

export async function fetchPromotions(apiClient: ApiClient): Promise<Promotion[]> {
  return apiClient.getList<Promotion>('/api/promotions?limit=50&offset=0')
}

export async function fetchPromotion(
  apiClient: ApiClient,
  promotionId: string
): Promise<Promotion> {
  return apiClient.getOne<Promotion>(`/api/promotions/${promotionId}`)
}

export async function fetchPromotionPreview(
  apiClient: ApiClient,
  sourceEnvId: string
): Promise<EnvironmentDiff> {
  return apiClient.get<EnvironmentDiff>(
    `/api/promotions/preview?sourceEnvId=${encodeURIComponent(sourceEnvId)}`
  )
}

export async function createPromotion(
  apiClient: ApiClient,
  request: CreatePromotionRequest
): Promise<Promotion> {
  return apiClient.postResource<Promotion>('/api/promotions', request)
}

export async function approvePromotion(
  apiClient: ApiClient,
  promotionId: string
): Promise<unknown> {
  return apiClient.post(`/api/promotions/${promotionId}/approve`, {})
}

export async function executePromotion(
  apiClient: ApiClient,
  promotionId: string
): Promise<unknown> {
  return apiClient.post(`/api/promotions/${promotionId}/execute`, {})
}

export async function rollbackPromotion(
  apiClient: ApiClient,
  promotionId: string
): Promise<unknown> {
  return apiClient.post(`/api/promotions/${promotionId}/rollback`, {})
}

export async function fetchPromotionItems(
  apiClient: ApiClient,
  promotionId: string
): Promise<PromotionItem[]> {
  return apiClient.getList<PromotionItem>(`/api/promotions/${promotionId}/items`)
}
