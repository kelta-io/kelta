import { createContext, useContext } from 'react'

/** Shares the dialog's submitting flag with its fields so they disable together. */
export const CrudFormStateContext = createContext<{ isSubmitting: boolean }>({
  isSubmitting: false,
})

export function useCrudFormState(): { isSubmitting: boolean } {
  return useContext(CrudFormStateContext)
}

/** The field-label style (DESIGN.md), for a page-specific control that joins a CrudFormDialog. */
export const CRUD_FIELD_LABEL_CLASS =
  'text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground'
