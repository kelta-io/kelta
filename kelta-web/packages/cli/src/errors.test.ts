import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { CommanderError } from 'commander';
import { z } from 'zod';
import { buildCommandIndex, checkInvocation } from './docsgen/docLines.js';
import { CliError, EXIT, errorFromResponse, mapError, toErrorPayload } from './errors.js';
import { buildManifest } from './registry/manifest.js';
import { allCommands } from './registry/registry.js';

function axiosError(
  status: number,
  body: unknown,
  responseHeaders: Record<string, string> = {}
): AxiosError {
  const headers = new AxiosHeaders();
  const config = { headers };
  return new AxiosError(
    `Request failed with status code ${String(status)}`,
    'ERR_BAD_REQUEST',
    config as never,
    {},
    {
      status,
      statusText: '',
      headers: responseHeaders,
      config: config as never,
      data: body,
    }
  );
}

describe('mapError', () => {
  it('passes CliError through unchanged', () => {
    const original = new CliError('x', { code: 'X', exitCode: 5 });
    expect(mapError(original)).toBe(original);
  });

  it('extracts the JSON:API error contract (code, detail, requestId)', () => {
    const mapped = mapError(
      axiosError(400, {
        errors: [
          {
            status: '400',
            code: 'VALIDATION_FAILED',
            detail: 'name is required',
            meta: { requestId: 'req-1' },
          },
        ],
      })
    );
    expect(mapped.code).toBe('VALIDATION_FAILED');
    expect(mapped.message).toBe('name is required');
    expect(mapped.requestId).toBe('req-1');
    expect(mapped.exitCode).toBe(EXIT.API);
  });

  it('preserves source.pointer and the full meta for a reference error', () => {
    const mapped = mapError(
      axiosError(400, {
        errors: [
          {
            status: '400',
            code: 'REFERENCE_ERROR',
            detail: "Referenced record 'x' does not exist in collection 'contacts' (field owner)",
            source: { pointer: '/data/attributes/owner' },
            meta: { field: 'owner', value: 'x', targetCollection: 'contacts', requestId: 'req-2' },
          },
        ],
      })
    );
    expect(mapped.source).toEqual({ pointer: '/data/attributes/owner' });
    expect(mapped.meta).toMatchObject({ targetCollection: 'contacts', requestId: 'req-2' });
  });

  it('keeps every entry of errors[], not just the first', () => {
    const mapped = mapError(
      axiosError(400, {
        errors: [
          {
            status: '400',
            code: 'VALIDATION_FAILED',
            detail: 'name is required',
            source: { pointer: '/data/attributes/name' },
          },
          {
            status: '400',
            code: 'VALIDATION_FAILED',
            detail: 'amount must be >= 0',
            source: { pointer: '/data/attributes/amount' },
          },
        ],
      })
    );
    expect(mapped.errors).toHaveLength(2);
    expect(mapped.errors?.[1]).toMatchObject({
      detail: 'amount must be >= 0',
      source: { pointer: '/data/attributes/amount' },
    });
  });

  it('leaves errors undefined when the body has no JSON:API envelope', () => {
    const mapped = mapError(axiosError(500, 'oops'));
    expect(mapped.errors).toBeUndefined();
  });

  it.each([
    [401, EXIT.AUTH, 'UNAUTHENTICATED'],
    [404, EXIT.NOT_FOUND, 'NOT_FOUND'],
    [409, EXIT.CONFLICT, 'CONFLICT'],
    [429, EXIT.CONFLICT, 'RATE_LIMIT_EXCEEDED'],
    [500, EXIT.API, 'SERVER_ERROR'],
  ])('maps status %i to exit %i / %s without a JSON:API body', (status, exitCode, code) => {
    const mapped = mapError(axiosError(status, 'oops'));
    expect(mapped.exitCode).toBe(exitCode);
    expect(mapped.code).toBe(code);
    expect(mapped.status).toBe(status);
  });

  it('hints to wait Retry-After seconds on RATE_LIMIT_EXCEEDED', () => {
    const mapped = mapError(
      axiosError(
        429,
        {
          errors: [
            {
              status: '429',
              code: 'RATE_LIMIT_EXCEEDED',
              title: 'RATE_LIMIT_EXCEEDED',
              detail: 'Rate limit exceeded. Retry after 42 seconds.',
              meta: { requestId: 'req-429' },
            },
          ],
        },
        { 'retry-after': '42' }
      )
    );
    expect(mapped.code).toBe('RATE_LIMIT_EXCEEDED');
    expect(mapped.exitCode).toBe(EXIT.CONFLICT);
    expect(mapped.requestId).toBe('req-429');
    expect(mapped.hint).toContain('Wait 42 seconds (Retry-After)');
    const payload = JSON.parse(toErrorPayload(mapped)) as { error: { hint?: string } };
    expect(payload.error.hint).toBe(mapped.hint);
  });

  it('still hints on a 429 without Retry-After', () => {
    const mapped = mapError(axiosError(429, 'slow down'));
    expect(mapped.hint).toMatch(/^Wait before retrying/);
  });

  it('gives no hint for other errors', () => {
    expect(mapError(axiosError(500, 'oops')).hint).toBeUndefined();
    expect(mapError(axiosError(409, 'taken')).hint).toBeUndefined();
  });

  it('maps network errors (no response) with a hint to check the API URL', () => {
    const error = new AxiosError('socket hang up', 'ECONNRESET');
    const mapped = mapError(error);
    expect(mapped.code).toBe('NETWORK_ERROR');
    expect(mapped.exitCode).toBe(EXIT.API);
    expect(mapped.hint).toContain('`kelta profile show`');
    expect(mapped.hint).toContain('API URL');
  });

  it('maps zod failures to usage errors (exit 2)', () => {
    const result = z.object({ name: z.string() }).safeParse({});
    expect(result.success).toBe(false);
    if (!result.success) {
      const mapped = mapError(result.error);
      expect(mapped.code).toBe('INVALID_ARGUMENTS');
      expect(mapped.exitCode).toBe(EXIT.USAGE);
      expect(mapped.message).toContain('name');
    }
  });

  it.each([
    ['commander.missingArgument', "error: missing required argument 'id'"],
    ['commander.optionMissingArgument', "error: option '--data <json>' argument missing"],
    ['commander.unknownOption', "error: unknown option '--colour'"],
    ['commander.unknownCommand', "error: unknown command 'recrods'\n(Did you mean records?)"],
    [
      'commander.excessArguments',
      "error: too many arguments for 'get'. Expected 2 arguments but got 3.",
    ],
  ])(
    'maps a %s parse error to INVALID_ARGUMENTS (exit 2), keeping the message',
    (code, message) => {
      const mapped = mapError(new CommanderError(1, code, message));
      expect(mapped.code).toBe('INVALID_ARGUMENTS');
      expect(mapped.exitCode).toBe(EXIT.USAGE);
      expect(mapped.message).toBe(message.replace(/^error: /, ''));
    }
  );

  it('maps SDK KeltaError-like errors by statusCode', () => {
    const sdkError = Object.assign(new Error('denied'), { statusCode: 401 });
    const mapped = mapError(sdkError);
    expect(mapped.exitCode).toBe(EXIT.AUTH);
    expect(mapped.code).toBe('UNAUTHENTICATED');
  });

  it.each([
    [401, '`kelta auth login`'],
    [403, 'permission'],
    [404, 'tenant slug'],
  ])('hints on SDK KeltaError-like errors with status %i', (statusCode, expected) => {
    const mapped = mapError(Object.assign(new Error('failed'), { statusCode }));
    expect(mapped.hint).toContain(expected);
  });

  it('falls back for plain and unknown errors', () => {
    expect(mapError(new Error('boom')).code).toBe('ERROR');
    expect(mapError('boom').code).toBe('ERROR');
  });
});

describe('errorFromResponse hints', () => {
  it('hints to log in on 401', () => {
    const mapped = errorFromResponse(401, 'unauthorized');
    expect(mapped.hint).toContain('`kelta auth login`');
  });

  it('hints at permissions and the profile/tenant on 403', () => {
    const mapped = errorFromResponse(403, {
      errors: [{ status: '403', code: 'INSUFFICIENT_PERMISSIONS', detail: 'denied' }],
    });
    expect(mapped.code).toBe('INSUFFICIENT_PERMISSIONS');
    expect(mapped.hint).toMatch(/permission.*profile and tenant/);
  });

  it('hints to check the id, name and tenant slug on 404', () => {
    const mapped = errorFromResponse(404, {
      errors: [{ status: '404', code: 'RECORD_NOT_FOUND', detail: 'no such record' }],
    });
    expect(mapped.hint).toMatch(/id or name.*tenant slug/);
  });

  it('keeps the rate-limit hint when a 401/403/404 carries RATE_LIMIT_EXCEEDED', () => {
    const mapped = errorFromResponse(403, {
      errors: [{ status: '403', code: 'RATE_LIMIT_EXCEEDED', detail: 'slow down' }],
    });
    expect(mapped.hint).toMatch(/^Wait before retrying/);
  });

  it('names only commands that exist in the CLI registry', () => {
    const hints = [
      errorFromResponse(401, '').hint,
      errorFromResponse(403, '').hint,
      errorFromResponse(404, '').hint,
      errorFromResponse(429, '').hint,
      mapError(new AxiosError('socket hang up', 'ECONNRESET')).hint,
    ];
    const commands = hints.flatMap((hint) =>
      [...(hint ?? '').matchAll(/`(kelta [^`]+)`/g)].map((match) => match[1])
    );
    expect(commands.length).toBeGreaterThanOrEqual(4);
    const index = buildCommandIndex(buildManifest(allCommands), [], []);
    for (const command of commands) {
      expect(checkInvocation(command, index), command).toBeNull();
    }
  });
});

describe('toErrorPayload', () => {
  it('includes the hint on a 401', () => {
    const mapped = errorFromResponse(401, 'unauthorized');
    const payload = JSON.parse(toErrorPayload(mapped)) as { error: { hint?: string } };
    expect(payload.error.hint).toBeTruthy();
    expect(payload.error.hint).toBe(mapped.hint);
  });

  it('emits the single-line machine contract', () => {
    const payload = toErrorPayload(new CliError('bad', { code: 'X', exitCode: 1, status: 400 }));
    expect(JSON.parse(payload)).toEqual({
      error: { code: 'X', status: 400, detail: 'bad' },
    });
    expect(payload).not.toContain('\n');
  });

  it('carries source, meta, and the full errors[] through onto the envelope', () => {
    const mapped = mapError(
      axiosError(400, {
        errors: [
          {
            status: '400',
            code: 'REFERENCE_ERROR',
            detail: "Referenced record 'x' does not exist in collection 'contacts'",
            source: { pointer: '/data/attributes/owner' },
            meta: { targetCollection: 'contacts', requestId: 'req-3' },
          },
          {
            status: '400',
            code: 'VALIDATION_FAILED',
            detail: 'name is required',
            source: { pointer: '/data/attributes/name' },
          },
        ],
      })
    );
    const payload = JSON.parse(toErrorPayload(mapped)) as {
      error: { source?: { pointer?: string }; meta?: { targetCollection?: string } };
      errors: unknown[];
    };
    expect(payload.error.source?.pointer).toBe('/data/attributes/owner');
    expect(payload.error.meta?.targetCollection).toBe('contacts');
    expect(payload.errors).toHaveLength(2);
  });

  it('omits errors[] when the failure did not come from a JSON:API response', () => {
    const payload = JSON.parse(
      toErrorPayload(new CliError('boom', { code: 'ERROR', exitCode: 1 }))
    );
    expect(payload).not.toHaveProperty('errors');
  });
});
