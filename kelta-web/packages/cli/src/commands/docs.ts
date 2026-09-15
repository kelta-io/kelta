import { z } from 'zod';
import { AGENT_GUIDE } from '../docsgen/agentGuide.js';
import { AUTHORING_DOCS } from '../docsgen/authoringDocs.js';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

/** Every doc topic `kelta docs <topic>` can print. */
export const DOCS: Record<string, string> = {
  agent: AGENT_GUIDE,
  ...AUTHORING_DOCS,
};

const docs = defineCommand({
  group: '',
  name: 'docs',
  summary: 'Print a reference doc: the agent guide, or a metadata-authoring topic',
  requiresAuth: false,
  positionals: [
    {
      name: 'topic',
      description: `One of: ${Object.keys(DOCS).join(', ')}`,
      required: true,
    },
  ],
  input: z.object({ topic: z.string() }),
  handler: (_ctx, input) => {
    const text = DOCS[input.topic];
    if (text === undefined) {
      throw new CliError(
        `Unknown doc topic "${input.topic}" (expected one of: ${Object.keys(DOCS).join(', ')})`,
        { code: 'INVALID_ARGUMENTS', exitCode: EXIT.USAGE }
      );
    }
    return Promise.resolve({ text });
  },
});

export const docsCommands: RegisteredCommand[] = [docs];
