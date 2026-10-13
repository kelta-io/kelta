/**
 * useCrudMutations
 *
 * The create / update / delete `useMutation` trio every admin CRUD page used to
 * hand-roll: each mutation invalidates the list query on success and toasts a
 * success or error message. Error toasts prefer the thrown error's message and
 * fall back to the page-supplied text.
 */

import { toast } from 'sonner'
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query'

export interface CrudMessages {
  /** Toast shown after a successful create. */
  created: string
  /** Toast shown after a successful update. */
  updated: string
  /** Toast shown after a successful delete. */
  deleted: string
  /** Fallback error toast when the thrown error carries no message. */
  error: string
  /** Per-operation fallbacks; each defaults to `error`. */
  createError?: string
  updateError?: string
  deleteError?: string
}

export interface UseCrudMutationsOptions<TRow, TValues> {
  /** Query key of the list to invalidate after every successful mutation. */
  queryKey: QueryKey
  create?: (values: TValues) => Promise<unknown>
  update?: (row: TRow, values: TValues) => Promise<unknown>
  remove?: (row: TRow) => Promise<unknown>
  messages: CrudMessages
  /** Runs after the create toast (e.g. to show a one-time secret). */
  onCreated?: (result: unknown) => void
}

export interface UpdateVariables<TRow, TValues> {
  row: TRow
  values: TValues
}

const missing = (op: string) => () =>
  Promise.reject(new Error(`useCrudMutations: no ${op} function supplied`))

export function useCrudMutations<TRow, TValues>({
  queryKey,
  create,
  update,
  remove,
  messages,
  onCreated,
}: UseCrudMutationsOptions<TRow, TValues>) {
  const queryClient = useQueryClient()

  const toastError = (fallback: string | undefined) => (err: Error) => {
    toast.error(err.message || fallback || messages.error)
  }

  const createMutation = useMutation<unknown, Error, TValues>({
    mutationFn: create ? (values) => create(values) : missing('create'),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey })
      toast.success(messages.created)
      onCreated?.(result)
    },
    onError: toastError(messages.createError),
  })

  const updateMutation = useMutation<unknown, Error, UpdateVariables<TRow, TValues>>({
    mutationFn: update ? ({ row, values }) => update(row, values) : missing('update'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey })
      toast.success(messages.updated)
    },
    onError: toastError(messages.updateError),
  })

  const deleteMutation = useMutation<unknown, Error, TRow>({
    mutationFn: remove ? (row) => remove(row) : missing('remove'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey })
      toast.success(messages.deleted)
    },
    onError: toastError(messages.deleteError),
  })

  return {
    createMutation,
    updateMutation,
    deleteMutation,
    isSaving: createMutation.isPending || updateMutation.isPending,
  }
}
