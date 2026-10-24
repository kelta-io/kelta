// Invariants of the generated MCP replay fixture. Run: node --test examples/templates/applicant-tracking/mcp-replay/generate.test.ts
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

type Call = { step: string; tool: string; arguments: Record<string, any>; save?: Record<string, string> };

const HERE = dirname(fileURLToPath(import.meta.url));
const TEMPLATE = dirname(HERE);
const readJson = (path: string) => JSON.parse(readFileSync(join(TEMPLATE, path), 'utf8'));
const calls: Call[] = readJson('mcp-replay/calls.json').calls;
const metadata = readJson('metadata.json');
const items = (type: string) => metadata.items.filter((i: any) => i.type === type).map((i: any) => i.data);
const callsOf = (tool: string) => calls.filter((c) => c.tool === tool);

test('calls.json is what generate.mjs writes', () => {
  const out = execFileSync(process.execPath, [join(HERE, 'generate.mjs'), '--check'], { encoding: 'utf8' });
  assert.match(out, new RegExp(`is up to date \\(${calls.length} calls\\)`));
});

test('baseline.txt is the call count, at most 55', () => {
  const baseline = Number(readFileSync(join(HERE, 'baseline.txt'), 'utf8').trim());
  assert.equal(baseline, calls.length);
  assert.ok(baseline <= 55, `baseline ${baseline} > 55`);
});

test('no expected-error call and no local cli_ tool', () => {
  assert.deepEqual(calls.filter((c) => 'expectError' in c), []);
  assert.deepEqual(calls.filter((c) => c.tool.startsWith('cli_')).map((c) => c.tool), []);
  assert.doesNotMatch(readFileSync(join(HERE, 'generate.mjs'), 'utf8'), /expectError:/);
});

test('each collection is one apply_collection with its fields, picklist values and display field', () => {
  for (const tool of ['create_collection', 'add_field', 'add_picklist_value', 'update_collection', 'create_record']) {
    assert.deepEqual(callsOf(tool), [], `${tool} is still called`);
  }
  const applied = callsOf('apply_collection');
  const collections = items('COLLECTION');
  assert.deepEqual(applied.map((c) => c.arguments.name).sort(), collections.map((c: any) => c.name).sort());
  const values = items('PICKLIST_VALUE');
  for (const collection of collections) {
    const args = applied.find((c) => c.arguments.name === collection.name)!.arguments;
    assert.equal(args.displayFieldName, collection.display_field_name);
    const fields = items('FIELD').filter((f: any) => f.collection_name === collection.name);
    assert.deepEqual(args.fields.map((f: any) => f.fieldName).sort(), fields.map((f: any) => f.name).sort());
    for (const field of fields.filter((f: any) => f.type === 'PICKLIST')) {
      const expected = values
        .filter((v: any) => v.picklist_source_type === 'FIELD' && v.picklist_source_id === field.id)
        .sort((a: any, b: any) => a.sort_order - b.sort_order)
        .map((v: any) => v.value);
      const inline = args.fields.find((f: any) => f.fieldName === field.name).values.map((v: any) => v.value);
      assert.deepEqual(inline, expected, `${collection.name}.${field.name} values`);
    }
  }
});

test('every list view goes through apply_listview, kanban boards with viewType and typeConfig', () => {
  assert.deepEqual(callsOf('create_listview'), []);
  const applied = callsOf('apply_listview');
  const views = items('LIST_VIEW');
  assert.equal(applied.length, views.length);
  assert.ok(views.some((v: any) => v.view_type === 'KANBAN'), 'the package carries no kanban view');
  for (const view of views) {
    const call = applied.find(
      (c) => c.arguments.collectionName === view.collection_name && c.arguments.name === view.name
    );
    assert.ok(call, `no apply_listview for ${view.collection_name} / ${view.name}`);
    assert.deepEqual(call.arguments.columns, view.columns);
    assert.deepEqual(call.arguments.filters, view.filters);
    if (view.view_type === 'KANBAN') {
      assert.equal(call.arguments.viewType, 'KANBAN');
      assert.deepEqual(call.arguments.typeConfig, view.type_config);
    }
  }
});

test('every dashboard item is one apply_dashboard of its data', () => {
  assert.deepEqual(
    callsOf('apply_dashboard').map((c) => c.arguments),
    items('DASHBOARD')
  );
});

test('one bulk_apply per records section writes all its rows, in section order', () => {
  const sections = metadata.records;
  const seedCalls = calls.filter((c) => c.step === 'Seed records');
  assert.deepEqual(
    seedCalls.map((c) => c.arguments.operations[0].type),
    sections.map((s: any) => s.collection)
  );
  for (const section of sections) {
    const call = seedCalls.find((c) => c.arguments.operations[0].type === section.collection)!;
    assert.equal(call.tool, 'bulk_apply');
    assert.equal(call.arguments.operations.length, section.rows.length);
    for (const [field, ref] of Object.entries<any>(section.references ?? {})) {
      const parent = seedCalls.find((c) => c.arguments.operations[0].type === ref.collection)!;
      assert.ok(parent.save, `${ref.collection} saves no ids for ${section.collection}.${field}`);
      assert.match(Object.values(parent.save)[0], new RegExp(`\\.attributes\\.${ref.field} `));
    }
  }
});

test('dashboard check and export use the hosted tools', () => {
  assert.deepEqual(
    calls.filter((c) => c.step === 'Dashboard check' || c.step === 'Export').map((c) => c.tool),
    ['validate_dashboard', 'dashboard_data', 'export_metadata']
  );
});
