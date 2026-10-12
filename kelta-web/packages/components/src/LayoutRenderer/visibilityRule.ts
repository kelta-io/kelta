import type { VisibilityRule } from '@kelta/sdk';

/**
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `isVisible`).
 * Use `isVisible` instead.
 */
export function parseVisibilityRule(json: string | undefined | null): VisibilityRule | null {
  if (!json) return null;
  try {
    const parsed = JSON.parse(json) as VisibilityRule;
    if (parsed.fieldName && parsed.operator) return parsed;
    const alt = parsed as unknown as { fieldId?: string; operator?: string; value?: string };
    if (alt.fieldId && alt.operator) {
      return {
        fieldName: alt.fieldId,
        operator: alt.operator as VisibilityRule['operator'],
        value: alt.value,
      };
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `isVisible`).
 * Use `isVisible` instead.
 */
export function evaluateVisibilityRule(
  rule: VisibilityRule,
  record: Record<string, unknown>
): boolean {
  const fieldValue = String(record[rule.fieldName] ?? '');

  switch (rule.operator) {
    case 'EQUALS':
      return fieldValue === (rule.value ?? '');
    case 'NOT_EQUALS':
      return fieldValue !== (rule.value ?? '');
    case 'CONTAINS':
      return fieldValue.includes(rule.value ?? '');
    case 'IS_EMPTY':
      return !fieldValue;
    case 'IS_NOT_EMPTY':
      return !!fieldValue;
    default:
      return true;
  }
}

export function isVisible(
  visibilityRule: string | undefined | null,
  record: Record<string, unknown>
): boolean {
  const rule = parseVisibilityRule(visibilityRule);
  if (!rule) return true;
  return evaluateVisibilityRule(rule, record);
}
