import { useCallback, useEffect, useLayoutEffect, useMemo, useRef } from 'react';
import type { LayoutRule, CollectionValidationRule } from '@kelta/sdk';
import { RuleEngine } from './RuleEngine';
import type { BeforeSaveResult, FormBinding, RuleEngineDiagnostic } from './types';

export interface UseLayoutRulesOptions {
  /** Active layout rules (typically from PageLayout.rules). */
  rules: LayoutRule[] | undefined;
  /** Optional collection-wide validation rules with enforceOnClient. */
  validationRules?: CollectionValidationRule[];
  /** Current form values (read-only mirror; engine consumes this each tick). */
  values: Record<string, unknown>;
  /** Patch one field. Engine calls this for compute/default/transform writes. */
  setFieldValue: (field: string, value: unknown) => void;
  /** Set or clear an inline error message. */
  setFieldError: (field: string, message: string) => void;
  clearFieldError: (field: string) => void;
  /** Disable the engine (e.g. when feature flag is off). */
  enabled?: boolean;
}

export interface UseLayoutRulesResult {
  /** Whether the engine is active for this form. */
  enabled: boolean;
  /** Cycle/parse diagnostics for the builder UI. */
  diagnostics: RuleEngineDiagnostic[];
  /** Whether a field is a compute target (render as read-only with ƒ adornment). */
  isComputed: (field: string) => boolean;
  /**
   * Run one onChange tick — call from your form's field-change handler.
   *
   * Pass the post-mutation values map as the second argument to bypass the
   * stale-closure / async-setState race: with plain useState, calling
   * `setFormData(prev => ({...prev, name: value}))` does NOT make the new
   * value visible to the engine until React re-renders and our `values`
   * prop updates. Supply `nextValues` so the engine sees the fresh value
   * immediately.
   */
  onFieldChange: (field: string, nextValues?: Record<string, unknown>) => void;
  /** Run one onBlur tick — call from your input onBlur handler. */
  onFieldBlur: (field: string, nextValues?: Record<string, unknown>) => void;
  /** Run before-save checks. Block submission when blocked === true. */
  runBeforeSave: () => BeforeSaveResult;
}

/**
 * React hook that hosts the RuleEngine for a form. The hook is binding-agnostic
 * — adapt to React Hook Form, plain useState, or any other state library by
 * supplying values/setFieldValue/setFieldError/clearFieldError.
 *
 * Engine state lives in a ref so it survives re-renders without reset. The
 * engine is rebuilt only when the rule list changes (by reference).
 */
export function useLayoutRules(opts: UseLayoutRulesOptions): UseLayoutRulesResult {
  const { rules, validationRules, values, setFieldValue, setFieldError, clearFieldError } = opts;
  const hasRules = !!rules && rules.length > 0;
  const hasValidations = !!validationRules && validationRules.length > 0;
  const enabled = opts.enabled !== false && (hasRules || hasValidations);

  const valuesRef = useRef(values);
  const setFieldValueRef = useRef(setFieldValue);
  const setFieldErrorRef = useRef(setFieldError);
  const clearFieldErrorRef = useRef(clearFieldError);

  // Mirror the latest props into the refs after every commit. A layout effect
  // runs before every passive effect (including the onLoad effect below), so the
  // engine never reads values or callbacks from a previous render.
  useLayoutEffect(() => {
    valuesRef.current = values;
    setFieldValueRef.current = setFieldValue;
    setFieldErrorRef.current = setFieldError;
    clearFieldErrorRef.current = clearFieldError;
  });

  const engine = useMemo(() => {
    if (!enabled) return null;
    return new RuleEngine({
      layoutRules: rules ?? [],
      validationRules,
    });
  }, [enabled, rules, validationRules]);

  const binding = useMemo<FormBinding>(
    () => ({
      getValue: (field) => valuesRef.current[field],
      getValues: () => ({ ...valuesRef.current }),
      setValue: (field, value) => {
        // Mirror in the ref so subsequent rule evaluations within the same tick
        // see the new value (engine-internal cascades).
        valuesRef.current = { ...valuesRef.current, [field]: value };
        setFieldValueRef.current(field, value);
      },
      setError: (field, message) => setFieldErrorRef.current(field, message),
      clearError: (field) => clearFieldErrorRef.current(field),
    }),
    []
  );

  // Run onLoad once when an engine is created.
  const loadedRef = useRef<RuleEngine | null>(null);
  useEffect(() => {
    if (engine && loadedRef.current !== engine) {
      loadedRef.current = engine;
      engine.onLoad(binding);
    }
  }, [engine, binding]);

  const onFieldChange = useCallback(
    (field: string, nextValues?: Record<string, unknown>) => {
      // Override the ref with caller-supplied fresh values BEFORE the engine
      // reads them. Avoids the React useState/useRef race where the engine
      // would otherwise read pre-change values.
      if (nextValues) valuesRef.current = nextValues;
      engine?.onFieldChange(field, binding);
    },
    [engine, binding]
  );

  const onFieldBlur = useCallback(
    (field: string, nextValues?: Record<string, unknown>) => {
      if (nextValues) valuesRef.current = nextValues;
      engine?.onFieldBlur(field, binding);
    },
    [engine, binding]
  );

  const runBeforeSave = useCallback((): BeforeSaveResult => {
    if (!engine) return { blocked: false, violations: [] };
    return engine.runBeforeSave(binding);
  }, [engine, binding]);

  const isComputed = useCallback((field: string) => engine?.isComputed(field) ?? false, [engine]);

  return {
    enabled: !!engine,
    diagnostics: engine?.getDiagnostics() ?? [],
    isComputed,
    onFieldChange,
    onFieldBlur,
    runBeforeSave,
  };
}
