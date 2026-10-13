/**
 * Legacy toast API — a thin adapter over `sonner`.
 *
 * @deprecated `sonner` is the app's single toast API: call `toast.success/error/info/warning`
 * from `'sonner'` (or `showToast` from `@/utils/toast` when the variant is chosen at run time).
 * `useToast().showToast` now forwards to `sonner`, and `ToastProvider` renders only its
 * children — toasts appear in the `<Toaster />` viewport mounted in `App`. Kept so callers not
 * yet migrated keep working; remove once nothing imports it.
 */

import React from 'react'
import { toast } from 'sonner'
import { showToast as showSonnerToast, type ToastType } from '@/utils/toast'

export type { ToastType }

/**
 * @deprecated See the module note — use `sonner` directly.
 */
export interface ToastContextValue {
  /**
   * Show a toast (forwards to `sonner`). `duration` is in ms; `0` keeps the toast until it is
   * closed. Returns an id `hideToast` accepts.
   */
  showToast: (message: string, type: ToastType, duration?: number) => string
  /** Dismiss one toast by the id `showToast` returned. */
  hideToast: (id: string) => void
  /** Dismiss every toast. */
  hideAllToasts: () => void
}

/**
 * @deprecated `ToastProvider` no longer holds state; its props are ignored.
 */
export interface ToastProviderProps {
  children: React.ReactNode
  defaultDuration?: number
  maxToasts?: number
  position?:
    | 'top-right'
    | 'top-left'
    | 'bottom-right'
    | 'bottom-left'
    | 'top-center'
    | 'bottom-center'
}

let nextToastId = 0

const SONNER_TOAST_API: ToastContextValue = {
  showToast: (message, type, duration) => {
    nextToastId += 1
    const id = `toast-${nextToastId}`
    showSonnerToast(message, type, { id, duration })
    return id
  },
  hideToast: (id) => {
    toast.dismiss(id)
  },
  hideAllToasts: () => {
    toast.dismiss()
  },
}

/**
 * @deprecated Pass-through — toasts render in the `sonner` `<Toaster />` mounted in `App`.
 */
export function ToastProvider({ children }: ToastProviderProps): React.ReactElement {
  return <>{children}</>
}

/**
 * @deprecated Use `toast` from `'sonner'` directly. Works with or without a `ToastProvider`.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useToast(): ToastContextValue {
  return SONNER_TOAST_API
}
