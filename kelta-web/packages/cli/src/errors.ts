import axios from 'axios';
import { CommanderError } from 'commander';
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
  /** What the caller can do about it, when the CLI knows. */
  hint?: string;
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
  readonly hint?: string;

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
    this.hint = options.hint;
    Object.setPrototypeOf(this, CliError.prototype);
  }
}

interface JsonApiErrorBody {
  errors?: JsonApiErrorEntry[];
  /** Legacy `{ "error": "..." }` bodies some endpoints still return. */
  error?: unknown;
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

/** `Retry-After` as whole seconds; the HTTP-date form is not used by the gateway. */
function retryAfterSeconds(headers: unknown): number | undefined {
  if (typeof headers !== 'object' || headers === null) return undefined;
  const raw = (headers as Record<string, unknown>)['retry-after'];
  const value = typeof raw === 'number' ? raw : typeof raw === 'string' ? Number(raw.trim()) : NaN;
  return Number.isInteger(value) && value >= 0 ? value : undefined;
}

/** What to do next for the statuses every command can hit; keyed on HTTP status, not the server's code. */
const STATUS_HINTS: Record<number, string> = {
  401: 'Run `kelta auth login` to sign in again, or check that KELTA_TOKEN holds a valid token.',
  403: "The token's user lacks permission for this on the tenant; check the profile and tenant with `kelta auth status`.",
  404: 'Check the id or name, and that the tenant slug is right (`kelta auth status`).',
};

/** Hint for a failure that never reached the server. */
const NETWORK_HINT =
  "Check the profile's API URL with `kelta profile show` and that it is reachable from here.";

function statusHint(status: number | undefined): string | undefined {
  return status !== undefined ? STATUS_HINTS[status] : undefined;
}

function hintFor(code: string, status: number, headers: unknown): string | undefined {
  if (code !== 'RATE_LIMIT_EXCEEDED') return statusHint(status);
  const seconds = retryAfterSeconds(headers);
  const wait =
    seconds !== undefined
      ? `Wait ${String(seconds)} second${seconds === 1 ? '' : 's'} (Retry-After) before retrying`
      : 'Wait before retrying';
  return `${wait}; check usage with \`kelta limits get\` and batch or slow down requests.`;
}

/**
 * Map a non-2xx HTTP response to a CliError, keeping the server's own reason:
 * `errors[0].detail` of a JSON:API envelope, else a legacy `error` string.
 * The generic `Request failed with status N` is only for a body with neither.
 */
export function errorFromResponse(status: number, data: unknown, headers?: unknown): CliError {
  const body = (typeof data === 'object' && data !== null ? data : {}) as JsonApiErrorBody;
  const entries = Array.isArray(body.errors) ? body.errors : [];
  const first = entries[0];
  const legacy = typeof body.error === 'string' && body.error !== '' ? body.error : undefined;
  const code = first?.code ?? codeForStatus(status);
  return new CliError(
    first?.detail ?? first?.title ?? legacy ?? `Request failed with status ${status}`,
    {
      code,
      exitCode: exitCodeForStatus(status),
      status,
      requestId: first?.meta?.requestId,
      source: first?.source,
      meta: first?.meta,
      errors: entries.length > 0 ? entries : undefined,
      hint: hintFor(code, status, headers),
    }
  );
}

/**
 * Map any thrown value to a CliError. Prefers the platform's JSON:API error
 * envelope (`errors[0].code` is the stable UPPER_SNAKE_CASE contract clients
 * branch on); falls back to SDK error classes, then generic mapping.
 */
export function mapError(error: unknown): CliError {
  if (error instanceof CliError) return error;

  // missing argument, unknown option/command, excess arguments: commander's
  // message (with its "Did you mean …?" suggestion) becomes the detail
  if (error instanceof CommanderError) {
    return new CliError(error.message.replace(/^error: /, ''), {
      code: 'INVALID_ARGUMENTS',
      exitCode: EXIT.USAGE,
    });
  }

  if (error instanceof ZodError) {
    const detail = error.issues.map((e) => `${e.path.join('.')}: ${e.message}`).join('; ');
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
        hint: NETWORK_HINT,
      });
    }
    return errorFromResponse(error.response.status, error.response.data, error.response.headers);
  }

  if (isKeltaErrorLike(error)) {
    const status = error.statusCode;
    return new CliError(error.message, {
      code: status !== undefined ? codeForStatus(status) : 'API_ERROR',
      exitCode: status !== undefined ? exitCodeForStatus(status) : EXIT.API,
      status,
      hint: statusHint(status),
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
      hint: error.hint,
    },
    ...(error.errors && error.errors.length > 0 ? { errors: error.errors } : {}),
  });
}
