import type { FieldDefinition, FilterExpression } from '@kelta/sdk';

/**
 * Props for FilterBuilder component
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major release of
 * @kelta/components. The Kelta UI uses its own filter builder. Copy it into your app if you depend on it.
 */
export interface FilterBuilderProps {
  /**
   * Available fields for filtering
   */
  fields: FieldDefinition[];

  /**
   * Current filter values
   */
  value: FilterExpression[];

  /**
   * Callback when filters change
   */
  onChange: (filters: FilterExpression[]) => void;

  /**
   * Maximum number of filters allowed
   */
  maxFilters?: number;

  /**
   * Custom class name
   */
  className?: string;

  /**
   * Test ID for testing
   */
  testId?: string;
}

/**
 * Operators available for each field type
 */
export const OPERATORS_BY_TYPE: Record<string, Array<{ value: string; label: string }>> = {
  string: [
    { value: 'eq', label: 'Equals' },
    { value: 'ne', label: 'Not equals' },
    { value: 'contains', label: 'Contains' },
    { value: 'startsWith', label: 'Starts with' },
    { value: 'endsWith', label: 'Ends with' },
  ],
  number: [
    { value: 'eq', label: 'Equals' },
    { value: 'ne', label: 'Not equals' },
    { value: 'gt', label: 'Greater than' },
    { value: 'gte', label: 'Greater than or equal' },
    { value: 'lt', label: 'Less than' },
    { value: 'lte', label: 'Less than or equal' },
  ],
  boolean: [{ value: 'eq', label: 'Equals' }],
  date: [
    { value: 'eq', label: 'Equals' },
    { value: 'gt', label: 'After' },
    { value: 'lt', label: 'Before' },
    { value: 'gte', label: 'On or after' },
    { value: 'lte', label: 'On or before' },
  ],
  datetime: [
    { value: 'eq', label: 'Equals' },
    { value: 'gt', label: 'After' },
    { value: 'lt', label: 'Before' },
    { value: 'gte', label: 'On or after' },
    { value: 'lte', label: 'On or before' },
  ],
};
