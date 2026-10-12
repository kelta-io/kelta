import { createContext, useContext } from 'react'

/** Shares the dialog's submitting flag with its fields so they disable together. */
export const CrudFormStateContext = createContext<{ isSubmitting: boolean }>({
  isSubmitting: false,
})

export function useCrudFormState(): { isSubmitting: boolean } {
  return useContext(CrudFormStateContext)
}
