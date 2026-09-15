import type React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LayoutFieldSections } from './LayoutFieldSections'
import type { LayoutSectionDto, LayoutFieldPlacementDto } from '@/hooks/usePageLayout'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

vi.mock('@/context/I18nContext', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))
// Real FieldSection lives in @kelta/components; stub it to just call through
// to `renderField` for each field so this test can inspect what the caller
// (LayoutFieldSections) actually hands to FieldRenderer/InlineFieldValue.
vi.mock('@/components/detail', () => ({
  FieldSection: ({
    fields,
    record,
    renderField,
  }: {
    fields: FieldDefinition[]
    record: Record<string, unknown>
    renderField: (ctx: {
      field: FieldDefinition
      value: unknown
      displayLabel?: string
    }) => React.ReactNode
  }) => (
    <section>
      {fields.map((field) => (
        <div key={field.name}>{renderField({ field, value: record[field.name] })}</div>
      ))}
    </section>
  ),
}))
vi.mock('@/components/FieldRenderer', () => ({
  FieldRenderer: ({
    fieldName,
    picklistDisplayMap,
    value,
  }: {
    fieldName: string
    value: unknown
    picklistDisplayMap?: Map<string, { label: string; color?: string }>
  }) => (
    <span>
      view:{fieldName}={picklistDisplayMap?.get(String(value))?.label ?? String(value)}
    </span>
  ),
}))
vi.mock('@/components/record/InlineFieldValue', () => ({
  InlineFieldValue: ({
    field,
    picklistDisplayMap,
    value,
  }: {
    field: { name: string }
    value: unknown
    picklistDisplayMap?: Map<string, { label: string; color?: string }>
  }) => (
    <span>
      inline:{field.name}={picklistDisplayMap?.get(String(value))?.label ?? String(value)}
    </span>
  ),
}))

function placement(overrides: Partial<LayoutFieldPlacementDto>): LayoutFieldPlacementDto {
  return {
    id: 'p1',
    fieldId: 'f1',
    fieldName: 'stage',
    fieldType: 'picklist',
    fieldDisplayName: 'Stage',
    columnNumber: 0,
    columnSpan: 1,
    sortOrder: 0,
    requiredOnLayout: false,
    readOnlyOnLayout: false,
    labelOverride: null,
    helpTextOverride: null,
    visibilityRule: null,
    ...overrides,
  }
}

function section(overrides: Partial<LayoutSectionDto>): LayoutSectionDto {
  return {
    id: 's1',
    heading: 'Overview',
    columns: 2,
    sortOrder: 0,
    collapsed: false,
    style: 'CARD',
    sectionType: 'FIELDS',
    tabGroup: null,
    tabLabel: null,
    visibilityRule: null,
    fields: [placement({})],
    ...overrides,
  }
}

const schemaFields: FieldDefinition[] = [
  { id: 'f1', name: 'stage', displayName: 'Stage', type: 'picklist' } as FieldDefinition,
]

const picklistDisplayMaps = {
  stage: new Map([['in_progress', { label: 'In progress', color: '#F59E0B' }]]),
}

describe('LayoutFieldSections picklist display maps', () => {
  it('passes the field-specific display map into the read view', () => {
    render(
      <LayoutFieldSections
        sections={[section({})]}
        schemaFields={schemaFields}
        record={{ id: 'r1', stage: 'in_progress' }}
        picklistDisplayMaps={picklistDisplayMaps}
      />
    )
    expect(screen.getByText('view:stage=In progress')).toBeInTheDocument()
  })

  it('passes the field-specific display map into the inline editor', () => {
    render(
      <LayoutFieldSections
        sections={[section({})]}
        schemaFields={schemaFields}
        record={{ id: 'r1', stage: 'in_progress' }}
        picklistDisplayMaps={picklistDisplayMaps}
        editable
        onFieldCommit={async () => {}}
      />
    )
    expect(screen.getByText('inline:stage=In progress')).toBeInTheDocument()
  })
})
