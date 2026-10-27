// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { RegisteredCommand } from '../registry/types.js';
import { AUTHORING_DOCS } from '../docsgen/authoringDocs.js';
import { AGENT_GUIDE } from '../docsgen/agentGuide.js';
import { CliError, EXIT } from '../errors.js';
import { docsCommands } from './docs.js';

const def = docsCommands[0] as RegisteredCommand;

async function run(topic?: string) {
  return def.handler(
    undefined as never,
    def.input.parse(topic === undefined ? {} : { topic }) as never
  );
}

const authoringDir = fileURLToPath(new URL('../../../../../docs/authoring/', import.meta.url));

function authoringDoc(topic: string): string {
  return readFileSync(`${authoringDir}${topic}.md`, 'utf-8');
}

function firstHeading(text: string): string {
  const line = text.split('\n').find((l) => l.startsWith('# ')) ?? '';
  return line.slice(2).trim();
}

/** `agent` plus every docs/authoring/*.md, each mapped to its H1. */
function expectedListing(): Record<string, string> {
  const expected: Record<string, string> = { agent: firstHeading(AGENT_GUIDE) };
  for (const name of readdirSync(authoringDir).filter((n) => n.endsWith('.md'))) {
    const topic = name.slice(0, -'.md'.length);
    expected[topic] = firstHeading(authoringDoc(topic));
  }
  return expected;
}

function parseListing(text: string): Record<string, string> {
  return Object.fromEntries(
    text.split('\n').map((line) => {
      const separator = line.indexOf(' — ');
      return [line.slice(0, separator), line.slice(separator + ' — '.length)];
    })
  );
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

  it('`kelta docs` with no topic lists every topic with its H1 as the summary', async () => {
    const result = await run();
    const listing = parseListing(result.text ?? '');
    expect(listing).toEqual(expectedListing());
    expect(listing['api-access']).toBe('PAT-Based Read-Only API Access');
    expect(Object.keys(listing)).toEqual(Object.keys(listing).sort());
  });

  it('`kelta docs list` prints the same listing', async () => {
    const result = await run('list');
    expect(parseListing(result.text ?? '')).toEqual(expectedListing());
    expect(result.text).toBe((await run()).text);
  });

  it('`kelta docs api-access` prints docs/authoring/api-access.md', async () => {
    const result = await run('api-access');
    expect(result.text).toBe(authoringDoc('api-access'));
  });

  it('`kelta docs nosuch` is a usage error pointing at `kelta docs list`', async () => {
    const error: unknown = await run('nosuch').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CliError);
    expect(error).toMatchObject({ code: 'INVALID_ARGUMENTS', exitCode: EXIT.USAGE });
    expect(EXIT.USAGE).toBe(2);
    expect((error as CliError).hint).toContain('kelta docs list');
  });

  it('`kelta docs agent` prints the agent guide', async () => {
    const result = await run('agent');
    expect(result.text).toBe(AGENT_GUIDE);
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
