import { buildIncludedDisplayMap } from './jsonapi'

/**
 * Which attribute of a lookup's TARGET collection labels a referenced record — one rule for every
 * surface that turns a lookup id into text (the record list core's JSON:API `include` labels, the
 * related list, the end-user detail page and `useLookupDisplayMap`), so the same record reads the
 * same in both record stacks.
 *
 * Precedence: the target's configured display field → a field named `name` (any case) → the
 * first `string` field → `id` (the label is the raw id). With no target schema at all the
 * answer is `name`, the conventional label field.
 */
/** What the label rule reads from a lookup's target schema. */
export interface LookupTargetSchema {
  displayFieldName?: string
  fields?: Array<{ name: string; type: string }>
}

export function resolveLookupDisplayField(targetSchema: LookupTargetSchema | undefined): string {
  if (!targetSchema) return 'name'
  if (targetSchema.displayFieldName) return targetSchema.displayFieldName
  const fields = targetSchema.fields ?? []
  const nameField = fields.find((f) => f.name.toLowerCase() === 'name')
  if (nameField) return nameField.name
  const firstString = fields.find((f) => f.type === 'string')
  return firstString ? firstString.name : 'id'
}

/**
 * Labels for the lookup columns of a JSON:API response that carried `include` — field name →
 * (referenced id → label), each target labelled by {@link resolveLookupDisplayField} over the
 * schema `targetSchema` returns for it (typically the collection store's
 * `getCollectionByName`). Fields with no included targets are left out; `undefined` when none
 * resolve.
 */
export function buildLookupLabels(
  response: unknown,
  referenceFields: Array<{ name: string; referenceTarget?: string }>,
  targetSchema: (collectionName: string) => LookupTargetSchema | undefined
): Record<string, Record<string, string>> | undefined {
  if (!response) return undefined
  const labels: Record<string, Record<string, string>> = {}
  for (const field of referenceFields) {
    const target = field.referenceTarget
    if (!target) continue
    const byId = buildIncludedDisplayMap(
      response,
      target,
      resolveLookupDisplayField(targetSchema(target))
    )
    if (Object.keys(byId).length > 0) labels[field.name] = byId
  }
  return Object.keys(labels).length > 0 ? labels : undefined
}
