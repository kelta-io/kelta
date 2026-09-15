import { describe, it, expect } from 'vitest';
import { flattenBody, flattenResource, extractIds } from './flatten.js';
import { renderCsv, renderTable } from './table.js';
import { pickFormat, renderResult } from './render.js';

const listBody = {
  data: [
    {
      id: '1',
      type: 'invoices',
      attributes: { status: 'open', amount: 12.5 },
      relationships: {
        customer: { data: { id: 'c1' } },
        lines: { data: [{ id: 'l1' }, { id: 'l2' }] },
      },
    },
    { id: '2', type: 'invoices', attributes: { status: 'paid', amount: 99 } },
  ],
  meta: { totalCount: 2 },
};

describe('flatten', () => {
  it('flattens a resource to id + attributes + relationship ids', () => {
    expect(flattenResource(listBody.data[0])).toEqual({
      id: '1',
      status: 'open',
      amount: 12.5,
      customer: 'c1',
      lines: ['l1', 'l2'],
    });
  });

  it('flattens list and single envelopes; passes plain objects through', () => {
    expect(flattenBody(listBody)).toHaveLength(2);
    expect(flattenBody({ data: listBody.data[1] })).toEqual({
      id: '2',
      status: 'paid',
      amount: 99,
    });
    expect(flattenBody({ plain: true })).toEqual({ plain: true });
  });

  it('extracts ids for --quiet', () => {
    expect(extractIds(listBody)).toEqual(['1', '2']);
  });

  it('skips a relationship id already present in attributes (server now echoes lookup/master-detail ids into both)', () => {
    const resource = {
      id: 'layout-1',
      type: 'page-layouts',
      attributes: { name: 'Detail Layout', collectionId: 'coll-42' },
      relationships: {
        collectionId: { data: { id: 'coll-42' } },
      },
    };
    expect(flattenResource(resource)).toEqual({
      id: 'layout-1',
      name: 'Detail Layout',
      collectionId: 'coll-42',
    });
  });
});

describe('table + csv', () => {
  const rows = [
    { id: '1', name: 'alpha', note: 'a "quoted", note' },
    { id: '2', name: 'beta', note: null },
  ];

  it('renders an aligned table with derived columns', () => {
    const out = renderTable(rows as Record<string, unknown>[]);
    expect(out).toContain('id  name');
    expect(out.split('\n')[2]).toMatch(/^1\s+alpha/);
  });

  it('escapes csv cells', () => {
    const out = renderCsv(rows as Record<string, unknown>[]);
    expect(out.split('\n')[0]).toBe('id,name,note');
    expect(out).toContain('"a ""quoted"", note"');
  });
});

describe('pickFormat', () => {
  it('explicit wins; TTY defaults to table; pipe defaults to json', () => {
    expect(pickFormat('yaml', true)).toBe('yaml');
    expect(pickFormat(undefined, true)).toBe('table');
    expect(pickFormat(undefined, false)).toBe('json');
  });
});

describe('renderResult', () => {
  const base = { raw: false, quiet: false } as const;

  it('json mode flattens by default and honors --raw', () => {
    const flat = JSON.parse(
      renderResult({ data: listBody }, { ...base, format: 'json' })
    ) as unknown[];
    expect(flat).toHaveLength(2);
    const raw = JSON.parse(
      renderResult({ data: listBody }, { format: 'json', raw: true, quiet: false })
    ) as { meta: { totalCount: number } };
    expect(raw.meta.totalCount).toBe(2);
  });

  it('ndjson emits one row per line', () => {
    const out = renderResult({ data: listBody }, { ...base, format: 'ndjson' });
    expect(out.trim().split('\n')).toHaveLength(2);
  });

  it('yaml serializes the flattened payload', () => {
    const out = renderResult({ data: listBody }, { ...base, format: 'yaml' });
    expect(out).toContain('status: open');
  });

  it('quiet prints ids only', () => {
    const out = renderResult({ data: listBody }, { format: 'json', raw: false, quiet: true });
    expect(out).toBe('1\n2\n');
  });

  it('table mode prefers human, then columns table, then message', () => {
    expect(renderResult({ data: listBody, human: 'CUSTOM\n' }, { ...base, format: 'table' })).toBe(
      'CUSTOM\n'
    );
    const table = renderResult(
      { data: listBody, columns: [{ key: 'status', header: 'STATUS' }] },
      { ...base, format: 'table' }
    );
    expect(table).toContain('STATUS');
    expect(renderResult({ message: 'done' }, { ...base, format: 'table' })).toBe('done\n');
  });

  it('message-only results are wrapped as JSON in machine modes', () => {
    expect(renderResult({ message: 'done' }, { ...base, format: 'json' })).toBe(
      '{"message":"done"}\n'
    );
  });
});
