import type { ZodType } from 'zod';
import type { KeltaClient } from '@kelta/sdk';
import type { ResolvedProfile } from '../config/resolve.js';
import type { HandlerResult, OutputFormat } from '../render/render.js';

export interface GlobalOptions {
  profile?: string;
  output?: OutputFormat;
  raw: boolean;
  quiet: boolean;
  yes: boolean;
}

export interface CommandContext {
  /** Resolved profile (flags > env > file); token present only when authenticated. */
  profile: ResolvedProfile;
  /** SDK client bound to the profile. Throws AUTH_REQUIRED if the profile is incomplete. */
  readonly client: KeltaClient;
  global: GlobalOptions;
  /** Diagnostics — always stderr, never stdout. */
  log: (message: string) => void;
}

export interface PositionalSpec {
  /** camelCase input key; also the display name in help. */
  name: string;
  description: string;
  required: boolean;
}

export interface OptionSpec {
  /** Commander flag syntax, e.g. `--data <json>`, `-n, --name <name>`, `--wait`. */
  flag: string;
  description: string;
  default?: unknown;
  /** Repeatable flag collected into a string[]. */
  repeatable?: boolean;
}

/** One runnable invocation, shown in `--help`, COMMANDS.md and the manifest. */
export interface CommandExample {
  /** Full invocation starting with `kelta`; parsed by registry/examples.test.ts. */
  cmd: string;
  description?: string;
}

/**
 * One CLI command as data — the single source of truth the parser, the
 * manifest, and (later) local MCP tools are derived from. Handlers return
 * structured results; rendering is the dispatcher's job.
 */
export interface CommandDef<I = unknown> {
  /** Command group, e.g. `records`. Empty string = top-level command. */
  group: string;
  name: string;
  summary: string;
  positionals?: PositionalSpec[];
  options?: OptionSpec[];
  /** At least one realistic invocation; each must parse (examples.test.ts). */
  examples: CommandExample[];
  /** Validates the merged positionals+options object; failures exit 2. */
  input: ZodType<I, unknown>;
  /**
   * Destructive: confirm on a TTY, require --yes otherwise. A predicate makes
   * the gate input-dependent (e.g. `metadata apply --dry-run` is safe).
   */
  dangerous?: boolean | ((input: I) => boolean);
  /** Default true; false for local-only commands (profile, login). */
  requiresAuth?: boolean;
  /**
   * This command writes its own protocol bytes to stdout (the stdio MCP
   * server). The dispatcher must NOT render a result or print any hint —
   * a single stray line corrupts the JSON-RPC stream and the client rejects
   * the connection.
   */
  ownsStdout?: boolean;
  // method syntax (not a property) — bivariant, so CommandDef<Specific>
  // stays assignable to the erased RegisteredCommand below
  handler(ctx: CommandContext, input: I): Promise<HandlerResult>;
}

/**
 * The type-erased view of a command the registry, binder, manifest, and MCP
 * layers work with. Structural on purpose: every `CommandDef<I>` is assignable
 * with no casts (`parse` is duck-typed, the handler is a bivariant method, the
 * dangerous predicate is contravariant via `never`).
 */
export interface RegisteredCommand {
  group: string;
  name: string;
  summary: string;
  positionals?: PositionalSpec[];
  options?: OptionSpec[];
  examples: CommandExample[];
  input: { parse(raw: unknown): unknown };
  dangerous?: boolean | ((input: never) => boolean);
  requiresAuth?: boolean;
  ownsStdout?: boolean;
  handler(ctx: CommandContext, input: never): Promise<HandlerResult>;
}

/** Type-inference helper for defining commands. */
export function defineCommand<I>(def: CommandDef<I>): CommandDef<I> {
  return def;
}
