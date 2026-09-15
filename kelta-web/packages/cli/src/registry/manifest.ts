import { zodToJsonSchema } from 'zod-to-json-schema';
import type { ZodType } from 'zod';
import { EXIT } from '../errors.js';
import { OUTPUT_FORMATS } from '../render/render.js';
import { VERSION } from '../version.js';
import type { RegisteredCommand } from './types.js';

/**
 * Machine-readable command catalog derived from the registry — the discovery
 * surface for agents. `manifestVersion` is a contract: additions only within
 * a major (manifest.test.ts pins the structure).
 */
export const MANIFEST_VERSION = 1;

export interface ManifestCommand {
  group: string;
  name: string;
  /** Full invocation, e.g. "kelta records list". */
  command: string;
  summary: string;
  dangerous: boolean;
  requiresAuth: boolean;
  positionals: { name: string; description: string; required: boolean }[];
  options: { flag: string; description: string; default?: unknown; repeatable?: boolean }[];
  /** JSON Schema of the validated handler input (positionals + option keys merged). */
  inputSchema: unknown;
}

export interface Manifest {
  manifestVersion: number;
  cliVersion: string;
  outputFormats: string[];
  /** Stable exit-code contract (also on every error payload as `code`). */
  exitCodes: Record<string, number>;
  errorEnvelope: string;
  commands: ManifestCommand[];
}

export function buildManifest(commands: RegisteredCommand[], group?: string): Manifest {
  const selected = group ? commands.filter((command) => command.group === group) : commands;
  return {
    manifestVersion: MANIFEST_VERSION,
    cliVersion: VERSION,
    outputFormats: [...OUTPUT_FORMATS],
    exitCodes: { ...EXIT },
    errorEnvelope:
      '{"error":{"code","status","detail","source","meta"},"errors":[...]} on stderr, one line — "error" is errors[0] flattened, "errors" is the full JSON:API array',
    commands: selected.map((command) => ({
      group: command.group,
      name: command.name,
      command: ['kelta', command.group, command.name].filter(Boolean).join(' '),
      summary: command.summary,
      // a predicate means "sometimes dangerous" — advertise the gate
      dangerous: Boolean(command.dangerous),
      requiresAuth: command.requiresAuth !== false,
      positionals: (command.positionals ?? []).map((positional) => ({ ...positional })),
      options: (command.options ?? []).map((option) => ({ ...option })),
      inputSchema: zodToJsonSchema(command.input as unknown as ZodType, {
        $refStrategy: 'none',
      }),
    })),
  };
}
