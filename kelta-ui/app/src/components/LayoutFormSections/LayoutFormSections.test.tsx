import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { LayoutFormSections, type LayoutFormFieldDefinition } from './LayoutFormSections'
import type { LayoutSectionDto, LayoutFieldPlacementDto } from '@/hooks/usePageLayout'

const schemaFields: LayoutFormFieldDefinition[] = [
  { id: 'f1', name: 'name', displayName: 'Name', type: 'string', required: false },
  { id: 'f2', name: 'total', displayName: 'Total', type: 'currency', required: false },
  { id: 'f3', name: 'qty', displayName: 'Qty', type: 'number', required: false },
  {
    id: 'f4',
    name: 'veto_window',
    displayName: 'Veto window',
    type: 'number',
    required: false,
    description: '0 = auto-merge, 1 = 24 h veto',
  },
]

function placement(
  overrides: Partial<LayoutFieldPlacementDto> & { id: string; fieldId: string; fieldName: string }
): LayoutFieldPlacementDto {
  return {
    fieldType: 'string',
    fieldDisplayName: overrides.fieldName,
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

function section(
  fields: LayoutFieldPlacementDto[],
  overrides: Partial<LayoutSectionDto> = {}
): LayoutSectionDto {
  return {
    id: overrides.id ?? 'section-1',
    heading: overrides.heading ?? 'Details',
    columns: overrides.columns ?? 2,
    sortOrder: overrides.sortOrder ?? 0,
    collapsed: false,
    style: 'default',
    sectionType: 'fields',
    tabGroup: null,
    tabLabel: null,
    visibilityRule: overrides.visibilityRule ?? null,
    fields,
  }
}

const renderInput = (field: LayoutFormFieldDefinition) => (
  <input data-testid={`input-${field.name}`} defaultValue="" />
)

describe('LayoutFormSections', () => {
  it('renders fields under their section heading', () => {
    const sections = [section([placement({ id: 'p1', fieldId: 'f1', fieldName: 'name' })])]
    render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
      />
    )
    expect(screen.getByText('Details')).toBeInTheDocument()
    expect(screen.getByTestId('input-name')).toBeInTheDocument()
  })

  it('disables inputs in fields marked readOnlyOnLayout via fieldset', () => {
    const sections = [
      section([
        placement({
          id: 'p1',
          fieldId: 'f2',
          fieldName: 'total',
          readOnlyOnLayout: true,
        }),
        placement({
          id: 'p2',
          fieldId: 'f1',
          fieldName: 'name',
          sortOrder: 1,
        }),
      ]),
    ]
    render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
      />
    )
    const readOnlyFieldset = screen
      .getByTestId('input-total')
      .closest('fieldset') as HTMLFieldSetElement
    const editableFieldset = screen
      .getByTestId('input-name')
      .closest('fieldset') as HTMLFieldSetElement
    expect(readOnlyFieldset.disabled).toBe(true)
    expect(editableFieldset.disabled).toBe(false)
  })

  it('renders helpTextOverride below the field', () => {
    const sections = [
      section([
        placement({
          id: 'p1',
          fieldId: 'f1',
          fieldName: 'name',
          helpTextOverride: 'Enter your full legal name',
        }),
      ]),
    ]
    render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
      />
    )
    expect(screen.getByTestId('layout-field-help-name')).toHaveTextContent(
      'Enter your full legal name'
    )
  })

  it('renders the field description as help text when no layout override is set', () => {
    const sections = [
      section([
        placement({
          id: 'p1',
          fieldId: 'f4',
          fieldName: 'veto_window',
        }),
      ]),
    ]
    render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
      />
    )
    expect(screen.getByTestId('layout-field-help-veto_window')).toHaveTextContent(
      '0 = auto-merge, 1 = 24 h veto'
    )
  })

  it('prefers the layout helpTextOverride over the field description', () => {
    const sections = [
      section([
        placement({
          id: 'p1',
          fieldId: 'f4',
          fieldName: 'veto_window',
          helpTextOverride: 'Custom override text',
        }),
      ]),
    ]
    render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
      />
    )
    expect(screen.getByTestId('layout-field-help-veto_window')).toHaveTextContent(
      'Custom override text'
    )
  })

  it('applies columnSpan via inline gridColumn style', () => {
    const sections = [
      section(
        [
          placement({
            id: 'p1',
            fieldId: 'f1',
            fieldName: 'name',
            columnSpan: 2,
          }),
        ],
        { columns: 2 }
      ),
    ]
    render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
      />
    )
    const cell = screen.getByTestId('layout-field-cell-name')
    expect(cell.style.gridColumn).toBe('span 2')
  })

  it('hides fields when visibilityRule does not match the record', () => {
    const sections = [
      section([
        placement({
          id: 'p1',
          fieldId: 'f1',
          fieldName: 'name',
        }),
        placement({
          id: 'p2',
          fieldId: 'f2',
          fieldName: 'total',
          sortOrder: 1,
          visibilityRule: JSON.stringify({
            fieldName: 'qty',
            operator: 'EQUALS',
            value: '5',
          }),
        }),
      ]),
    ]
    const { rerender } = render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
        record={{ qty: 1 }}
      />
    )
    expect(screen.getByTestId('input-name')).toBeInTheDocument()
    expect(screen.queryByTestId('input-total')).toBeNull()

    rerender(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
        record={{ qty: 5 }}
      />
    )
    expect(screen.getByTestId('input-total')).toBeInTheDocument()
  })

  it('shows all fields when no record is supplied (visibility rules ignored)', () => {
    const sections = [
      section([
        placement({
          id: 'p1',
          fieldId: 'f2',
          fieldName: 'total',
          visibilityRule: JSON.stringify({
            fieldName: 'qty',
            operator: 'EQUALS',
            value: '5',
          }),
        }),
      ]),
    ]
    render(
      <LayoutFormSections
        sections={sections}
        schemaFields={schemaFields}
        renderField={renderInput}
      />
    )
    expect(screen.getByTestId('input-total')).toBeInTheDocument()
  })
})
