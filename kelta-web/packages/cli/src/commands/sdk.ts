import { writeFileSync } from 'node:fs';
import type { AxiosInstance } from 'axios';
import { generateTypesFromSpec } from '@kelta/sdk/cli';
import { z } from 'zod';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

export interface GenerateTypesResult {
  file: string;
  typesGenerated: number;
}

/**
 * Generates TypeScript types for this tenant by fetching its live OpenAPI
 * document (`GET /api/docs/openapi.json`) through the authenticated CLI client
 * and delegating to the `@kelta/sdk` OpenAPI type generator. Returns the file
 * written and the number of types generated.
 *
 * <p>This is the CLI-native, authenticated entry point to the same generator the
 * `kelta-generate-types` bin exposes — no OpenAPI URL or token needs to be passed
 * once `kelta auth login` has run.
 */
export async function runGenerateTypes(
  client: AxiosInstance,
  opts: { output?: string }
): Promise<GenerateTypesResult> {
  const res = await client.get('/api/docs/openapi.json');
  if (res.status !== 200) {
    throw new Error(`Failed to fetch OpenAPI spec (status ${String(res.status)})`);
  }
  const file = opts.output ?? 'kelta-types.ts';
  const { content, result } = generateTypesFromSpec(res.data as unknown, file, {
    includeRequests: true,
    includeResponses: true,
  });
  if (!result.success) {
    throw new Error(`Type generation failed: ${(result.errors ?? ['unknown error']).join('; ')}`);
  }
  writeFileSync(file, content);
  return { file, typesGenerated: result.typesGenerated };
}

const types = defineCommand({
  group: 'sdk',
  name: 'types',
  summary: "Generate TypeScript types from this tenant's live OpenAPI document",
  options: [{ flag: '-o, --out <file>', description: 'Output file (default: kelta-types.ts)' }],
  examples: [{ cmd: 'kelta sdk types --out src/kelta-types.ts' }],
  input: z.object({ out: z.string().optional() }),
  handler: async (ctx, input) => {
    const { file, typesGenerated } = await runGenerateTypes(ctx.client.getAxiosInstance(), {
      output: input.out,
    });
    return {
      data: { file, typesGenerated },
      message: `Wrote ${String(typesGenerated)} types to ${file}`,
    };
  },
});

export const sdkCommands: RegisteredCommand[] = [types];
