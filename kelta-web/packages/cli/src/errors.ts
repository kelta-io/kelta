import axios from 'axios';
import { ZodError } from 'zod';

/** Stable exit codes — part of the CLI's machine contract (see specs/kelta-cli). */
export const EXIT = {
  OK: 0,
  API: 1,
  USAGE: 2,
  AUTH: 3,
  NOT_FOUND: 4,
  CONFLICT: 5,
} as const;

/** One JSON:API error object, as sent by the platform (`JsonApiError#toMap`). */
export interface JsonApiErrorEntry {
  status?: string;
  code?: string;
  detail?: string;
  title?: string;
  source?: Record<string, unknown>;
  meta?: Record<string, unknown> & { requestId?: string };
}

export interface CliErrorOptions {
  code: string;
  exitCode: number;
  status?: number;
  requestId?: string;
  source?: Record<string, unknown>;
  meta?: Record<string, unknown>;
  /** The full JSON:API `errors[]` array, when the failure came from one. */
  errors?: JsonApiErrorEntry[];
}

/** A fully-mapped CLI failure: stable `code`, exit code, optional HTTP context. */
export class CliError extends Error {
  readonly code: string;
  readonly exitCode: number;
  readonly status?: number;
  readonly requestId?: string;
  readonly source?: Record<string, unknown>;
  readonly meta?: Record<string, unknown>;
  readonly errors?: JsonApiErrorEntry[];

  constructor(message: string, options: CliErrorOptions) {
    super(message);
    this.name = 'CliError';
    this.code = options.code;
    this.exitCode = options.exitCode;
    this.status = options.status;
    this.requestId = options.requestId;
    this.source = options.source;
    this.meta = options.meta;
    this.errors = options.errors;
    Object.setPrototypeOf(this, CliError.prototype);
  }
}

interface JsonApiErrorBody {
  errors?: JsonApiErrorEntry[];
}

/**
 * Structural check for the SDK's KeltaError family. Deliberately not
 * `instanceof` — the SDK class identity differs between the bundled binary,
 * dist imports, and vitest source aliasing.
 */
function isKeltaErrorLike(error: unknown): error is Error & { statusCode?: number } {
  return error instanceof Error && 'statusCode' in error;
}

function exitCodeForStatus(status: number): number {
  if (status === 401) return EXIT.AUTH;
  if (status === 404) return EXIT.NOT_FOUND;
  if (status === 409 || status === 429) return EXIT.CONFLICT;
  return EXIT.API;
}

function codeForStatus(status: number): string {
  switch (status) {
    case 400:
      return 'BAD_REQUEST';
    case 401:
      return 'UNAUTHENTICATED';
    case 403:
      return 'FORBIDDEN';
    case 404:
      return 'NOT_FOUND';
    case 409:
      return 'CONFLICT';
    case 429:
      return 'RATE_LIMIT_EXCEEDED';
    default:
      return status >= 500 ? 'SERVER_ERROR' : 'API_ERROR';
  }
}

/**
 * Map any thrown value to a CliError. Prefers the platform's JSON:API error
 * envelope (`errors[0].code` is the stable UPPER_SNAKE_CASE contract clients
 * branch on); falls back to SDK error classes, then generic mapping.
 */
export function mapError(error: unknown): CliError {
  if (error instanceof CliError) return error;

  if (error instanceof ZodError) {
    const detail = error.errors.map((e) => `${e.path.join('.')}: ${e.message}`).join('; ');
    return new CliError(`Invalid arguments — ${detail}`, {
      code: 'INVALID_ARGUMENTS',
      exitCode: EXIT.USAGE,
    });
  }

  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return new CliError(error.message || 'Network error', {
        code: 'NETWORK_ERROR',
        exitCode: EXIT.API,
      });
    }
    const status = error.response.status;
    const body = error.response.data as JsonApiErrorBody | undefined;
    const entries = body?.errors ?? [];
    const first = entries[0];
    return new CliError(first?.detail ?? first?.title ?? `Request failed with status ${status}`, {
      code: first?.code ?? codeForStatus(status),
      exitCode: exitCodeForStatus(status),
      status,
      requestId: first?.meta?.requestId,
      source: first?.source,
      meta: first?.meta,
      errors: entries.length > 0 ? entries : undefined,
    });
  }

  if (isKeltaErrorLike(error)) {
    const status = error.statusCode;
    return new CliError(error.message, {
      code: status !== undefined ? codeForStatus(status) : 'API_ERROR',
      exitCode: status !== undefined ? exitCodeForStatus(status) : EXIT.API,
      status,
    });
  }

  if (error instanceof Error) {
    return new CliError(error.message, { code: 'ERROR', exitCode: EXIT.API });
  }
  return new CliError('Unknown error', { code: 'ERROR', exitCode: EXIT.API });
}

/**
 * Machine-readable single-line error payload written to stderr in non-table
 * modes. `error` is `errors[0]` flattened onto the stable CliError fields for
 * backwards compatibility; `errors` (when the failure came from a JSON:API
 * response) carries every entry — source, meta, and all — so callers no
 * longer have to reach past the first validation failure.
 */
export function toErrorPayload(error: CliError): string {
  return JSON.stringify({
    error: {
      code: error.code,
      status: error.status,
      detail: error.message,
      source: error.source,
      meta: error.meta,
    },
    ...(error.errors && error.errors.length > 0 ? { errors: error.errors } : {}),
  });
}
