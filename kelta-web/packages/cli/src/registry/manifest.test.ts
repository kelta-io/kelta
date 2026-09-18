import { describe, it, expect } from 'vitest';
import { z } from 'zod';
import { allCommands } from './registry.js';
import { buildManifest, MANIFEST_VERSION } from './manifest.js';

// The manifest structure is a versioned contract — additions only within a major.
const ManifestSchema = z.object({
  manifestVersion: z.literal(MANIFEST_VERSION),
  cliVersion: z.string().min(1),
  outputFormats: z.array(z.string()).min(1),
  exitCodes: z.record(z.number()),
  errorEnvelope: z.string(),
  commands: z
    .array(
      z.object({
        group: z.string(),
        name: z.string().min(1),
        command: z.string().startsWith('kelta'),
        summary: z.string().min(1),
        dangerous: z.boolean(),
        requiresAuth: z.boolean(),
        positionals: z.array(
          z.object({ name: z.string(), description: z.string(), required: z.boolean() })
        ),
        options: z.array(
          z.object({
            flag: z.string(),
            description: z.string(),
            default: z.unknown().optional(),
            repeatable: z.boolean().optional(),
          })
        ),
        inputSchema: z.unknown(),
      })
    )
    .min(1),
});

interface JsonSchemaObject {
  type?: string;
  properties?: Record<string, unknown>;
}

function flagKey(flag: string): string {
  // '--display-name <label>' → displayName ; '-n, --name <name>' → name ; '--no-browser' → browser
  const long = /--(?:no-)?([a-z0-9-]+)/i.exec(flag);
  if (!long) throw new Error(`no long flag in "${flag}"`);
  return long[1].replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
}

describe('kelta manifest', () => {
  const manifest = buildManifest(allCommands);

  it('validates against the versioned contract schema', () => {
    expect(() => ManifestSchema.parse(manifest)).not.toThrow();
  });

  it('covers every registered command', () => {
    expect(manifest.commands).toHaveLength(allCommands.length);
    expect(manifest.commands.map((c) => c.command)).toContain('kelta records list');
    expect(manifest.commands.map((c) => c.command)).toContain('kelta manifest');
  });

  it('--group filters the catalog', () => {
    const records = buildManifest(allCommands, 'records');
    expect(records.commands.length).toBeGreaterThan(0);
    expect(records.commands.every((c) => c.group === 'records')).toBe(true);
  });

  it('every positional and option key appears in the input JSON Schema (drift guard)', () => {
    for (const command of manifest.commands) {
      const schema = command.inputSchema as JsonSchemaObject;
      const properties = schema.properties ?? {};
      for (const positional of command.positionals) {
        expect(properties, `${command.command} positional ${positional.name}`).toHaveProperty(
          positional.name
        );
      }
      for (const option of command.options) {
        expect(properties, `${command.command} option ${option.flag}`).toHaveProperty(
          flagKey(option.flag)
        );
      }
    }
  });

  it('pins the shape of a representative command (records list)', () => {
    const recordsList = manifest.commands.find((c) => c.command === 'kelta records list');
    expect(recordsList).toBeDefined();
    expect(recordsList?.dangerous).toBe(false);
    expect(recordsList?.requiresAuth).toBe(true);
    const schema = recordsList?.inputSchema as JsonSchemaObject;
    expect(Object.keys(schema.properties ?? {})).toEqual(
      expect.arrayContaining([
        'collection',
        'filter',
        'sort',
        'fields',
        'include',
        'page',
        'size',
        'all',
      ])
    );
  });

  it('marks destructive and auth-free commands correctly', () => {
    const byName = new Map(manifest.commands.map((c) => [c.command, c]));
    expect(byName.get('kelta records delete')?.dangerous).toBe(true);
    expect(byName.get('kelta api')?.dangerous).toBe(true); // predicate → advertised as gated
    expect(byName.get('kelta manifest')?.requiresAuth).toBe(false);
    expect(byName.get('kelta profile list')?.requiresAuth).toBe(false);
  });

  it('documents the pages apply/publish {action, id, changed} output shape (KLT-263)', () => {
    const byName = new Map(manifest.commands.map((c) => [c.command, c]));
    expect(byName.get('kelta pages apply')?.summary).toMatch(
      /\{action, id, path, changed, published\}/
    );
    expect(byName.get('kelta pages apply')?.summary).toMatch(/--raw/);
    expect(byName.get('kelta pages publish')?.summary).toMatch(
      /\{action: "published"\|"unchanged", id, path\}/
    );
  });
});
