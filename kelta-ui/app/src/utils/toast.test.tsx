/**
 * `showToast` renders through the real `sonner` viewport: the variant chosen at run time
 * becomes the toast's `data-type`.
 */
import { describe, it, expect } from 'vitest'
import { act, render, waitFor } from '@testing-library/react'
import { Toaster } from 'sonner'
import { showToast } from './toast'

describe('showToast', () => {
  it.each(['success', 'error', 'info', 'warning'] as const)(
    'renders a %s toast in the sonner viewport',
    async (type) => {
      render(<Toaster />)
      act(() => {
        showToast(`A ${type} message`, type)
      })
      await waitFor(() => {
        const toast = document.querySelector(`[data-sonner-toast][data-type="${type}"]`)
        expect(toast?.textContent).toContain(`A ${type} message`)
      })
    }
  )

  it('defaults to an info toast', async () => {
    render(<Toaster />)
    act(() => {
      showToast('Plain message')
    })
    await waitFor(() =>
      expect(document.querySelector('[data-sonner-toast][data-type="info"]')).not.toBeNull()
    )
  })
})
