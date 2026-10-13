/**
 * The `sonner` toast viewport, themed with the app's resolved light/dark mode. Mounted once in
 * `App` — every `toast()` from `sonner` (record list, saved views, quick actions, …) renders
 * here; without it those calls show nothing.
 */
import React from 'react'
import { Toaster as Sonner } from 'sonner'
import { useTheme } from '@/context/ThemeContext'

export function Toaster(): React.ReactElement {
  const { resolvedMode } = useTheme()
  return (
    <Sonner
      theme={resolvedMode}
      position="bottom-right"
      closeButton
      style={
        {
          '--normal-bg': 'hsl(var(--popover))',
          '--normal-text': 'hsl(var(--popover-foreground))',
          '--normal-border': 'hsl(var(--border))',
          '--border-radius': 'var(--radius)',
        } as React.CSSProperties
      }
    />
  )
}
