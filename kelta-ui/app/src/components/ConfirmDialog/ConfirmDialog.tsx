/**
 * ConfirmDialog Component
 *
 * A reusable confirmation dialog for destructive or important actions.
 * Built on shadcn AlertDialog (Radix UI) with Tailwind CSS styling.
 *
 * Requirements:
 * - 3.10: Display confirmation dialog before collection deletion
 * - 4.8: Display confirmation dialog before field deletion
 * - 5.5: Display confirmation dialog before role deletion
 * - 6.7: Display confirmation dialog before OIDC provider deletion
 *
 * Features:
 * - Modal overlay with centered dialog
 * - Title and message display
 * - Confirm and Cancel buttons
 * - Danger variant with red confirm button for destructive actions
 * - Focus trap (handled by Radix AlertDialog)
 * - Escape key closes dialog
 * - Click outside closes dialog
 * - Accessible with ARIA alertdialog role
 */

import { useI18n } from '../../context/I18nContext'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { cn } from '@/lib/utils'

/**
 * ConfirmDialog variant type
 */
type ConfirmDialogVariant = 'default' | 'danger'

/**
 * Props for the ConfirmDialog component
 */
export interface ConfirmDialogProps {
  /** Whether the dialog is open */
  open: boolean
  /** Dialog title */
  title: string
  /** Dialog message/description */
  message: string
  /** Label for the confirm button (defaults to "Confirm") */
  confirmLabel?: string
  /** Label for the cancel button (defaults to "Cancel") */
  cancelLabel?: string
  /** Callback when confirm button is clicked */
  onConfirm: () => void
  /** Callback when cancel button is clicked or dialog is dismissed */
  onCancel: () => void
  /** Visual variant - 'danger' shows red confirm button for destructive actions */
  variant?: ConfirmDialogVariant
  /** Whether clicking outside the dialog closes it (default: true) */
  closeOnOverlayClick?: boolean
  /** Whether pressing Escape closes the dialog (default: true) */
  closeOnEscape?: boolean
  /** ID for the dialog element (for accessibility) */
  id?: string
}

/**
 * ConfirmDialog Component
 *
 * Displays a modal confirmation dialog with customizable title, message,
 * and action buttons. Supports danger variant for destructive actions.
 *
 * @example
 * ```tsx
 * <ConfirmDialog
 *   open={isOpen}
 *   title="Delete Collection"
 *   message="Are you sure you want to delete this collection? This action cannot be undone."
 *   confirmLabel="Delete"
 *   cancelLabel="Cancel"
 *   onConfirm={handleDelete}
 *   onCancel={() => setIsOpen(false)}
 *   variant="danger"
 * />
 * ```
 */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
  variant = 'default',
  closeOnOverlayClick = true,
  closeOnEscape = true,
  id,
}: ConfirmDialogProps): React.ReactElement {
  const { t } = useI18n()

  // Default labels with i18n support
  const resolvedConfirmLabel = confirmLabel ?? t('common.confirm')
  const resolvedCancelLabel = cancelLabel ?? t('common.cancel')

  const handleOpenChange = (isOpen: boolean) => {
    if (!isOpen && closeOnOverlayClick) {
      onCancel()
    }
  }

  const handleEscapeKeyDown = (e: KeyboardEvent) => {
    if (!closeOnEscape) {
      e.preventDefault()
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogContent
        id={id}
        data-testid="confirm-dialog"
        data-variant={variant}
        className={cn('max-w-md', variant === 'danger' && 'border-destructive/20')}
        onEscapeKeyDown={handleEscapeKeyDown}
      >
        <AlertDialogHeader>
          {/* Title/Description ids stay Radix-managed so the content's
              aria-labelledby/aria-describedby wiring (and its dev-mode
              accessibility checks) keep working. */}
          <AlertDialogTitle
            data-testid="confirm-dialog-title"
            className={cn(variant === 'danger' && 'text-destructive')}
          >
            {title}
          </AlertDialogTitle>
          <AlertDialogDescription data-testid="confirm-dialog-message">
            {message}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel data-testid="confirm-dialog-cancel">
            {resolvedCancelLabel}
          </AlertDialogCancel>
          <AlertDialogAction
            data-testid="confirm-dialog-confirm"
            variant={variant === 'danger' ? 'destructive' : 'default'}
            onClick={onConfirm}
          >
            {resolvedConfirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
