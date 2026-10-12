import { useState, useCallback, useEffect, useRef, KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { SortCriteria, FilterExpression } from '@kelta/sdk';
import { useKeltaClient } from '../context/KeltaContext';
import type { DataTableProps, ColumnDefinition } from './types';

/**
 * DataTable component for displaying resource lists with TanStack Query integration.
 *
 * Features:
 * - Data fetching using KeltaClient
 * - Column rendering based on configuration
 * - Sorting on column header click
 * - Pagination controls
 * - Filter application
 * - Loading and error states
 * - Keyboard navigation support for accessibility
 *
 * @example
 * ```tsx
 * <DataTable
 *   resourceName="users"
 *   columns={[
 *     { field: 'name', header: 'Name', sortable: true },
 *     { field: 'email', header: 'Email' },
 *   ]}
 *   pageSize={10}
 *   onRowClick={(row) => console.log('Clicked:', row)}
 * />
 * ```
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major release of
 * @kelta/components. The Kelta UI's lists use its own `AdminDataTable` / `ObjectDataTable`. Copy it into your app if you depend on it.
 */
export function DataTable<T = unknown>({
  resourceName,
  columns,
  filters: externalFilters,
  defaultSort = [],
  pageSize = 10,
  onRowClick,
  selectable = false,
  onSelectionChange,
  onFiltersChange: _onFiltersChange,
  onSortChange,
  onPageChange,
  className = '',
  testId = 'kelta-datatable',
}: DataTableProps<T>) {
  const client = useKeltaClient();
  const [page, setPage] = useState(1);
  const [sort, setSort] = useState<SortCriteria[]>(defaultSort);
  const [internalFilters, setInternalFilters] = useState<FilterExpression[]>(externalFilters ?? []);
  const [selected, setSelected] = useState<T[]>([]);
  const [focusedRowIndex, setFocusedRowIndex] = useState<number>(-1);
  const tableRef = useRef<HTMLTableElement>(null);

  // Use external filters if provided, otherwise use internal state
  const filters = externalFilters ?? internalFilters;

  // Sync internal filters with external filters when they change
  useEffect(() => {
    if (externalFilters !== undefined) {
      setInternalFilters(externalFilters);
    }
  }, [externalFilters]);

  // Fetch data using TanStack Query
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['resource', resourceName, page, pageSize, sort, filters],
    queryFn: async () => {
      const resource = client.resource<T>(resourceName);
      return resource.list({
        page,
        size: pageSize,
        sort: sort.length > 0 ? sort : undefined,
        filters: filters.length > 0 ? filters : undefined,
      });
    },
  });

  // Extract pagination early to avoid reference errors
  const rows = data?.data ?? [];
  const pagination = data?.pagination;

  // Handle page change
  const handlePageChange = useCallback(
    (newPage: number) => {
      setPage(newPage);
      setFocusedRowIndex(-1); // Reset focus when page changes
      onPageChange?.(newPage);
    },
    [onPageChange]
  );

  // Handle column header click for sorting
  const handleSort = useCallback(
    (column: ColumnDefinition<T>) => {
      if (!column.sortable) return;

      setSort((prevSort) => {
        const field = String(column.field);
        const existing = prevSort.find((s) => s.field === field);

        let newSort: SortCriteria[];
        if (!existing) {
          newSort = [...prevSort, { field, direction: 'asc' as const }];
        } else if (existing.direction === 'asc') {
          newSort = prevSort.map((s) =>
            s.field === field ? { ...s, direction: 'desc' as const } : s
          );
        } else {
          newSort = prevSort.filter((s) => s.field !== field);
        }

        onSortChange?.(newSort);
        return newSort;
      });
    },
    [onSortChange]
  );

  // Handle row selection
  const handleRowSelect = useCallback(
    (row: T) => {
      if (!selectable) return;

      setSelected((prev) => {
        const isSelected = prev.includes(row);
        const newSelected = isSelected ? prev.filter((r) => r !== row) : [...prev, row];

        onSelectionChange?.(newSelected);
        return newSelected;
      });
    },
    [selectable, onSelectionChange]
  );

  // Handle select all
  const handleSelectAll = useCallback(() => {
    if (selected.length === rows.length) {
      setSelected([]);
      onSelectionChange?.([]);
    } else {
      setSelected(rows);
      onSelectionChange?.(rows);
    }
  }, [rows, selected.length, onSelectionChange]);

  // Handle row click
  const handleRowClick = useCallback(
    (row: T, rowIndex: number) => {
      setFocusedRowIndex(rowIndex);
      onRowClick?.(row);
    },
    [onRowClick]
  );

  // Keyboard navigation handler
  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLTableElement>) => {
      if (rows.length === 0) return;

      switch (event.key) {
        case 'ArrowDown':
          event.preventDefault();
          setFocusedRowIndex((prev) => Math.min(prev + 1, rows.length - 1));
          break;
        case 'ArrowUp':
          event.preventDefault();
          setFocusedRowIndex((prev) => Math.max(prev - 1, 0));
          break;
        case 'Home':
          event.preventDefault();
          setFocusedRowIndex(0);
          break;
        case 'End':
          event.preventDefault();
          setFocusedRowIndex(rows.length - 1);
          break;
        case 'Enter':
        case ' ':
          event.preventDefault();
          if (focusedRowIndex >= 0 && focusedRowIndex < rows.length) {
            const row = rows[focusedRowIndex];
            if (selectable && event.key === ' ') {
              handleRowSelect(row);
            } else if (event.key === 'Enter') {
              onRowClick?.(row);
            }
          }
          break;
        case 'PageDown':
          event.preventDefault();
          if (pagination && page < pagination.totalPages) {
            handlePageChange(page + 1);
          }
          break;
        case 'PageUp':
          event.preventDefault();
          if (page > 1) {
            handlePageChange(page - 1);
          }
          break;
      }
    },
    [
      rows,
      focusedRowIndex,
      selectable,
      handleRowSelect,
      onRowClick,
      page,
      handlePageChange,
      pagination,
    ]
  );

  // Get sort indicator for a column
  const getSortIndicator = (column: ColumnDefinition<T>): string => {
    const sortItem = sort.find((s) => s.field === String(column.field));
    if (!sortItem) return '';
    return sortItem.direction === 'asc' ? ' ↑' : ' ↓';
  };

  // Get sort direction for aria-sort
  const getAriaSort = (
    column: ColumnDefinition<T>
  ): 'ascending' | 'descending' | 'none' | undefined => {
    if (!column.sortable) return undefined;
    const sortItem = sort.find((s) => s.field === String(column.field));
    if (!sortItem) return 'none';
    return sortItem.direction === 'asc' ? 'ascending' : 'descending';
  };

  // Render loading state
  if (isLoading) {
    return (
      <div
        className={`kelta-datatable kelta-datatable--loading ${className}`}
        data-testid={testId}
        role="status"
        aria-busy="true"
        aria-label={`Loading ${resourceName} data`}
      >
        <div className="kelta-datatable__loading" aria-live="polite">
          Loading...
        </div>
      </div>
    );
  }

  // Render error state
  if (error) {
    return (
      <div
        className={`kelta-datatable kelta-datatable--error ${className}`}
        data-testid={testId}
        role="alert"
        aria-live="assertive"
      >
        <div className="kelta-datatable__error">
          Error loading data: {error instanceof Error ? error.message : 'Unknown error'}
        </div>
        <button
          className="kelta-datatable__retry-button"
          onClick={() => void refetch()}
          aria-label="Retry loading data"
        >
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className={`kelta-datatable ${className}`} data-testid={testId}>
      <table
        ref={tableRef}
        className="kelta-datatable__table"
        role="grid"
        aria-label={`${resourceName} data table`}
        aria-rowcount={pagination?.total ?? rows.length}
        tabIndex={0}
        onKeyDown={handleKeyDown}
      >
        <thead>
          <tr role="row">
            {selectable && (
              <th
                className="kelta-datatable__header kelta-datatable__header--checkbox"
                role="columnheader"
                scope="col"
              >
                <input
                  type="checkbox"
                  checked={selected.length === rows.length && rows.length > 0}
                  onChange={handleSelectAll}
                  aria-label="Select all rows"
                />
              </th>
            )}
            {columns.map((column, index) => (
              <th
                key={index}
                className={`kelta-datatable__header ${column.sortable ? 'kelta-datatable__header--sortable' : ''}`}
                style={{ width: column.width }}
                onClick={() => handleSort(column)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    handleSort(column);
                  }
                }}
                role="columnheader"
                scope="col"
                aria-sort={getAriaSort(column)}
                tabIndex={column.sortable ? 0 : undefined}
                data-field={String(column.field)}
              >
                {column.header}
                {column.sortable && (
                  <span className="kelta-datatable__sort-indicator" aria-hidden="true">
                    {getSortIndicator(column)}
                  </span>
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr role="row">
              <td
                colSpan={columns.length + (selectable ? 1 : 0)}
                className="kelta-datatable__empty"
                role="gridcell"
              >
                No data available
              </td>
            </tr>
          ) : (
            rows.map((row, rowIndex) => (
              <tr
                key={rowIndex}
                className={`kelta-datatable__row ${selected.includes(row) ? 'kelta-datatable__row--selected' : ''} ${focusedRowIndex === rowIndex ? 'kelta-datatable__row--focused' : ''}`}
                onClick={() => handleRowClick(row, rowIndex)}
                role="row"
                aria-rowindex={rowIndex + 1}
                aria-selected={selectable ? selected.includes(row) : undefined}
                tabIndex={focusedRowIndex === rowIndex ? 0 : -1}
                data-row-index={rowIndex}
              >
                {selectable && (
                  <td
                    className="kelta-datatable__cell kelta-datatable__cell--checkbox"
                    role="gridcell"
                  >
                    <input
                      type="checkbox"
                      checked={selected.includes(row)}
                      onChange={(e) => {
                        e.stopPropagation();
                        handleRowSelect(row);
                      }}
                      onClick={(e) => e.stopPropagation()}
                      aria-label={`Select row ${rowIndex + 1}`}
                    />
                  </td>
                )}
                {columns.map((column, colIndex) => (
                  <td
                    key={colIndex}
                    className="kelta-datatable__cell"
                    role="gridcell"
                    data-field={String(column.field)}
                  >
                    {column.render
                      ? column.render(row[column.field], row)
                      : String(row[column.field] ?? '')}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>

      {pagination && (
        <nav
          className="kelta-datatable__pagination"
          role="navigation"
          aria-label="Table pagination"
        >
          <button
            className="kelta-datatable__pagination-button kelta-datatable__pagination-button--prev"
            disabled={page <= 1}
            onClick={() => handlePageChange(Math.max(1, page - 1))}
            aria-label="Go to previous page"
          >
            Previous
          </button>
          <span className="kelta-datatable__pagination-info" aria-live="polite">
            Page {pagination.page} of {pagination.totalPages}
            <span className="kelta-datatable__pagination-total">
              {' '}
              ({pagination.total} total items)
            </span>
          </span>
          <button
            className="kelta-datatable__pagination-button kelta-datatable__pagination-button--next"
            disabled={page >= pagination.totalPages}
            onClick={() => handlePageChange(Math.min(pagination.totalPages, page + 1))}
            aria-label="Go to next page"
          >
            Next
          </button>
        </nav>
      )}
    </div>
  );
}

// Export applyFilters helper for external filter control
export type { DataTableProps, ColumnDefinition } from './types';
