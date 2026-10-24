/**
 * KeyboardShortcutsHelp Component
 *
 * Modal overlay that displays all available keyboard shortcuts grouped
 * by category. Opened by pressing the `?` key.
 * Built on shadcn Dialog (Radix UI) with Tailwind CSS styling.
 *
 * Features:
 * - Grouped shortcuts table (Navigation, Search, Record Actions, General)
 * - Styled kbd elements for key display
 * - Accessible modal with role="dialog" and Radix overlay
 * - Escape to close, close button in header
 */

import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog'

export interface KeyboardShortcutsHelpProps {
  isOpen: boolean
  onClose: () => void
}

interface ShortcutEntry {
  keys: string[][]
  descriptionKey: string
}

interface ShortcutGroup {
  titleKey: string
  shortcuts: ShortcutEntry[]
}

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/.test(navigator.userAgent)

const shortcutGroups: ShortcutGroup[] = [
  {
    titleKey: 'shortcuts.navigation',
    shortcuts: [
      { keys: [['g', 'h']], descriptionKey: 'shortcuts.goHome' },
      { keys: [['g', 'c']], descriptionKey: 'shortcuts.goCollections' },
      { keys: [['g', 'r']], descriptionKey: 'shortcuts.goResources' },
    ],
  },
  {
    titleKey: 'shortcuts.search',
    shortcuts: [
      {
        keys: isMac ? [['⌘', 'K']] : [['Ctrl', 'K']],
        descriptionKey: 'shortcuts.openSearch',
      },
      { keys: [['\u2044']], descriptionKey: 'shortcuts.focusFilter' },
    ],
  },
  {
    titleKey: 'shortcuts.recordActions',
    shortcuts: [
      { keys: [['e']], descriptionKey: 'shortcuts.editRecord' },
      { keys: [['n']], descriptionKey: 'shortcuts.newRecord' },
      { keys: [['Backspace']], descriptionKey: 'shortcuts.goBack' },
    ],
  },
  {
    titleKey: 'shortcuts.general',
    shortcuts: [
      { keys: [['?']], descriptionKey: 'shortcuts.showHelp' },
      { keys: [['Esc']], descriptionKey: 'shortcuts.closeDialog' },
    ],
  },
]

export function KeyboardShortcutsHelp({ isOpen, onClose }: KeyboardShortcutsHelpProps) {
  const { t } = useI18n()

  // Fix: the `/` key should display as `/`
  const displayKey = (key: string) => (key === '\u2044' ? '/' : key)

  return (
    <Dialog open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        className="sm:max-w-[560px] max-h-[80vh] overflow-y-auto p-6"
        data-testid="keyboard-shortcuts-help"
      >
        <DialogHeader>
          <DialogTitle className="text-lg font-semibold text-foreground">
            {t('shortcuts.title')}
          </DialogTitle>
          <DialogDescription className="sr-only">{t('shortcuts.title')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4 mt-2">
          {shortcutGroups.map((group) => (
            <div key={group.titleKey}>
              <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                {t(group.titleKey)}
              </h3>
              {group.shortcuts.map((shortcut) => (
                <div
                  key={shortcut.descriptionKey}
                  className={cn(
                    'flex justify-between items-center py-2',
                    'border-b border-border/40 last:border-b-0'
                  )}
                >
                  <div className="flex gap-1">
                    {shortcut.keys.map((keyCombo, comboIdx) => (
                      <span key={comboIdx} className="flex gap-1">
                        {keyCombo.map((key, keyIdx) => (
                          <kbd
                            key={keyIdx}
                            className="inline-flex items-center justify-center min-w-[24px] rounded border bg-muted px-2 py-0.5 font-mono text-[0.6875rem] text-foreground shadow-[0_1px_0] shadow-border text-center"
                          >
                            {displayKey(key)}
                          </kbd>
                        ))}
                      </span>
                    ))}
                  </div>
                  <span className="text-sm text-muted-foreground">
                    {t(shortcut.descriptionKey)}
                  </span>
                </div>
              ))}
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  )
}
