/**
 * UserDetailPage tests — focused on the admin-on-behalf-of PAT mint action
 * (Security tab, MANAGE_USERS-gated), added alongside the existing MFA reset
 * and password reset flows.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { UserDetailPage } from './UserDetailPage'
import { I18nProvider } from '../../context/I18nContext'

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return { ...actual, useParams: () => ({ id: 'user-1' }), useNavigate: () => vi.fn() }
})

vi.mock('../../context/TenantContext', () => ({ getTenantSlug: () => 't' }))

const hasPermission = vi.fn()
vi.mock('../../hooks/useSystemPermissions', () => ({
  useSystemPermissions: () => ({ hasPermission, isLoading: false }),
}))

const showToast = vi.fn()
vi.mock('../../components/Toast', () => ({ useToast: () => ({ showToast }) }))

const getOne = vi.fn()
const getList = vi.fn()
const mintTokenCreate = vi.fn()

vi.mock('../../context/ApiContext', () => ({
  useApi: () => ({
    apiClient: {
      getOne,
      getPage: vi.fn(),
      getList,
      putResource: vi.fn(),
      patchResource: vi.fn(),
    },
    keltaClient: {
      admin: {
        mfa: { getUserStatus: vi.fn(), resetUser: vi.fn() },
        users: {
          resetPassword: vi.fn(),
          tokens: { create: mintTokenCreate },
        },
      },
    },
  }),
}))

const USER = {
  id: 'user-1',
  email: 'target@example.com',
  firstName: 'Target',
  lastName: 'User',
  username: 'target',
  status: 'ACTIVE' as const,
  locale: 'en',
  timezone: 'UTC',
  loginCount: 3,
  mfaEnabled: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <MemoryRouter>
          <UserDetailPage />
        </MemoryRouter>
      </I18nProvider>
    </QueryClientProvider>
  )
}

async function goToSecurityTab() {
  await waitFor(() => expect(screen.getByText('Target User')).toBeInTheDocument())
  fireEvent.click(screen.getByText('Security'))
}

describe('UserDetailPage — admin PAT mint', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getOne.mockResolvedValue(USER)
    getList.mockResolvedValue([])
  })

  it('hides the mint-token action for a caller without MANAGE_USERS', async () => {
    hasPermission.mockReturnValue(false)
    renderPage()

    await goToSecurityTab()

    expect(screen.queryByTestId('mint-token-button')).not.toBeInTheDocument()
  })

  it('shows the mint-token action for a MANAGE_USERS holder and opens the form', async () => {
    hasPermission.mockReturnValue(true)
    renderPage()

    await goToSecurityTab()

    const button = await screen.findByTestId('mint-token-button')
    fireEvent.click(button)

    expect(screen.getByTestId('mint-token-form-modal')).toBeInTheDocument()
  })

  it('rejects an empty name without calling the mint endpoint', async () => {
    hasPermission.mockReturnValue(true)
    renderPage()

    await goToSecurityTab()
    fireEvent.click(await screen.findByTestId('mint-token-button'))
    fireEvent.click(screen.getByTestId('mint-token-submit'))

    expect(screen.getByRole('alert')).toHaveTextContent('Name is required')
    expect(mintTokenCreate).not.toHaveBeenCalled()
  })

  it('mints a token for the target user id (not the caller) and displays it once', async () => {
    hasPermission.mockReturnValue(true)
    mintTokenCreate.mockResolvedValue({
      token: 'klt_admin_minted_token',
      name: 'Integration bot',
      tokenPrefix: 'klt_admi',
      scopes: ['api'],
      expiresAt: '2027-01-01T00:00:00Z',
    })
    renderPage()

    await goToSecurityTab()
    fireEvent.click(await screen.findByTestId('mint-token-button'))
    fireEvent.change(screen.getByTestId('mint-token-name-input'), {
      target: { value: 'Integration bot' },
    })
    fireEvent.click(screen.getByTestId('mint-token-submit'))

    await waitFor(() => expect(mintTokenCreate).toHaveBeenCalledTimes(1))
    expect(mintTokenCreate).toHaveBeenCalledWith('user-1', {
      name: 'Integration bot',
      expiresInDays: 90,
    })

    await waitFor(() =>
      expect(screen.getByTestId('minted-token-value')).toHaveTextContent('klt_admin_minted_token')
    )
    expect(showToast).toHaveBeenCalledWith('Token minted successfully', 'success')
  })
})
