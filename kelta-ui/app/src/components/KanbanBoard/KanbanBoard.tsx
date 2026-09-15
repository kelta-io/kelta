/**
 * KanbanBoard Component (app-data-entry slice 5)
 *
 * `viewType='kanban'` renderer for ObjectListPage: one lane per picklist value of
 * the chosen lane field (plus data-only values and an unassigned lane), cards via
 * FieldRenderer, per-lane counts. Dragging a card to another lane calls
 * `onMoveCard` (the page PATCHes with a fresh If-Match); an optimistic lane
 * override shows the move immediately and reverts if the move rejects.
 *
 * Own @dnd-kit component tree — never mixed with the page-builder canvas
 * (concerns.md: one DnD library per tree).
 */

import React, { useMemo, useState } from 'react'
import {
  DndContext,
  PointerSensor,
  useSensor,
  useSensors,
  useDraggable,
  useDroppable,
  type DragEndEvent,
} from '@dnd-kit/core'
import { cn } from '@/lib/utils'
import { FieldRenderer } from '@/components/FieldRenderer'
import { resolveLanes, UNASSIGNED_LANE, type KanbanLane } from './lanes'
import type { FieldDefinition } from '@/hooks/useCollectionSchema'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'

export interface KanbanBoardProps {
  records: CollectionRecord[]
  /** Lane field (picklist). */
  laneField: FieldDefinition
  /** Picklist values in configured order (lanes render even when empty). */
  laneOptions: string[]
  /** Bold card title field name. */
  titleField: string
  /** Fields rendered on the card body (already capped by the caller). */
  cardFields: FieldDefinition[]
  /** Drag is disabled without edit permission. */
  canEdit: boolean
  onCardClick: (record: CollectionRecord) => void
  /**
   * Persist a lane move (PATCH with If-Match in the page). Rejection reverts
   * the optimistic move — the caller owns the error toast + refetch.
   */
  onMoveCard: (recordId: string, lane: string | null) => Promise<void>
  tenantSlug?: string
  lookupDisplayMap?: Record<string, Record<string, string>>
  /** Lane field's raw value → authored `{label, color}` (see `usePicklistDisplayMap`). */
  laneDisplayMap?: Map<string, { label: string; color?: string }>
}

function KanbanCard({
  record,
  titleField,
  cardFields,
  canEdit,
  onCardClick,
  tenantSlug,
  lookupDisplayMap,
}: {
  record: CollectionRecord
  titleField: string
  cardFields: FieldDefinition[]
  canEdit: boolean
  onCardClick: (record: CollectionRecord) => void
  tenantSlug?: string
  lookupDisplayMap?: Record<string, Record<string, string>>
}) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: record.id,
    disabled: !canEdit,
  })
  const style = transform
    ? { transform: `translate(${transform.x}px, ${transform.y}px)` }
    : undefined
  const title = record[titleField]
  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        'cursor-pointer rounded-md border border-border bg-card p-2.5 shadow-sm hover:ring-1 hover:ring-primary/40',
        isDragging && 'z-10 opacity-80 shadow-md'
      )}
      data-testid={`kanban-card-${record.id}`}
      onClick={() => onCardClick(record)}
      {...listeners}
      {...attributes}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onCardClick(record)
        }
      }}
    >
      <div className="truncate text-sm font-medium">
        {title === null || title === undefined || title === '' ? record.id : String(title)}
      </div>
      {cardFields.length > 0 && (
        <div className="mt-1.5 space-y-0.5">
          {cardFields.map((field) => {
            const value = record[field.name]
            const isLookup =
              field.type === 'master_detail' ||
              field.type === 'lookup' ||
              field.type === 'reference'
            const displayLabel =
              isLookup && lookupDisplayMap?.[field.name]
                ? lookupDisplayMap[field.name][String(value)] || undefined
                : undefined
            return (
              <div key={field.name} className="truncate text-xs text-muted-foreground">
                <FieldRenderer
                  type={field.type}
                  value={value}
                  fieldName={field.name}
                  displayName={field.displayName || field.name}
                  tenantSlug={tenantSlug}
                  targetCollection={field.referenceTarget}
                  displayLabel={displayLabel}
                  truncate
                />
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function KanbanLaneColumn({ lane, children }: { lane: KanbanLane; children: React.ReactNode }) {
  const { setNodeRef, isOver } = useDroppable({ id: lane.id })
  return (
    <div
      ref={setNodeRef}
      className={cn(
        'flex w-64 shrink-0 flex-col rounded-[10px] border border-border bg-muted/40',
        isOver && 'ring-2 ring-primary/40'
      )}
      data-testid={`kanban-lane-${lane.id}`}
    >
      <div className="flex items-center gap-2 border-b border-border px-3 py-2">
        {lane.color && (
          <span
            className="h-2 w-2 shrink-0 rounded-full"
            style={{ backgroundColor: lane.color }}
            aria-hidden="true"
          />
        )}
        <span className="truncate text-[11px] font-semibold uppercase tracking-[0.09em] text-muted-foreground">
          {lane.label}
        </span>
        <span
          className="text-[11px] font-normal tabular-nums text-muted-foreground"
          data-testid={`kanban-lane-count-${lane.id}`}
        >
          ({lane.records.length})
        </span>
      </div>
      <div className="flex flex-1 flex-col gap-2 p-2">{children}</div>
    </div>
  )
}

export function KanbanBoard({
  records,
  laneField,
  laneOptions,
  titleField,
  cardFields,
  canEdit,
  onCardClick,
  onMoveCard,
  tenantSlug,
  lookupDisplayMap,
  laneDisplayMap,
}: KanbanBoardProps): React.ReactElement {
  // Optimistic lane overrides for in-flight moves; entries clear when the move
  // settles (success refetches, failure reverts here).
  const [pendingMoves, setPendingMoves] = useState<Record<string, string | null>>({})

  const effectiveRecords = useMemo(() => {
    if (Object.keys(pendingMoves).length === 0) return records
    return records.map((r) =>
      r.id in pendingMoves ? { ...r, [laneField.name]: pendingMoves[r.id] } : r
    )
  }, [records, pendingMoves, laneField.name])

  const lanes = useMemo(
    () => resolveLanes(effectiveRecords, laneField.name, laneOptions, laneDisplayMap),
    [effectiveRecords, laneField.name, laneOptions, laneDisplayMap]
  )

  // Distance activation keeps plain clicks flowing to onCardClick.
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }))

  const handleDragEnd = (event: DragEndEvent): void => {
    const { active, over } = event
    if (!over) return
    const recordId = String(active.id)
    const lane = over.id === UNASSIGNED_LANE ? null : String(over.id)
    const record = records.find((r) => r.id === recordId)
    if (!record) return
    const current = record[laneField.name]
    const currentLane =
      current === null || current === undefined || current === '' ? null : String(current)
    if (currentLane === lane) return
    setPendingMoves((prev) => ({ ...prev, [recordId]: lane }))
    void onMoveCard(recordId, lane)
      .catch(() => {
        /* revert below; the caller owns the toast */
      })
      .finally(() => {
        setPendingMoves((prev) => {
          const next = { ...prev }
          delete next[recordId]
          return next
        })
      })
  }

  return (
    <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
      <div className="flex gap-3 overflow-x-auto pb-2" data-testid="kanban-board">
        {lanes.map((lane) => (
          <KanbanLaneColumn key={lane.id} lane={lane}>
            {lane.records.map((record) => (
              <KanbanCard
                key={record.id}
                record={record}
                titleField={titleField}
                cardFields={cardFields}
                canEdit={canEdit}
                onCardClick={onCardClick}
                tenantSlug={tenantSlug}
                lookupDisplayMap={lookupDisplayMap}
              />
            ))}
          </KanbanLaneColumn>
        ))}
      </div>
    </DndContext>
  )
}
