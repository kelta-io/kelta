import type { AxiosInstance } from 'axios';
import { CliError, EXIT } from '../errors.js';
import { collectionIdByName, picklistIdByName } from './lookups.js';

/**
 * Friendly alias → native FieldType. Mirrors the MCP AddFieldTool table
 * (kelta-mcp FieldBodyBuilder.resolveNativeType) — keep the two in sync.
 * Exact uppercase enum names also pass through verbatim.
 */
const TYPE_ALIASES: Record<string, string> = {
  text: 'STRING',
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

const NATIVE_TYPES = new Set(Object.values(TYPE_ALIASES));

export function resolveFieldType(type: string): string {
  const alias = TYPE_ALIASES[type.toLowerCase()];
  if (alias) return alias;
  if (NATIVE_TYPES.has(type)) return type;
  throw new CliError(
    `Unknown field type "${type}" — use a friendly alias (text, number, picklist, reference, …) or a native FieldType enum name`,
    { code: 'INVALID_ARGUMENTS', exitCode: EXIT.USAGE }
  );
}

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

  if (nativeType === 'PICKLIST' || nativeType === 'MULTI_PICKLIST') {
    if (!spec.picklist) {
      throw new CliError(`${nativeType} fields need --picklist <name|id>`, {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
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
