/**
 * ViewSelector Component
 *
 * Dropdown for switching between saved list views of a collection.
 * Shows the active view name (or "All Records"), a list of saved views,
 * and an inline input to save the current configuration as a new view.
 *
 * Features:
 * - "All Records" option always shown first
 * - Star icon on the default view
 * - Delete button (X) on each saved view
 * - Inline text input to save current view
 * - Keyboard accessible (Enter to select, Escape to close)
 * - Click outside to close
 */

import React, { useState, useCallback, useRef, useEffect } from 'react'
import { cn } from '@/lib/utils'
import type { SavedView } from '../../hooks/useSavedViews'

export interface ViewSelectorProps {
  views: SavedView[]
  activeView: SavedView | null
  onSelectView: (viewId: string | null) => void
  onSaveView: (name: string) => void
  onDeleteView: (viewId: string) => void
  onRenameView: (viewId: string, newName: string) => void
  onSetDefault: (viewId: string) => void
}

export function ViewSelector({
  views,
  activeView,
  onSelectView,
  onSaveView,
  onDeleteView,
  onRenameView,
  onSetDefault,
}: ViewSelectorProps): React.ReactElement {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveName, setSaveName] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const saveInputRef = useRef<HTMLInputElement>(null)

  // Suppress unused variable warning — onRenameView is part of the public
  // API and will be wired up by the integrating page. We accept it here
  // so the component contract stays stable.
  void onRenameView

  const toggleOpen = useCallback(() => {
    setOpen((prev) => {
      if (prev) {
        // Closing — reset save mode
        setSaving(false)
        setSaveName('')
      }
      return !prev
    })
  }, [])

  const close = useCallback(() => {
    setOpen(false)
    setSaving(false)
    setSaveName('')
  }, [])

  // Close on outside click and Escape
  useEffect(() => {
    if (!open) return

    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        close()
      }
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close()
        buttonRef.current?.focus()
      }
    }

    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, close])

  // Focus save input when entering save mode
  useEffect(() => {
    if (saving) {
      saveInputRef.current?.focus()
    }
  }, [saving])

  const handleSelectAllRecords = useCallback(() => {
    onSelectView(null)
    close()
  }, [onSelectView, close])

  const handleSelectView = useCallback(
    (viewId: string) => {
      onSelectView(viewId)
      close()
    },
    [onSelectView, close]
  )

  const handleDeleteView = useCallback(
    (event: React.MouseEvent, viewId: string) => {
      event.stopPropagation()
      onDeleteView(viewId)
    },
    [onDeleteView]
  )

  const handleSetDefault = useCallback(
    (event: React.MouseEvent, viewId: string) => {
      event.stopPropagation()
      onSetDefault(viewId)
    },
    [onSetDefault]
  )

  const handleStartSave = useCallback(() => {
    setSaving(true)
    setSaveName('')
  }, [])

  const handleConfirmSave = useCallback(() => {
    const trimmed = saveName.trim()
    if (!trimmed) return
    onSaveView(trimmed)
    setSaving(false)
    setSaveName('')
    close()
  }, [saveName, onSaveView, close])

  const handleSaveKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLInputElement>) => {
      if (event.key === 'Enter') {
        event.preventDefault()
        handleConfirmSave()
      } else if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        setSaving(false)
        setSaveName('')
      }
    },
    [handleConfirmSave]
  )

  const displayLabel = activeView?.name ?? 'All Records'

  return (
    <div
      className="relative inline-flex items-center"
      ref={containerRef}
      data-testid="view-selector"
    >
      <button
        ref={buttonRef}
        type="button"
        className={cn(
          'inline-flex items-center gap-1.5 px-3 py-1.5 text-[0.8125rem] font-medium leading-snug',
          'text-foreground bg-background border border-input rounded',
          'cursor-pointer whitespace-nowrap',
          'transition-colors duration-150 motion-reduce:transition-none',
          'hover:bg-accent hover:border-muted-foreground/50',
          'focus:outline-2 focus:outline-primary focus:outline-offset-2',
          'focus:not(:focus-visible):outline-none'
        )}
        onClick={toggleOpen}
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-label={`Current view: ${displayLabel}`}
        data-testid="view-selector-trigger"
      >
        <span>{displayLabel}</span>
        <span className="text-[0.625rem] leading-none opacity-60" aria-hidden="true">
          {open ? '\u25B2' : '\u25BC'}
        </span>
      </button>

      {open && (
        <div
          className="absolute top-[calc(100%+4px)] left-0 min-w-[240px] max-h-[360px] bg-background border border-input rounded shadow-lg z-[1000] overflow-y-auto flex flex-col max-md:min-w-[200px]"
          role="listbox"
          aria-label="Saved views"
          data-testid="view-selector-dropdown"
        >
          {/* All Records option */}
          <button
            type="button"
            className={cn(
              'flex items-center w-full px-4 py-2 bg-transparent border-none cursor-pointer text-left text-[0.8125rem] text-foreground gap-2',
              'transition-colors duration-100 motion-reduce:transition-none',
              'hover:bg-accent',
              'focus:outline-2 focus:outline-primary focus:-outline-offset-2',
              'focus:not(:focus-visible):outline-none',
              activeView === null && 'bg-primary/10 font-semibold hover:bg-primary/[0.12]'
            )}
            role="option"
            aria-selected={activeView === null}
            onClick={handleSelectAllRecords}
            data-testid="view-option-all"
          >
            <span className="flex-1 min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">
              All Records
            </span>
          </button>

          {views.length > 0 && <hr className="h-px my-1 bg-border border-none" />}

          {/* Saved views list */}
          {views.map((view) => (
            <button
              type="button"
              key={view.id}
              className={cn(
                'group flex items-center w-full px-4 py-2 bg-transparent border-none cursor-pointer text-left text-[0.8125rem] text-foreground gap-2',
                'transition-colors duration-100 motion-reduce:transition-none',
                'hover:bg-accent',
                'focus:outline-2 focus:outline-primary focus:-outline-offset-2',
                'focus:not(:focus-visible):outline-none',
                activeView?.id === view.id && 'bg-primary/10 font-semibold hover:bg-primary/[0.12]'
              )}
              role="option"
              aria-selected={activeView?.id === view.id}
              onClick={() => handleSelectView(view.id)}
              data-testid={`view-option-${view.id}`}
            >
              {view.isDefault && (
                <span
                  className="shrink-0 text-xs text-amber-500 leading-none"
                  title="Default view"
                  role="button"
                  tabIndex={0}
                  onClick={(e) => handleSetDefault(e, view.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.stopPropagation()
                      onSetDefault(view.id)
                    }
                  }}
                  aria-label={`${view.name} is the default view`}
                >
                  &#9733;
                </span>
              )}
              {!view.isDefault && (
                <span
                  className="shrink-0 text-xs text-amber-500 leading-none opacity-30"
                  title="Set as default"
                  role="button"
                  tabIndex={0}
                  onClick={(e) => handleSetDefault(e, view.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.stopPropagation()
                      onSetDefault(view.id)
                    }
                  }}
                  aria-label={`Set ${view.name} as default view`}
                >
                  &#9734;
                </span>
              )}
              <span className="flex-1 min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">
                {view.name}
              </span>
              <button
                type="button"
                className={cn(
                  'shrink-0 inline-flex items-center justify-center w-5 h-5 p-0 bg-transparent border-none rounded cursor-pointer',
                  'text-xs leading-none text-muted-foreground opacity-0',
                  'transition-all duration-100 motion-reduce:transition-none',
                  'group-hover:opacity-100',
                  'hover:text-destructive hover:bg-destructive/10',
                  'focus:opacity-100 focus:outline-2 focus:outline-primary focus:-outline-offset-2',
                  'focus:not(:focus-visible):outline-none'
                )}
                onClick={(e) => handleDeleteView(e, view.id)}
                aria-label={`Delete view ${view.name}`}
                title={`Delete ${view.name}`}
                data-testid={`view-delete-${view.id}`}
              >
                &#x2715;
              </button>
            </button>
          ))}

          <hr className="h-px my-1 bg-border border-none" />

          {/* Save current view */}
          {!saving && (
            <button
              type="button"
              className={cn(
                'flex items-center w-full px-4 py-2 bg-transparent border-none cursor-pointer text-left text-[0.8125rem] font-medium text-primary gap-1.5',
                'transition-colors duration-100 motion-reduce:transition-none',
                'hover:bg-accent',
                'focus:outline-2 focus:outline-primary focus:-outline-offset-2',
                'focus:not(:focus-visible):outline-none'
              )}
              onClick={handleStartSave}
              data-testid="view-save-button"
            >
              <span aria-hidden="true">+</span>
              Save Current View
            </button>
          )}

          {saving && (
            <div className="flex items-center px-4 py-2 gap-2">
              <input
                ref={saveInputRef}
                type="text"
                className={cn(
                  'flex-1 min-w-0 px-2 py-1 text-[0.8125rem] text-foreground bg-background border border-input rounded',
                  'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring'
                )}
                value={saveName}
                onChange={(e) => setSaveName(e.target.value)}
                onKeyDown={handleSaveKeyDown}
                placeholder="View name..."
                aria-label="New view name"
                data-testid="view-save-input"
              />
              <button
                type="button"
                className={cn(
                  'shrink-0 inline-flex items-center justify-center px-2 py-1 text-xs font-medium',
                  'text-primary-foreground bg-primary border-none rounded cursor-pointer',
                  'transition-colors duration-150 motion-reduce:transition-none',
                  'hover:bg-primary/90',
                  'focus:outline-2 focus:outline-primary focus:outline-offset-2',
                  'focus:not(:focus-visible):outline-none',
                  'disabled:opacity-50 disabled:cursor-not-allowed'
                )}
                onClick={handleConfirmSave}
                disabled={!saveName.trim()}
                aria-label="Confirm save view"
                data-testid="view-save-confirm"
              >
                Save
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
