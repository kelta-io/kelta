import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'

import { ProviderAvailabilityPage } from './ProviderAvailabilityPage'

// Echo i18n keys (and honor a string default) so assertions target stable text.
vi.mock('../../context/I18nContext', () => ({
  useI18n: vi.fn(() => ({ locale: 'en', t: (key: string) => key })),
}))

const { mockToast } = vi.hoisted(() => ({
  mockToast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
}))
vi.mock('sonner', async (importOriginal) => ({
  ...(await importOriginal<typeof import('sonner')>()),
  toast: mockToast,
}))

const get = vi.fn()
const put = vi.fn()
vi.mock('../../context/ApiContext', () => ({
  useApi: vi.fn(() => ({ apiClient: { get, put } })),
}))

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    React.createElement(
      QueryClientProvider,
      { client: qc },
      React.createElement(ProviderAvailabilityPage)
    )
  )
}

describe('ProviderAvailabilityPage', () => {
  beforeEach(() => {
    Object.values(mockToast).forEach((fn) => fn.mockReset())
    get.mockReset()
    put.mockReset()
    put.mockResolvedValue({})
  })

  it('loads the schedule and renders a saved window', async () => {
    get.mockResolvedValue({
      timezone: 'Europe/Lisbon',
      rules: [{ weekday: 1, startTime: '09:00:00', endTime: '13:00' }],
      exceptions: [],
    })
    renderPage()

    // Monday label is derived via Intl (no hardcoded translations).
    await waitFor(() => expect(screen.getByText('Monday')).toBeInTheDocument())
    // "09:00:00" from the server is normalized to the HH:mm the time input expects.
    expect(screen.getByDisplayValue('09:00')).toBeInTheDocument()
    expect(screen.getByDisplayValue('13:00')).toBeInTheDocument()
  })

  it('saves the edited schedule to the scoped endpoint (provider id never in body)', async () => {
    get.mockResolvedValue({
      timezone: 'Europe/Lisbon',
      rules: [{ weekday: 1, startTime: '09:00', endTime: '13:00' }],
      exceptions: [],
    })
    renderPage()
    await waitFor(() => expect(screen.getByDisplayValue('09:00')).toBeInTheDocument())

    fireEvent.click(screen.getByText('availability.save'))

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1))
    const [url, body] = put.mock.calls[0]
    expect(url).toBe('/api/telehealth/availability/me')
    expect(body).toMatchObject({
      timezone: 'Europe/Lisbon',
      rules: [{ weekday: 1, startTime: '09:00', endTime: '13:00' }],
    })
    expect(JSON.stringify(body)).not.toContain('providerId')
    await waitFor(() => expect(mockToast.success).toHaveBeenCalledWith('availability.saved'))
  })

  it('disables save when a window ends before it starts', async () => {
    get.mockResolvedValue({
      timezone: 'Europe/Lisbon',
      rules: [{ weekday: 1, startTime: '13:00', endTime: '09:00' }],
      exceptions: [],
    })
    renderPage()
    await waitFor(() => expect(screen.getByDisplayValue('13:00')).toBeInTheDocument())

    expect(screen.getByText('availability.save').closest('button')).toBeDisabled()
  })
})
