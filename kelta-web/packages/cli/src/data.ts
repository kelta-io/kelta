import { readFileSync } from 'node:fs';
import { CliError, EXIT } from './errors.js';

function readRaw(value: string): string {
  if (value === '-') return readFileSync(0, 'utf-8');
  if (value.startsWith('@')) {
    try {
      return readFileSync(value.slice(1), 'utf-8');
    } catch {
      throw new CliError(`Cannot read file "${value.slice(1)}"`, {
        code: 'FILE_NOT_FOUND',
        exitCode: EXIT.USAGE,
      });
    }
  }
  return value;
}

/**
 * Parse a `--data` value (inline JSON, `@file`, or `-` for stdin) into ANY
 * JSON value — the raw `kelta api` escape hatch takes bodies verbatim.
 */
export function readJsonArgument(value: string): unknown {
  try {
    return JSON.parse(readRaw(value));
  } catch {
    throw new CliError('Invalid JSON in --data', {
      code: 'INVALID_JSON',
      exitCode: EXIT.USAGE,
    });
  }
}

/**
 * Parse a `--data` value: inline JSON, `@file`, or `-` for stdin. Returns the
 * parsed object; anything non-object (arrays included) is rejected — record
 * attributes are always a JSON object.
 */
export function readDataArgument(value: string): Record<string, unknown> {
  const parsed = readJsonArgument(value);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new CliError('--data must be a JSON object of attributes', {
      code: 'INVALID_JSON',
      exitCode: EXIT.USAGE,
    });
  }
  return parsed as Record<string, unknown>;
}

/**
 * Order-independent for objects, order-dependent for arrays — enough to diff JSON
 * attributes before an `apply`-style command decides whether a write is actually needed.
 * A no-op write still bumps `updatedAt` server-side, so `apply` commands must skip it
 * entirely rather than send an empty/unchanged PATCH.
 */
export function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) || Array.isArray(b)) {
    return (
      Array.isArray(a) &&
      Array.isArray(b) &&
      a.length === b.length &&
      a.every((v, i) => deepEqual(v, b[i]))
    );
  }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    const keysA = Object.keys(a as Record<string, unknown>);
    const keysB = Object.keys(b as Record<string, unknown>);
    return (
      keysA.length === keysB.length &&
      keysA.every((k) =>
        deepEqual((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k])
      )
    );
  }
  return false;
}
