import { FormulaEvaluator, FormulaException } from '@kelta/formula';
import type {
  LayoutRule,
  ComputeLayoutRule,
  ValidateLayoutRule,
  DefaultLayoutRule,
  TransformLayoutRule,
  ScriptLayoutRule,
  CollectionValidationRule,
  LayoutRuleEvent,
} from '@kelta/sdk';
import type { FormBinding, BeforeSaveResult, RuleViolation, RuleEngineDiagnostic } from './types';
import { downstreamRules, topologicalSort, type RuleNode } from './dependencyGraph';

interface PreparedRule {
  rule: LayoutRule;
  dependencies: string[];
}

interface PreparedValidationRule {
  rule: CollectionValidationRule;
  dependencies: string[];
}

const RULE_KIND_LABEL: Record<LayoutRule['kind'], string> = {
  compute: 'Compute',
  validate: 'Validate',
  default: 'Default',
  transform: 'Transform',
  script: 'Script',
};

/**
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `useLayoutRules`).
 * Use `useLayoutRules` instead.
 */
export interface RuleEngineOptions {
  /**
   * Layout-scoped client rules to apply.
   */
  layoutRules: LayoutRule[];
  /**
   * Collection validation rules with `enforceOnClient = true`. Mirrored from
   * the server so the form can surface validation errors live and block save
   * for ERROR severity.
   */
  validationRules?: CollectionValidationRule[];
  /**
   * Optional pre-built FormulaEvaluator. If omitted, the engine constructs
   * its own with the standard built-in functions.
   */
  evaluator?: FormulaEvaluator;
}

/**
 * RuleEngine evaluates per-layout client rules against a form binding.
 * Owns the dependency graph, topological order, cycle detection, and the
 * actual evaluation loop. The host component drives it with form events
 * (onLoad / onFieldChange / onFieldBlur / onBeforeSave).
 *
 * Cycles in compute/default dependencies disable the entire client-rule
 * set for that layout (fail-closed); diagnostics are exposed via
 * `getDiagnostics()` so the builder UI can show them.
 *
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `useLayoutRules`).
 * Use `useLayoutRules` instead.
 */
export class RuleEngine {
  private readonly evaluator: FormulaEvaluator;
  private readonly active: PreparedRule[];
  private readonly validationActive: PreparedValidationRule[];
  private readonly evaluationOrder: string[];
  private readonly diagnostics: RuleEngineDiagnostic[] = [];
  private readonly disabled: boolean;
  private readonly writingFields = new Set<string>();
  private readonly computeTargets = new Set<string>();

  constructor(opts: RuleEngineOptions) {
    this.evaluator = opts.evaluator ?? new FormulaEvaluator();

    const prepared: PreparedRule[] = [];
    for (const rule of opts.layoutRules.filter((r) => r.active)) {
      try {
        const deps = this.resolveDependencies(rule);
        prepared.push({ rule, dependencies: deps });
        if (rule.kind === 'compute') this.computeTargets.add(rule.target);
      } catch (err) {
        this.diagnostics.push({
          ruleId: rule.id,
          ruleName: rule.name,
          level: 'error',
          message: `Could not parse formula: ${(err as Error).message}`,
        });
      }
    }

    const computeAndDefault: RuleNode[] = prepared
      .filter((p) => p.rule.kind === 'compute' || p.rule.kind === 'default')
      .map((p) => {
        const r = p.rule as ComputeLayoutRule | DefaultLayoutRule;
        return { id: r.id, target: r.target, dependsOn: p.dependencies };
      });

    const topo = topologicalSort(computeAndDefault);
    if (!topo.ok) {
      this.diagnostics.push({
        ruleId: topo.cycle[0] ?? 'unknown',
        ruleName: 'cycle',
        level: 'error',
        message: `Rule dependency cycle detected: ${topo.cycle.join(' -> ')}. All client rules disabled for this layout.`,
      });
      this.disabled = true;
      this.active = [];
      this.evaluationOrder = [];
    } else {
      this.disabled = false;
      // Sort all active rules by sortOrder; topo order takes precedence within
      // compute/default (already enforced by Kahn). For validate/transform,
      // sortOrder is enough.
      this.active = [...prepared].sort((a, b) => a.rule.sortOrder - b.rule.sortOrder);
      this.evaluationOrder = topo.order;
    }

    this.validationActive = (opts.validationRules ?? [])
      .filter((r) => r.active && r.enforceOnClient)
      .map((rule) => ({ rule, dependencies: this.safeExtract(rule.errorConditionFormula) }));
  }

  /**
   * Diagnostics surfaced to the builder UI (cycle errors, parse failures).
   */
  getDiagnostics(): RuleEngineDiagnostic[] {
    return [...this.diagnostics];
  }

  /**
   * Whether the engine is disabled (rule set rejected — usually due to a cycle).
   */
  isDisabled(): boolean {
    return this.disabled;
  }

  /**
   * Whether a field is a compute target (the form should render it as
   * read-only when this returns true).
   */
  isComputed(field: string): boolean {
    return this.computeTargets.has(field);
  }

  /**
   * Run on form mount. Applies DEFAULT rules where target is empty, then
   * COMPUTE rules in topo order. VALIDATE rules with `onLoad` also fire.
   */
  onLoad(form: FormBinding): void {
    if (this.disabled) return;
    this.runForEvent('onLoad', form);
  }

  /**
   * Run on every form-field change.
   */
  onFieldChange(field: string, form: FormBinding): void {
    if (this.disabled) return;
    if (this.writingFields.has(field)) return;
    this.runForEvent('onChange', form, field);
  }

  /**
   * Run on field blur. Used primarily for TRANSFORM rules.
   */
  onFieldBlur(field: string, form: FormBinding): void {
    if (this.disabled) return;
    this.runForEvent('onBlur', form, field);
  }

  /**
   * Run before submit. Returns the result; if blocked, the form should not
   * call its mutation.
   */
  runBeforeSave(form: FormBinding): BeforeSaveResult {
    const violations: RuleViolation[] = [];
    if (this.disabled) return { blocked: false, violations };

    // Re-run computes to ensure final state is consistent.
    for (const id of this.evaluationOrder) {
      const prepared = this.active.find((p) => p.rule.id === id);
      if (prepared) this.applyRule(prepared, form);
    }

    // Layout-scoped validate rules (those configured for onBeforeSave).
    for (const prepared of this.active) {
      const rule = prepared.rule;
      if (rule.kind !== 'validate') continue;
      if (!rule.when.includes('onBeforeSave')) continue;
      const violation = this.evaluateValidate(rule, form);
      if (violation) {
        violations.push(violation);
        if (violation.severity === 'error') {
          form.setError(violation.field ?? '_form', violation.message);
        }
      }
    }

    // Collection-wide validate rules with enforceOnClient.
    for (const v of this.validationActive) {
      const violation = this.evaluateCollectionValidation(v.rule, form);
      if (violation) {
        violations.push(violation);
        if (violation.severity === 'error') {
          form.setError(violation.field ?? '_form', violation.message);
        }
      }
    }

    // Layout-scoped SCRIPT rules configured for onBeforeSave — a non-empty
    // message blocks the submit. Fail-open on a runtime error: the server
    // record-event hook (slice 7) is authoritative.
    for (const prepared of this.active) {
      const rule = prepared.rule;
      if (rule.kind !== 'script') continue;
      if (!rule.when.includes('onBeforeSave')) continue;
      let message: string | null = null;
      try {
        message = this.evaluateScript(rule, form);
      } catch (err) {
        this.diagnostics.push({
          ruleId: rule.id,
          ruleName: rule.name,
          level: 'warning',
          message: `Script rule failed at runtime: ${(err as Error).message}`,
        });
        continue;
      }
      if (message) {
        violations.push({
          ruleId: rule.id,
          ruleName: rule.name,
          field: rule.target,
          message,
          severity: 'error',
        });
        form.setError(rule.target ?? '_form', message);
      }
    }

    return {
      blocked: violations.some((v) => v.severity === 'error'),
      violations,
    };
  }

  private runForEvent(event: LayoutRuleEvent, form: FormBinding, changedField?: string): void {
    // Always evaluate compute rules in topo order on every event so the form
    // stays consistent. We use the dependency graph to skip ones whose deps
    // haven't changed when `changedField` is provided.
    const dirtyComputeIds = changedField
      ? new Set(downstreamRules(changedField, this.computeAndDefaultNodes(), this.evaluationOrder))
      : null;

    for (const id of this.evaluationOrder) {
      const prepared = this.active.find((p) => p.rule.id === id);
      if (!prepared) continue;
      if (!prepared.rule.when.includes(event) && event !== 'onChange') continue;
      if (dirtyComputeIds && !dirtyComputeIds.has(id) && event !== 'onLoad') continue;
      this.applyRule(prepared, form);
    }

    // Other rules (validate/transform) outside the compute/default DAG.
    for (const prepared of this.active) {
      if (prepared.rule.kind === 'compute' || prepared.rule.kind === 'default') continue;
      if (!prepared.rule.when.includes(event)) continue;
      if (changedField && prepared.rule.kind === 'transform') {
        if (prepared.rule.target !== changedField) continue;
      }
      this.applyRule(prepared, form);
    }
  }

  private computeAndDefaultNodes(): RuleNode[] {
    return this.active
      .filter((p) => p.rule.kind === 'compute' || p.rule.kind === 'default')
      .map((p) => {
        const r = p.rule as ComputeLayoutRule | DefaultLayoutRule;
        return { id: r.id, target: r.target, dependsOn: p.dependencies };
      });
  }

  private applyRule(prepared: PreparedRule, form: FormBinding): void {
    const rule = prepared.rule;
    try {
      switch (rule.kind) {
        case 'compute':
          this.applyCompute(rule, form);
          return;
        case 'default':
          this.applyDefault(rule, form);
          return;
        case 'validate': {
          const violation = this.evaluateValidate(rule, form);
          if (violation && violation.severity === 'error') {
            form.setError(violation.field ?? '_form', violation.message);
          } else if (rule.target) {
            form.clearError(rule.target);
          }
          return;
        }
        case 'transform':
          this.applyTransform(rule, form);
          return;
        case 'script': {
          const message = this.evaluateScript(rule, form);
          if (message) {
            form.setError(rule.target ?? '_form', message);
          } else if (rule.target) {
            form.clearError(rule.target);
          }
          return;
        }
      }
    } catch (err) {
      this.diagnostics.push({
        ruleId: rule.id,
        ruleName: rule.name,
        level: 'warning',
        message: `${RULE_KIND_LABEL[rule.kind]} rule failed at runtime: ${(err as Error).message}`,
      });
    }
  }

  private applyCompute(rule: ComputeLayoutRule, form: FormBinding): void {
    const value = this.evaluator.evaluate(rule.formula, form.getValues());
    this.writeField(form, rule.target, value);
  }

  private applyDefault(rule: DefaultLayoutRule, form: FormBinding): void {
    const current = form.getValue(rule.target);
    if (current !== null && current !== undefined && current !== '') return;
    const value = this.evaluator.evaluate(rule.formula, form.getValues());
    if (value === null || value === undefined) return;
    this.writeField(form, rule.target, value);
  }

  private applyTransform(rule: TransformLayoutRule, form: FormBinding): void {
    const current = form.getValue(rule.target);
    if (current === null || current === undefined) return;
    const transformed = this.transform(current, rule.transform, form);
    if (transformed === undefined) return;
    if (transformed === current) return;
    this.writeField(form, rule.target, transformed);
  }

  private transform(
    value: unknown,
    spec: TransformLayoutRule['transform'],
    form: FormBinding
  ): unknown {
    if (spec.type === 'formula') {
      return this.evaluator.evaluate(spec.formula, form.getValues());
    }
    if (typeof value !== 'string') return value;
    switch (spec.type) {
      case 'upper':
        return value.toUpperCase();
      case 'lower':
        return value.toLowerCase();
      case 'trim':
        return value.trim();
      case 'titleCase':
        return value.toLowerCase().replace(/\b\w/g, (m) => m.toUpperCase());
    }
  }

  private evaluateValidate(rule: ValidateLayoutRule, form: FormBinding): RuleViolation | null {
    const triggered = this.evaluator.evaluateBoolean(rule.formula, form.getValues());
    if (!triggered) {
      if (rule.target) form.clearError(rule.target);
      return null;
    }
    return {
      ruleId: rule.id,
      ruleName: rule.name,
      field: rule.target,
      message: rule.errorMessage,
      severity: rule.enforce === 'block' ? 'error' : 'warning',
    };
  }

  /**
   * Evaluate a SCRIPT rule's expression and coerce the result into a message.
   * Falsy results (empty string, null, undefined, false, 0) mean "no problem"
   * and return null. A boolean `true` uses the rule's static `message`; any
   * other value is stringified. Strictly expression-based via `@kelta/formula`
   * — no `eval`/`window` reach (parser guarantee).
   */
  private evaluateScript(rule: ScriptLayoutRule, form: FormBinding): string | null {
    const raw = this.evaluator.evaluate(rule.expression, form.getValues());
    if (!raw) return null;
    if (raw === true) return rule.message ?? 'Invalid value';
    const message = typeof raw === 'string' ? raw : String(raw);
    return message.trim() === '' ? null : message;
  }

  private evaluateCollectionValidation(
    rule: CollectionValidationRule,
    form: FormBinding
  ): RuleViolation | null {
    let triggered = false;
    try {
      triggered = this.evaluator.evaluateBoolean(rule.errorConditionFormula, form.getValues());
    } catch (err) {
      // Server-side validation is authoritative; on parse failure, skip client.
      this.diagnostics.push({
        ruleId: rule.id,
        ruleName: rule.name,
        level: 'warning',
        message: `Collection validation failed to evaluate: ${(err as Error).message}`,
      });
      return null;
    }
    if (!triggered) {
      if (rule.errorField) form.clearError(rule.errorField);
      return null;
    }
    return {
      ruleId: rule.id,
      ruleName: rule.name,
      field: rule.errorField,
      message: rule.errorMessage,
      severity: rule.severity === 'WARNING' ? 'warning' : 'error',
    };
  }

  private writeField(form: FormBinding, field: string, value: unknown): void {
    this.writingFields.add(field);
    try {
      form.setValue(field, value);
    } finally {
      this.writingFields.delete(field);
    }
  }

  private resolveDependencies(rule: LayoutRule): string[] {
    if (rule.dependsOn && rule.dependsOn.length > 0) return rule.dependsOn;
    switch (rule.kind) {
      case 'compute':
      case 'default':
      case 'validate':
        return this.evaluator.extractFieldRefs(rule.formula);
      case 'transform':
        if (rule.transform.type === 'formula') {
          return this.evaluator.extractFieldRefs(rule.transform.formula);
        }
        return [rule.target];
      case 'script':
        return this.evaluator.extractFieldRefs(rule.expression);
    }
  }

  private safeExtract(formula: string): string[] {
    try {
      return this.evaluator.extractFieldRefs(formula);
    } catch (err) {
      if (err instanceof FormulaException) return [];
      throw err;
    }
  }
}
