/**
 * FieldPermissionEditor Component
 *
 * A field visibility editor for managing field-level security.
 * Allows configuring each field's visibility as Visible, Read Only, or Hidden
 * within a selected collection.
 *
 * Features:
 * - Collection selector dropdown at top
 * - Table with Field Name, Field Type, and Visibility radio buttons
 * - Bulk actions: Set All Visible, Set All Read Only, Set All Hidden
 * - Read-only mode support
 */

import React, { useState, useCallback, useMemo } from 'react'
import { Eye, EyeOff, Lock, Asterisk } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

/** Visibility options */
type FieldVisibility = 'VISIBLE' | 'READ_ONLY' | 'HIDDEN' | 'MASKED'

/** Collection reference */
export interface CollectionRef {
  id: string
  name: string
}

/** Field reference */
export interface FieldRef {
  id: string
  name: string
  type: string
  collectionId: string
}

/** Field permission entry */
export interface FieldPermission {
  fieldId: string
  visibility: FieldVisibility
}

/** Visibility option definition */
interface VisibilityOption {
  value: FieldVisibility
  label: string
  icon: React.ReactNode
}

const VISIBILITY_OPTIONS: VisibilityOption[] = [
  { value: 'VISIBLE', label: 'Visible', icon: <Eye size={14} /> },
  { value: 'READ_ONLY', label: 'Read Only', icon: <Lock size={14} /> },
  { value: 'MASKED', label: 'Masked', icon: <Asterisk size={14} /> },
  { value: 'HIDDEN', label: 'Hidden', icon: <EyeOff size={14} /> },
]

export interface FieldPermissionEditorProps {
  /** Available collections */
  collections: CollectionRef[]
  /** All fields across collections */
  fields: FieldRef[]
  /** Current field permissions */
  permissions: FieldPermission[]
  /** Callback when a field visibility changes */
  onChange: (fieldId: string, visibility: string) => void
  /** Whether the editor is read-only */
  readOnly?: boolean
  /** Test ID for the component */
  testId?: string
}

export function FieldPermissionEditor({
  collections,
  fields,
  permissions,
  onChange,
  readOnly = false,
  testId = 'field-permission-editor',
}: FieldPermissionEditorProps): React.ReactElement {
  /**
   * Radix throws on a <SelectItem value="" />, which takes down the whole page
   * through the error boundary. Drop id-less collections instead.
   */
  const selectableCollections = useMemo(() => collections.filter((c) => c.id !== ''), [collections])

  const [selectedCollectionId, setSelectedCollectionId] = useState<string>(
    selectableCollections.length > 0 ? selectableCollections[0].id : ''
  )

  /** Build a lookup map of fieldId -> visibility */
  const permissionMap = useMemo(() => {
    const map = new Map<string, FieldVisibility>()
    for (const perm of permissions) {
      map.set(perm.fieldId, perm.visibility)
    }
    return map
  }, [permissions])

  /** Fields filtered by selected collection */
  const filteredFields = useMemo(() => {
    if (!selectedCollectionId) return []
    return fields.filter((f) => f.collectionId === selectedCollectionId)
  }, [fields, selectedCollectionId])

  /** Handle visibility change for a single field */
  const handleVisibilityChange = useCallback(
    (fieldId: string, visibility: string) => {
      if (!readOnly) {
        onChange(fieldId, visibility)
      }
    },
    [onChange, readOnly]
  )

  /** Bulk action: set all fields in current collection to given visibility */
  const handleBulkAction = useCallback(
    (visibility: FieldVisibility) => {
      if (readOnly) return
      for (const field of filteredFields) {
        const currentVisibility = permissionMap.get(field.id) ?? 'VISIBLE'
        if (currentVisibility !== visibility) {
          onChange(field.id, visibility)
        }
      }
    },
    [readOnly, filteredFields, permissionMap, onChange]
  )

  /** Get the selected collection name */
  const selectedCollection = useMemo(
    () => selectableCollections.find((c) => c.id === selectedCollectionId),
    [selectableCollections, selectedCollectionId]
  )

  return (
    <div className="space-y-4" data-testid={testId}>
      {/* Collection selector */}
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex-1 min-w-[200px]">
          <Label
            htmlFor="collection-selector"
            className="mb-1.5 text-sm font-medium text-foreground"
          >
            Collection
          </Label>
          <Select
            value={selectedCollectionId}
            onValueChange={setSelectedCollectionId}
            disabled={selectableCollections.length === 0}
          >
            <SelectTrigger
              id="collection-selector"
              className="w-full"
              data-testid={`${testId}-collection-select`}
            >
              <SelectValue placeholder="Select a collection" />
            </SelectTrigger>
            <SelectContent>
              {selectableCollections.map((col) => (
                <SelectItem key={col.id} value={col.id}>
                  {col.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* Bulk actions */}
        {!readOnly && filteredFields.length > 0 && (
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">Set All:</span>
            {VISIBILITY_OPTIONS.map((opt) => (
              <Button
                key={opt.value}
                variant="outline"
                size="sm"
                onClick={() => handleBulkAction(opt.value)}
                data-testid={`${testId}-bulk-${opt.value.toLowerCase()}`}
                className="gap-1.5 text-xs"
              >
                {opt.icon}
                {opt.label}
              </Button>
            ))}
          </div>
        )}
      </div>

      {/* Field permissions table */}
      {selectableCollections.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card px-4 py-8 text-center text-sm text-muted-foreground"
          data-testid={`${testId}-no-collections`}
        >
          No collections available.
        </div>
      ) : filteredFields.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card px-4 py-8 text-center text-sm text-muted-foreground"
          data-testid={`${testId}-no-fields`}
        >
          {selectedCollection
            ? `No fields found in ${selectedCollection.name}.`
            : 'Select a collection to view fields.'}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table
            className="w-full border-collapse text-sm"
            role="grid"
            aria-label={`Field permissions for ${selectedCollection?.name ?? 'collection'}`}
            data-testid={`${testId}-table`}
          >
            <thead>
              <tr role="row" className="bg-muted">
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Field Name
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Field Type
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b border-border px-4 py-3 text-center text-xs font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  Visibility
                </th>
              </tr>
            </thead>
            <tbody>
              {filteredFields.map((field, index) => {
                const currentVisibility = permissionMap.get(field.id) ?? 'VISIBLE'

                return (
                  <tr
                    key={field.id}
                    role="row"
                    className={cn(
                      'border-b border-border last:border-b-0 transition-colors',
                      !readOnly && 'hover:bg-muted/50'
                    )}
                    data-testid={`${testId}-row-${index}`}
                  >
                    <td role="gridcell" className="px-4 py-3 font-medium text-foreground">
                      {field.name}
                    </td>
                    <td role="gridcell" className="px-4 py-3 text-muted-foreground">
                      <span className="inline-flex items-center rounded bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
                        {field.type}
                      </span>
                    </td>
                    <td role="gridcell" className="px-4 py-3">
                      <RadioGroup
                        value={currentVisibility}
                        onValueChange={(value) => handleVisibilityChange(field.id, value)}
                        disabled={readOnly}
                        className="flex items-center justify-center gap-4"
                        aria-label={`Visibility for ${field.name}`}
                        data-testid={`${testId}-visibility-${field.id}`}
                      >
                        {VISIBILITY_OPTIONS.map((opt) => {
                          const radioId = `visibility-${field.id}-${opt.value}`
                          return (
                            <div key={opt.value} className="flex items-center gap-1.5">
                              <RadioGroupItem
                                value={opt.value}
                                id={radioId}
                                data-testid={`${testId}-radio-${field.id}-${opt.value.toLowerCase()}`}
                              />
                              <Label
                                htmlFor={radioId}
                                className={cn(
                                  'cursor-pointer text-xs text-muted-foreground',
                                  readOnly && 'cursor-default'
                                )}
                              >
                                {opt.label}
                              </Label>
                            </div>
                          )
                        })}
                      </RadioGroup>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Summary */}
      {filteredFields.length > 0 && (
        <div className="flex gap-4 text-xs text-muted-foreground" data-testid={`${testId}-summary`}>
          <span>
            Visible:{' '}
            {
              filteredFields.filter((f) => (permissionMap.get(f.id) ?? 'VISIBLE') === 'VISIBLE')
                .length
            }
          </span>
          <span>
            Read Only:{' '}
            {filteredFields.filter((f) => permissionMap.get(f.id) === 'READ_ONLY').length}
          </span>
          <span>
            Masked: {filteredFields.filter((f) => permissionMap.get(f.id) === 'MASKED').length}
          </span>
          <span>
            Hidden: {filteredFields.filter((f) => permissionMap.get(f.id) === 'HIDDEN').length}
          </span>
        </div>
      )}
    </div>
  )
}
