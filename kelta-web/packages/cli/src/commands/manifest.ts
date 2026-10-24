import { z } from 'zod';
import { buildManifest } from '../registry/manifest.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const manifest = defineCommand({
  group: '',
  name: 'manifest',
  summary: 'Print the machine-readable command catalog (JSON Schema per command)',
  requiresAuth: false,
  options: [{ flag: '--group <group>', description: 'Only commands of one group' }],
  examples: [
    { cmd: 'kelta manifest', description: 'Every command with its JSON Schema' },
    { cmd: 'kelta manifest --group records', description: 'Only the records commands' },
  ],
  input: z.object({ group: z.string().optional() }),
  handler: async (_ctx, input) => {
    // registry.ts imports this module — a static import back would be a cycle
    // whose init order depends on the entry point; resolve it at call time
    const { allCommands } = await import('../registry/registry.js');
    return { data: buildManifest(allCommands, input.group) };
  },
});

export const manifestCommands: RegisteredCommand[] = [manifest];
