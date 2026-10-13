/**
 * AssignmentRulesEditor
 *
 * Modal editor for the layout-assignments rows that select which layout to
 * render for a given record. Each row binds (profile, recordType, condition,
 * evaluationOrder) to a layout. The runtime resolver walks assignments by
 * ascending evaluationOrder and applies the first matching one.
 *
 * Distinct from the per-layout "Rules" button, which edits per-field
 * COMPUTE/VALIDATE/DEFAULT/TRANSFORM rules.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { uuid } from '@/utils/uuid'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useApi } from '@/context/ApiContext'
import { FilterBuilder, type FilterExpression, type FieldOption } from '@/components/FilterBuilder'

interface AssignmentRow {
  /** Editor-local id. `serverId` is set when the row originated from the API. */
  id: string
  serverId?: string
  profileId: string
  recordTypeId: string
  condition: FilterExpression | null
  evaluationOrder: number
}

interface ServerAssignment {
  id: string
  collectionId: string
  profileId?: string
  recordTypeId?: string
  layoutId: string
  condition?: FilterExpression | null
  evaluationOrder: number
}

export interface AssignmentRulesEditorProps {
  open: boolean
  onClose: () => void
  layoutId: string
  layoutName: string
  collectionId: string
  /** If omitted the editor fetches the collection's fields itself. */
  fields?: FieldOption[]
}

interface CollectionFieldResource {
  id: string
  name: string
  displayName?: string
  type?: string
}

export function AssignmentRulesEditor({
  open,
  onClose,
  layoutId,
  layoutName,
  collectionId,
  fields,
}: AssignmentRulesEditorProps): React.ReactElement {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const [rows, setRows] = useState<AssignmentRow[]>([])
  const [originalServerIds, setOriginalServerIds] = useState<Set<string>>(new Set())

  const { data, isLoading } = useQuery({
    queryKey: ['layoutAssignments', layoutId],
    queryFn: () =>
      apiClient.getList<ServerAssignment>(
        `/api/layout-assignments?filter[layoutId][eq]=${encodeURIComponent(layoutId)}`
      ),
    enabled: open,
  })

  // Fetch fields for the assignment's collection if the caller didn't pass them.
  const { data: fetchedFields } = useQuery({
    queryKey: ['collection-fields', collectionId],
    queryFn: async () => {
      type JsonApiResource = {
        type: string
        id: string
        attributes: Record<string, unknown>
      }
      const raw = await apiClient.get<{
        included?: JsonApiResource[]
      }>(`/api/collections/${collectionId}?include=fields`)
      return (raw.included ?? [])
        .filter((r) => r.type === 'fields')
        .map(
          (r) =>
            ({
              id: r.id,
              name: r.attributes.name as string,
              displayName: (r.attributes.displayName as string) || (r.attributes.name as string),
              type: r.attributes.type as string,
            }) as CollectionFieldResource
        )
    },
    enabled: open && !fields,
  })

  const effectiveFields: FieldOption[] = useMemo(() => {
    if (fields) return fields
    return (fetchedFields ?? []).map((f) => ({
      id: f.id,
      name: f.name,
      displayName: f.displayName ?? f.name,
    }))
  }, [fields, fetchedFields])

  // Reset local edit state when fresh server data arrives; setState-in-effect
  // is the standard pattern for syncing server data into local editable state.
  useEffect(() => {
    if (!data) return
    const sorted = [...data].sort((a, b) => (a.evaluationOrder ?? 100) - (b.evaluationOrder ?? 100))
    /* eslint-disable react-hooks/set-state-in-effect */
    setRows(
      sorted.map((a) => ({
        id: uuid(),
        serverId: a.id,
        profileId: a.profileId ?? '',
        recordTypeId: a.recordTypeId ?? '',
        condition: a.condition ?? null,
        evaluationOrder: a.evaluationOrder ?? 100,
      }))
    )
    setOriginalServerIds(new Set(sorted.map((a) => a.id)))
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [data])

  const mutation = useMutation({
    mutationFn: async () => {
      const desiredServerIds = new Set(rows.filter((r) => r.serverId).map((r) => r.serverId!))
      const idsToDelete = Array.from(originalServerIds).filter((id) => !desiredServerIds.has(id))
      await Promise.all(
        idsToDelete.map((id) => apiClient.deleteResource(`/api/layout-assignments/${id}`))
      )

      for (const r of rows) {
        const payload = {
          collectionId,
          layoutId,
          profileId: r.profileId || null,
          recordTypeId: r.recordTypeId || null,
          condition: r.condition ?? null,
          evaluationOrder: r.evaluationOrder,
        }
        if (r.serverId) {
          await apiClient.putResource(`/api/layout-assignments/${r.serverId}`, payload)
        } else {
          await apiClient.postResource('/api/layout-assignments', payload)
        }
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['layoutAssignments', layoutId] })
      toast.success('Assignments saved')
      onClose()
    },
    onError: (err: Error) => {
      toast.error(err.message || 'Failed to save assignments')
    },
  })

  const nextOrder = useMemo(() => {
    const max = rows.reduce((m, r) => Math.max(m, r.evaluationOrder), 0)
    return max + 10
  }, [rows])

  const addRow = useCallback(() => {
    setRows((prev) => [
      ...prev,
      {
        id: uuid(),
        profileId: '',
        recordTypeId: '',
        condition: null,
        evaluationOrder: nextOrder,
      },
    ])
  }, [nextOrder])

  const updateRow = useCallback((id: string, patch: Partial<AssignmentRow>) => {
    setRows((prev) => prev.map((r) => (r.id === id ? { ...r, ...patch } : r)))
  }, [])

  const removeRow = useCallback((id: string) => {
    setRows((prev) => prev.filter((r) => r.id !== id))
  }, [])

  const moveRow = useCallback((index: number, dir: -1 | 1) => {
    setRows((prev) => {
      const next = [...prev]
      const target = index + dir
      if (target < 0 || target >= next.length) return prev
      ;[next[index], next[target]] = [next[target], next[index]]
      // Renumber evaluationOrder by 10s so we always have gaps.
      return next.map((r, i) => ({ ...r, evaluationOrder: (i + 1) * 10 }))
    })
  }, [])

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-4xl">
        <DialogHeader>
          <DialogTitle>Assignments — {layoutName}</DialogTitle>
          <DialogDescription>
            Choose when this layout applies. Runtime walks rules by evaluation order ascending and
            picks the first one whose profile, record type, and condition all match. Rules without a
            condition act as fallbacks.
          </DialogDescription>
        </DialogHeader>

        <div className="flex max-h-[60vh] flex-col gap-3 overflow-y-auto">
          {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}

          {!isLoading && rows.length === 0 && (
            <p className="text-sm text-muted-foreground">
              No assignment rules. Add one to control when this layout applies.
            </p>
          )}

          {rows.map((row, i) => (
            <div
              key={row.id}
              className="flex flex-col gap-2 rounded-md border border-border bg-muted/30 p-3"
              data-testid={`assignment-row-${i}`}
            >
              <div className="flex flex-wrap items-end gap-3">
                <div className="flex flex-col gap-1">
                  <Label className="text-xs">Order</Label>
                  <Input
                    type="number"
                    className="h-8 w-20"
                    value={row.evaluationOrder}
                    onChange={(e) =>
                      updateRow(row.id, { evaluationOrder: Number(e.target.value) || 0 })
                    }
                    data-testid={`assignment-order-${i}`}
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label className="text-xs">Profile ID</Label>
                  <Input
                    className="h-8 w-56"
                    placeholder="(any)"
                    value={row.profileId}
                    onChange={(e) => updateRow(row.id, { profileId: e.target.value })}
                    data-testid={`assignment-profile-${i}`}
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label className="text-xs">Record type ID</Label>
                  <Input
                    className="h-8 w-56"
                    placeholder="(any)"
                    value={row.recordTypeId}
                    onChange={(e) => updateRow(row.id, { recordTypeId: e.target.value })}
                    data-testid={`assignment-recordtype-${i}`}
                  />
                </div>
                <div className="ml-auto flex gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Move up"
                    disabled={i === 0}
                    onClick={() => moveRow(i, -1)}
                  >
                    <ArrowUp className="h-4 w-4" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Move down"
                    disabled={i === rows.length - 1}
                    onClick={() => moveRow(i, 1)}
                  >
                    <ArrowDown className="h-4 w-4" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Remove row"
                    onClick={() => removeRow(row.id)}
                    data-testid={`assignment-remove-${i}`}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>

              <div className="flex flex-col gap-1">
                <Label className="text-xs text-muted-foreground">
                  Condition (leave empty to act as fallback)
                </Label>
                <FilterBuilder
                  value={row.condition}
                  onChange={(next) => updateRow(row.id, { condition: next })}
                  fields={effectiveFields}
                  idPrefix={`assignment-${i}-cond`}
                />
              </div>
            </div>
          ))}

          <div>
            <Button type="button" variant="outline" size="sm" onClick={addRow}>
              <Plus className="mr-1 h-4 w-4" />
              Add assignment
            </Button>
          </div>
        </div>

        <DialogFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            data-testid="assignment-save"
          >
            {mutation.isPending ? 'Saving…' : 'Save'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
