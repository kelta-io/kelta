/**
 * LayoutFormSections Component
 *
 * Renders form fields organized by a page layout's sections.
 * Each section becomes a collapsible card with the configured
 * column count. Fields are rendered via the parent's renderField
 * callback so all existing form field logic (inputs, validation,
 * picklists, lookups, custom renderers) is reused unchanged.
 *
 * Layout-level overrides applied:
 * - labelOverride        → replaces displayName for the form label
 * - requiredOnLayout     → marks the field required even if schema says optional
 * - readOnlyOnLayout     → exposed on the resolved field as `readOnly`; consumers
 *                          either honor it directly or rely on the wrapping
 *                          <fieldset disabled> emitted by this component
 * - helpTextOverride     → renders as a small muted line under each field
 * - columnSpan           → applies inline `gridColumn: span N` to the field cell
 * - visibilityRule       → filters fields and sections per record (when supplied)
 */

import React, { useState, useMemo } from 'react'
import { ChevronDown } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { cn } from '@/lib/utils'
import { isVisible } from '@kelta/components'
import type { LayoutSectionDto, LayoutFieldPlacementDto } from '@/hooks/usePageLayout'

export interface LayoutFormFieldDefinition {
  id: string
  name: string
  displayName?: string
  type: string
  required: boolean
  readOnly?: boolean
  helpText?: string
  description?: string
  columnSpan?: number
  [key: string]: unknown
}

export interface LayoutFormSectionsProps {
  /** Sections from the resolved page layout */
  sections: LayoutSectionDto[]
  /** Full collection schema fields for resolving placements */
  schemaFields: LayoutFormFieldDefinition[]
  /** Render callback for a single form field — reuses the parent's renderField */
  renderField: (field: LayoutFormFieldDefinition, index: number) => React.ReactNode
  /**
   * Optional record values used for visibility-rule evaluation. When omitted
   * (e.g. create form with no values yet), all visibility rules pass.
   */
  record?: Record<string, unknown>
  /**
   * Optional callback that returns true when the field is the target of a
   * client-side compute rule. Computed targets render as read-only with a
   * small "ƒ" adornment so users know the value is derived.
   */
  isComputed?: (fieldName: string) => boolean
}

interface ResolvedField {
  field: LayoutFormFieldDefinition
  placement: LayoutFieldPlacementDto
}

/**
 * Resolves layout placements to schema fields, applying layout overrides.
 *
 * Fields are interleaved by column so that CSS grid auto-placement (left-to-right,
 * top-to-bottom) puts each field in the correct designer-assigned column.
 */
function resolvePlacements(
  placements: LayoutFieldPlacementDto[],
  fieldsByName: Map<string, LayoutFormFieldDefinition>,
  fieldsById: Map<string, LayoutFormFieldDefinition>,
  columns: number,
  record: Record<string, unknown> | undefined,
  isComputed?: (fieldName: string) => boolean
): ResolvedField[] {
  const visiblePlacements = record
    ? placements.filter((p) => isVisible(p.visibilityRule, record))
    : placements

  const columnGroups: LayoutFieldPlacementDto[][] = Array.from({ length: columns }, () => [])
  for (const p of visiblePlacements) {
    const col = Math.min(p.columnNumber, columns - 1)
    columnGroups[col].push(p)
  }
  for (const group of columnGroups) {
    group.sort((a, b) => a.sortOrder - b.sortOrder)
  }

  const interleaved: LayoutFieldPlacementDto[] = []
  const maxRows = Math.max(...columnGroups.map((g) => g.length), 0)
  for (let row = 0; row < maxRows; row++) {
    for (let col = 0; col < columns; col++) {
      if (row < columnGroups[col].length) {
        interleaved.push(columnGroups[col][row])
      }
    }
  }

  return interleaved
    .map((placement) => {
      const schemaField = fieldsById.get(placement.fieldId) || fieldsByName.get(placement.fieldName)
      if (!schemaField) return null

      const computed = isComputed?.(schemaField.name) ?? false
      const resolved: LayoutFormFieldDefinition = {
        ...schemaField,
        displayName: placement.labelOverride || schemaField.displayName || schemaField.name,
        required: placement.requiredOnLayout || schemaField.required,
        readOnly: computed || placement.readOnlyOnLayout || schemaField.readOnly,
        helpText: placement.helpTextOverride || schemaField.description,
        columnSpan: placement.columnSpan ?? 1,
        isComputedField: computed,
      }
      return { field: resolved, placement }
    })
    .filter((f): f is ResolvedField => f !== null)
}

function FormSection({
  section,
  resolved,
  renderField,
  startIndex,
}: {
  section: LayoutSectionDto
  resolved: ResolvedField[]
  renderField: (field: LayoutFormFieldDefinition, index: number) => React.ReactNode
  startIndex: number
}) {
  const [isOpen, setIsOpen] = useState(!section.collapsed)
  const columns = (section.columns as 1 | 2 | 3) || 1

  if (resolved.length === 0) return null

  return (
    <Collapsible open={isOpen} onOpenChange={setIsOpen}>
      <Card>
        <CollapsibleTrigger asChild>
          <CardHeader className="cursor-pointer select-none py-3">
            <CardTitle className="flex items-center gap-2 text-sm font-medium">
              <ChevronDown
                className={cn(
                  'h-4 w-4 text-muted-foreground transition-transform',
                  isOpen && 'rotate-0',
                  !isOpen && '-rotate-90'
                )}
              />
              {section.heading || 'Details'}
              <span className="text-xs font-normal text-muted-foreground">
                ({resolved.length} field{resolved.length !== 1 ? 's' : ''})
              </span>
            </CardTitle>
          </CardHeader>
        </CollapsibleTrigger>

        <CollapsibleContent>
          <CardContent className="pt-0">
            <div
              className={cn(
                'grid grid-cols-1 gap-6',
                columns === 2 && 'md:grid-cols-2',
                columns >= 3 && 'md:grid-cols-2 lg:grid-cols-3'
              )}
            >
              {resolved.map(({ field }, i) => {
                const span = Math.min(field.columnSpan ?? 1, columns)
                const cellStyle = span > 1 ? { gridColumn: `span ${span}` } : undefined
                return (
                  <div
                    key={field.id}
                    style={cellStyle}
                    data-testid={`layout-field-cell-${field.name}`}
                  >
                    {/* fieldset[disabled] disables every nested form control natively */}
                    <fieldset
                      disabled={!!field.readOnly}
                      className="m-0 flex min-w-0 flex-col gap-1 border-0 p-0"
                    >
                      {renderField(field, startIndex + i)}
                      {field.helpText && (
                        <p
                          className="mt-1 text-xs text-muted-foreground"
                          data-testid={`layout-field-help-${field.name}`}
                        >
                          {field.helpText}
                        </p>
                      )}
                    </fieldset>
                  </div>
                )
              })}
            </div>
          </CardContent>
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}

export function LayoutFormSections({
  sections,
  schemaFields,
  renderField,
  record,
  isComputed,
}: LayoutFormSectionsProps): React.ReactElement {
  const { fieldsByName, fieldsById } = useMemo(() => {
    const byName = new Map<string, LayoutFormFieldDefinition>()
    const byId = new Map<string, LayoutFormFieldDefinition>()
    for (const field of schemaFields) {
      byName.set(field.name, field)
      byId.set(field.id, field)
    }
    return { fieldsByName: byName, fieldsById: byId }
  }, [schemaFields])

  const resolvedSections = useMemo(() => {
    const visibleSections = record
      ? sections.filter((s) => isVisible(s.visibilityRule, record))
      : sections
    const sorted = [...visibleSections].sort((a, b) => a.sortOrder - b.sortOrder)
    const result: {
      section: LayoutSectionDto
      resolved: ResolvedField[]
      startIndex: number
    }[] = []
    sorted.reduce((acc, section) => {
      const resolved = resolvePlacements(
        section.fields,
        fieldsByName,
        fieldsById,
        section.columns || 1,
        record,
        isComputed
      )
      result.push({ section, resolved, startIndex: acc })
      return acc + resolved.length
    }, 0)
    return result
  }, [sections, fieldsByName, fieldsById, record, isComputed])

  return (
    <div className="flex flex-col gap-4">
      {resolvedSections.map(({ section, resolved, startIndex }) => (
        <FormSection
          key={section.id}
          section={section}
          resolved={resolved}
          renderField={renderField}
          startIndex={startIndex}
        />
      ))}
    </div>
  )
}
