/**
 * Which record stack (admin `/resources` or end-user `/app/o`) the current subtree renders in
 * (record-stack convergence P7).
 *
 * The shared record shells — `RecordShell`, `RecordForm`, `ListShell` — provide it from the
 * `variant` their page wrapper already passes, so a deep shared component (`RelatedList`,
 * `FieldRenderer`, `ObjectDataTable`, `RecordActionsBar`, …) builds record links for the stack
 * it is rendered in through {@link useRecordRoutes} without prop-drilling. Outside any provider
 * it is the end-user stack, which is what those components linked to before.
 */

import React, { createContext, useContext, useMemo } from 'react'
import { recordRoutes } from '@/utils/recordRoutes'
import type { RecordRoutes, RecordStackVariant } from '@/utils/recordRoutes'

const RecordStackContext = createContext<RecordStackVariant>('enduser')

export function RecordStackProvider({
  variant,
  children,
}: {
  variant: RecordStackVariant
  children: React.ReactNode
}): React.ReactElement {
  return <RecordStackContext.Provider value={variant}>{children}</RecordStackContext.Provider>
}

/** The record stack the calling component renders in (`'enduser'` outside any provider). */
// eslint-disable-next-line react-refresh/only-export-components
export function useRecordStack(): RecordStackVariant {
  return useContext(RecordStackContext)
}

/** Record URLs for the stack the calling component renders in. */
// eslint-disable-next-line react-refresh/only-export-components
export function useRecordRoutes(tenantSlug: string): RecordRoutes {
  const variant = useRecordStack()
  return useMemo(() => recordRoutes(variant, tenantSlug), [variant, tenantSlug])
}
