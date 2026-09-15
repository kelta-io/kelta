import type React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LayoutFieldSections } from './LayoutFieldSections'
import { sectionAnchorId, resolveSectionNavItems } from './sectionNavItems'
import type { LayoutSectionDto, LayoutFieldPlacementDto } from '@/hooks/usePageLayout'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'

vi.mock('@/context/I18nContext', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))
// FieldSection comes from @kelta/components whose workspace carries its own
// React copy in this repo layout — stub it to keep the render single-React,
// while still calling through to renderLabel/renderField so this test can
// inspect what LayoutFieldSections hands it per field.
vi.mock('@/components/detail', () => ({
  FieldSection: ({
    title,
    fields,
    record,
    renderField,
    renderLabel,
  }: {
    title: string
    fields: FieldDefinition[]
    record: Record<string, unknown>
    renderField: (ctx: { field: FieldDefinition; value: unknown }) => React.ReactNode
    renderLabel?: (field: FieldDefinition) => React.ReactNode
  }) => (
    <section>
      {title}
      {fields.map((field) => (
        <div key={field.name}>
          <span>{renderLabel ? renderLabel(field) : field.displayName || field.name}</span>
          {renderField({ field, value: record[field.name] })}
        </div>
      ))}
    </section>
  ),
}))
vi.mock('@/components/FieldRenderer', () => ({
  FieldRenderer: ({ fieldName }: { fieldName: string }) => <span>value:{fieldName}</span>,
}))
vi.mock('@/components/record/InlineFieldValue', () => ({
  InlineFieldValue: ({ field }: { field: { name: string } }) => <span>inline:{field.name}</span>,
}))

function placement(overrides: Partial<LayoutFieldPlacementDto>): LayoutFieldPlacementDto {
  return {
    id: 'p1',
    fieldId: 'f1',
    fieldName: 'title',
    fieldType: 'string',
    fieldDisplayName: 'Title',
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
    fields: [],
    ...overrides,
  }
}

const schemaFields: FieldDefinition[] = [
  { id: 'f1', name: 'title', displayName: 'Title', type: 'string' } as FieldDefinition,
  { id: 'f2', name: 'summary', displayName: 'Summary', type: 'string' } as FieldDefinition,
]

describe('sectionAnchorId', () => {
  it('prefixes the section id', () => {
    expect(sectionAnchorId('abc')).toBe('record-section-abc')
  })
})

describe('resolveSectionNavItems', () => {
  it('returns one entry per section, sorted by sortOrder, with resolved counts', () => {
    const items = resolveSectionNavItems(
      [
        section({
          id: 's2',
          heading: 'Content',
          sortOrder: 1,
          fields: [placement({ id: 'p2', fieldId: 'f2', fieldName: 'summary' })],
        }),
        section({
          id: 's1',
          heading: 'Overview',
          sortOrder: 0,
          fields: [
            placement({}),
            placement({ id: 'p2', fieldId: 'f2', fieldName: 'summary', sortOrder: 1 }),
          ],
        }),
      ],
      schemaFields
    )
    expect(items).toEqual([
      { anchorId: 'record-section-s1', label: 'Overview', count: 2 },
      { anchorId: 'record-section-s2', label: 'Content', count: 1 },
    ])
  })

  it('skips placements whose schema field no longer exists and drops empty sections', () => {
    const items = resolveSectionNavItems(
      [
        section({
          id: 's1',
          fields: [
            placement({}),
            placement({ id: 'p9', fieldId: 'gone', fieldName: 'deleted_field' }),
          ],
        }),
        section({
          id: 's2',
          heading: 'Ghost',
          sortOrder: 1,
          fields: [placement({ id: 'p9', fieldId: 'gone', fieldName: 'deleted_field' })],
        }),
      ],
      schemaFields
    )
    expect(items).toEqual([{ anchorId: 'record-section-s1', label: 'Overview', count: 1 }])
  })

  it('falls back to "Details" for a section without a heading', () => {
    const items = resolveSectionNavItems([section({ heading: '' })], schemaFields)
    expect(items).toEqual([])
    const withField = resolveSectionNavItems(
      [section({ heading: '', fields: [placement({})] })],
      schemaFields
    )
    expect(withField[0].label).toBe('Details')
  })
})

describe('LayoutFieldSections anchors', () => {
  it('wraps each rendered section in its scroll anchor', () => {
    render(
      <LayoutFieldSections
        sections={[section({ fields: [placement({})] })]}
        schemaFields={schemaFields}
        record={{ id: 'r1', title: 'Hello' }}
      />
    )
    const anchor = document.getElementById('record-section-s1')
    expect(anchor).not.toBeNull()
    expect(screen.getByText('Overview')).toBeInTheDocument()
  })
})

describe('LayoutFieldSections help text', () => {
  const schemaFieldsWithDescription: FieldDefinition[] = [
    {
      id: 'f1',
      name: 'title',
      displayName: 'Title',
      type: 'string',
      description: '0 = auto-merge, 1 = 24 h veto',
    } as FieldDefinition,
    { id: 'f2', name: 'summary', displayName: 'Summary', type: 'string' } as FieldDefinition,
  ]

  it('shows a help icon with a tooltip trigger for a field with a description', () => {
    render(
      <LayoutFieldSections
        sections={[section({ fields: [placement({})] })]}
        schemaFields={schemaFieldsWithDescription}
        record={{ id: 'r1', title: 'Hello' }}
      />
    )
    expect(screen.getByTestId('layout-field-help-icon-title')).toBeInTheDocument()
  })

  it('renders no help icon for a field without a description', () => {
    render(
      <LayoutFieldSections
        sections={[section({ fields: [placement({ fieldId: 'f2', fieldName: 'summary' })] })]}
        schemaFields={schemaFieldsWithDescription}
        record={{ id: 'r1', summary: 'Hello' }}
      />
    )
    expect(screen.queryByTestId('layout-field-help-icon-summary')).toBeNull()
  })

  it('prefers the layout helpTextOverride over the field description for the tooltip content', () => {
    render(
      <LayoutFieldSections
        sections={[
          section({
            fields: [
              placement({ fieldId: 'f2', fieldName: 'summary', helpTextOverride: 'Override text' }),
            ],
          }),
        ]}
        schemaFields={schemaFieldsWithDescription}
        record={{ id: 'r1', summary: 'Hello' }}
      />
    )
    expect(screen.getByTestId('layout-field-help-icon-summary')).toBeInTheDocument()
  })
})
