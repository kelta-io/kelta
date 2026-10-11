import type { AxiosInstance } from 'axios';
import { CliError, EXIT } from '../errors.js';
import { collectionIdByName, picklistIdByName } from './lookups.js';

/**
 * Friendly alias → native FieldType. Mirrors the MCP AddFieldTool table
 * (kelta-mcp FieldBodyBuilder.resolveNativeType) — keep the two in sync.
 * Exact native enum names are checked first, so `TEXT` stays TEXT while `text` is STRING.
 */
const TYPE_ALIASES: Record<string, string> = {
  text: 'STRING',
  textarea: 'STRING',
  string: 'STRING',
  longtext: 'STRING',
  long_text: 'STRING',
  number: 'INTEGER',
  integer: 'INTEGER',
  decimal: 'DOUBLE',
  double: 'DOUBLE',
  long: 'LONG',
  boolean: 'BOOLEAN',
  bool: 'BOOLEAN',
  date: 'DATE',
  datetime: 'DATETIME',
  picklist: 'PICKLIST',
  multipicklist: 'MULTI_PICKLIST',
  multi_picklist: 'MULTI_PICKLIST',
  reference: 'LOOKUP',
  lookup: 'LOOKUP',
  masterdetail: 'MASTER_DETAIL',
  master_detail: 'MASTER_DETAIL',
  json: 'JSON',
  richtext: 'RICH_TEXT',
  rich_text: 'RICH_TEXT',
  vector: 'VECTOR',
  currency: 'CURRENCY',
  percent: 'PERCENT',
  url: 'URL',
  email: 'EMAIL',
  phone: 'PHONE',
  autonumber: 'AUTO_NUMBER',
  auto_number: 'AUTO_NUMBER',
  externalid: 'EXTERNAL_ID',
  external_id: 'EXTERNAL_ID',
  encrypted: 'ENCRYPTED',
  geolocation: 'GEOLOCATION',
  array: 'ARRAY',
};

/** Every runtime-core FieldType constant (fieldBody.test.ts checks this against FieldType.java). */
const NATIVE_TYPES: readonly string[] = [
  'STRING',
  'INTEGER',
  'LONG',
  'DOUBLE',
  'BOOLEAN',
  'DATE',
  'DATETIME',
  'JSON',
  'REFERENCE',
  'ARRAY',
  'PICKLIST',
  'MULTI_PICKLIST',
  'CURRENCY',
  'PERCENT',
  'AUTO_NUMBER',
  'PHONE',
  'EMAIL',
  'URL',
  'TEXT',
  'RICH_TEXT',
  'VECTOR',
  'ENCRYPTED',
  'EXTERNAL_ID',
  'GEOLOCATION',
  'LOOKUP',
  'MASTER_DETAIL',
  'FORMULA',
  'ROLLUP_SUMMARY',
];

export function resolveFieldType(type: string): string {
  if (NATIVE_TYPES.includes(type)) return type === 'REFERENCE' ? 'LOOKUP' : type;
  const alias = TYPE_ALIASES[type.toLowerCase()];
  if (alias) return alias;
  throw new CliError(
    `Unknown field type "${type}" — use a friendly alias (${Object.keys(TYPE_ALIASES).join(', ')}) or a native FieldType name (${NATIVE_TYPES.join(', ')})`,
    { code: 'INVALID_ARGUMENTS', exitCode: EXIT.USAGE }
  );
}

const PICKLIST_TYPES = new Set(['PICKLIST', 'MULTI_PICKLIST']);

export interface FieldSpec {
  collection: string;
  name: string;
  type: string;
  displayName?: string;
  required?: boolean;
  unique?: boolean;
  indexed?: boolean;
  searchable?: boolean;
  description?: string;
  defaultValue?: string | number | boolean;
  /** Global picklist name or id (PICKLIST/MULTI_PICKLIST). */
  picklist?: string;
  /** Field-scoped picklist values (PICKLIST/MULTI_PICKLIST); the caller POSTs them after the field. */
  values?: string[];
  /** Target collection name or id (LOOKUP/MASTER_DETAIL). */
  reference?: string;
  relationshipName?: string;
  /** VECTOR dimension. */
  dimension?: number;
  /** Extra attributes merged last (escape hatch, wins on collision). */
  extra?: Record<string, unknown>;
}

interface JsonApiFieldBody {
  data: {
    type: 'fields';
    attributes: Record<string, unknown>;
    relationships?: Record<string, unknown>;
  };
}

/**
 * Build the POST /api/fields body — a TS port of the MCP FieldBodyBuilder
 * (same attribute renames: unique→uniqueConstraint; picklist/reference/vector
 * per-type config; reference target sent as BOTH the display-name attribute
 * and the id relationship).
 */
export async function buildFieldBody(
  axios: AxiosInstance,
  spec: FieldSpec
): Promise<JsonApiFieldBody> {
  const nativeType = resolveFieldType(spec.type);
  if (spec.values !== undefined) {
    if (!PICKLIST_TYPES.has(nativeType)) {
      throw new CliError(`--values only applies to PICKLIST and MULTI_PICKLIST fields`, {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    if (spec.picklist) {
      throw new CliError(
        'Use either --picklist (global picklist) or --values (field-scoped), not both',
        {
          code: 'INVALID_ARGUMENTS',
          exitCode: EXIT.USAGE,
        }
      );
    }
    if (spec.values.length === 0) {
      throw new CliError('--values needs at least one value (a,b,c)', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
  }
  const collectionId = await collectionIdByName(axios, spec.collection);

  const attributes: Record<string, unknown> = {
    collectionId,
    name: spec.name,
    type: nativeType,
  };
  if (spec.displayName) attributes.displayName = spec.displayName;
  if (spec.required !== undefined) attributes.required = spec.required;
  if (spec.unique !== undefined) attributes.uniqueConstraint = spec.unique;
  if (spec.indexed !== undefined) attributes.indexed = spec.indexed;
  if (spec.searchable !== undefined) attributes.searchable = spec.searchable;
  if (spec.description) attributes.description = spec.description;
  if (spec.defaultValue !== undefined) attributes.defaultValue = spec.defaultValue;

  let relationships: Record<string, unknown> | undefined;

  if (PICKLIST_TYPES.has(nativeType) && spec.values === undefined) {
    if (!spec.picklist) {
      throw new CliError(
        `${nativeType} fields need --picklist <name|id> (global picklist) or --values <a,b,c> (field-scoped values)`,
        { code: 'INVALID_ARGUMENTS', exitCode: EXIT.USAGE }
      );
    }
    const picklistId = await picklistIdByName(axios, spec.picklist);
    attributes.fieldTypeConfig = { picklistSourceType: 'GLOBAL', globalPicklistId: picklistId };
  }

  if (nativeType === 'LOOKUP' || nativeType === 'MASTER_DETAIL') {
    if (!spec.reference) {
      throw new CliError(`${nativeType} fields need --reference <collection>`, {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const targetId = await collectionIdByName(axios, spec.reference);
    attributes.referenceTarget = spec.reference;
    if (spec.relationshipName) attributes.relationshipName = spec.relationshipName;
    relationships = {
      referenceCollectionId: { data: { type: 'collections', id: targetId } },
    };
  }

  if (nativeType === 'VECTOR') {
    if (!spec.dimension || spec.dimension < 1 || spec.dimension > 16384) {
      throw new CliError('VECTOR fields need --dimension (1-16384)', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    attributes.fieldTypeConfig = { dimension: spec.dimension };
  }

  if (spec.extra) Object.assign(attributes, spec.extra);

  return {
    data: {
      type: 'fields',
      attributes,
      ...(relationships ? { relationships } : {}),
    },
  };
}
