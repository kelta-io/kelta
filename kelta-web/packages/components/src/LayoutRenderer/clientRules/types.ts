import type { LayoutRule, LayoutRuleEvent } from '@kelta/sdk';

export type { LayoutRule, LayoutRuleEvent };

/**
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `useLayoutRules`).
 * Use `useLayoutRules` instead.
 */
export interface FormBinding {
  getValue(field: string): unknown;
  getValues(): Record<string, unknown>;
  setValue(field: string, value: unknown): void;
  setError(field: string, message: string): void;
  clearError(field: string): void;
}

export interface RuleViolation {
  ruleId: string;
  ruleName: string;
  field?: string;
  message: string;
  severity: 'error' | 'warning';
}

export interface BeforeSaveResult {
  blocked: boolean;
  violations: RuleViolation[];
}

export interface RuleEngineDiagnostic {
  ruleId: string;
  ruleName: string;
  level: 'error' | 'warning';
  message: string;
}
