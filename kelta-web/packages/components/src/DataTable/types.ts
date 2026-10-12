import type { ReactNode } from 'react';
import type { FilterExpression, SortCriteria } from '@kelta/sdk';

/**
 * Column definition for DataTable
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major release of
 * @kelta/components. The Kelta UI's lists use its own `AdminDataTable` / `ObjectDataTable`. Copy it into your app if you depend on it.
 */
export interface ColumnDefinition<T = unknown> {
  /**
   * Field name to display
   */
  field: keyof T;

  /**
   * Column header text
   */
  header: string;

  /**
   * Whether the column is sortable
   */
  sortable?: boolean;

  /**
   * Whether the column is filterable
   */
  filterable?: boolean;

  /**
   * Custom render function for the cell
   */
  render?: (value: unknown, row: T) => ReactNode;

  /**
   * Column width (CSS value)
   */
  width?: string;
}

/**
 * Props for DataTable component
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major release of
 * @kelta/components. The Kelta UI's lists use its own `AdminDataTable` / `ObjectDataTable`. Copy it into your app if you depend on it.
 */
export interface DataTableProps<T = unknown> {
  /**
   * Name of the resource to display
   */
  resourceName: string;

  /**
   * Column definitions
   */
  columns: ColumnDefinition<T>[];

  /**
   * Initial filters to apply
   */
  filters?: FilterExpression[];

  /**
   * Default sort criteria
   */
  defaultSort?: SortCriteria[];

  /**
   * Number of items per page
   */
  pageSize?: number;

  /**
   * Callback when a row is clicked
   */
  onRowClick?: (row: T) => void;

  /**
   * Whether rows are selectable
   */
  selectable?: boolean;

  /**
   * Callback when selection changes
   */
  onSelectionChange?: (selected: T[]) => void;

  /**
   * Callback when filters change (for controlled filter mode)
   */
  onFiltersChange?: (filters: FilterExpression[]) => void;

  /**
   * Callback when sort changes
   */
  onSortChange?: (sort: SortCriteria[]) => void;

  /**
   * Callback when page changes
   */
  onPageChange?: (page: number) => void;

  /**
   * Custom class name
   */
  className?: string;

  /**
   * Test ID for testing purposes
   */
  testId?: string;
}
