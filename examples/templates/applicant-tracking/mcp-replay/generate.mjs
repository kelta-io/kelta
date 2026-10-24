#!/usr/bin/env node
// Writes calls.json: the steps of this template's MCP build (BUILD-LOG.md, "The 101 MCP calls by
// step"), in build order, on the hosted kelta-mcp tools that now do each step in fewest calls
// (apply_collection, apply_listview, validate_dashboard, dashboard_data, export_metadata), with
// every argument spelled out. The build's error calls are not reproduced. The arguments are read
// from the template's metadata.json (its items, LIST_VIEW and DASHBOARD included, and its seed
// `records` sections), so the replay builds exactly what install.sh installs.
//
//   node examples/templates/applicant-tracking/mcp-replay/generate.mjs           rewrite calls.json
//   node examples/templates/applicant-tracking/mcp-replay/generate.mjs --check   exit 1 if stale
//
// Fixture format (read by ci/mcp-replay.sh):
//   calls[].tool         tool name, as tools/list of `kelta mcp serve --toolset all` lists it
//   calls[].arguments    the tools/call arguments
//   calls[].save         {"<var>": "<jq expression>"}: evaluated on the JSON text of a successful
//                        result; the value is kept under <var>
//   "{{var}}" / "{{var:key}}"  an argument string that is exactly this is replaced by the saved
//                        value (or by its member "key"), so ids minted earlier in the run are
//                        never written into the fixture
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const TEMPLATE = dirname(HERE);
const OUT = join(HERE, 'calls.json');

const readJson = (path) => JSON.parse(readFileSync(join(TEMPLATE, path), 'utf8'));
const metadata = readJson('metadata.json');
const items = (type) => metadata.items.filter((item) => item.type === type).map((item) => item.data);

// Parents before children, as the build created them (and as the package's records sections seed them).
const COLLECTION_ORDER = ['job-openings', 'candidates', 'job-applications', 'interviews'];
const seedSections = metadata.records;
// The parent field each reference names (the sections' `references`), keyed by parent collection.
const SEED_KEYS = Object.fromEntries(
  seedSections.flatMap((s) => Object.values(s.references ?? {}).map((ref) => [ref.collection, ref.field]))
);

const camel = (name) => name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
const seedVar = (collection) => `${camel(collection)}Ids`;

const collections = Object.fromEntries(items('COLLECTION').map((c) => [c.name, c]));
const fields = items('FIELD');
const fieldsOf = (collection) => fields.filter((f) => f.collection_name === collection);
const valuesOf = (field) =>
  items('PICKLIST_VALUE')
    .filter((v) => v.picklist_source_type === 'FIELD' && v.picklist_source_id === field.id)
    .sort((a, b) => a.sort_order - b.sort_order);

const calls = [];
const step = (name, call) => calls.push({ step: name, ...call });

// --- Discovery and state check -------------------------------------------------------------
// The build listed tools twice (tools/list is not a tools/call; the replay lists once) and read
// the dashboards through the local cli_dashboards_list, which `--source remote` does not serve:
// query_collection reads the same rows.
const DISCOVERY = 'Discovery and state check';
step(DISCOVERY, { tool: 'list_collections', arguments: { includeSystem: true } });
step(DISCOVERY, { tool: 'list_collections', arguments: { includeSystem: false } });
step(DISCOVERY, { tool: 'list_picklists', arguments: {} });
step(DISCOVERY, { tool: 'list_flows', arguments: {} });
step(DISCOVERY, { tool: 'query_collection', arguments: { collection: 'dashboards', pageSize: 50 } });

// --- Collections ---------------------------------------------------------------------------
// One apply_collection per collection: its fields, each picklist field's own (FIELD-scoped)
// values and the display field, inline. The build took create_collection, a create_record on
// `fields` per picklist field, one per picklist value and an update_collection per display field
// (BUILD-LOG.md errors 1-4 and "Observed").
const fieldArgs = (f) => {
  const args = { fieldName: f.name, type: f.type, displayName: f.display_name, required: f.required };
  if (f.unique_constraint) args.unique = true;
  if (f.default_value !== null) args.defaultValue = f.default_value;
  if (f.description) args.description = f.description;
  if (f.type === 'LOOKUP') {
    args.referenceCollection = f.reference_collection_name;
    if (f.relationship_name) args.relationshipName = f.relationship_name;
  }
  if (f.type === 'PICKLIST') {
    args.values = valuesOf(f).map((v) => {
      const value = { value: v.value, label: v.label };
      if (v.color) value.color = v.color;
      if (v.description) value.description = v.description;
      return value;
    });
  }
  return args;
};
for (const name of COLLECTION_ORDER) {
  const c = collections[name];
  step('Collections', {
    tool: 'apply_collection',
    arguments: {
      name,
      displayName: c.display_name,
      description: c.description,
      fields: fieldsOf(name).map(fieldArgs),
      displayFieldName: c.display_field_name,
    },
  });
}

// --- Page layouts --------------------------------------------------------------------------
const layouts = items('PAGE_LAYOUT');
const sections = items('LAYOUT_SECTION');
const layoutFields = items('LAYOUT_FIELD');
const relatedLists = items('LAYOUT_RELATED_LIST');
const layoutArgs = (layout) => ({
  collectionName: layout.collection_name,
  name: layout.name,
  layoutType: layout.layout_type,
  isDefault: layout.is_default,
  headerConfig: layout.header_config,
  sections: sections
    .filter((s) => s.layout_id === layout.id)
    .sort((a, b) => a.sort_order - b.sort_order)
    .map((s) => ({
      heading: s.heading,
      columns: s.columns,
      collapsed: s.collapsed,
      fields: layoutFields
        .filter((lf) => lf.section_id === s.id)
        .sort((a, b) => a.sort_order - b.sort_order)
        .map((lf) => ({ name: lf.field_name, column: lf.column_number })),
    })),
  relatedLists: relatedLists
    .filter((r) => r.layout_id === layout.id)
    .sort((a, b) => a.sort_order - b.sort_order)
    .map((r) => ({
      collection: r.related_collection_name,
      relationshipField: r.relationship_field_name,
      displayColumns: r.display_columns,
      sortField: r.sort_field,
      sortDirection: r.sort_direction,
      rowLimit: r.row_limit,
    })),
});
// The build's first apply_layout, named 'Job opening', was rejected (BUILD-LOG.md error 5).
for (const collection of COLLECTION_ORDER) {
  const layout = layouts.find((l) => l.collection_name === collection);
  step('Page layouts', { tool: 'apply_layout', arguments: layoutArgs(layout) });
}

// --- Flow ----------------------------------------------------------------------------------
const [flow] = items('FLOW');
step('Flow', {
  tool: 'create_flow',
  arguments: {
    name: flow.name,
    description: flow.description,
    flowType: flow.flow_type,
    triggerConfig: flow.trigger_config,
    definition: flow.definition,
    active: flow.active,
  },
});

// --- List views ----------------------------------------------------------------------------
for (const view of items('LIST_VIEW')) {
  const args = {
    collectionName: view.collection_name,
    name: view.name,
    columns: view.columns,
    filters: view.filters,
    visibility: view.visibility,
    isDefault: view.is_default,
  };
  if (view.sort_field) args.sort = `${view.sort_direction === 'DESC' ? '-' : ''}${view.sort_field}`;
  if (view.row_limit) args.rowLimit = view.row_limit;
  if (view.view_type !== 'TABLE') {
    args.viewType = view.view_type;
    args.typeConfig = view.type_config;
  }
  step('List views', { tool: 'apply_listview', arguments: args });
}

// --- Dashboard -----------------------------------------------------------------------------
for (const dashboard of items('DASHBOARD')) {
  step('Dashboard', { tool: 'apply_dashboard', arguments: dashboard });
}

// --- Seed records --------------------------------------------------------------------------
// One bulk_apply per collection; a reference holds "{{<parent>Ids:<natural key>}}", resolved
// from the parent's bulk_apply result.
for (const { collection, references = {}, rows } of seedSections) {
  const operations = rows.map((row) => ({
    op: 'add',
    type: collection,
    attributes: Object.fromEntries(
      Object.entries(row).map(([key, value]) =>
        references[key] && value !== null
          ? [key, `{{${seedVar(references[key].collection)}:${value}}}`]
          : [key, value]
      )
    ),
  }));
  const call = { tool: 'bulk_apply', arguments: { operations } };
  if (SEED_KEYS[collection]) {
    call.save = {
      [seedVar(collection)]: `[."atomic:results"[].data | {key: (.attributes.${SEED_KEYS[collection]} | tostring), value: .id}] | from_entries`,
    };
  }
  step('Seed records', call);
}

// --- Flow test -----------------------------------------------------------------------------
const appId = (name) => `{{${seedVar('job-applications')}:${name}}}`;
const RAFAEL = 'Rafael Duarte - Account Executive';
const FELIX = 'Felix Marchetti - Backend Engineer';
// The build's verification sandbox moved Rafael to Hired again; in one tenant that is no stage
// change, so the replay's verification moves another Interview-stage application.
const LENA = 'Lena Vasquez-Hart - Product Designer';
step('Flow test', {
  tool: 'update_record',
  arguments: { collection: 'job-applications', id: appId(RAFAEL), attributes: { stage: 'HIRED' } },
});
step('Flow test', {
  tool: 'update_record',
  arguments: { collection: 'job-applications', id: appId(FELIX), attributes: { stage: 'OFFER' } },
});
step('Flow test', {
  tool: 'query_collection',
  arguments: {
    collection: 'job-applications',
    filter: { name: { IN: [RAFAEL, FELIX] } },
    fields: 'name,stage,stageChangedAt,hiredAt',
  },
});

// --- Dashboard check and export ------------------------------------------------------------
const DASHBOARD = 'Hiring overview';
step('Dashboard check', { tool: 'validate_dashboard', arguments: { dashboard: DASHBOARD } });
step('Dashboard check', { tool: 'dashboard_data', arguments: { dashboard: DASHBOARD } });
step('Export', { tool: 'export_metadata', arguments: { collections: COLLECTION_ORDER } });

// --- Second state check and verification ---------------------------------------------------
step('Second state check', { tool: 'list_collections', arguments: { includeSystem: false } });
step('Second state check', { tool: 'list_flows', arguments: {} });
step('Second state check', { tool: 'query_collection', arguments: { collection: 'dashboards', pageSize: 50 } });
step('Second state check', { tool: 'list_picklists', arguments: {} });

for (const collection of COLLECTION_ORDER) {
  step('Verification', { tool: 'query_collection', arguments: { collection, pageSize: 1 } });
}
step('Verification', {
  tool: 'query_collection',
  arguments: { collection: 'fields', filter: { type: { EQ: 'PICKLIST' } }, pageSize: 50 },
});
step('Verification', {
  tool: 'query_collection',
  arguments: { collection: 'picklist-values', filter: { picklistSourceType: { EQ: 'FIELD' } }, pageSize: 100 },
});
step('Verification', { tool: 'get_collection_schema', arguments: { collection: 'job-applications' } });
step('Verification', {
  tool: 'update_record',
  arguments: { collection: 'job-applications', id: appId(LENA), attributes: { stage: 'HIRED' } },
});
step('Verification', { tool: 'get_record', arguments: { collection: 'job-applications', id: appId(LENA) } });
step('Verification', {
  tool: 'query_collection',
  arguments: { collection: 'job-applications', filter: { stage: { EQ: 'HIRED' } }, fields: 'name,stage,hiredAt' },
});
// cli_dashboards_list in the build.
step('Verification', {
  tool: 'query_collection',
  arguments: { collection: 'dashboards', filter: { name: { EQ: DASHBOARD } } },
});
step('Verification', { tool: 'validate_dashboard', arguments: { dashboard: DASHBOARD } });
step('Verification', { tool: 'dashboard_data', arguments: { dashboard: DASHBOARD } });

const fixture = {
  template: 'applicant-tracking',
  description:
    'The MCP tool calls of the applicant-tracking build (BUILD-LOG.md), replayed by ci/mcp-replay.sh. ' +
    'Generated by generate.mjs from the template files: edit those or the generator, never this file.',
  calls,
};
const text = `${JSON.stringify(fixture, null, 2)}\n`;

if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(OUT, 'utf8');
  } catch {
    // missing counts as stale
  }
  if (current !== text) {
    console.error(`${OUT} is stale: run node ${join('examples/templates/applicant-tracking/mcp-replay', 'generate.mjs')}`);
    process.exit(1);
  }
  console.log(`${OUT} is up to date (${calls.length} calls)`);
} else {
  writeFileSync(OUT, text);
  console.log(`wrote ${OUT} (${calls.length} calls)`);
}
