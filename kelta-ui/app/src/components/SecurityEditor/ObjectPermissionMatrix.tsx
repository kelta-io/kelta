/**
 * ObjectPermissionMatrix Component
 *
 * A reusable CRUD permission matrix for managing object-level access.
 * Displays a table with columns for Create, Read, Edit, and Delete
 * with checkboxes in each cell.
 *
 * Features:
 * - Select All / Deselect All per column header
 * - Search/filter by collection name
 * - Read-only mode support
 * - Accessible table with proper ARIA attributes
 */

import React, { useState, useCallback, useMemo } from 'react'
import { Search } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'

/** Single object permission row */
export interface ObjectPermission {
  collectionId: string
  collectionName: string
  canCreate: boolean
  canRead: boolean
  canEdit: boolean
  canDelete: boolean
}

/** Permission field keys */
type PermissionField = 'canCreate' | 'canRead' | 'canEdit' | 'canDelete'

/** Column definition */
interface ColumnDef {
  field: PermissionField
  label: string
  shortLabel: string
}

const COLUMNS: ColumnDef[] = [
  { field: 'canCreate', label: 'Create', shortLabel: 'Create' },
  { field: 'canRead', label: 'Read', shortLabel: 'Read' },
  { field: 'canEdit', label: 'Edit', shortLabel: 'Edit' },
  { field: 'canDelete', label: 'Delete', shortLabel: 'Delete' },
]

export interface ObjectPermissionMatrixProps {
  /** Array of object permissions */
  permissions: ObjectPermission[]
  /** Callback when a permission is toggled */
  onChange: (collectionId: string, field: string, value: boolean) => void
  /** Whether the matrix is read-only */
  readOnly?: boolean
  /** Test ID for the component */
  testId?: string
}

export function ObjectPermissionMatrix({
  permissions,
  onChange,
  readOnly = false,
  testId = 'object-permission-matrix',
}: ObjectPermissionMatrixProps): React.ReactElement {
  const [searchQuery, setSearchQuery] = useState('')

  /** Filtered permissions based on search query */
  const filteredPermissions = useMemo(() => {
    if (!searchQuery.trim()) return permissions
    const query = searchQuery.toLowerCase()
    return permissions.filter((p) => p.collectionName.toLowerCase().includes(query))
  }, [permissions, searchQuery])

  /** Compute column-level "all checked" state for select-all logic */
  const columnStates = useMemo(() => {
    const states: Record<PermissionField, 'all' | 'none' | 'indeterminate'> = {
      canCreate: 'none',
      canRead: 'none',
      canEdit: 'none',
      canDelete: 'none',
    }

    if (filteredPermissions.length === 0) return states

    for (const col of COLUMNS) {
      const checkedCount = filteredPermissions.filter((p) => p[col.field]).length
      if (checkedCount === 0) {
        states[col.field] = 'none'
      } else if (checkedCount === filteredPermissions.length) {
        states[col.field] = 'all'
      } else {
        states[col.field] = 'indeterminate'
      }
    }

    return states
  }, [filteredPermissions])

  /** Handle toggling all permissions in a column */
  const handleSelectAllColumn = useCallback(
    (field: PermissionField) => {
      if (readOnly) return
      const currentState = columnStates[field]
      const newValue = currentState !== 'all'
      for (const perm of filteredPermissions) {
        if (perm[field] !== newValue) {
          onChange(perm.collectionId, field, newValue)
        }
      }
    },
    [readOnly, columnStates, filteredPermissions, onChange]
  )

  /** Handle individual cell change */
  const handleCellChange = useCallback(
    (collectionId: string, field: PermissionField, checked: boolean | 'indeterminate') => {
      if (!readOnly) {
        onChange(collectionId, field, checked === true)
      }
    },
    [onChange, readOnly]
  )

  return (
    <div className="space-y-3" data-testid={testId}>
      {/* Search filter */}
      <div className="relative">
        <Search
          size={16}
          className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          type="text"
          placeholder="Filter collections..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="pl-9"
          data-testid={`${testId}-search`}
          aria-label="Filter collections by name"
        />
      </div>

      {/* Permission matrix table */}
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table
          className="w-full border-collapse text-sm"
          role="grid"
          aria-label="Object permissions"
          data-testid={`${testId}-table`}
        >
          <thead>
            <tr role="row" className="bg-muted">
              <th
                role="columnheader"
                scope="col"
                className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
              >
                Collection
              </th>
              {COLUMNS.map((col) => {
                const state = columnStates[col.field]
                return (
                  <th
                    key={col.field}
                    role="columnheader"
                    scope="col"
                    className="border-b border-border px-3 py-3 text-center text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                  >
                    <div className="flex flex-col items-center gap-1.5">
                      <span>{col.shortLabel}</span>
                      {!readOnly && filteredPermissions.length > 0 && (
                        <Checkbox
                          checked={
                            state === 'all'
                              ? true
                              : state === 'indeterminate'
                                ? 'indeterminate'
                                : false
                          }
                          onCheckedChange={() => handleSelectAllColumn(col.field)}
                          aria-label={`Select all ${col.label}`}
                          data-testid={`${testId}-select-all-${col.field}`}
                        />
                      )}
                    </div>
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {filteredPermissions.length === 0 ? (
              <tr>
                <td
                  colSpan={5}
                  className="px-4 py-8 text-center text-sm text-muted-foreground"
                  data-testid={`${testId}-empty`}
                >
                  {searchQuery.trim()
                    ? 'No collections match your filter.'
                    : 'No object permissions configured.'}
                </td>
              </tr>
            ) : (
              filteredPermissions.map((perm, index) => (
                <tr
                  key={perm.collectionId}
                  role="row"
                  className={cn(
                    'border-b border-border last:border-b-0 transition-colors',
                    !readOnly && 'hover:bg-muted/50'
                  )}
                  data-testid={`${testId}-row-${index}`}
                >
                  <td role="gridcell" className="px-4 py-3 font-medium text-foreground">
                    {perm.collectionName}
                  </td>
                  {COLUMNS.map((col) => (
                    <td key={col.field} role="gridcell" className="px-3 py-3 text-center">
                      <div className="flex justify-center">
                        <Checkbox
                          checked={perm[col.field]}
                          onCheckedChange={(checked) =>
                            handleCellChange(perm.collectionId, col.field, checked)
                          }
                          disabled={readOnly}
                          aria-label={`${col.label} for ${perm.collectionName}`}
                          data-testid={`${testId}-cell-${perm.collectionId}-${col.field}`}
                        />
                      </div>
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Summary */}
      {filteredPermissions.length > 0 && (
        <p className="text-xs text-muted-foreground" data-testid={`${testId}-summary`}>
          Showing {filteredPermissions.length} of {permissions.length} collection
          {permissions.length !== 1 ? 's' : ''}
        </p>
      )}
    </div>
  )
}
