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

/** A doc's first `# ` heading — its one-line summary in `kelta docs list`. */
export function docSummary(text: string): string {
  return /^# (.+)$/m.exec(text)?.[1].trim() ?? '';
}

function listTopics(): string {
  return Object.keys(DOCS)
    .sort()
    .map((topic) => `${topic} — ${docSummary(DOCS[topic])}`)
    .join('\n');
}

const docs = defineCommand({
  group: '',
  name: 'docs',
  summary: 'Print a reference doc (the agent guide or an authoring topic), or list every topic',
  requiresAuth: false,
  positionals: [
    {
      name: 'topic',
      description: `\`list\` (the default) prints every topic; otherwise one of: ${Object.keys(DOCS).join(', ')}`,
      required: false,
    },
  ],
  examples: [
    { cmd: 'kelta docs list', description: 'List every doc topic' },
    { cmd: 'kelta docs', description: 'Same as `kelta docs list`' },
    { cmd: 'kelta docs agent', description: 'Print the agent guide' },
  ],
  input: z.object({ topic: z.string().optional() }),
  handler: (_ctx, input) => {
    if (input.topic === undefined || input.topic === 'list') {
      return Promise.resolve({ text: listTopics() });
    }
    const text = DOCS[input.topic];
    if (text === undefined) {
      throw new CliError(
        `Unknown doc topic "${input.topic}" (expected one of: ${Object.keys(DOCS).join(', ')})`,
        {
          code: 'INVALID_ARGUMENTS',
          exitCode: EXIT.USAGE,
          hint: 'Run `kelta docs list` to see every topic.',
        }
      );
    }
    return Promise.resolve({ text });
  },
});

export const docsCommands: RegisteredCommand[] = [docs];
