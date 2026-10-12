/**
 * Which attribute of a lookup's TARGET collection labels a referenced record — one rule for every
 * surface that turns a lookup id into text (the record list core's JSON:API `include` labels and
 * `useLookupDisplayMap`), so the same record reads the same in both record stacks.
 *
 * Precedence: the target's configured display field → a field named `name` (any case) → the
 * first `string` field → `id` (the label is the raw id). With no target schema at all the
 * answer is `name`, the conventional label field.
 */
export function resolveLookupDisplayField(
  targetSchema:
    | { displayFieldName?: string; fields?: Array<{ name: string; type: string }> }
    | undefined
): string {
  if (!targetSchema) return 'name'
  if (targetSchema.displayFieldName) return targetSchema.displayFieldName
  const fields = targetSchema.fields ?? []
  const nameField = fields.find((f) => f.name.toLowerCase() === 'name')
  if (nameField) return nameField.name
  const firstString = fields.find((f) => f.type === 'string')
  return firstString ? firstString.name : 'id'
}
