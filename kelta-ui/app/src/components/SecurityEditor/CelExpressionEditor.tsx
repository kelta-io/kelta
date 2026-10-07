/**
 * CelExpressionEditor Component
 *
 * A text editor for CEL (Common Expression Language) expressions used in
 * custom Cerbos authorization policies. Provides syntax help and
 * validation feedback.
 */

import React, { useCallback } from 'react'
import { AlertCircle, Info } from 'lucide-react'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

export interface CelExpressionEditorProps {
  /** Current CEL expression */
  value: string
  /** Callback when expression changes */
  onChange: (value: string) => void
  /** Validation error message */
  error?: string | null
  /** Whether the editor is read-only */
  readOnly?: boolean
  /** Test ID for the component */
  testId?: string
}

const CEL_HELP = [
  { variable: 'R.attr.<fieldName>', description: 'Record attribute value' },
  {
    variable: 'P.attr.userId',
    description: 'Current user ID — compare with createdBy or a user lookup',
  },
  { variable: 'P.attr.profileId', description: 'Current user profile ID' },
  { variable: 'P.attr.tenantId', description: 'Current tenant ID' },
  { variable: 'P.id', description: 'Current user email — compare only with email fields' },
]

const CEL_EXAMPLES = [
  { label: 'Deny when status is closed', expr: 'R.attr.status == "closed"' },
  { label: 'Restrict to own records', expr: 'R.attr.createdBy == P.attr.userId' },
  { label: 'Region restriction', expr: 'R.attr.region in ["US", "EU"]' },
  { label: 'Amount threshold', expr: 'double(R.attr.amount) > 10000.0' },
]

export function CelExpressionEditor({
  value,
  onChange,
  error,
  readOnly = false,
  testId = 'cel-expression-editor',
}: CelExpressionEditorProps): React.ReactElement {
  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      onChange(e.target.value)
    },
    [onChange]
  )

  return (
    <div className="space-y-3" data-testid={testId}>
      <div>
        <Label htmlFor="cel-expression" className="mb-1.5 text-sm font-medium text-foreground">
          CEL Expression
        </Label>
        <Textarea
          id="cel-expression"
          value={value}
          onChange={handleChange}
          readOnly={readOnly}
          placeholder='R.attr.status == "closed"'
          className="font-mono text-sm"
          rows={3}
          data-testid={`${testId}-input`}
        />
        {error && (
          <div className="mt-1 flex items-center gap-1 text-xs text-destructive">
            <AlertCircle size={12} />
            {error}
          </div>
        )}
      </div>

      {/* Help section */}
      <details className="text-sm">
        <summary className="flex cursor-pointer items-center gap-1 text-muted-foreground hover:text-foreground">
          <Info size={14} />
          CEL Syntax Help
        </summary>
        <div className="mt-2 space-y-3 rounded-lg border border-border bg-muted/50 p-3">
          <div>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Available Variables
            </h4>
            <div className="space-y-1">
              {CEL_HELP.map((item) => (
                <div key={item.variable} className="flex items-baseline gap-2 text-xs">
                  <code className="rounded bg-muted px-1 py-0.5 font-mono text-foreground">
                    {item.variable}
                  </code>
                  <span className="text-muted-foreground">{item.description}</span>
                </div>
              ))}
            </div>
          </div>

          <div>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Examples
            </h4>
            <div className="space-y-1">
              {CEL_EXAMPLES.map((example) => (
                <div key={example.expr} className="flex items-baseline gap-2 text-xs">
                  <button
                    type="button"
                    onClick={() => onChange(example.expr)}
                    disabled={readOnly}
                    title={readOnly ? undefined : 'Use this expression'}
                    className="rounded bg-muted px-1 py-0.5 font-mono text-foreground enabled:hover:bg-accent disabled:cursor-default"
                    data-testid={`${testId}-example`}
                  >
                    {example.expr}
                  </button>
                  <span className="text-muted-foreground">{example.label}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </details>
    </div>
  )
}

export default CelExpressionEditor
