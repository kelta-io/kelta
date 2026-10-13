/**
 * Legacy toast API (deprecated) — a thin adapter over `sonner`, the app's single toast API.
 * New code calls `toast` from `'sonner'`; see `./Toast`.
 */
export { ToastProvider, useToast } from './Toast'

export type { ToastType, ToastContextValue, ToastProviderProps } from './Toast'
