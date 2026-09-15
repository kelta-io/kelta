/**
 * Lane resolution for KanbanBoard (app-data-entry slice 5). Separate module so
 * the component file only exports components (react-refresh rule).
 */

import type { CollectionRecord } from '@/hooks/useCollectionRecords'

/** Droppable id for the unassigned (null lane-value) column. */
export const UNASSIGNED_LANE = '__unassigned__'

export interface KanbanLane {
  /** Droppable id (`UNASSIGNED_LANE` for the null lane). */
  id: string
  /** Lane value written on drop (null for the unassigned lane). Grouping always keys on this. */
  value: string | null
  label: string
  /** Authored picklist-value color for the lane header dot; absent renders no dot. */
  color?: string
  records: CollectionRecord[]
}

/**
 * Resolve the lane list: picklist options in their configured order, then any
 * distinct record values not in the picklist (data wins), then an unassigned
 * lane when records without a value exist. `displayMap` (raw value → authored
 * `{label, color}`, see `usePicklistDisplayMap`) only changes the header text/dot —
 * grouping and ordering stay keyed on the stored `options` values.
 */
export function resolveLanes(
  records: CollectionRecord[],
  laneFieldName: string,
  options: string[],
  displayMap?: Map<string, { label: string; color?: string }>
): KanbanLane[] {
  const byValue = new Map<string, CollectionRecord[]>()
  const unassigned: CollectionRecord[] = []
  for (const record of records) {
    const raw = record[laneFieldName]
    if (raw === null || raw === undefined || raw === '') {
      unassigned.push(record)
      continue
    }
    const value = String(raw)
    const bucket = byValue.get(value)
    if (bucket) bucket.push(record)
    else byValue.set(value, [record])
  }
  const lanes: KanbanLane[] = options.map((value) => ({
    id: value,
    value,
    label: displayMap?.get(value)?.label || value,
    color: displayMap?.get(value)?.color,
    records: byValue.get(value) ?? [],
  }))
  for (const [value, bucket] of byValue) {
    if (!options.includes(value)) {
      lanes.push({
        id: value,
        value,
        label: displayMap?.get(value)?.label || value,
        color: displayMap?.get(value)?.color,
        records: bucket,
      })
    }
  }
  if (unassigned.length > 0) {
    lanes.push({ id: UNASSIGNED_LANE, value: null, label: '—', records: unassigned })
  }
  return lanes
}
