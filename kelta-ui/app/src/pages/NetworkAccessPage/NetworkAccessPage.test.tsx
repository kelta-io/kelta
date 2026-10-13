import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NetworkAccessPage } from './NetworkAccessPage'

const save = vi.fn().mockResolvedValue(undefined)

let hookState = {
  allowlist: { enabled: false, cidrs: [] as string[] },
  isLoading: false,
  error: null as Error | null,
  save,
  isSaving: false,
}

vi.mock('@/hooks/useTenantIpAllowlist', () => ({
  useTenantIpAllowlist: () => hookState,
}))

const { mockToast } = vi.hoisted(() => ({
  mockToast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
}))
vi.mock('sonner', async (importOriginal) => ({
  ...(await importOriginal<typeof import('sonner')>()),
  toast: mockToast,
}))

describe('NetworkAccessPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    hookState = {
      allowlist: { enabled: false, cidrs: [] },
      isLoading: false,
      error: null,
      save,
      isSaving: false,
    }
  })

  it('renders existing ranges', () => {
    hookState.allowlist = { enabled: true, cidrs: ['10.0.0.0/8', '192.168.0.0/16'] }
    render(<NetworkAccessPage />)
    expect(screen.getByText('10.0.0.0/8')).toBeInTheDocument()
    expect(screen.getByText('192.168.0.0/16')).toBeInTheDocument()
    expect(screen.getAllByTestId('cidr-row')).toHaveLength(2)
  })

  it('disables Add and shows an error for an invalid CIDR', () => {
    render(<NetworkAccessPage />)
    fireEvent.change(screen.getByTestId('cidr-input'), { target: { value: 'nope' } })
    expect(screen.getByTestId('add-cidr')).toBeDisabled()
    expect(screen.getByTestId('cidr-error')).toBeInTheDocument()
  })

  it('adds a valid range and removes it', async () => {
    const user = userEvent.setup()
    render(<NetworkAccessPage />)

    fireEvent.change(screen.getByTestId('cidr-input'), { target: { value: '10.0.0.0/8' } })
    await user.click(screen.getByTestId('add-cidr'))
    expect(screen.getByText('10.0.0.0/8')).toBeInTheDocument()

    await user.click(screen.getByTestId('remove-cidr'))
    expect(screen.queryByText('10.0.0.0/8')).not.toBeInTheDocument()
    expect(screen.getByTestId('cidr-empty')).toBeInTheDocument()
  })

  it('saves the enabled flag and ranges', async () => {
    const user = userEvent.setup()
    hookState.allowlist = { enabled: true, cidrs: ['10.0.0.0/8'] }
    render(<NetworkAccessPage />)

    await user.click(screen.getByTestId('save-network-access'))

    await waitFor(() => {
      expect(save).toHaveBeenCalledWith({ enabled: true, cidrs: ['10.0.0.0/8'] })
    })
    expect(mockToast.success).toHaveBeenCalledWith('Network access settings saved')
  })

  it('blocks saving when enabled with no ranges', async () => {
    const user = userEvent.setup()
    hookState.allowlist = { enabled: true, cidrs: [] }
    render(<NetworkAccessPage />)

    await user.click(screen.getByTestId('save-network-access'))

    expect(save).not.toHaveBeenCalled()
    expect(mockToast.error).toHaveBeenCalledWith(
      expect.stringContaining('Add at least one allowed range')
    )
  })
})
