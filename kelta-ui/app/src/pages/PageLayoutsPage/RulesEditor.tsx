/**
 * RulesEditor
 *
 * Dialog editor for the layout-rules system collection. Authors compute,
 * validate, default, and transform rules attached to a single page-layout.
 *
 * Notes:
 *  - Uses the @kelta/formula evaluator to syntax-check formulas live.
 *  - Builds the rule dependency graph via @kelta/components and rejects
 *    cyclic save attempts with the specific cycle path.
 *  - Talks to the layout-rules JSON:API endpoint directly via the admin
 *    apiClient — same pattern as the rest of this page.
 */

import { useEffect, useMemo, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { FormulaEvaluator, FormulaException } from '@kelta/formula'
import { topologicalSort, type RuleNode } from '@kelta/components'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { Dialog, DialogClose, DialogContent, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

const RULE_KIND_LABEL: Record<RuleKind, string> = {
  COMPUTE: 'Compute',
  VALIDATE: 'Validate',
  DEFAULT: 'Default',
  TRANSFORM: 'Transform',
  SCRIPT: 'Script',
}

type RuleKind = 'COMPUTE' | 'VALIDATE' | 'DEFAULT' | 'TRANSFORM' | 'SCRIPT'
type RuleEvent = 'onChange' | 'onBlur' | 'onLoad' | 'onBeforeSave'

interface ApiLayoutRule {
  id: string
  layoutId: string
  name: string
  description: string | null
  kind: RuleKind
  active: boolean
  whenEvents: string[] | string
  targetField: string | null
  dependsOn: string[] | string | null
  body: Record<string, unknown> | string
  sortOrder: number
}

export interface RulesEditorProps {
  layoutId: string
  layoutName: string
  /** All field API names belonging to the layout's collection. Drives the
   *  target/formula picker autocomplete. */
  fieldNames: string[]
  onClose: () => void
}

interface DraftRule {
  id?: string
  name: string
  description: string
  kind: RuleKind
  active: boolean
  whenEvents: RuleEvent[]
  targetField: string
  formula: string
  errorMessage: string
  enforce: 'block' | 'warn'
  transformType: 'upper' | 'lower' | 'trim' | 'titleCase' | 'formula'
  sortOrder: number
}

const emptyDraft = (kind: RuleKind, sortOrder: number): DraftRule => ({
  name: '',
  description: '',
  kind,
  active: true,
  whenEvents:
    kind === 'TRANSFORM'
      ? ['onBlur']
      : kind === 'VALIDATE' || kind === 'SCRIPT'
        ? ['onChange', 'onBeforeSave']
        : ['onChange', 'onLoad'],
  targetField: '',
  formula: '',
  errorMessage: '',
  enforce: 'block',
  transformType: 'upper',
  sortOrder,
})

const evaluator = new FormulaEvaluator()

function parseField<T>(raw: unknown, fallback: T): T {
  if (raw === null || raw === undefined) return fallback
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw) as T
    } catch {
      return fallback
    }
  }
  return raw as T
}

function apiRuleToDraft(rule: ApiLayoutRule): DraftRule {
  const body = parseField<Record<string, unknown>>(rule.body, {})
  const transformSpec = (body.transform as { type?: string } | undefined)?.type
  return {
    id: rule.id,
    name: rule.name,
    description: rule.description ?? '',
    kind: rule.kind,
    active: rule.active,
    whenEvents: parseField<RuleEvent[]>(rule.whenEvents, []),
    targetField: rule.targetField ?? '',
    formula:
      typeof body.formula === 'string'
        ? body.formula
        : typeof body.expression === 'string'
          ? body.expression
          : '',
    errorMessage:
      typeof body.errorMessage === 'string'
        ? body.errorMessage
        : typeof body.message === 'string'
          ? body.message
          : '',
    enforce: body.enforce === 'warn' ? 'warn' : 'block',
    transformType:
      transformSpec === 'lower' ||
      transformSpec === 'trim' ||
      transformSpec === 'titleCase' ||
      transformSpec === 'formula'
        ? transformSpec
        : 'upper',
    sortOrder: rule.sortOrder,
  }
}

function draftToBody(draft: DraftRule): Record<string, unknown> {
  switch (draft.kind) {
    case 'COMPUTE':
      return { formula: draft.formula }
    case 'DEFAULT':
      return { formula: draft.formula }
    case 'VALIDATE':
      return {
        formula: draft.formula,
        errorMessage: draft.errorMessage,
        enforce: draft.enforce,
      }
    case 'TRANSFORM':
      return {
        transform:
          draft.transformType === 'formula'
            ? { type: 'formula', formula: draft.formula }
            : { type: draft.transformType },
      }
    case 'SCRIPT':
      return {
        expression: draft.formula,
        ...(draft.errorMessage.trim() ? { message: draft.errorMessage } : {}),
      }
  }
}

function syntaxError(formula: string): string | null {
  if (!formula.trim()) return null
  try {
    evaluator.validate(formula)
    return null
  } catch (err) {
    return err instanceof FormulaException ? (err as FormulaException).message : 'Invalid formula'
  }
}

export function RulesEditor({ layoutId, layoutName, fieldNames, onClose }: RulesEditorProps) {
  const { apiClient } = useApi()
  const qc = useQueryClient()

  const { data: rules = [], isLoading } = useQuery({
    queryKey: ['layout-rules', layoutId],
    queryFn: async () => {
      try {
        const list = await apiClient.getList<ApiLayoutRule>(
          `/api/layout-rules?filter[layoutId][eq]=${layoutId}&sort=sortOrder&page[size]=200`
        )
        return list
      } catch {
        return []
      }
    },
  })

  const [draft, setDraft] = useState<DraftRule | null>(null)
  const [cycleError, setCycleError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState<{ id: string; name: string } | null>(null)

  const saveMutation = useMutation({
    mutationFn: async (d: DraftRule) => {
      const body = {
        layoutId,
        name: d.name,
        description: d.description || undefined,
        kind: d.kind,
        active: d.active,
        whenEvents: d.whenEvents,
        targetField: d.targetField || null,
        body: draftToBody(d),
        sortOrder: d.sortOrder,
      }
      const envelope = {
        data: {
          type: 'layout-rules',
          ...(d.id ? { id: d.id } : {}),
          attributes: body,
        },
      }
      if (d.id) {
        return apiClient.patch(`/api/layout-rules/${d.id}`, envelope)
      }
      return apiClient.post('/api/layout-rules', envelope)
    },
    // Await the refetch before clearing the draft so the side panel reflects
    // the new rule before the editor pane goes back to the empty state.
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['layout-rules', layoutId] })
      await qc.refetchQueries({ queryKey: ['layout-rules', layoutId] })
      setDraft(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: async (id: string) => apiClient.delete(`/api/layout-rules/${id}`),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['layout-rules', layoutId] })
      await qc.refetchQueries({ queryKey: ['layout-rules', layoutId] })
    },
  })

  const saveError = saveMutation.error as Error | null
  const deleteError = deleteMutation.error as Error | null

  // Intentionally depend only on draft?.formula — recomputing on any draft
  // mutation would thrash the memo for every keystroke on unrelated fields.
  /* eslint-disable react-hooks/preserve-manual-memoization, react-hooks/exhaustive-deps */
  const formulaError = useMemo(() => (draft ? syntaxError(draft.formula) : null), [draft?.formula])
  /* eslint-enable react-hooks/preserve-manual-memoization, react-hooks/exhaustive-deps */

  // Cycle check across the saved set + the in-flight draft. We deliberately
  // setState inside the effect to react to changes in `rules` or `draft` —
  // refactoring this to useMemo would require duplicating the dependency
  // tracking of the cycle-detection algorithm.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!draft) {
      setCycleError(null)
      return
    }
    if (draft.kind !== 'COMPUTE' && draft.kind !== 'DEFAULT') {
      setCycleError(null)
      return
    }
    if (formulaError || !draft.targetField || !draft.formula.trim()) {
      setCycleError(null)
      return
    }
    let deps: string[] = []
    try {
      deps = evaluator.extractFieldRefs(draft.formula)
    } catch {
      setCycleError(null)
      return
    }
    const nodes: RuleNode[] = rules
      .filter(
        (r) => (r.kind === 'COMPUTE' || r.kind === 'DEFAULT') && r.id !== draft.id && r.targetField
      )
      .map((r) => {
        const body = parseField<Record<string, unknown>>(r.body, {})
        const formula = typeof body.formula === 'string' ? body.formula : ''
        let formDeps: string[] = []
        try {
          if (formula) formDeps = evaluator.extractFieldRefs(formula)
        } catch {
          formDeps = []
        }
        return { id: r.id, target: r.targetField as string, dependsOn: formDeps }
      })
    nodes.push({ id: draft.id ?? '__draft__', target: draft.targetField, dependsOn: deps })
    const result = topologicalSort(nodes)
    setCycleError(result.ok ? null : `Cycle detected: ${result.cycle.join(' -> ')}`)
  }, [draft, rules, formulaError])
  /* eslint-enable react-hooks/set-state-in-effect */

  const canSave =
    !!draft &&
    !!draft.name.trim() &&
    !!draft.kind &&
    (draft.kind === 'TRANSFORM' || !!draft.formula.trim()) &&
    !formulaError &&
    !cycleError &&
    (draft.kind !== 'TRANSFORM' || !!draft.targetField) &&
    ((draft.kind !== 'COMPUTE' && draft.kind !== 'DEFAULT') || !!draft.targetField) &&
    (draft.kind !== 'VALIDATE' || !!draft.errorMessage.trim())

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent
        showCloseButton={false}
        aria-describedby={undefined}
        className="flex max-h-[90vh] flex-col gap-0 overflow-hidden p-0 sm:max-w-[900px]"
        data-testid="rules-editor-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <DialogTitle className="m-0 text-xl font-semibold text-foreground">
            Client rules — {layoutName}
          </DialogTitle>
          <DialogClose asChild>
            <button
              type="button"
              className="cursor-pointer rounded border-none bg-transparent p-2 text-2xl leading-none text-muted-foreground"
              aria-label="Close"
              data-testid="rules-editor-close"
            >
              &times;
            </button>
          </DialogClose>
        </div>

        <div className="grid min-h-0 flex-1 grid-cols-[300px_1fr] overflow-hidden">
          {/* Rule list */}
          <aside className="overflow-y-auto border-r border-border p-4">
            <div className="mb-2 flex items-center justify-between">
              <h3 className="m-0 text-sm font-semibold text-foreground">Rules ({rules.length})</h3>
            </div>
            <div className="mb-3 grid grid-cols-2 gap-1">
              {(['COMPUTE', 'VALIDATE', 'DEFAULT', 'TRANSFORM', 'SCRIPT'] as RuleKind[]).map(
                (k) => (
                  <button
                    key={k}
                    type="button"
                    className="cursor-pointer rounded border border-border bg-background px-2 py-1 text-xs text-foreground hover:bg-muted"
                    onClick={() => setDraft(emptyDraft(k, rules.length * 10))}
                    data-testid={`rules-editor-add-${k.toLowerCase()}`}
                  >
                    + {RULE_KIND_LABEL[k]}
                  </button>
                )
              )}
            </div>

            {isLoading ? (
              <div className="text-xs text-muted-foreground">Loading…</div>
            ) : rules.length === 0 ? (
              <div className="text-xs text-muted-foreground">No rules yet</div>
            ) : (
              <ul className="m-0 flex list-none flex-col gap-1 p-0">
                {rules.map((r) => (
                  <li key={r.id} className="m-0">
                    <button
                      type="button"
                      onClick={() => setDraft(apiRuleToDraft(r))}
                      className={cn(
                        'flex w-full cursor-pointer flex-col items-start gap-0.5 rounded border border-transparent bg-transparent px-2 py-1.5 text-left hover:bg-muted',
                        draft?.id === r.id && 'border-primary bg-muted'
                      )}
                      data-testid={`rules-editor-rule-${r.id}`}
                    >
                      <span className="text-sm font-medium text-foreground">
                        {r.name}
                        {!r.active && ' (inactive)'}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {RULE_KIND_LABEL[r.kind]}
                        {r.targetField ? ` → ${r.targetField}` : ''}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </aside>

          {/* Editor pane */}
          <div className="overflow-y-auto p-6">
            {!draft ? (
              <div className="text-sm text-muted-foreground">
                Pick a rule to edit, or create a new one.
              </div>
            ) : (
              <RuleForm
                draft={draft}
                onChange={setDraft}
                fieldNames={fieldNames}
                formulaError={formulaError}
                cycleError={cycleError}
              />
            )}

            {(saveError || deleteError) && (
              <div
                className="mt-4 rounded border border-destructive bg-destructive/10 p-2 text-xs text-destructive"
                role="alert"
                data-testid="rules-editor-server-error"
              >
                {(saveError ?? deleteError)?.message ?? 'Operation failed'}
              </div>
            )}

            {draft && (
              <div className="mt-6 flex items-center justify-end gap-3 border-t border-border pt-4">
                {draft.id && (
                  <button
                    type="button"
                    className="cursor-pointer rounded border border-destructive bg-transparent px-3 py-1.5 text-sm text-destructive hover:bg-destructive/10"
                    onClick={() => {
                      if (draft.id) setPendingDelete({ id: draft.id, name: draft.name })
                    }}
                    data-testid="rules-editor-delete"
                  >
                    Delete
                  </button>
                )}
                <button
                  type="button"
                  className="cursor-pointer rounded border border-border bg-transparent px-3 py-1.5 text-sm text-muted-foreground"
                  onClick={() => setDraft(null)}
                  data-testid="rules-editor-cancel"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className={cn(
                    'cursor-pointer rounded border-none px-3 py-1.5 text-sm text-primary-foreground',
                    canSave
                      ? 'bg-primary hover:bg-primary/90'
                      : 'cursor-not-allowed bg-muted text-muted-foreground'
                  )}
                  disabled={!canSave || saveMutation.isPending}
                  onClick={() => draft && saveMutation.mutate(draft)}
                  data-testid="rules-editor-save"
                >
                  {saveMutation.isPending ? 'Saving…' : 'Save rule'}
                </button>
              </div>
            )}
          </div>
        </div>

        <ConfirmDialog
          open={pendingDelete !== null}
          title="Delete rule"
          message={`Delete rule "${pendingDelete?.name ?? ''}"?`}
          confirmLabel="Delete"
          cancelLabel="Cancel"
          variant="danger"
          onConfirm={() => {
            if (pendingDelete) {
              deleteMutation.mutate(pendingDelete.id)
              setDraft(null)
            }
          }}
          onCancel={() => setPendingDelete(null)}
        />
      </DialogContent>
    </Dialog>
  )
}

interface RuleFormProps {
  draft: DraftRule
  onChange: (next: DraftRule) => void
  fieldNames: string[]
  formulaError: string | null
  cycleError: string | null
}

const ALL_EVENTS: RuleEvent[] = ['onChange', 'onBlur', 'onLoad', 'onBeforeSave']

function RuleForm({ draft, onChange, fieldNames, formulaError, cycleError }: RuleFormProps) {
  const update = <K extends keyof DraftRule>(key: K, value: DraftRule[K]) =>
    onChange({ ...draft, [key]: value })

  const toggleEvent = (e: RuleEvent) => {
    const next = draft.whenEvents.includes(e)
      ? draft.whenEvents.filter((x) => x !== e)
      : [...draft.whenEvents, e]
    update('whenEvents', next)
  }

  const showFormula = draft.kind !== 'TRANSFORM' || draft.transformType === 'formula'
  const showTarget = draft.kind !== 'VALIDATE' || true

  return (
    <div className="flex flex-col gap-4">
      <div>
        <label
          htmlFor="rule-kind"
          className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
        >
          Kind
        </label>
        <select
          id="rule-kind"
          className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
          value={draft.kind}
          onChange={(e) => update('kind', e.target.value as RuleKind)}
        >
          <option value="COMPUTE">Compute</option>
          <option value="VALIDATE">Validate</option>
          <option value="DEFAULT">Default</option>
          <option value="TRANSFORM">Transform</option>
          <option value="SCRIPT">Script</option>
        </select>
      </div>

      <div>
        <label
          htmlFor="rule-name"
          className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
        >
          Name
        </label>
        <input
          id="rule-name"
          type="text"
          className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
          value={draft.name}
          onChange={(e) => update('name', e.target.value)}
          data-testid="rule-name-input"
        />
      </div>

      <div>
        <label
          htmlFor="rule-description"
          className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
        >
          Description
        </label>
        <input
          id="rule-description"
          type="text"
          className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
          value={draft.description}
          onChange={(e) => update('description', e.target.value)}
        />
      </div>

      {showTarget && (
        <div>
          <label
            htmlFor="rule-target"
            className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
          >
            Target field{' '}
            {draft.kind !== 'VALIDATE' && draft.kind !== 'SCRIPT' && (
              <span className="text-destructive">*</span>
            )}
          </label>
          <input
            id="rule-target"
            type="text"
            list="rule-fields"
            className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm font-mono"
            value={draft.targetField}
            onChange={(e) => update('targetField', e.target.value)}
            placeholder={
              draft.kind === 'VALIDATE' || draft.kind === 'SCRIPT'
                ? 'optional — leave blank for form-level'
                : 'field_api_name'
            }
            data-testid="rule-target-input"
          />
          <datalist id="rule-fields">
            {fieldNames.map((n) => (
              <option key={n} value={n} />
            ))}
          </datalist>
        </div>
      )}

      {draft.kind === 'TRANSFORM' && (
        <div>
          <label
            htmlFor="rule-transform-type"
            className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
          >
            Transform type
          </label>
          <select
            id="rule-transform-type"
            className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
            value={draft.transformType}
            onChange={(e) => update('transformType', e.target.value as DraftRule['transformType'])}
          >
            <option value="upper">UPPER</option>
            <option value="lower">lower</option>
            <option value="trim">trim whitespace</option>
            <option value="titleCase">Title Case</option>
            <option value="formula">Formula</option>
          </select>
        </div>
      )}

      {showFormula && (
        <div>
          <label
            htmlFor="rule-formula"
            className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
          >
            {draft.kind === 'SCRIPT' ? 'Expression' : 'Formula'}{' '}
            {draft.kind !== 'TRANSFORM' && <span className="text-destructive">*</span>}
          </label>
          <textarea
            id="rule-formula"
            className={cn(
              'w-full rounded border border-border bg-background px-2 py-1.5 font-mono text-sm',
              formulaError && 'border-destructive'
            )}
            rows={3}
            value={draft.formula}
            onChange={(e) => update('formula', e.target.value)}
            placeholder={
              draft.kind === 'VALIDATE'
                ? 'discount > unit_price * 0.5'
                : draft.kind === 'SCRIPT'
                  ? 'IF(discount > 0.5, "Discount over 50% needs approval", "")'
                  : '(quantity * unit_price) - discount'
            }
            data-testid="rule-formula-input"
          />
          {draft.kind === 'SCRIPT' && (
            <span className="mt-1 block text-xs text-muted-foreground">
              Returns a message string — non-empty blocks submit (on onBeforeSave) or shows live on
              the target field. Return an empty string when valid.
            </span>
          )}
          {formulaError && (
            <span className="mt-1 block text-xs text-destructive" role="alert">
              {formulaError}
            </span>
          )}
        </div>
      )}

      {cycleError && (
        <div
          className="rounded border border-destructive bg-destructive/10 p-2 text-xs text-destructive"
          role="alert"
        >
          {cycleError}
        </div>
      )}

      {draft.kind === 'VALIDATE' && (
        <>
          <div>
            <label
              htmlFor="rule-error-message"
              className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
            >
              Error message <span className="text-destructive">*</span>
            </label>
            <input
              id="rule-error-message"
              type="text"
              className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
              value={draft.errorMessage}
              onChange={(e) => update('errorMessage', e.target.value)}
              data-testid="rule-error-message-input"
            />
          </div>
          <div>
            <label
              htmlFor="rule-enforce"
              className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
            >
              Enforce
            </label>
            <select
              id="rule-enforce"
              className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
              value={draft.enforce}
              onChange={(e) => update('enforce', e.target.value as 'block' | 'warn')}
            >
              <option value="block">Block save</option>
              <option value="warn">Warn (allow save)</option>
            </select>
          </div>
        </>
      )}

      {draft.kind === 'SCRIPT' && (
        <div>
          <label
            htmlFor="rule-script-message"
            className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
          >
            Fallback message
          </label>
          <input
            id="rule-script-message"
            type="text"
            className="w-full rounded border border-border bg-background px-2 py-1.5 text-sm"
            value={draft.errorMessage}
            onChange={(e) => update('errorMessage', e.target.value)}
            placeholder="Used when the expression returns true instead of a message string"
            data-testid="rule-script-message-input"
          />
        </div>
      )}

      <div>
        <span className="mb-1 block text-xs font-medium uppercase text-muted-foreground">When</span>
        <div className="flex flex-wrap gap-2">
          {ALL_EVENTS.map((e) => (
            <label key={e} className="flex cursor-pointer items-center gap-1 text-sm">
              <input
                type="checkbox"
                checked={draft.whenEvents.includes(e)}
                onChange={() => toggleEvent(e)}
                data-testid={`rule-event-${e}`}
              />
              {e}
            </label>
          ))}
        </div>
      </div>

      <div>
        <label className="flex cursor-pointer items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.active}
            onChange={(e) => update('active', e.target.checked)}
          />
          Active
        </label>
      </div>

      <div>
        <label
          htmlFor="rule-sort-order"
          className="mb-1 block text-xs font-medium uppercase text-muted-foreground"
        >
          Sort order
        </label>
        <input
          id="rule-sort-order"
          type="number"
          className="w-32 rounded border border-border bg-background px-2 py-1.5 text-sm"
          value={draft.sortOrder}
          onChange={(e) => update('sortOrder', Number(e.target.value) || 0)}
        />
      </div>
    </div>
  )
}
