import { useCallback } from 'react';
import type { ReactElement } from 'react';
import type { FilterExpression } from '@kelta/sdk';
import type { FilterBuilderProps } from './types';
import { OPERATORS_BY_TYPE } from './types';

/**
 * FilterBuilder component for building filter expressions.
 *
 * Features:
 * - Displays available fields from schema
 * - Implements add/remove filter UI
 * - Shows appropriate operators for field types
 * - Provides type-appropriate value inputs
 * - Calls onFilterChange callback on changes
 *
 * @example
 * ```tsx
 * <FilterBuilder
 *   fields={schema.fields}
 *   value={filters}
 *   onChange={setFilters}
 *   maxFilters={5}
 * />
 * ```
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major release of
 * @kelta/components. The Kelta UI uses its own filter builder. Copy it into your app if you depend on it.
 */
export function FilterBuilder({
  fields,
  value,
  onChange,
  maxFilters = 10,
  className = '',
  testId = 'kelta-filter-builder',
}: FilterBuilderProps): ReactElement {
  // Add a new filter
  const addFilter = useCallback(() => {
    if (value.length >= maxFilters || fields.length === 0) return;

    const firstField = fields[0];
    const operators = OPERATORS_BY_TYPE[firstField.type] || OPERATORS_BY_TYPE.string;

    const newFilter: FilterExpression = {
      field: firstField.name,
      operator: operators[0].value as FilterExpression['operator'],
      value: '',
    };

    onChange([...value, newFilter]);
  }, [value, fields, maxFilters, onChange]);

  // Remove a filter
  const removeFilter = useCallback(
    (index: number) => {
      const newFilters = value.filter((_, i) => i !== index);
      onChange(newFilters);
    },
    [value, onChange]
  );

  // Update a filter
  const updateFilter = useCallback(
    (index: number, updates: Partial<FilterExpression>) => {
      const newFilters = value.map((filter, i) => {
        if (i !== index) return filter;

        const updated = { ...filter, ...updates };

        // Reset operator if field type changed
        if (updates.field) {
          const field = fields.find((f) => f.name === updates.field);
          if (field) {
            const operators = OPERATORS_BY_TYPE[field.type] || OPERATORS_BY_TYPE.string;
            const currentOperatorValid = operators.some((op) => op.value === filter.operator);
            if (!currentOperatorValid) {
              updated.operator = operators[0].value as FilterExpression['operator'];
              // Reset value when field type changes
              updated.value = '';
            }
          }
        }

        return updated;
      });

      onChange(newFilters);
    },
    [value, fields, onChange]
  );

  // Get operators for a field
  const getOperators = (fieldName: string) => {
    const field = fields.find((f) => f.name === fieldName);
    if (!field) return OPERATORS_BY_TYPE.string;
    return OPERATORS_BY_TYPE[field.type] || OPERATORS_BY_TYPE.string;
  };

  // Get field type
  const getFieldType = (fieldName: string) => {
    const field = fields.find((f) => f.name === fieldName);
    return field?.type || 'string';
  };

  // Get input type for a field
  const getInputType = (fieldName: string) => {
    const fieldType = getFieldType(fieldName);

    switch (fieldType) {
      case 'number':
        return 'number';
      case 'date':
        return 'date';
      case 'datetime':
        return 'datetime-local';
      case 'boolean':
        return 'select'; // Use select for boolean
      default:
        return 'text';
    }
  };

  // Render value input based on field type
  const renderValueInput = (filter: FilterExpression, index: number) => {
    const inputType = getInputType(filter.field);
    const fieldType = getFieldType(filter.field);
    const inputId = `filter-value-${index}`;

    if (inputType === 'select' || fieldType === 'boolean') {
      return (
        <select
          id={inputId}
          className="kelta-filter-builder__value-input kelta-filter-builder__value-input--boolean"
          value={String(filter.value ?? '')}
          onChange={(e) =>
            updateFilter(index, {
              value: e.target.value === 'true',
            })
          }
          aria-label={`Filter value for ${filter.field}`}
        >
          <option value="">Select...</option>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      );
    }

    return (
      <input
        id={inputId}
        className={`kelta-filter-builder__value-input kelta-filter-builder__value-input--${fieldType}`}
        type={inputType}
        value={String(filter.value ?? '')}
        onChange={(e) =>
          updateFilter(index, {
            value:
              inputType === 'number'
                ? e.target.value === ''
                  ? ''
                  : Number(e.target.value)
                : e.target.value,
          })
        }
        placeholder={`Enter ${fieldType} value`}
        aria-label={`Filter value for ${filter.field}`}
      />
    );
  };

  return (
    <div
      className={`kelta-filter-builder ${className}`}
      data-testid={testId}
      role="group"
      aria-label="Filter builder"
    >
      <div className="kelta-filter-builder__filters" role="list">
        {value.length === 0 && (
          <div className="kelta-filter-builder__empty" role="listitem">
            No filters applied. Click &quot;Add Filter&quot; to add one.
          </div>
        )}
        {value.map((filter, index) => (
          <div
            key={index}
            className="kelta-filter-builder__filter"
            role="listitem"
            data-filter-index={index}
          >
            <label
              htmlFor={`filter-field-${index}`}
              className="kelta-filter-builder__label kelta-filter-builder__label--sr-only"
            >
              Field
            </label>
            <select
              id={`filter-field-${index}`}
              className="kelta-filter-builder__field-select"
              value={filter.field}
              onChange={(e) => updateFilter(index, { field: e.target.value })}
              aria-label={`Filter field ${index + 1}`}
            >
              {fields.map((field) => (
                <option key={field.name} value={field.name}>
                  {field.displayName || field.name}
                </option>
              ))}
            </select>

            <label
              htmlFor={`filter-operator-${index}`}
              className="kelta-filter-builder__label kelta-filter-builder__label--sr-only"
            >
              Operator
            </label>
            <select
              id={`filter-operator-${index}`}
              className="kelta-filter-builder__operator-select"
              value={filter.operator}
              onChange={(e) =>
                updateFilter(index, {
                  operator: e.target.value as FilterExpression['operator'],
                })
              }
              aria-label={`Filter operator ${index + 1}`}
            >
              {getOperators(filter.field).map((op) => (
                <option key={op.value} value={op.value}>
                  {op.label}
                </option>
              ))}
            </select>

            {renderValueInput(filter, index)}

            <button
              className="kelta-filter-builder__remove-button"
              type="button"
              onClick={() => removeFilter(index)}
              aria-label={`Remove filter ${index + 1}`}
            >
              <span aria-hidden="true">×</span>
            </button>
          </div>
        ))}
      </div>

      <button
        className="kelta-filter-builder__add-button"
        type="button"
        onClick={addFilter}
        disabled={value.length >= maxFilters || fields.length === 0}
        aria-label="Add new filter"
      >
        Add Filter
        {maxFilters && value.length > 0 && (
          <span className="kelta-filter-builder__count">
            {' '}
            ({value.length}/{maxFilters})
          </span>
        )}
      </button>
    </div>
  );
}
