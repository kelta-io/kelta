/**
 * RecordConflictDialog — the one concurrent-edit prompt for record writes.
 *
 * Shown when a save sent with `If-Match` comes back `409 STALE_WRITE` (unified record slice 5):
 * someone else saved the record after this user loaded it. The user's edits are NOT applied and
 * NOT discarded — the caller keeps them in place while the prompt is open. "Reload latest"
 * refetches the record and re-initialises the form (discarding the edits); "Keep editing" closes
 * the prompt so the user can copy their changes first.
 *
 * Used by the shared `RecordForm` core (end-user `/app/o` form) and the admin `ResourceFormPage`.
 */

import React from 'react'
import { Loader2 } from 'lucide-react'
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
import { useI18n } from '@/context/I18nContext'

export interface RecordConflictDialogProps {
  open: boolean
  /** Refetch the record and reset the form to it. The caller closes the prompt when done. */
  onReload: () => void
  /** Close the prompt, keeping the user's edits in the form. */
  onKeepEditing: () => void
  /** True while the reload is in flight (disables both actions). */
  reloading?: boolean
}

export function RecordConflictDialog({
  open,
  onReload,
  onKeepEditing,
  reloading = false,
}: RecordConflictDialogProps): React.ReactElement {
  const { t } = useI18n()

  return (
    <AlertDialog
      open={open}
      onOpenChange={(isOpen) => {
        if (!isOpen && !reloading) onKeepEditing()
      }}
    >
      <AlertDialogContent className="max-w-md" data-testid="record-conflict-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{t('recordConflict.title')}</AlertDialogTitle>
          <AlertDialogDescription>{t('recordConflict.message')}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={reloading} data-testid="record-conflict-keep-editing">
            {t('recordConflict.keepEditing')}
          </AlertDialogCancel>
          <AlertDialogAction
            disabled={reloading}
            data-testid="record-conflict-reload"
            onClick={(event) => {
              // Stay open until the caller has reloaded (it closes the prompt itself).
              event.preventDefault()
              onReload()
            }}
          >
            {reloading && <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />}
            {t('recordConflict.reload')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

export default RecordConflictDialog
