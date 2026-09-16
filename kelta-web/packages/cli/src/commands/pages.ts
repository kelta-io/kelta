import { readFileSync } from 'node:fs';
import type { AxiosInstance } from 'axios';
import { z } from 'zod';
import { deepEqual, readDataArgument } from '../data.js';
import { CliError, EXIT, type JsonApiErrorEntry } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

interface PageConfigProblem {
  path: string;
  message: string;
  severity: string;
}

interface PageValidateResponse {
  valid: boolean;
  errors: PageConfigProblem[];
}

/** Reads and parses a `pages apply <file>` JSON document — {name, path, slug?, title?, config?, published?, active?}. */
function readPageFile(path: string): Record<string, unknown> {
  let raw: string;
  try {
    raw = readFileSync(path, 'utf-8');
  } catch {
    throw new CliError(`Cannot read file "${path}"`, {
      code: 'FILE_NOT_FOUND',
      exitCode: EXIT.USAGE,
    });
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new CliError(`Invalid JSON in "${path}"`, { code: 'INVALID_JSON', exitCode: EXIT.USAGE });
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new CliError(`"${path}" must contain a JSON object (a page document)`, {
      code: 'INVALID_JSON',
      exitCode: EXIT.USAGE,
    });
  }
  return parsed as Record<string, unknown>;
}

/** Resolves an existing `ui-pages` row by a single natural-key field, or undefined when none matches. */
async function findPage(
  axios: AxiosInstance,
  field: 'path' | 'slug',
  value: string
): Promise<{ id: string; attributes?: Record<string, unknown> } | undefined> {
  const response = await axios.get<{ data?: { id: string; attributes?: Record<string, unknown> }[] }>(
    `/api/ui-pages?filter[${field}][eq]=${encodeURIComponent(value)}&page[size]=1`
  );
  return response.data.data?.[0];
}

const pageList = defineCommand({
  group: 'pages',
  name: 'list',
  summary: 'List UI pages (screen builder)',
  input: z.object({}),
  handler: async (ctx) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>('/api/ui-pages');
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'path', header: 'PATH' },
        { key: 'published', header: 'PUBLISHED' },
        { key: 'active', header: 'ACTIVE' },
      ],
    };
  },
});

const pageGet = defineCommand({
  group: 'pages',
  name: 'get',
  summary: 'Get a UI page by id',
  positionals: [{ name: 'pageId', description: 'Page id', required: true }],
  input: z.object({ pageId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/ui-pages/${input.pageId}`);
    return { data: response.data };
  },
});

const pageCreate = defineCommand({
  group: 'pages',
  name: 'create',
  summary: 'Create a UI page (draft — unpublished until `pages publish`)',
  options: [
    { flag: '--name <name>', description: 'Page name (required)' },
    { flag: '--path <path>', description: 'Route the page is served at (required)' },
    { flag: '--title <title>', description: 'Browser and header title' },
    {
      flag: '--config <json>',
      description: 'Page config as JSON, @file, or - (layout, components, bindings)',
    },
    { flag: '--active <bool>', description: 'true|false (default true)' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    name: z.string().min(1),
    path: z.string().min(1),
    title: z.string().optional(),
    config: z.string().optional(),
    active: z.enum(['true', 'false']).optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = { name: input.name, path: input.path };
    if (input.title) attributes.title = input.title;
    if (input.config) attributes.config = readDataArgument(input.config);
    if (input.active !== undefined) attributes.active = input.active === 'true';
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .post<{ data?: { id?: string } }>('/api/ui-pages', {
        data: { type: 'ui-pages', attributes },
      });
    return {
      data: response.data,
      message: `Page "${input.name}" created at ${input.path}`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const pageApply = defineCommand({
  group: 'pages',
  name: 'apply',
  summary: 'Validate then create-or-update a UI page from a JSON file, keyed on path',
  dangerous: (input) => !input.dryRun,
  positionals: [
    {
      name: 'file',
      description: 'Page JSON file — {name, path, slug?, title?, config?, published?, active?}',
      required: true,
    },
  ],
  options: [{ flag: '--dry-run', description: 'Validate config only; write nothing' }],
  input: z.object({
    file: z.string().min(1),
    dryRun: z.boolean().default(false),
  }),
  handler: async (ctx, input) => {
    const doc = readPageFile(input.file);
    const axios = ctx.client.getAxiosInstance();
    const config = (
      doc.config && typeof doc.config === 'object' && !Array.isArray(doc.config) ? doc.config : {}
    ) as Record<string, unknown>;

    const validation = await axios.post<PageValidateResponse>('/api/ui-pages/validate', { config });
    if (!validation.data.valid) {
      const problems: PageConfigProblem[] = validation.data.errors;
      const errors: JsonApiErrorEntry[] = problems.map((e: PageConfigProblem) => ({
        status: '400',
        code: 'VALIDATION_FAILED',
        title: 'Validation Error',
        detail: e.message,
        source: { pointer: e.path },
      }));
      throw new CliError(
        `Page config is invalid: ${problems.map((e: PageConfigProblem) => `${e.path}: ${e.message}`).join('; ')}`,
        { code: 'VALIDATION_FAILED', exitCode: EXIT.USAGE, errors }
      );
    }
    if (input.dryRun) {
      return { data: validation.data, message: 'Page config is valid (dry run — nothing written)' };
    }

    if (typeof doc.name !== 'string' || !doc.name) {
      throw new CliError('Page file needs a non-blank "name"', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    if (typeof doc.path !== 'string' || !doc.path) {
      throw new CliError('Page file needs a non-blank "path"', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const slug = typeof doc.slug === 'string' && doc.slug ? doc.slug : undefined;

    const attributes: Record<string, unknown> = { name: doc.name, path: doc.path, config };
    if (slug) attributes.slug = slug;
    if (typeof doc.title === 'string' && doc.title) attributes.title = doc.title;
    if (typeof doc.published === 'boolean') attributes.published = doc.published;
    if (typeof doc.active === 'boolean') attributes.active = doc.active;

    let existing = await findPage(axios, 'path', doc.path);
    if (!existing && slug) {
      existing = await findPage(axios, 'slug', slug);
    }

    if (existing) {
      const changed = Object.keys(attributes).filter(
        (key) => !deepEqual(existing?.attributes?.[key], attributes[key])
      );
      if (changed.length === 0) {
        return {
          data: { action: 'unchanged', id: existing.id },
          message: `Page "${doc.path}" unchanged`,
          ids: [existing.id],
        };
      }
      const patchAttrs: Record<string, unknown> = {};
      for (const key of changed) patchAttrs[key] = attributes[key];
      const response = await axios.patch<unknown>(`/api/ui-pages/${existing.id}`, {
        data: { type: 'ui-pages', id: existing.id, attributes: patchAttrs },
      });
      return { data: response.data, message: `Page "${doc.path}" updated`, ids: [existing.id] };
    }
    const response = await axios.post<{ data?: { id?: string } }>('/api/ui-pages', {
      data: { type: 'ui-pages', attributes },
    });
    return {
      data: response.data,
      message: `Page "${doc.path}" created`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const pageUpdate = defineCommand({
  group: 'pages',
  name: 'update',
  summary: 'Update a UI page by id',
  positionals: [{ name: 'pageId', description: 'Page id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Page name' },
    { flag: '--path <path>', description: 'Route the page is served at' },
    { flag: '--title <title>', description: 'Browser and header title' },
    { flag: '--config <json>', description: 'Page config as JSON, @file, or -' },
    { flag: '--active <bool>', description: 'true|false' },
    { flag: '--published <bool>', description: 'true|false' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    pageId: z.string().min(1),
    name: z.string().optional(),
    path: z.string().optional(),
    title: z.string().optional(),
    config: z.string().optional(),
    active: z.enum(['true', 'false']).optional(),
    published: z.enum(['true', 'false']).optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.name) attributes.name = input.name;
    if (input.path) attributes.path = input.path;
    if (input.title) attributes.title = input.title;
    if (input.config) attributes.config = readDataArgument(input.config);
    if (input.active !== undefined) attributes.active = input.active === 'true';
    if (input.published !== undefined) attributes.published = input.published === 'true';
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    if (Object.keys(attributes).length === 0) {
      throw new CliError('Nothing to update — pass at least one field flag', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/ui-pages/${input.pageId}`, {
        data: { type: 'ui-pages', id: input.pageId, attributes },
      });
    return { data: response.data, message: `Page ${input.pageId} updated` };
  },
});

const pageDelete = defineCommand({
  group: 'pages',
  name: 'delete',
  summary: 'Delete a UI page',
  dangerous: true,
  positionals: [{ name: 'pageId', description: 'Page id', required: true }],
  input: z.object({ pageId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/ui-pages/${input.pageId}`);
    return {
      data: { deleted: true, id: input.pageId },
      message: `Page ${input.pageId} deleted`,
      ids: [input.pageId],
    };
  },
});

const pagePublish = defineCommand({
  group: 'pages',
  name: 'publish',
  summary: 'Publish the UI page served at a route path (sets published=true)',
  positionals: [{ name: 'path', description: 'Route the page is served at', required: true }],
  input: z.object({ path: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const page = await findPage(axios, 'path', input.path);
    if (!page) {
      throw new CliError(`Page at path "${input.path}" not found`, {
        code: 'NOT_FOUND',
        exitCode: EXIT.NOT_FOUND,
      });
    }
    if (page.attributes?.published === true) {
      return { data: { action: 'unchanged', id: page.id }, message: `Page "${input.path}" already published`, ids: [page.id] };
    }
    const updated = await axios.patch<unknown>(`/api/ui-pages/${page.id}`, {
      data: { type: 'ui-pages', id: page.id, attributes: { published: true } },
    });
    return { data: updated.data, message: `Page "${input.path}" published`, ids: [page.id] };
  },
});

export const pageCommands: RegisteredCommand[] = [
  pageList,
  pageGet,
  pageCreate,
  pageApply,
  pageUpdate,
  pageDelete,
  pagePublish,
];
