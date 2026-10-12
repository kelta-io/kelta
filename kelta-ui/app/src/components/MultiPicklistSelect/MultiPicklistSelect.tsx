/**
 * MultiPicklistSelect Component
 *
 * Dropdown with checkboxes for selecting multiple values from a picklist.
 * Shows selected values as badges in the trigger button.
 * Used in resource forms for MULTI_PICKLIST field types.
 */

import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react'
import { X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'

export interface MultiPicklistSelectProps {
  id?: string
  name?: string
  /** Currently selected values */
  value: string[]
  /** Available picklist options */
  options: string[]
  /** Called when selection changes */
  onChange: (values: string[]) => void
  placeholder?: string
  required?: boolean
  disabled?: boolean
  className?: string
  /** Marks the control invalid (e.g. it has a field error) */
  'aria-invalid'?: boolean
  /** Id of the element describing the control (e.g. its field-error alert) */
  'aria-describedby'?: string
  /** `data-testid` for the control's root element */
  'data-testid'?: string
}

export function MultiPicklistSelect({
  id,
  name,
  value,
  options,
  onChange,
  placeholder = 'Select...',
  required = false,
  disabled = false,
  className,
  'aria-invalid': ariaInvalid,
  'aria-describedby': ariaDescribedBy,
  'data-testid': testId,
}: MultiPicklistSelectProps): React.ReactElement {
  const [isOpen, setIsOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  const selectedSet = useMemo(() => new Set(value), [value])

  const open = useCallback(() => {
    if (disabled) return
    setIsOpen(true)
  }, [disabled])

  const close = useCallback(() => {
    setIsOpen(false)
  }, [])

  const toggleOption = useCallback(
    (option: string) => {
      if (selectedSet.has(option)) {
        onChange(value.filter((v) => v !== option))
      } else {
        onChange([...value, option])
      }
    },
    [value, selectedSet, onChange]
  )

  const removeValue = useCallback(
    (option: string, e: React.MouseEvent) => {
      e.stopPropagation()
      onChange(value.filter((v) => v !== option))
    },
    [value, onChange]
  )

  const handleTriggerClick = useCallback(() => {
    if (isOpen) {
      close()
    } else {
      open()
    }
  }, [isOpen, open, close])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!isOpen) {
        if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
          e.preventDefault()
          open()
        }
        return
      }

      if (e.key === 'Escape') {
        e.preventDefault()
        close()
        containerRef.current?.querySelector<HTMLButtonElement>('[data-trigger]')?.focus()
      }
    },
    [isOpen, open, close]
  )

  // Close on click outside
  useEffect(() => {
    if (!isOpen) return

    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        close()
      }
    }

    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [isOpen, close])

  const listboxId = `${id || name || 'multi-picklist'}-listbox`

  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={containerRef}
      className={cn('relative w-full', className)}
      onKeyDown={handleKeyDown}
      data-testid={testId}
    >
      <button
        type="button"
        id={id}
        data-trigger="true"
        className={cn(
          'flex items-center justify-between w-full min-h-[36px] px-3 py-1.5 text-sm text-foreground bg-background border border-input rounded-md',
          'cursor-pointer text-left',
          'transition-colors duration-200 motion-reduce:transition-none',
          'hover:border-muted-foreground/50',
          'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
          disabled && 'opacity-60 cursor-not-allowed bg-muted'
        )}
        onClick={handleTriggerClick}
        disabled={disabled}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        aria-controls={isOpen ? listboxId : undefined}
        aria-required={required}
        aria-invalid={ariaInvalid || undefined}
        aria-describedby={ariaDescribedBy}
      >
        <span className="flex flex-1 flex-wrap items-center gap-1 overflow-hidden">
          {value.length === 0 ? (
            <span className="text-muted-foreground">{placeholder}</span>
          ) : (
            value.map((v) => (
              <Badge key={v} variant="secondary" className="max-w-[120px] gap-0.5 truncate text-xs">
                {v}
                {!disabled && (
                  <X
                    className="h-3 w-3 shrink-0 cursor-pointer opacity-60 hover:opacity-100"
                    onClick={(e) => removeValue(v, e)}
                    aria-label={`Remove ${v}`}
                  />
                )}
              </Badge>
            ))
          )}
        </span>
        <span
          className={cn(
            'ml-2 shrink-0 text-[10px] text-muted-foreground transition-transform duration-200 motion-reduce:transition-none',
            isOpen && 'rotate-180'
          )}
          aria-hidden="true"
        >
          &#9662;
        </span>
      </button>

      {isOpen && (
        <div
          className="absolute top-[calc(100%+4px)] left-0 right-0 z-50 bg-background border border-input rounded-md shadow-md"
          role="presentation"
        >
          <ul
            id={listboxId}
            className="list-none m-0 py-1 max-h-[250px] overflow-y-auto"
            role="listbox"
            aria-label={name || 'Options'}
            aria-multiselectable="true"
          >
            {options.length === 0 ? (
              <li className="px-4 py-3 text-sm text-muted-foreground text-center cursor-default">
                No options available
              </li>
            ) : (
              options.map((option) => {
                const isSelected = selectedSet.has(option)
                return (
                  <li
                    key={option}
                    role="option"
                    aria-selected={isSelected}
                    className={cn(
                      'flex items-center gap-2 px-3 py-2 text-sm text-foreground cursor-pointer transition-colors duration-100 motion-reduce:transition-none',
                      'hover:bg-accent',
                      isSelected && 'font-medium'
                    )}
                    onClick={() => toggleOption(option)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        toggleOption(option)
                      }
                    }}
                    tabIndex={0}
                  >
                    <span
                      className={cn(
                        'flex h-4 w-4 shrink-0 items-center justify-center rounded-sm border',
                        isSelected
                          ? 'border-primary bg-primary text-primary-foreground'
                          : 'border-muted-foreground/40'
                      )}
                      aria-hidden="true"
                    >
                      {isSelected && (
                        <svg
                          className="h-3 w-3"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                          strokeWidth={3}
                        >
                          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </span>
                    <span>{option}</span>
                  </li>
                )
              })
            )}
          </ul>
        </div>
      )}
    </div>
  )
}

export default MultiPicklistSelect
