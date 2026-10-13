/**
 * The deprecated `useToast` / `ToastProvider` adapter: every call forwards to `sonner`, so a
 * caller not yet migrated shows its toast in the app's single `<Toaster />` viewport.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, renderHook, screen } from '@testing-library/react'
import { ToastProvider, useToast } from './Toast'

const { mockToast } = vi.hoisted(() => ({
  mockToast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
    dismiss: vi.fn(),
  },
}))
vi.mock('sonner', async (importOriginal) => ({
  ...(await importOriginal<typeof import('sonner')>()),
  toast: mockToast,
}))

beforeEach(() => {
  Object.values(mockToast).forEach((fn) => fn.mockReset())
})

describe('useToast (deprecated sonner adapter)', () => {
  it('works without a ToastProvider', () => {
    const { result } = renderHook(() => useToast())
    expect(typeof result.current.showToast).toBe('function')
  })

  it.each(['success', 'error', 'info', 'warning'] as const)(
    'forwards a %s toast to the sonner method of the same name',
    (type) => {
      const { result } = renderHook(() => useToast())
      result.current.showToast('Saved', type)
      expect(mockToast[type]).toHaveBeenCalledWith(
        'Saved',
        expect.objectContaining({ id: expect.any(String) })
      )
    }
  )

  it('keeps an explicit duration and maps 0 to a toast that stays until closed', () => {
    const { result } = renderHook(() => useToast())
    result.current.showToast('Five seconds', 'info', 5000)
    result.current.showToast('Sticky', 'error', 0)
    expect(mockToast.info).toHaveBeenCalledWith(
      'Five seconds',
      expect.objectContaining({ duration: 5000 })
    )
    expect(mockToast.error).toHaveBeenCalledWith(
      'Sticky',
      expect.objectContaining({ duration: Infinity })
    )
  })

  it('leaves the duration to the Toaster default when none is given', () => {
    const { result } = renderHook(() => useToast())
    result.current.showToast('Default', 'success')
    expect(mockToast.success.mock.calls[0][1]).not.toHaveProperty('duration')
  })

  it('returns an id hideToast dismisses, and hideAllToasts dismisses every toast', () => {
    const { result } = renderHook(() => useToast())
    const first = result.current.showToast('One', 'info')
    const second = result.current.showToast('Two', 'info')
    expect(first).not.toBe(second)
    expect(mockToast.info).toHaveBeenCalledWith('One', expect.objectContaining({ id: first }))

    result.current.hideToast(first)
    expect(mockToast.dismiss).toHaveBeenCalledWith(first)

    result.current.hideAllToasts()
    expect(mockToast.dismiss).toHaveBeenLastCalledWith()
  })
})

describe('ToastProvider (deprecated)', () => {
  it('renders only its children', () => {
    render(
      <ToastProvider>
        <p>content</p>
      </ToastProvider>
    )
    expect(screen.getByText('content')).toBeInTheDocument()
    expect(document.querySelector('[data-testid="toast-container"]')).toBeNull()
  })
})
