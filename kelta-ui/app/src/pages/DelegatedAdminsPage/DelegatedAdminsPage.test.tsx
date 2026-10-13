/**
 * DelegatedAdminsPage tests — lists scopes, opens the editor with profile options,
 * creates a scope from the entered name, and deletes a scope through confirmation.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { DelegatedAdminScope } from '@kelta/sdk'
import { DelegatedAdminsPage } from './DelegatedAdminsPage'
import { I18nProvider } from '../../context/I18nContext'

const scopesList = vi.fn()
const scopesCreate = vi.fn()
const scopesUpdate = vi.fn()
const scopesDelete = vi.fn()
const usersList = vi.fn()
const profilesList = vi.fn()

vi.mock('../../context/ApiContext', () => ({
  useApi: () => ({
    keltaClient: {
      admin: {
        delegatedAdminScopes: {
          list: scopesList,
          create: scopesCreate,
          update: scopesUpdate,
          delete: scopesDelete,
        },
        users: { list: usersList },
        profiles: { list: profilesList },
      },
    },
  }),
}))

const { mockToast } = vi.hoisted(() => ({
  mockToast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
}))
vi.mock('sonner', async (importOriginal) => ({
  ...(await importOriginal<typeof import('sonner')>()),
  toast: mockToast,
}))

const SCOPE: DelegatedAdminScope = {
  id: 'scope-1',
  name: 'Support Team',
  description: 'Front-line support delegates',
  active: true,
  delegatedUserIds: ['u1'],
  manageableProfileIds: ['p1'],
  canCreateUsers: true,
  canDeactivateUsers: false,
  canResetPasswords: false,
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <DelegatedAdminsPage />
      </I18nProvider>
    </QueryClientProvider>
  )
}

describe('DelegatedAdminsPage', () => {
  beforeEach(() => {
    scopesList.mockReset().mockResolvedValue([])
    scopesCreate.mockReset().mockResolvedValue(SCOPE)
    scopesUpdate.mockReset().mockResolvedValue(SCOPE)
    scopesDelete.mockReset().mockResolvedValue(undefined)
    usersList.mockReset().mockResolvedValue({ content: [] })
    profilesList.mockReset().mockResolvedValue([
      { id: 'p1', name: 'Standard User' },
      { id: 'p2', name: 'Manager' },
    ])
  })

  it('shows the empty state when there are no scopes', async () => {
    scopesList.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText('No delegated-admin scopes yet')).toBeInTheDocument()
  })

  it('renders a scope row with Edit and Delete actions', async () => {
    scopesList.mockResolvedValue([SCOPE])
    renderPage()
    expect(await screen.findByText('Support Team')).toBeInTheDocument()
    expect(screen.getByText('Edit')).toBeInTheDocument()
    expect(screen.getByText('Delete')).toBeInTheDocument()
  })

  it('opens the editor with profile options from profiles.list on New Scope', async () => {
    scopesList.mockResolvedValue([])
    renderPage()
    await screen.findByText('No delegated-admin scopes yet')

    fireEvent.click(screen.getByText('New Scope'))

    expect(await screen.findByTestId('scope-editor')).toBeInTheDocument()
    const picker = await screen.findByTestId('picker-profiles')
    await waitFor(() => {
      expect(picker).toHaveTextContent('Standard User')
      expect(picker).toHaveTextContent('Manager')
    })
  })

  it('creates a scope with the entered name on Save', async () => {
    scopesList.mockResolvedValue([])
    renderPage()
    await screen.findByText('No delegated-admin scopes yet')

    fireEvent.click(screen.getByText('New Scope'))
    await screen.findByTestId('scope-editor')

    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'New Delegated Scope' } })
    fireEvent.click(screen.getByText('Save'))

    await waitFor(() => expect(scopesCreate).toHaveBeenCalledTimes(1))
    expect(scopesCreate.mock.calls[0][0]).toMatchObject({ name: 'New Delegated Scope' })
  })

  it('deletes a scope after confirmation', async () => {
    scopesList.mockResolvedValue([SCOPE])
    renderPage()
    await screen.findByText('Support Team')

    fireEvent.click(screen.getByText('Delete'))

    // Confirmation dialog: the destructive confirm button is the second "Delete".
    const deleteButtons = await screen.findAllByText('Delete')
    fireEvent.click(deleteButtons[deleteButtons.length - 1])

    await waitFor(() => expect(scopesDelete).toHaveBeenCalledWith('scope-1'))
  })
})
