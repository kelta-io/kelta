import { z } from 'zod';
import { readJsonArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;

const api = defineCommand({
  group: '',
  name: 'api',
  summary: 'Raw API escape hatch: profile auth + tenant prefix applied, response verbatim',
  positionals: [
    { name: 'method', description: 'GET|POST|PUT|PATCH|DELETE', required: true },
    {
      name: 'path',
      description: 'Path incl. query, e.g. /api/collections?page[size]=5',
      required: true,
    },
  ],
  options: [
    { flag: '--data <json>', description: 'Request body as JSON, @file, or - (any JSON value)' },
    {
      flag: '--header <h>',
      description: 'Extra header as Name:value (repeatable)',
      repeatable: true,
    },
  ],
  examples: [
    { cmd: "kelta api GET '/api/customers?page[size]=5'" },
    {
      cmd: 'kelta api PATCH /api/customers/rec_123 --data \'{"data":{"type":"customers","id":"rec_123","attributes":{"status":"active"}}}\' --yes',
      description: 'Update a record with a raw JSON:API body',
    },
  ],
  // sugar-level validation is bypassed BY DESIGN — the gateway and Cerbos
  // remain the enforcement layer, exactly as for any other API client
  input: z.object({
    method: z.string().transform((value) => value.toUpperCase()),
    path: z.string().min(1),
    data: z.string().optional(),
    header: z.array(z.string()).default([]),
  }),
  dangerous: (input) => input.method !== 'GET',
  handler: async (ctx, input) => {
    if (!(METHODS as readonly string[]).includes(input.method)) {
      throw new CliError(`Unsupported method "${input.method}" (expected ${METHODS.join('|')})`, {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    if (!input.path.startsWith('/')) {
      throw new CliError('Path must start with / (it is resolved under the tenant prefix)', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const headers: Record<string, string> = {};
    for (const header of input.header) {
      const idx = header.indexOf(':');
      if (idx <= 0) {
        throw new CliError(`Invalid --header "${header}" (expected Name:value)`, {
          code: 'INVALID_ARGUMENTS',
          exitCode: EXIT.USAGE,
        });
      }
      headers[header.slice(0, idx).trim()] = header.slice(idx + 1).trim();
    }
    const response = await ctx.client.getAxiosInstance().request<unknown>({
      method: input.method,
      url: input.path,
      ...(input.data !== undefined ? { data: readJsonArgument(input.data) } : {}),
      ...(Object.keys(headers).length > 0 ? { headers } : {}),
    });
    return { data: response.data, ids: [], verbatim: true };
  },
});

export const apiCommands: RegisteredCommand[] = [api];
