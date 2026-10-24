// Invariants of the generated MCP replay fixture. Run: node --test examples/templates/applicant-tracking/mcp-replay/generate.test.ts
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
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
  for (const file of readdirSync(join(TEMPLATE, 'list-views')).filter((f) => f.endsWith('.json'))) {
    const collectionName = file.replace(/\.json$/, '');
    for (const view of readJson(`list-views/${file}`)) {
      const call = applied.find((c) => c.arguments.collectionName === collectionName && c.arguments.name === view.name);
      assert.ok(call, `no apply_listview for ${collectionName} / ${view.name}`);
      if (view.viewType === 'KANBAN') {
        assert.equal(call.arguments.viewType, 'KANBAN');
        assert.deepEqual(call.arguments.typeConfig, {
          kanban: { laneField: view.laneField, cardFields: view.cardFields },
        });
      }
    }
  }
});

test('one bulk_apply per seed file writes all its records', () => {
  const seeds = readdirSync(join(TEMPLATE, 'seeds')).filter((f) => f.endsWith('.json'));
  const seedCalls = calls.filter((c) => c.step === 'Seed records');
  assert.equal(seedCalls.length, seeds.length);
  for (const file of seeds) {
    const collection = file.replace(/\.json$/, '');
    const call = seedCalls.find((c) => c.arguments.operations[0].type === collection);
    assert.ok(call, `no seed call for ${collection}`);
    assert.equal(call.tool, 'bulk_apply');
    assert.equal(call.arguments.operations.length, readJson(`seeds/${file}`).length);
  }
});

test('dashboard check and export use the hosted tools', () => {
  assert.deepEqual(
    calls.filter((c) => c.step === 'Dashboard check' || c.step === 'Export').map((c) => c.tool),
    ['validate_dashboard', 'dashboard_data', 'export_metadata']
  );
});
