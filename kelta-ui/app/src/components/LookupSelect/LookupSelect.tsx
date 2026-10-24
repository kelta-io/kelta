/**
 * LookupSelect Component
 *
 * Searchable dropdown for selecting records from a related collection.
 * Used in resource forms for LOOKUP, MASTER_DETAIL, and REFERENCE field types.
 */

import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react'
import { cn } from '@/lib/utils'

export interface LookupOption {
  id: string
  label: string
}

export interface LookupSelectProps {
  id?: string
  name?: string
  value: string
  options: LookupOption[]
  onChange: (value: string) => void
  placeholder?: string
  required?: boolean
  disabled?: boolean
  error?: boolean
  className?: string
  'data-testid'?: string
  /** Id of the element describing the control (e.g. its field-error alert) */
  'aria-describedby'?: string
}

export function LookupSelect({
  id,
  name,
  value,
  options,
  onChange,
  placeholder = 'Select...',
  required = false,
  disabled = false,
  error = false,
  className,
  'data-testid': testId,
  'aria-describedby': ariaDescribedBy,
}: LookupSelectProps): React.ReactElement {
  const [isOpen, setIsOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [highlightedIndex, setHighlightedIndex] = useState(-1)
  const containerRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  const selectedOption = useMemo(() => options.find((o) => o.id === value), [options, value])

  const filteredOptions = useMemo(() => {
    if (!search.trim()) return options
    const lower = search.toLowerCase()
    return options.filter((o) => o.label.toLowerCase().includes(lower))
  }, [options, search])

  const open = useCallback(() => {
    if (disabled) return
    setIsOpen(true)
    setSearch('')
    setHighlightedIndex(-1)
    requestAnimationFrame(() => {
      searchInputRef.current?.focus()
    })
  }, [disabled])

  const close = useCallback(() => {
    setIsOpen(false)
    setSearch('')
    setHighlightedIndex(-1)
  }, [])

  const handleSelect = useCallback(
    (optionId: string) => {
      onChange(optionId)
      close()
    },
    [onChange, close]
  )

  const handleClear = useCallback(
    (e: React.MouseEvent | React.KeyboardEvent) => {
      e.stopPropagation()
      onChange('')
      close()
    },
    [onChange, close]
  )

  const handleTriggerClick = useCallback(() => {
    if (isOpen) {
      close()
    } else {
      open()
    }
  }, [isOpen, open, close])

  const scrollOptionIntoView = useCallback((index: number) => {
    requestAnimationFrame(() => {
      const list = listRef.current
      if (!list) return
      const item = list.children[index] as HTMLElement
      if (item) {
        item.scrollIntoView({ block: 'nearest' })
      }
    })
  }, [])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!isOpen) {
        if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
          e.preventDefault()
          open()
        }
        return
      }

      switch (e.key) {
        case 'Escape':
          e.preventDefault()
          close()
          containerRef.current?.querySelector<HTMLButtonElement>('[data-trigger]')?.focus()
          break
        case 'ArrowDown':
          e.preventDefault()
          setHighlightedIndex((prev) => {
            const next = prev < filteredOptions.length - 1 ? prev + 1 : 0
            scrollOptionIntoView(next)
            return next
          })
          break
        case 'ArrowUp':
          e.preventDefault()
          setHighlightedIndex((prev) => {
            const next = prev > 0 ? prev - 1 : filteredOptions.length - 1
            scrollOptionIntoView(next)
            return next
          })
          break
        case 'Enter':
          e.preventDefault()
          if (highlightedIndex >= 0 && highlightedIndex < filteredOptions.length) {
            handleSelect(filteredOptions[highlightedIndex].id)
          }
          break
      }
    },
    [isOpen, open, close, filteredOptions, highlightedIndex, handleSelect, scrollOptionIntoView]
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

  const displayText = selectedOption ? selectedOption.label : value ? value : ''

  const showClear = value && !required && !disabled

  const listboxId = `${id || name || 'lookup'}-listbox`

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
          'flex items-center justify-between w-full min-h-[38px] px-3 py-2 text-sm text-foreground bg-background border border-input rounded-md',
          'cursor-pointer text-left',
          'transition-colors duration-200 motion-reduce:transition-none',
          'hover:border-muted-foreground/50',
          'focus:outline-none focus:border-primary focus:ring-2 focus:ring-ring',
          error && 'border-destructive',
          disabled && 'opacity-60 cursor-not-allowed bg-muted'
        )}
        onClick={handleTriggerClick}
        disabled={disabled}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        aria-controls={isOpen ? listboxId : undefined}
        aria-invalid={error || undefined}
        aria-describedby={ariaDescribedBy}
        data-testid={testId ? `${testId}-trigger` : undefined}
      >
        <span
          className={cn(
            'flex-1 overflow-hidden text-ellipsis whitespace-nowrap',
            !displayText && 'text-muted-foreground',
            value && !selectedOption && 'italic text-muted-foreground'
          )}
        >
          {displayText || placeholder}
        </span>
        <span className="flex items-center gap-1 ml-2 shrink-0">
          {showClear && (
            <span
              role="button"
              tabIndex={-1}
              className="flex items-center justify-center w-[18px] h-[18px] text-sm text-muted-foreground cursor-pointer rounded-full transition-colors duration-150 motion-reduce:transition-none hover:bg-accent hover:text-foreground"
              onClick={handleClear}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  handleClear(e)
                }
              }}
              aria-label="Clear selection"
              data-testid={testId ? `${testId}-clear` : undefined}
            >
              &times;
            </span>
          )}
          <span
            className={cn(
              'text-[10px] text-muted-foreground transition-transform duration-200 motion-reduce:transition-none',
              isOpen && 'rotate-180'
            )}
            aria-hidden="true"
          >
            &#9662;
          </span>
        </span>
      </button>

      {isOpen && (
        <div
          className="absolute top-[calc(100%+4px)] left-0 right-0 z-50 bg-background border border-input rounded-md shadow-md"
          role="presentation"
          data-testid={testId ? `${testId}-dropdown` : undefined}
        >
          <input
            ref={searchInputRef}
            type="text"
            className="w-full px-3 py-2 text-sm text-foreground bg-background border-none border-b border-input rounded-t-md outline-none box-border focus:border-b-primary"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setHighlightedIndex(-1)
            }}
            placeholder="Search..."
            aria-label="Search options"
            data-testid={testId ? `${testId}-search` : undefined}
          />
          <ul
            ref={listRef}
            id={listboxId}
            className="list-none m-0 py-1 max-h-[250px] overflow-y-auto"
            role="listbox"
            aria-label={name || 'Options'}
          >
            {filteredOptions.length === 0 ? (
              <li
                className="px-4 py-3 text-sm text-muted-foreground text-center cursor-default"
                role="option"
                aria-selected={false}
              >
                No records found
              </li>
            ) : (
              filteredOptions.map((option, index) => (
                <li
                  key={option.id}
                  role="option"
                  aria-selected={option.id === value}
                  className={cn(
                    'px-3 py-2 text-sm text-foreground cursor-pointer transition-colors duration-100 motion-reduce:transition-none',
                    'hover:bg-accent',
                    option.id === value && 'font-medium text-primary',
                    index === highlightedIndex && 'bg-accent'
                  )}
                  onClick={() => handleSelect(option.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      handleSelect(option.id)
                    }
                  }}
                  onMouseEnter={() => setHighlightedIndex(index)}
                  data-testid={testId ? `${testId}-option-${index}` : undefined}
                >
                  {option.label}
                </li>
              ))
            )}
          </ul>
        </div>
      )}
    </div>
  )
}
