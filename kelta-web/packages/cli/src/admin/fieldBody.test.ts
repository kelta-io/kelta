import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { AxiosInstance } from 'axios';
import { buildFieldBody, resolveFieldType } from './fieldBody.js';
import { collectionIdByName, fieldIdsByName, picklistIdByName } from './lookups.js';
import { CliError, EXIT } from '../errors.js';

const COLLECTION_ID = '11111111-2222-3333-4444-555555555555';
const TARGET_ID = '99999999-8888-7777-6666-555555555555';
const PICKLIST_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

function fakeAxios(routes: Record<string, unknown>): AxiosInstance {
  const get = vi.fn((url: string) => {
    for (const [prefix, body] of Object.entries(routes)) {
      if (url.startsWith(prefix)) return Promise.resolve({ data: body });
    }
    return Promise.resolve({ data: {} });
  });
  return { get } as unknown as AxiosInstance;
}

describe('resolveFieldType', () => {
  it.each([
    ['text', 'STRING'],
    ['String', 'STRING'],
    ['number', 'INTEGER'],
    ['decimal', 'DOUBLE'],
    ['bool', 'BOOLEAN'],
    ['dateTime', 'DATETIME'],
    ['reference', 'LOOKUP'],
    ['master_detail', 'MASTER_DETAIL'],
    ['multiPicklist', 'MULTI_PICKLIST'],
    ['auto_number', 'AUTO_NUMBER'],
    ['rich_text', 'RICH_TEXT'],
  ])('maps alias %s to %s (MCP parity)', (alias, native) => {
    expect(resolveFieldType(alias)).toBe(native);
  });

  it('passes every runtime-core FieldType constant through (REFERENCE → LOOKUP)', () => {
    const javaFile = resolve(
      dirname(fileURLToPath(import.meta.url)),
      '../../../../../kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/model/FieldType.java'
    );
    const source = readFileSync(javaFile, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    const constants = (source.match(/enum FieldType\s*\{([^;]*);/)?.[1] ?? '')
      .replace(/@\w+/g, '')
      .split(',')
      .map((c) => c.trim())
      .filter((c) => c !== '');
    expect(constants).toContain('TEXT');
    expect(constants.length).toBeGreaterThanOrEqual(28);
    for (const name of constants) {
      expect(resolveFieldType(name)).toBe(name === 'REFERENCE' ? 'LOOKUP' : name);
    }
  });

  it('native TEXT wins over the text alias; textarea is a STRING alias', () => {
    expect(resolveFieldType('TEXT')).toBe('TEXT');
    expect(resolveFieldType('text')).toBe('STRING');
    expect(resolveFieldType('textarea')).toBe('STRING');
  });

  it('lists the aliases and native names when the type is unknown', () => {
    expect(() => resolveFieldType('nope')).toThrow(/TEXT/);
    expect(() => resolveFieldType('nope')).toThrow(/textarea/);
  });

  it('passes native enum names through and rejects unknowns', () => {
    expect(resolveFieldType('GEOLOCATION')).toBe('GEOLOCATION');
    expect(() => resolveFieldType('nope')).toThrow(CliError);
  });
});

describe('buildFieldBody', () => {
  let axios: AxiosInstance;

  beforeEach(() => {
    axios = fakeAxios({
      '/api/collections/invoices': { data: { id: COLLECTION_ID } },
      '/api/collections/customers': { data: { id: TARGET_ID } },
      '/api/global-picklists?filter[name][EQ]=statuses': { data: [{ id: PICKLIST_ID }] },
    });
  });

  it('builds the base body with the MCP attribute renames', async () => {
    const body = await buildFieldBody(axios, {
      collection: 'invoices',
      name: 'total',
      type: 'decimal',
      required: true,
      unique: true,
      indexed: false,
      description: 'd',
      defaultValue: '0',
    });
    expect(body).toEqual({
      data: {
        type: 'fields',
        attributes: {
          collectionId: COLLECTION_ID,
          name: 'total',
          type: 'DOUBLE',
          required: true,
          uniqueConstraint: true, // renamed from unique
          indexed: false,
          description: 'd',
          defaultValue: '0',
        },
      },
    });
  });

  it('picklist fields resolve the source and use the globalPicklistId key', async () => {
    const body = await buildFieldBody(axios, {
      collection: 'invoices',
      name: 'status',
      type: 'picklist',
      picklist: 'statuses',
    });
    expect(body.data.attributes.fieldTypeConfig).toEqual({
      picklistSourceType: 'GLOBAL',
      globalPicklistId: PICKLIST_ID,
    });
    await expect(
      buildFieldBody(axios, { collection: 'invoices', name: 'x', type: 'picklist' })
    ).rejects.toThrow(/--picklist.*--values/);
  });

  it('picklist fields with --values carry no fieldTypeConfig', async () => {
    const body = await buildFieldBody(axios, {
      collection: 'invoices',
      name: 'stage',
      type: 'picklist',
      values: ['a', 'b'],
    });
    expect(body.data.attributes).not.toHaveProperty('fieldTypeConfig');
  });

  it('rejects --values with --picklist or on a non-picklist type before any request', async () => {
    await expect(
      buildFieldBody(axios, {
        collection: 'invoices',
        name: 'x',
        type: 'picklist',
        picklist: 'statuses',
        values: ['a'],
      })
    ).rejects.toMatchObject({ exitCode: EXIT.USAGE });
    await expect(
      buildFieldBody(axios, { collection: 'invoices', name: 'x', type: 'text', values: ['a'] })
    ).rejects.toMatchObject({ exitCode: EXIT.USAGE });
    expect((axios.get as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(0);
  });

  it('reference fields send the name attribute AND the id relationship', async () => {
    const body = await buildFieldBody(axios, {
      collection: 'invoices',
      name: 'customer',
      type: 'reference',
      reference: 'customers',
      relationshipName: 'invoices',
    });
    expect(body.data.attributes.referenceTarget).toBe('customers');
    expect(body.data.attributes.relationshipName).toBe('invoices');
    expect(body.data.relationships).toEqual({
      referenceCollectionId: { data: { type: 'collections', id: TARGET_ID } },
    });
  });

  it('vector fields require a dimension in range', async () => {
    const body = await buildFieldBody(axios, {
      collection: 'invoices',
      name: 'embedding',
      type: 'vector',
      dimension: 1536,
    });
    expect(body.data.attributes.fieldTypeConfig).toEqual({ dimension: 1536 });
    await expect(
      buildFieldBody(axios, { collection: 'invoices', name: 'e', type: 'vector' })
    ).rejects.toThrow(/--dimension/);
    await expect(
      buildFieldBody(axios, { collection: 'invoices', name: 'e', type: 'vector', dimension: 99999 })
    ).rejects.toThrow(/--dimension/);
  });

  it('extra attributes merge last and win', async () => {
    const body = await buildFieldBody(axios, {
      collection: 'invoices',
      name: 'n',
      type: 'text',
      description: 'flag',
      extra: { description: 'from-data', custom: 1 },
    });
    expect(body.data.attributes.description).toBe('from-data');
    expect(body.data.attributes.custom).toBe(1);
  });
});

describe('lookups', () => {
  it('passes UUIDs through without a request', async () => {
    const axios = fakeAxios({});
    expect(await collectionIdByName(axios, COLLECTION_ID)).toBe(COLLECTION_ID);
    expect(await picklistIdByName(axios, PICKLIST_ID)).toBe(PICKLIST_ID);
    expect((axios.get as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(0);
  });

  it('resolves names and fails with NOT_FOUND when absent', async () => {
    const axios = fakeAxios({ '/api/collections/known': { data: { id: COLLECTION_ID } } });
    expect(await collectionIdByName(axios, 'known')).toBe(COLLECTION_ID);
    await expect(collectionIdByName(axios, 'ghost')).rejects.toMatchObject({
      code: 'NOT_FOUND',
      exitCode: 4,
    });
  });

  it('maps field names to ids from the collectionId-filtered listing', async () => {
    const axios = fakeAxios({
      [`/api/fields?filter[collectionId][EQ]=${COLLECTION_ID}`]: {
        data: [
          { id: 'f1', attributes: { name: 'status' } },
          { id: 'f2', attributes: { name: 'amount' } },
        ],
      },
    });
    const map = await fieldIdsByName(axios, COLLECTION_ID);
    expect(map.get('status')).toBe('f1');
    expect(map.get('amount')).toBe('f2');
  });
});
