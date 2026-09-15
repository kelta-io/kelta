/**
 * Shared building blocks for the FieldControl registry: a parity-locked `View` (delegates to the
 * existing `FieldRenderer`, so plugin overrides + formatting stay identical), a generic inline-edit
 * wrapper, and coerce/validate helpers mirroring `DefaultValidationEngine` semantics.
 */
/* eslint-disable react-refresh/only-export-components -- field-control module mixes components + helpers by design */
import React, { useEffect, useState } from 'react'
import { FieldRenderer } from '@/components/FieldRenderer/FieldRenderer'
import type { FieldControlContext, FieldEditProps, FieldInlineProps, FieldViewProps } from './types'

/**
 * The canonical read view — delegates to `FieldRenderer` so every control renders exactly as today
 * (and inherits its plugin-override + null-handling). This is what keeps the golden snapshot green.
 */
export function FieldControlView({
  type,
  value,
  ctx,
  truncate,
  className,
}: FieldViewProps): React.ReactElement {
  return (
    <FieldRenderer
      type={type}
      value={value}
      fieldName={ctx.fieldName}
      displayName={ctx.displayName}
      tenantSlug={ctx.tenantSlug}
      targetCollection={ctx.targetCollection}
      displayLabel={ctx.displayLabel}
      truncate={truncate}
      className={className}
      picklistDisplayMap={ctx.picklistDisplayMap}
    />
  )
}

/** Coerce empty-string to null; leave everything else untouched. */
export function emptyToNull(raw: unknown): unknown {
  if (raw === '' || raw === undefined) return null
  return raw
}

/** Required-field check shared by every control. */
export function requiredError(value: unknown, ctx: FieldControlContext): string | null {
  if (!ctx.required) return null
  const empty =
    value === null ||
    value === undefined ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  return empty ? `${ctx.displayName ?? ctx.fieldName ?? 'This field'} is required` : null
}

/**
 * Wrap any `Edit` component with commit-on-Enter/blur, cancel-on-Escape semantics so a single
 * generic path powers grid/detail inline editing. Local draft state is coerced on commit.
 */
export function makeInlineEdit(
  Edit: React.ComponentType<FieldEditProps>,
  coerce: (raw: unknown) => unknown
): React.ComponentType<FieldInlineProps> {
  function InlineEdit({
    onCommit,
    onCancel,
    value,
    ...rest
  }: FieldInlineProps): React.ReactElement {
    const [draft, setDraft] = useState<unknown>(value)

    // Reset the draft if the upstream value changes.
    useEffect(() => {
      setDraft(value)
    }, [value])

    // Recreated each render, so it closes over the latest `draft`.
    const commit = (): void => onCommit(coerce(draft))

    return (
      // The wrapper only forwards keyboard shortcuts to the already-interactive child control.
      // eslint-disable-next-line jsx-a11y/no-static-element-interactions
      <div
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            commit()
          } else if (e.key === 'Escape') {
            e.preventDefault()
            onCancel()
          }
        }}
      >
        <Edit
          {...rest}
          value={draft}
          onChange={(next) => {
            setDraft(next)
            rest.onChange?.(next)
          }}
          onBlur={() => {
            rest.onBlur?.()
            commit()
          }}
        />
      </div>
    )
  }
  return InlineEdit
}

/** A no-op inline editor for non-editable (server-computed) types — renders the read view. */
export function makeReadOnlyInline(
  View: React.ComponentType<FieldViewProps>
): React.ComponentType<FieldInlineProps> {
  function ReadOnlyInline({ type, value, ctx }: FieldInlineProps): React.ReactElement {
    return <View type={type} value={value} ctx={ctx} />
  }
  return ReadOnlyInline
}
