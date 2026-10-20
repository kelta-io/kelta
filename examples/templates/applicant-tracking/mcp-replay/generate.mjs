#!/usr/bin/env node
// Writes calls.json: the MCP tool calls of this template's build (BUILD-LOG.md, "The 101 MCP
// calls by step"), in build order, with every argument spelled out. The arguments are read from
// the template's own files (metadata.json, list-views/, dashboards/, seeds/), so the replay
// builds exactly what install.sh installs.
//
//   node examples/templates/applicant-tracking/mcp-replay/generate.mjs           rewrite calls.json
//   node examples/templates/applicant-tracking/mcp-replay/generate.mjs --check   exit 1 if stale
//
// Fixture format (read by ci/mcp-replay.sh):
//   calls[].tool         tool name, as tools/list of `kelta mcp serve --toolset all` lists it
//   calls[].arguments    the tools/call arguments
//   calls[].save         {"<var>": "<jq expression>"}: evaluated on the JSON text of a successful
//                        result; the value is kept under <var>
//   calls[].expectError  why this call returned an error in the build; the replay tolerates an
//                        error here (and warns if the call succeeds)
//   "{{var}}" / "{{var:key}}"  an argument string that is exactly this is replaced by the saved
//                        value (or by its member "key"), so ids minted earlier in the run are
//                        never written into the fixture
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const TEMPLATE = dirname(HERE);
const OUT = join(HERE, 'calls.json');

const readJson = (path) => JSON.parse(readFileSync(join(TEMPLATE, path), 'utf8'));
const metadata = readJson('metadata.json');
const items = (type) => metadata.items.filter((item) => item.type === type).map((item) => item.data);

// Parents before children, as the build created them (and as install.sh seeds them).
const COLLECTION_ORDER = ['job-openings', 'candidates', 'job-applications', 'interviews'];
// The natural key each seed file's references use (install.sh REFS).
const SEED_KEYS = {
  'job-openings': 'title',
  candidates: 'email',
  'job-applications': 'name',
};
const SEED_REFS = {
  'job-applications': { candidate: 'candidates', job: 'job-openings' },
  interviews: { application: 'job-applications' },
};

const camel = (name) => name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
const collectionVar = (name) => `${camel(name)}CollectionId`;
const fieldVar = (collection, field) => `${camel(collection)}.${field}.fieldId`;
const seedVar = (collection) => `${camel(collection)}Ids`;

const collections = Object.fromEntries(items('COLLECTION').map((c) => [c.name, c]));
const fields = items('FIELD');
const fieldsOf = (collection) => fields.filter((f) => f.collection_name === collection);
const picklistFields = COLLECTION_ORDER.flatMap((c) =>
  fieldsOf(c).filter((f) => f.type === 'PICKLIST')
);
const fieldById = Object.fromEntries(fields.map((f) => [f.id, f]));
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

// --- Collections and display fields --------------------------------------------------------
// Picklist fields are not created inline: the build's first create_collection could not create
// them (BUILD-LOG.md error 1), so all seven went through create_record on `fields` below.
const fieldArgs = (f) => {
  const args = { fieldName: f.name, type: f.type, displayName: f.display_name, required: f.required };
  if (f.unique_constraint) args.unique = true;
  if (f.default_value !== null) args.defaultValue = f.default_value;
  if (f.description) args.description = f.description;
  if (f.type === 'LOOKUP') {
    args.referenceCollection = f.reference_collection_name;
    if (f.relationship_name) args.relationshipName = f.relationship_name;
  }
  return args;
};
for (const name of COLLECTION_ORDER) {
  const c = collections[name];
  step('Collections and display fields', {
    tool: 'create_collection',
    arguments: {
      name,
      displayName: c.display_name,
      description: c.description,
      fields: fieldsOf(name).filter((f) => f.type !== 'PICKLIST').map(fieldArgs),
    },
  });
}
// create_collection leaves displayFieldId null when displayFieldName names an inline field
// (BUILD-LOG.md, "Observed"), so each display field is set afterwards.
for (const name of COLLECTION_ORDER) {
  step('Collections and display fields', {
    tool: 'update_collection',
    arguments: { id: name, displayFieldName: collections[name].display_field_name },
    save: { [collectionVar(name)]: '.data.id' },
  });
}

// --- Picklist fields -----------------------------------------------------------------------
const fieldRow = (f) => ({
  collectionId: `{{${collectionVar(f.collection_name)}}}`,
  name: f.name,
  type: 'PICKLIST',
  displayName: f.display_name,
  required: f.required,
});
for (const f of picklistFields) {
  step('Picklist fields', {
    tool: 'create_record',
    arguments: { collection: 'fields', attributes: fieldRow(f) },
    save: { [fieldVar(f.collection_name, f.name)]: '.data.id' },
  });
}
// Sent after the rows it repeats, so it fails on uk_field_collection_name even once atomic
// operations accept system collections: the count stays the build's either way.
const openingPicklists = picklistFields.filter((f) => f.collection_name === 'job-openings');
step('Picklist fields', {
  tool: 'bulk_apply',
  arguments: {
    operations: openingPicklists.map((f) => ({ op: 'add', type: 'fields', attributes: fieldRow(f) })),
  },
  expectError:
    'BUILD-LOG.md error 2: atomic operations on the fields system collection answered 422 OPERATION_FAILED',
});

// --- Picklist values -----------------------------------------------------------------------
const valueRow = (v) => {
  const f = fieldById[v.picklist_source_id];
  return {
    picklistSourceType: 'FIELD',
    picklistSourceId: `{{${fieldVar(f.collection_name, f.name)}}}`,
    value: v.value,
    label: v.label,
    sortOrder: v.sort_order,
    isActive: true,
  };
};
for (const f of picklistFields) {
  const values = valuesOf(f);
  for (const v of values) {
    step('Picklist values', {
      tool: 'create_record',
      arguments: { collection: 'picklist-values', attributes: valueRow(v) },
    });
  }
  if (f.collection_name === 'job-openings' && f.name === 'department') {
    step('Picklist values', {
      tool: 'create_record',
      arguments: { collection: 'picklist-values', attributes: valueRow(values[0]) },
      expectError:
        'BUILD-LOG.md error 4: the first department value re-sent; 409 UNIQUE_VIOLATION (uq_picklist_value)',
    });
  }
}
step('Picklist values', {
  tool: 'bulk_apply',
  arguments: {
    operations: openingPicklists.flatMap((f) =>
      valuesOf(f).map((v) => ({ op: 'add', type: 'picklist-values', attributes: valueRow(v) }))
    ),
  },
  expectError:
    'BUILD-LOG.md error 3: atomic operations on picklist-values answered 422 OPERATION_FAILED',
});

// --- Page layouts --------------------------------------------------------------------------
const layouts = items('PAGE_LAYOUT');
const sections = items('LAYOUT_SECTION');
const layoutFields = items('LAYOUT_FIELD');
const relatedLists = items('LAYOUT_RELATED_LIST');
const layoutArgs = (layout, name = layout.name) => ({
  collectionName: layout.collection_name,
  name,
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
const layoutOf = (collection) => layouts.find((l) => l.collection_name === collection);
step('Page layouts', {
  tool: 'apply_layout',
  arguments: layoutArgs(layoutOf('job-openings'), 'Job opening'),
  expectError:
    "BUILD-LOG.md error 5: a layout name with a space is sent as 'Job+opening' in the path and rejected",
});
for (const collection of ['candidates', 'job-applications', 'interviews', 'job-openings']) {
  step('Page layouts', { tool: 'apply_layout', arguments: layoutArgs(layoutOf(collection)) });
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
// install.sh's filter strings: "field=value" or "field.op=value".
const parseFilter = (text) => {
  const [lhs, value] = text.split('=');
  const [field, operator = 'eq'] = lhs.split('.');
  return { field, operator, value };
};
const listViewFiles = readdirSync(join(TEMPLATE, 'list-views')).filter((f) => f.endsWith('.json')).sort();
for (const file of listViewFiles) {
  const collectionName = file.replace(/\.json$/, '');
  for (const view of readJson(`list-views/${file}`)) {
    if (view.viewType === 'KANBAN') {
      // apply_listview has no viewType/typeConfig, so the board takes create_listview, whose
      // displayedFields is a comma-separated string: the build first sent an array.
      const kanban = {
        collectionName,
        name: view.name,
        visibility: view.visibility ?? 'PUBLIC',
        isDefault: view.default ?? false,
        rowLimit: view.rowLimit,
        viewType: view.viewType,
        typeConfig: { kanban: { laneField: view.laneField, cardFields: view.cardFields } },
      };
      step('List views', {
        tool: 'create_listview',
        arguments: { ...kanban, displayedFields: view.columns },
        expectError:
          "BUILD-LOG.md error 6: displayedFields sent as an array is stringified; Unknown field '[name'",
      });
      step('List views', {
        tool: 'create_listview',
        arguments: { ...kanban, displayedFields: view.columns.join(',') },
      });
      continue;
    }
    const args = {
      collectionName,
      name: view.name,
      columns: view.columns,
      filters: (view.filters ?? []).map(parseFilter),
      visibility: view.visibility ?? 'PUBLIC',
      isDefault: view.default ?? false,
    };
    if (view.sort) args.sort = view.sort;
    if (view.rowLimit) args.rowLimit = view.rowLimit;
    step('List views', { tool: 'apply_listview', arguments: args });
  }
}

// --- Dashboard -----------------------------------------------------------------------------
for (const file of readdirSync(join(TEMPLATE, 'dashboards')).filter((f) => f.endsWith('.json')).sort()) {
  step('Dashboard', { tool: 'apply_dashboard', arguments: readJson(`dashboards/${file}`) });
}

// --- Seed records --------------------------------------------------------------------------
// One bulk_apply per collection; a reference holds "{{<parent>Ids:<natural key>}}", resolved
// from the parent's bulk_apply result.
for (const collection of COLLECTION_ORDER) {
  const refs = SEED_REFS[collection] ?? {};
  const operations = readJson(`seeds/${collection}.json`).map((row) => ({
    op: 'add',
    type: collection,
    attributes: Object.fromEntries(
      Object.entries(row).map(([key, value]) =>
        refs[key] && value !== null ? [key, `{{${seedVar(refs[key])}:${value}}}`] : [key, value]
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
// BUILD-LOG.md's "Dashboard check" (cli_dashboards_validate, cli_dashboards_data) and "Export"
// (cli_metadata_export) are local CLI tools: `--source remote` does not serve them and kelta-mcp
// has no equivalent, so those 3 calls are not replayed.

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
// cli_dashboards_list in the build; cli_dashboards_validate and cli_dashboards_data are not
// replayed (see above).
step('Verification', {
  tool: 'query_collection',
  arguments: { collection: 'dashboards', filter: { name: { EQ: 'Hiring overview' } } },
});

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
