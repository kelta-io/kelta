/**
 * useAdminCrud
 *
 * Page-level state for an admin CRUD screen: whether the create/edit form is
 * open, which row is being edited, which row is pending delete confirmation,
 * plus the mutations from {@link useCrudMutations}. `submit` dispatches to
 * create or update depending on whether a row is being edited and closes the
 * form on success; `confirmDelete` deletes the pending row and clears it on
 * success. Failures leave the form / confirm dialog open so the user can retry.
 */

import { useCallback, useState } from 'react'
import { useCrudMutations, type UseCrudMutationsOptions } from './useCrudMutations'

export function useAdminCrud<TRow, TValues>(options: UseCrudMutationsOptions<TRow, TValues>) {
  const mutations = useCrudMutations<TRow, TValues>(options)
  const { createMutation, updateMutation, deleteMutation } = mutations

  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editing, setEditing] = useState<TRow | undefined>(undefined)
  const [deleteTarget, setDeleteTarget] = useState<TRow | null>(null)

  const openCreate = useCallback(() => {
    setEditing(undefined)
    setIsFormOpen(true)
  }, [])

  const openEdit = useCallback((row: TRow) => {
    setEditing(row)
    setIsFormOpen(true)
  }, [])

  const closeForm = useCallback(() => {
    setIsFormOpen(false)
    setEditing(undefined)
  }, [])

  const submit = useCallback(
    (values: TValues) => {
      if (editing !== undefined) {
        updateMutation.mutate({ row: editing, values }, { onSuccess: closeForm })
      } else {
        createMutation.mutate(values, { onSuccess: closeForm })
      }
    },
    [editing, createMutation, updateMutation, closeForm]
  )

  const requestDelete = useCallback((row: TRow) => setDeleteTarget(row), [])
  const cancelDelete = useCallback(() => setDeleteTarget(null), [])

  const confirmDelete = useCallback(() => {
    if (deleteTarget !== null) {
      deleteMutation.mutate(deleteTarget, { onSuccess: () => setDeleteTarget(null) })
    }
  }, [deleteTarget, deleteMutation])

  return {
    ...mutations,
    isFormOpen,
    editing,
    openCreate,
    openEdit,
    closeForm,
    submit,
    deleteTarget,
    requestDelete,
    cancelDelete,
    confirmDelete,
  }
}
