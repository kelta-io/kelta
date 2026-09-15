/**
 * Per-user renderer overrides for shared list views (K-8 item 11).
 *
 * A shared `list-views` row now publishes its own `viewType`/`typeConfig`, so an
 * admin can hand out "the board". A user must still be able to flip that board
 * back to a table for themselves without asking the admin — and have the choice
 * survive a reload. That choice is stored per user as one `user-ui-preferences`
 * row per collection (`prefType: 'list-view-type'`) holding a map of
 * shared-view id → override; the published view is untouched.
 *
 * Only shared views need this. A personal view already carries its own
 * `viewType` and the user can re-save it.
 */
import { useCallback } from 'react'
import { usePreferenceValue } from './usePreferenceStore'
import type { SavedView, SavedViewType } from './useSavedViews'

export interface ViewTypeOverride {
  viewType: SavedViewType
  typeConfig?: SavedView['typeConfig']
}

export interface UseViewTypeOverridesReturn {
  /** False until the stored overrides are known — apply the published view after this. */
  isLoaded: boolean
  overrideFor: (viewId: string | null | undefined) => ViewTypeOverride | null
  saveOverride: (viewId: string, override: ViewTypeOverride) => void
}

function storageKey(collectionName: string): string {
  return `kelta_view_type_${collectionName}`
}

export function useViewTypeOverrides(collectionName: string): UseViewTypeOverridesReturn {
  const pref = usePreferenceValue<Record<string, ViewTypeOverride>>(
    'list-view-type',
    collectionName,
    { localKey: storageKey(collectionName) }
  )
  const stored = pref.value
  const prefSave = pref.save

  const overrideFor = useCallback(
    (viewId: string | null | undefined): ViewTypeOverride | null => {
      if (!viewId || !stored) return null
      const override = stored[viewId]
      return override && typeof override.viewType === 'string' ? override : null
    },
    [stored]
  )

  const saveOverride = useCallback(
    (viewId: string, override: ViewTypeOverride) => {
      prefSave({ ...(stored ?? {}), [viewId]: override })
    },
    [prefSave, stored]
  )

  return { isLoaded: pref.isLoaded, overrideFor, saveOverride }
}
