/**
 * Toast helpers over `sonner` — the app's single toast API.
 *
 * Call `toast.success/error/info/warning(message)` from `sonner` directly when the variant is
 * known at the call site. {@link showToast} is only for a variant chosen at run time (a page
 * action's `level`, a pass/fail result). Toasts render in the `<Toaster />` viewport mounted once
 * in `App` (`components/ui/sonner.tsx`).
 */
import { toast } from 'sonner'

/** Toast variants — each maps onto the `sonner` method of the same name. */
export type ToastType = 'success' | 'error' | 'warning' | 'info'

export interface ShowToastOptions {
  /**
   * Auto-dismiss delay in ms. Omitted ⇒ the `<Toaster />` default; `0` (or negative) ⇒ the
   * toast stays until closed.
   */
  duration?: number
  /** Explicit id, so a later `toast.dismiss(id)` can close this toast. */
  id?: string | number
}

/**
 * Show a toast whose variant is only known at run time. Returns the `sonner` toast id.
 */
export function showToast(
  message: string,
  type: ToastType = 'info',
  options: ShowToastOptions = {}
): string | number {
  const { duration, id } = options
  return toast[type](message, {
    ...(id !== undefined ? { id } : {}),
    ...(duration !== undefined ? { duration: duration > 0 ? duration : Infinity } : {}),
  })
}
