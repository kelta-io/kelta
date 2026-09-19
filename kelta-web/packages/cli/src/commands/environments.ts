import type { AxiosInstance } from 'axios';
import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

/** Default delay between polls for `--wait` commands. */
const DEFAULT_POLL_INTERVAL_MS = 2000;
/** Default maximum number of polls before a `--wait` command gives up (~5 min). */
const DEFAULT_MAX_ATTEMPTS = 150;

const ENV_TERMINAL_STATUSES = new Set(['ACTIVE', 'FAILED']);
const PROMOTION_TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED']);
const PROMOTION_TYPES = ['FULL', 'SELECTIVE'];
const CONFLICT_MODES = ['SKIP', 'OVERWRITE'];

export interface EnvironmentAttrs {
  name?: string;
  description?: string;
  type?: string;
  status?: string;
  sandbox_slug?: string;
  sandbox_tenant_id?: string;
  remote_base_url?: string;
  /** Returned once on sandbox creation. */
  sandboxSlug?: string;
  /** Returned once on sandbox creation. */
  adminUsername?: string;
  /** Returned once on sandbox creation — never shown again. */
  adminInitialPassword?: string;
}

export interface PromotionAttrs {
  status?: string;
  promotion_type?: string;
  conflict_mode?: string;
  items_promoted?: number;
  items_skipped?: number;
  items_failed?: number;
  source_env_name?: string;
  target_env_name?: string;
  error_message?: string;
}

interface JsonApiResource<A> {
  id: string;
  type?: string;
  attributes?: A;
}

interface JsonApiListResponse<A> {
  data?: JsonApiResource<A>[];
}

interface JsonApiSingleResponse<A> {
  data?: JsonApiResource<A>;
}

export type EnvironmentResource = JsonApiResource<EnvironmentAttrs>;
export type PromotionResource = JsonApiResource<PromotionAttrs>;

export interface PromotionItem {
  itemType: string;
  itemName: string;
}

export interface PromotionChange {
  action?: string;
  type?: string;
  name?: string;
}

export interface PromotionPreview {
  changes?: PromotionChange[];
}

export interface WaitOptions {
  wait?: boolean;
  intervalMs?: number;
  maxAttempts?: number;
}

export interface SandboxCreateOptions {
  name: string;
  description?: string;
}

export interface PromoteCreateOptions {
  sourceEnvId: string;
  targetEnvId: string;
  promotionType: string;
  conflictMode: string;
  items?: PromotionItem[];
}

function requestError(res: { status: number; data: unknown }): Error {
  return new Error(`Request failed (status ${String(res.status)}): ${JSON.stringify(res.data)}`);
}

function isSuccess(status: number): boolean {
  return status >= 200 && status < 300;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Parse a repeatable `--item TYPE:name` spec, splitting on the first colon. */
export function parseItemSpec(spec: string): PromotionItem {
  const idx = spec.indexOf(':');
  if (idx <= 0 || idx === spec.length - 1) {
    throw new Error(`Invalid --item "${spec}" (expected TYPE:name, e.g. collection:orders)`);
  }
  return { itemType: spec.slice(0, idx), itemName: spec.slice(idx + 1) };
}

/** Uppercase and validate an enum-style CLI choice. */
function normalizeChoice(value: string, allowed: string[], label: string): string {
  const upper = value.toUpperCase();
  if (!allowed.includes(upper)) {
    throw new Error(`Invalid ${label} "${value}" (expected ${allowed.join(' or ')})`);
  }
  return upper;
}

/** Create a sandbox environment — POST /api/environments (202). */
export async function runSandboxCreate(
  client: AxiosInstance,
  opts: SandboxCreateOptions
): Promise<EnvironmentResource> {
  const attributes: Record<string, unknown> = { name: opts.name, type: 'SANDBOX' };
  if (opts.description !== undefined) {
    attributes.description = opts.description;
  }
  const res = await client.post<JsonApiSingleResponse<EnvironmentAttrs>>('/api/environments', {
    data: { attributes },
  });
  if (!isSuccess(res.status)) throw requestError(res);
  const resource = res.data?.data;
  if (!resource) throw new Error('Environment creation returned no resource');
  return resource;
}

/** List environments — GET /api/environments. */
export async function runSandboxList(client: AxiosInstance): Promise<EnvironmentResource[]> {
  const res = await client.get<JsonApiListResponse<EnvironmentAttrs>>('/api/environments');
  if (res.status !== 200) throw requestError(res);
  return Array.isArray(res.data?.data) ? res.data.data : [];
}

async function fetchEnvironment(
  client: AxiosInstance,
  envId: string
): Promise<EnvironmentResource> {
  const envs = await runSandboxList(client);
  const env = envs.find((e) => e.id === envId);
  if (!env) throw new Error(`Environment ${envId} not found`);
  return env;
}

/**
 * Show an environment's status. With {@code wait}, polls until the environment
 * reaches a terminal status (ACTIVE or FAILED) or the attempt budget runs out.
 */
export async function runSandboxStatus(
  client: AxiosInstance,
  envId: string,
  opts: WaitOptions = {}
): Promise<EnvironmentResource> {
  let env = await fetchEnvironment(client, envId);
  if (!opts.wait) return env;

  const intervalMs = opts.intervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const maxAttempts = opts.maxAttempts ?? DEFAULT_MAX_ATTEMPTS;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (ENV_TERMINAL_STATUSES.has(env.attributes?.status ?? '')) return env;
    await sleep(intervalMs);
    env = await fetchEnvironment(client, envId);
  }
  if (ENV_TERMINAL_STATUSES.has(env.attributes?.status ?? '')) return env;
  throw new Error(
    `Timed out waiting for environment ${envId} (last status: ${env.attributes?.status ?? 'unknown'})`
  );
}

/** Refresh a sandbox from its source — POST /api/environments/{id}/refresh (202). */
export async function runSandboxRefresh(client: AxiosInstance, envId: string): Promise<void> {
  const res = await client.post<unknown>(`/api/environments/${envId}/refresh`);
  if (!isSuccess(res.status)) throw requestError(res);
}

/**
 * Delete an environment — DELETE /api/environments/{id}. Archives the
 * environment row; for a local tenant-backed sandbox this also decommissions
 * the backing tenant.
 */
export async function runSandboxDelete(client: AxiosInstance, envId: string): Promise<void> {
  const res = await client.delete<unknown>(`/api/environments/${envId}`);
  if (!isSuccess(res.status)) throw requestError(res);
}

/** Create a promotion — POST /api/promotions. */
export async function runPromoteCreate(
  client: AxiosInstance,
  opts: PromoteCreateOptions
): Promise<PromotionResource> {
  const attributes: Record<string, unknown> = {
    sourceEnvId: opts.sourceEnvId,
    targetEnvId: opts.targetEnvId,
    promotionType: opts.promotionType,
    conflictMode: opts.conflictMode,
  };
  if (opts.items && opts.items.length > 0) {
    attributes.items = opts.items;
  }
  const res = await client.post<JsonApiSingleResponse<PromotionAttrs>>('/api/promotions', {
    data: { attributes },
  });
  if (!isSuccess(res.status)) throw requestError(res);
  const resource = res.data?.data;
  if (!resource) throw new Error('Promotion creation returned no resource');
  return resource;
}

/** Preview the changes a promotion would make — GET /api/promotions/preview. */
export async function runPromotePreview(
  client: AxiosInstance,
  sourceEnvId: string
): Promise<PromotionPreview> {
  const res = await client.get<PromotionPreview>(
    `/api/promotions/preview?sourceEnvId=${sourceEnvId}`
  );
  if (res.status !== 200) throw requestError(res);
  return res.data ?? {};
}

/** Fetch a promotion — GET /api/promotions/{id}. */
export async function runPromoteStatus(
  client: AxiosInstance,
  id: string
): Promise<PromotionResource> {
  const res = await client.get<JsonApiSingleResponse<PromotionAttrs>>(`/api/promotions/${id}`);
  if (res.status !== 200) throw requestError(res);
  const resource = res.data?.data;
  if (!resource) throw new Error(`Promotion ${id} not found`);
  return resource;
}

/** Approve a promotion — POST /api/promotions/{id}/approve (409 when approver == creator). */
export async function runPromoteApprove(
  client: AxiosInstance,
  id: string
): Promise<PromotionResource | undefined> {
  const res = await client.post<JsonApiSingleResponse<PromotionAttrs>>(
    `/api/promotions/${id}/approve`
  );
  if (res.status === 409) {
    throw new Error('Approval rejected (409): a promotion cannot be approved by its creator.');
  }
  if (!isSuccess(res.status)) throw requestError(res);
  return res.data?.data;
}

/**
 * Execute a promotion — POST /api/promotions/{id}/execute (202). Without
 * {@code wait} this returns {@code null} once execution is accepted. With
 * {@code wait} it polls GET /api/promotions/{id} until the promotion reaches
 * a terminal status (COMPLETED or FAILED) and returns the final resource.
 */
export async function runPromoteExecute(
  client: AxiosInstance,
  id: string,
  opts: WaitOptions = {}
): Promise<PromotionResource | null> {
  const res = await client.post<unknown>(`/api/promotions/${id}/execute`);
  if (!isSuccess(res.status)) throw requestError(res);
  if (!opts.wait) return null;

  const intervalMs = opts.intervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const maxAttempts = opts.maxAttempts ?? DEFAULT_MAX_ATTEMPTS;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const promotion = await runPromoteStatus(client, id);
    if (PROMOTION_TERMINAL_STATUSES.has(promotion.attributes?.status ?? '')) {
      return promotion;
    }
    await sleep(intervalMs);
  }
  throw new Error(`Timed out waiting for promotion ${id} to finish`);
}

/** Roll back an executed promotion — POST /api/promotions/{id}/rollback. */
export async function runPromoteRollback(client: AxiosInstance, id: string): Promise<void> {
  const res = await client.post<unknown>(`/api/promotions/${id}/rollback`);
  if (!isSuccess(res.status)) throw requestError(res);
}

function environmentHuman(env: EnvironmentResource): string {
  const attrs = env.attributes ?? {};
  const lines = [
    `Environment: ${attrs.name ?? env.id}`,
    `  ID:     ${env.id}`,
    `  Type:   ${attrs.type ?? ''}`,
    `  Status: ${attrs.status ?? ''}`,
  ];
  return lines.join('\n') + '\n';
}

function promotionHuman(promotion: PromotionResource): string {
  const attrs = promotion.attributes ?? {};
  const lines = [
    `Promotion ${promotion.id}`,
    `  Status:  ${attrs.status ?? ''}`,
    `  Type:    ${attrs.promotion_type ?? ''} (conflict: ${attrs.conflict_mode ?? ''})`,
    `  Source:  ${attrs.source_env_name ?? ''}`,
    `  Target:  ${attrs.target_env_name ?? ''}`,
    `  Items:   ${String(attrs.items_promoted ?? 0)} promoted, ` +
      `${String(attrs.items_skipped ?? 0)} skipped, ${String(attrs.items_failed ?? 0)} failed`,
  ];
  if (attrs.error_message) lines.push(`  Error:   ${attrs.error_message}`);
  return lines.join('\n') + '\n';
}

const sandboxCreate = defineCommand({
  group: 'sandbox',
  name: 'create',
  summary: 'Create a sandbox environment (prints one-time admin credentials)',
  options: [
    { flag: '-n, --name <name>', description: 'Environment name' },
    { flag: '-d, --description <description>', description: 'Environment description' },
  ],
  input: z.object({ name: z.string().min(1), description: z.string().optional() }),
  handler: async (ctx, input) => {
    const env = await runSandboxCreate(ctx.client.getAxiosInstance(), input);
    const attrs = env.attributes ?? {};
    const lines = [
      `Sandbox environment created (id: ${env.id}, status: ${attrs.status ?? 'PENDING'})`,
    ];
    if (attrs.sandboxSlug || attrs.adminUsername || attrs.adminInitialPassword) {
      lines.push(
        '',
        'One-time admin credentials — store them now, they will NOT be shown again:',
        `  Sandbox slug:   ${attrs.sandboxSlug ?? ''}`,
        `  Admin username: ${attrs.adminUsername ?? ''}`,
        `  Admin password: ${attrs.adminInitialPassword ?? ''}`,
        '',
        'Log in with these credentials:',
        '  POST /auth/direct-login ' +
          `{ "username": "${attrs.adminUsername ?? ''}", "password": "<password>", ` +
          `"tenantSlug": "${attrs.sandboxSlug ?? ''}" }`,
        '  or: kelta token create (after logging in via the browser)'
      );
    }
    return { data: { data: env }, human: lines.join('\n') + '\n', ids: [env.id] };
  },
});

const sandboxList = defineCommand({
  group: 'sandbox',
  name: 'list',
  summary: 'List environments',
  input: z.object({}),
  handler: async (ctx) => {
    const envs = await runSandboxList(ctx.client.getAxiosInstance());
    return {
      data: { data: envs },
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'type', header: 'TYPE' },
        { key: 'status', header: 'STATUS' },
        { key: 'sandbox_slug', header: 'SLUG' },
      ],
    };
  },
});

const sandboxStatus = defineCommand({
  group: 'sandbox',
  name: 'status',
  summary: 'Show environment status (with --wait, poll until ACTIVE or FAILED)',
  positionals: [{ name: 'envId', description: 'Environment id', required: true }],
  options: [{ flag: '--wait', description: 'Poll until the environment reaches ACTIVE or FAILED' }],
  input: z.object({ envId: z.string().min(1), wait: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const env = await runSandboxStatus(ctx.client.getAxiosInstance(), input.envId, {
      wait: input.wait,
    });
    return { data: { data: env }, human: environmentHuman(env) };
  },
});

const sandboxRefresh = defineCommand({
  group: 'sandbox',
  name: 'refresh',
  summary: 'Refresh a sandbox environment from its source',
  dangerous: true,
  positionals: [{ name: 'envId', description: 'Environment id', required: true }],
  input: z.object({ envId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await runSandboxRefresh(ctx.client.getAxiosInstance(), input.envId);
    return { message: `Refresh started for environment ${input.envId}.` };
  },
});

const sandboxDelete = defineCommand({
  group: 'sandbox',
  name: 'delete',
  summary: 'Delete a sandbox environment (archives it; decommissions the backing tenant)',
  dangerous: true,
  positionals: [{ name: 'envId', description: 'Environment id', required: true }],
  input: z.object({ envId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await runSandboxDelete(ctx.client.getAxiosInstance(), input.envId);
    return { message: `Environment ${input.envId} deleted.` };
  },
});

const promoteCreate = defineCommand({
  group: 'promote',
  name: 'create',
  summary: 'Create a promotion from a source to a target environment',
  options: [
    { flag: '-s, --source <envId>', description: 'Source environment id' },
    { flag: '-t, --target <envId>', description: 'Target environment id' },
    { flag: '--type <type>', description: 'Promotion type: FULL or SELECTIVE', default: 'FULL' },
    { flag: '--conflict <mode>', description: 'Conflict mode: skip or overwrite', default: 'skip' },
    {
      flag: '--item <spec>',
      description: 'Item to promote as TYPE:name (repeatable, for SELECTIVE promotions)',
      repeatable: true,
    },
  ],
  input: z.object({
    source: z.string().min(1),
    target: z.string().min(1),
    type: z.string().default('FULL'),
    conflict: z.string().default('skip'),
    item: z.array(z.string()).default([]),
  }),
  handler: async (ctx, input) => {
    const promotion = await runPromoteCreate(ctx.client.getAxiosInstance(), {
      sourceEnvId: input.source,
      targetEnvId: input.target,
      promotionType: normalizeChoice(input.type, PROMOTION_TYPES, 'promotion type'),
      conflictMode: normalizeChoice(input.conflict, CONFLICT_MODES, 'conflict mode'),
      items: input.item.map(parseItemSpec),
    });
    return {
      data: { data: promotion },
      message: `Promotion created (id: ${promotion.id}, status: ${promotion.attributes?.status ?? 'PENDING'})`,
      ids: [promotion.id],
    };
  },
});

const promotePreview = defineCommand({
  group: 'promote',
  name: 'preview',
  summary: 'Preview the changes a promotion from a source environment would make',
  options: [{ flag: '-s, --source <envId>', description: 'Source environment id' }],
  input: z.object({ source: z.string().min(1) }),
  handler: async (ctx, input) => {
    const preview = await runPromotePreview(ctx.client.getAxiosInstance(), input.source);
    const changes = preview.changes ?? [];
    const lines =
      changes.length === 0
        ? ['No changes to promote.']
        : [
            `${String(changes.length)} change(s):`,
            `  ${'Action'.padEnd(10)} ${'Type'.padEnd(18)} Name`,
            `  ${'-'.repeat(60)}`,
            ...changes.map(
              (change) =>
                `  ${(change.action ?? '').padEnd(10)} ${(change.type ?? '').padEnd(18)} ${change.name ?? ''}`
            ),
          ];
    return { data: preview, human: lines.join('\n') + '\n' };
  },
});

const promoteApprove = defineCommand({
  group: 'promote',
  name: 'approve',
  summary: 'Approve a promotion (a promotion cannot be approved by its creator)',
  positionals: [{ name: 'id', description: 'Promotion id', required: true }],
  input: z.object({ id: z.string().min(1) }),
  handler: async (ctx, input) => {
    const promotion = await runPromoteApprove(ctx.client.getAxiosInstance(), input.id);
    const status = promotion?.attributes?.status;
    return {
      data: promotion ? { data: promotion } : undefined,
      message: `Promotion ${input.id} approved${status ? ` (status: ${status})` : ''}.`,
    };
  },
});

const promoteExecute = defineCommand({
  group: 'promote',
  name: 'execute',
  summary: 'Execute a promotion (with --wait, poll until COMPLETED or FAILED)',
  dangerous: true,
  positionals: [{ name: 'id', description: 'Promotion id', required: true }],
  options: [
    { flag: '--wait', description: 'Poll until the promotion reaches COMPLETED or FAILED' },
  ],
  input: z.object({ id: z.string().min(1), wait: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const promotion = await runPromoteExecute(ctx.client.getAxiosInstance(), input.id, {
      wait: input.wait,
    });
    if (!promotion) {
      return {
        data: { started: true, id: input.id },
        message: `Execution started for promotion ${input.id}. Check progress with: kelta promote status ${input.id}`,
      };
    }
    if (promotion.attributes?.status === 'FAILED') {
      process.exitCode = 1;
    }
    return { data: { data: promotion }, human: promotionHuman(promotion) };
  },
});

const promoteStatus = defineCommand({
  group: 'promote',
  name: 'status',
  summary: 'Show a promotion and its item counts',
  positionals: [{ name: 'id', description: 'Promotion id', required: true }],
  input: z.object({ id: z.string().min(1) }),
  handler: async (ctx, input) => {
    const promotion = await runPromoteStatus(ctx.client.getAxiosInstance(), input.id);
    return { data: { data: promotion }, human: promotionHuman(promotion) };
  },
});

const promoteRollback = defineCommand({
  group: 'promote',
  name: 'rollback',
  summary: 'Roll back an executed promotion',
  dangerous: true,
  positionals: [{ name: 'id', description: 'Promotion id', required: true }],
  input: z.object({ id: z.string().min(1) }),
  handler: async (ctx, input) => {
    await runPromoteRollback(ctx.client.getAxiosInstance(), input.id);
    return { message: `Rollback started for promotion ${input.id}.` };
  },
});

export const environmentCommands: RegisteredCommand[] = [
  sandboxCreate,
  sandboxList,
  sandboxStatus,
  sandboxRefresh,
  sandboxDelete,
  promoteCreate,
  promotePreview,
  promoteApprove,
  promoteExecute,
  promoteStatus,
  promoteRollback,
];
