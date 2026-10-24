import { describe, it, expect } from 'vitest';
import type { RegisteredCommand } from '../registry/types.js';
import { AUTHORING_DOCS } from '../docsgen/authoringDocs.js';
import { docsCommands } from './docs.js';

const def = docsCommands[0] as RegisteredCommand;

async function run(topic: string) {
  return def.handler(undefined as never, def.input.parse({ topic }) as never);
}

describe('kelta docs', () => {
  it('list-views covers the filter grammar and the rowLimit set', async () => {
    const result = await run('list-views');
    expect(result.text).toContain('in');
    expect(result.text).toContain('isnull');
    expect(result.text).toContain('{10, 25, 50, 100}');
  });

  it('page-layouts states columns are 0-based', async () => {
    const result = await run('page-layouts');
    expect(result.text).toContain('0-based');
  });

  it('jsonapi documents system-maintained fields: who writes them and the 400', async () => {
    const result = await run('jsonapi');
    const text = result.text ?? '';
    const section = text.slice(text.indexOf('## System-maintained fields'));
    expect(section).toContain('`systemMaintained: true`');
    expect(section).toContain('Flows and server-side code');
    expect(section).toContain('**400**');
    expect(section).toContain('is system-maintained\n  and cannot be written through the API');
    expect(section).toContain('re-sends the value already stored');
    expect(section).toContain('--system-maintained');
  });

  it('an unknown topic is rejected with a usage error listing the valid topics', async () => {
    await expect(run('nope')).rejects.toMatchObject({ code: 'INVALID_ARGUMENTS', exitCode: 2 });
    await expect(run('nope')).rejects.toMatchObject({
      message: expect.stringContaining('list-views'),
    });
  });

  it('`kelta docs packages` prints the metadata-packages topic', async () => {
    const result = await run('packages');
    expect(result.text).toBe(AUTHORING_DOCS.packages);
    expect(result.text).toContain('# Authoring Metadata Packages');
  });

  it('serves every documented topic', async () => {
    for (const topic of [
      'agent',
      'jsonapi',
      'page-layouts',
      'list-views',
      'dashboards',
      'ui-pages',
      'ui-menus',
      'packages',
    ]) {
      const result = await run(topic);
      expect(result.text?.length ?? 0).toBeGreaterThan(0);
    }
  });
});
