// Static checks on every tenant template under examples/templates/ — run with
// `node --test ci/template-files.test.mjs` (the quickstart job runs it before installing
// the templates into a live stack). Catches a dangling field, collection or seed
// reference before ci/template-apply.sh spends a stack on it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = new URL('../examples/templates/', import.meta.url).pathname;
const SYSTEM_FIELDS = ['id', 'createdAt', 'updatedAt', 'createdBy', 'updatedBy'];

const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'));
const jsonFiles = (dir) =>
  existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => join(dir, f)) : [];

const templates = readdirSync(ROOT).filter((name) => statSync(join(ROOT, name)).isDirectory());

test('there is at least one template', () => {
  assert.ok(templates.length > 0);
});

for (const name of templates) {
  const dir = join(ROOT, name);
  const pkg = readJson(join(dir, 'package.json'));
  const items = (type) => pkg.items.filter((i) => i.type === type).map((i) => i.data);

  const fields = new Map();
  for (const c of items('COLLECTION')) fields.set(c.name, new Set(SYSTEM_FIELDS));
  for (const f of items('FIELD')) {
    assert.ok(fields.has(f.collection_name), `${name}: field ${f.name} on unknown collection ${f.collection_name}`);
    fields.get(f.collection_name).add(f.name);
  }
  const hasField = (collection, field) => fields.get(collection)?.has(field) ?? false;

  test(`${name}: has install.sh, README.md and BUILD-LOG.md`, () => {
    for (const file of ['install.sh', 'README.md', 'BUILD-LOG.md']) {
      assert.ok(existsSync(join(dir, file)), `${file} missing`);
    }
    assert.ok(statSync(join(dir, 'install.sh')).mode & 0o111, 'install.sh is not executable');
  });

  test(`${name}: package items carry no JSON-column wrappers or source instance`, () => {
    assert.equal(pkg.source, undefined);
    const wrapped = JSON.stringify(pkg).match(/"type":"jsonb"/g) ?? [];
    assert.equal(wrapped.length, 0, 'export wrapper {"null","type":"jsonb","value"} left in package.json');
  });

  test(`${name}: lookups and picklist fields resolve inside the package`, () => {
    const picklists = new Set(items('GLOBAL_PICKLIST').map((p) => p.id));
    for (const f of items('FIELD')) {
      if (f.reference_collection_name) {
        assert.ok(fields.has(f.reference_collection_name), `${f.collection_name}.${f.name} → ${f.reference_collection_name}`);
      }
      const picklistId = f.field_type_config?.globalPicklistId;
      if (picklistId) assert.ok(picklists.has(picklistId), `${f.collection_name}.${f.name} picklist ${picklistId}`);
    }
    for (const v of items('PICKLIST_VALUE')) {
      assert.ok(picklists.has(v.picklist_source_id), `picklist value ${v.value} on unknown picklist`);
    }
  });

  test(`${name}: layouts place only fields of their collection`, () => {
    for (const lf of items('LAYOUT_FIELD')) {
      assert.ok(hasField(lf.collection_name, lf.field_name), `${lf.layout_name}: ${lf.collection_name}.${lf.field_name}`);
    }
    for (const rl of items('LAYOUT_RELATED_LIST')) {
      assert.ok(hasField(rl.related_collection_name, rl.relationship_field_name));
      for (const col of rl.display_columns) assert.ok(hasField(rl.related_collection_name, col), `${rl.layout_name}: ${col}`);
    }
  });

  test(`${name}: list views name existing columns, filters and sort fields`, () => {
    const listViewDir = join(dir, 'list-views');
    for (const collection of existsSync(listViewDir) ? readdirSync(listViewDir) : []) {
      assert.ok(fields.has(collection), `list-views/${collection}: not a collection in package.json`);
      for (const file of jsonFiles(join(listViewDir, collection))) {
        const view = readJson(file);
        assert.ok(view.name && view.columns?.length, `${file}: name and columns are required`);
        const used = [...view.columns, ...(view.filters ?? []).map((f) => f.field), view.sortField].filter(Boolean);
        for (const field of used) assert.ok(hasField(collection, field), `${file}: ${field}`);
      }
    }
  });

  test(`${name}: dashboard widgets query existing collections and fields`, () => {
    for (const file of jsonFiles(join(dir, 'dashboards'))) {
      const dashboard = readJson(file);
      for (const c of dashboard.components) {
        assert.ok(c.columnPosition >= 1 && c.rowPosition >= 1, `${file}: ${c.title} grid position is 1-based`);
        assert.ok(c.columnPosition + (c.columnSpan ?? 1) - 1 <= dashboard.columnCount, `${file}: ${c.title} overflows the grid`);
        const { collectionName, aggregateField, groupByField, fields: columns = [], filters = [] } = c.config;
        for (const field of [aggregateField, groupByField, ...columns, ...filters.map((f) => f.field)].filter(Boolean)) {
          assert.ok(hasField(collectionName, field), `${file}: ${c.title} → ${collectionName}.${field}`);
        }
      }
    }
  });

  test(`${name}: flows trigger on existing collections and fields`, () => {
    for (const file of jsonFiles(join(dir, 'flows'))) {
      const { triggerConfig = {} } = readJson(file);
      if (!triggerConfig.collection) continue;
      for (const field of triggerConfig.triggerFields ?? []) {
        assert.ok(hasField(triggerConfig.collection, field), `${file}: ${triggerConfig.collection}.${field}`);
      }
    }
  });

  test(`${name}: seed batches use known attributes and only earlier lids`, () => {
    const lids = new Map();
    for (const file of jsonFiles(join(dir, 'seeds'))) {
      const ops = readJson(file)['atomic:operations'];
      assert.ok(ops.length > 0 && ops.length <= 100, `${file}: 1-100 operations per batch`);
      const batchLids = [];
      for (const { op, data } of ops) {
        assert.equal(op, 'add', `${file}: seeds only add records`);
        for (const [attr, value] of Object.entries(data.attributes)) {
          assert.ok(hasField(data.type, attr), `${file}: ${data.type}.${attr}`);
          if (value && typeof value === 'object' && 'lid' in value) {
            assert.ok(lids.has(value.lid), `${file}: lid ${value.lid} is not created by an earlier batch`);
          }
        }
        if (data.lid) {
          assert.ok(!lids.has(data.lid) && !batchLids.includes(data.lid), `${file}: duplicate lid ${data.lid}`);
          batchLids.push(data.lid);
        }
      }
      for (const lid of batchLids) lids.set(lid, file);
    }
  });

  test(`${name}: seed data stays fictional`, () => {
    for (const file of jsonFiles(join(dir, 'seeds'))) {
      for (const email of readFileSync(file, 'utf8').match(/[\w.+-]+@[\w.-]+/g) ?? []) {
        assert.match(email, /\.example\.(com|org|net)$|@example\.(com|org|net)$/, `${file}: ${email}`);
      }
    }
  });
}
