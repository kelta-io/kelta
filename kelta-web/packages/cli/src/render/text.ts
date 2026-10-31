/**
 * Text form of an arbitrary value without the "[object Object]" trap.
 *
 * Primitives, `null`/`undefined`, Dates and functions keep `String()` semantics.
 * Arrays keep `Array.prototype.toString` semantics (elements joined by ",", with
 * `null`/`undefined` elements as empty strings). Any other object is JSON-encoded,
 * since `String()` would print "[object Object]".
 */
export function toText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (
    value === null ||
    value === undefined ||
    typeof value === 'number' ||
    typeof value === 'boolean' ||
    typeof value === 'bigint' ||
    typeof value === 'symbol' ||
    typeof value === 'function'
  ) {
    return String(value);
  }
  if (value instanceof Date) return value.toString();
  if (Array.isArray(value)) {
    return value
      .map((item: unknown) => (item === null || item === undefined ? '' : toText(item)))
      .join(',');
  }
  return JSON.stringify(value);
}
