import { z } from 'zod';
import type { RegisteredCommand } from './types.js';

/**
 * JSON Schema (draft-07) of what a caller may send for a command — the shared
 * `inputSchema` of the manifest and the local MCP tools. `io: 'input'` keeps
 * defaulted keys optional; nested schemas are inlined (no `$ref`s). Plain
 * objects are closed (`additionalProperties: false`), as zod-to-json-schema
 * emitted them before the zod 4 migration.
 */
export function commandInputJsonSchema(command: RegisteredCommand): unknown {
  return z.toJSONSchema(command.input as unknown as z.ZodType, {
    target: 'draft-7',
    io: 'input',
    unrepresentable: 'any',
    override: ({ zodSchema, jsonSchema }) => {
      if (zodSchema._zod.def.type === 'object' && jsonSchema.additionalProperties === undefined) {
        jsonSchema.additionalProperties = false;
      }
    },
  });
}
