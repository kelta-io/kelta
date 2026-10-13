/**
 * UsersPage tests — the delegated (scoped) path routes all reads/writes through
 * `keltaClient.admin.delegated.*`, gates create/deactivate on the caller's summary,
 * and limits the create-form profile choices; the full-admin path uses `admin.users.*`.
 * Also covers the AdminCrud shell (search toolbar survives a refetch) and the
 * delegated-edit / portal-invite / status-confirm dialogs.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { DelegatedAdminSummary } from '@kelta/sdk'
import { UsersPage } from './UsersPage'
import { I18nProvider } from '../../context/I18nContext'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return { ...actual, useNavigate: () => mockNavigate }
})

vi.mock('../../context/TenantContext', () => ({ getTenantSlug: () => 't' }))

const hasPermission = vi.fn()
vi.mock('../../hooks/useSystemPermissions', () => ({
  useSystemPermissions: () => ({ hasPermission, isLoading: false }),
}))

let delegatedReturn: { isDelegated: boolean; summary: DelegatedAdminSummary | undefined }
vi.mock('../../hooks/useDelegatedAdmin', () => ({
  useDelegatedAdmin: () => delegatedReturn,
}))

const sonnerToast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('sonner', () => ({ toast: sonnerToast }))

const adminUsersList = vi.fn()
const adminUsersCreate = vi.fn()
const adminUsersDeactivate = vi.fn()
const adminUsersInvitePortal = vi.fn()
const profilesList = vi.fn()
const delegatedUsersList = vi.fn()
const delegatedUsersCreate = vi.fn()
const delegatedUsersUpdate = vi.fn()

vi.mock('../../context/ApiContext', () => ({
  useApi: () => ({
    keltaClient: {
      admin: {
        users: {
          list: adminUsersList,
          create: adminUsersCreate,
          activate: vi.fn(),
          deactivate: adminUsersDeactivate,
          invitePortal: adminUsersInvitePortal,
        },
        profiles: { list: profilesList },
        delegated: {
          users: {
            list: delegatedUsersList,
            create: delegatedUsersCreate,
            update: delegatedUsersUpdate,
          },
        },
      },
    },
  }),
}))

const SCOPED_SUMMARY: DelegatedAdminSummary = {
  delegated: true,
  canCreateUsers: true,
  canDeactivateUsers: false,
  canResetPasswords: false,
  manageableProfiles: [{ id: 'p1', name: 'Standard User' }],
}

const NONE_SUMMARY: DelegatedAdminSummary = {
  delegated: false,
  canCreateUsers: false,
  canDeactivateUsers: false,
  canResetPasswords: false,
  manageableProfiles: [],
}

const DELEGATED_USER = {
  id: 'du1',
  email: 'scoped@example.com',
  firstName: 'Scoped',
  lastName: 'User',
  username: 'scoped',
  status: 'ACTIVE',
  locale: 'en',
  timezone: 'UTC',
  loginCount: 0,
  mfaEnabled: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const ADMIN_USER = {
  id: 'au1',
  email: 'admin-listed@example.com',
  firstName: 'Full',
  lastName: 'Listed',
  username: 'flisted',
  status: 'ACTIVE',
  locale: 'en',
  timezone: 'UTC',
  loginCount: 0,
  mfaEnabled: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <MemoryRouter initialEntries={['/t/users']}>
          <UsersPage />
        </MemoryRouter>
      </I18nProvider>
    </QueryClientProvider>
  )
}

describe('UsersPage', () => {
  beforeEach(() => {
    mockNavigate.mockClear()
    hasPermission.mockReset()
    adminUsersList.mockReset().mockResolvedValue({ content: [], totalPages: 0 })
    adminUsersCreate.mockReset().mockResolvedValue(ADMIN_USER)
    profilesList.mockReset().mockResolvedValue([
      { id: 'p1', name: 'Standard User' },
      { id: 'p2', name: 'System Administrator' },
    ])
    delegatedUsersList.mockReset().mockResolvedValue([])
    delegatedUsersCreate.mockReset().mockResolvedValue(DELEGATED_USER)
    delegatedUsersUpdate.mockReset().mockResolvedValue(DELEGATED_USER)
    adminUsersDeactivate.mockReset().mockResolvedValue(undefined)
    adminUsersInvitePortal.mockReset().mockResolvedValue({})
    sonnerToast.success.mockReset()
    sonnerToast.error.mockReset()
  })

  describe('delegated (scoped) mode', () => {
    beforeEach(() => {
      hasPermission.mockReturnValue(false) // no MANAGE_USERS
      delegatedReturn = { isDelegated: true, summary: SCOPED_SUMMARY }
      delegatedUsersList.mockResolvedValue([DELEGATED_USER])
    })

    it('reads through delegated.users.list, not admin.users.list', async () => {
      renderPage()
      await waitFor(() => expect(delegatedUsersList).toHaveBeenCalled())
      expect(adminUsersList).not.toHaveBeenCalled()
      expect(await screen.findByText('Scoped User')).toBeInTheDocument()
    })

    it('shows the delegated-scope badge', async () => {
      renderPage()
      await waitFor(() => expect(delegatedUsersList).toHaveBeenCalled())
      expect(screen.getByTestId('delegated-scope-badge')).toBeInTheDocument()
    })

    it("limits the create-form profile select to the caller's manageable profiles", async () => {
      renderPage()
      await waitFor(() => expect(delegatedUsersList).toHaveBeenCalled())

      // Create button is present because canCreateUsers=true.
      fireEvent.click(screen.getByText('Create User'))

      const select = (await screen.findByTestId('create-profile-select')) as HTMLSelectElement
      const optionLabels = Array.from(select.options).map((o) => o.textContent)
      // "Select..." placeholder + the single manageable profile, nothing else.
      expect(optionLabels).toContain('Standard User')
      expect(optionLabels).not.toContain('Admin')
      expect(select.querySelectorAll('option[value="p1"]')).toHaveLength(1)
    })

    it('edits a user in the delegated dialog, sending only a changed profile', async () => {
      renderPage()
      fireEvent.click(await screen.findByText('Scoped User'))

      const dialog = await screen.findByTestId('delegated-edit-form-modal')
      expect(within(dialog).getByText('Edit User')).toBeInTheDocument()
      expect(within(dialog).getByText('scoped@example.com')).toBeInTheDocument()
      expect(within(dialog).getByLabelText('First Name')).toHaveValue('Scoped')

      fireEvent.change(within(dialog).getByLabelText('First Name'), {
        target: { value: 'Renamed' },
      })
      fireEvent.change(within(dialog).getByTestId('edit-profile-select'), {
        target: { value: 'p1' },
      })
      fireEvent.click(within(dialog).getByTestId('delegated-edit-form-submit'))

      await waitFor(() =>
        expect(delegatedUsersUpdate).toHaveBeenCalledWith('du1', {
          firstName: 'Renamed',
          lastName: 'User',
          username: 'scoped',
          profileId: 'p1',
        })
      )
      await waitFor(() =>
        expect(screen.queryByTestId('delegated-edit-form-modal')).not.toBeInTheDocument()
      )
    })

    it('hides the Deactivate action when canDeactivateUsers=false', async () => {
      renderPage()
      // Seed an ACTIVE user; with canDeactivateUsers=false no Deactivate button should render.
      expect(await screen.findByText('Scoped User')).toBeInTheDocument()
      expect(screen.queryByText('Deactivate')).not.toBeInTheDocument()
    })
  })

  describe('full-admin (non-scoped) mode', () => {
    beforeEach(() => {
      hasPermission.mockReturnValue(true) // MANAGE_USERS granted
      delegatedReturn = { isDelegated: false, summary: NONE_SUMMARY }
      adminUsersList.mockResolvedValue({ content: [ADMIN_USER], totalPages: 1 })
    })

    it('reads through admin.users.list, not delegated.users.list', async () => {
      renderPage()
      await waitFor(() => expect(adminUsersList).toHaveBeenCalled())
      expect(delegatedUsersList).not.toHaveBeenCalled()
      expect(await screen.findByText('Full Listed')).toBeInTheDocument()
      // No delegated badge in full-admin mode.
      expect(screen.queryByTestId('delegated-scope-badge')).not.toBeInTheDocument()
    })

    it("offers the tenant's profiles in the create form", async () => {
      renderPage()
      await waitFor(() => expect(profilesList).toHaveBeenCalled())

      fireEvent.click(screen.getByText('Create User'))

      const select = (await screen.findByTestId('create-profile-select')) as HTMLSelectElement
      await waitFor(() =>
        expect(Array.from(select.options).map((o) => o.textContent)).toEqual(
          expect.arrayContaining(['Standard User', 'System Administrator'])
        )
      )
    })

    it('blocks submit until a profile is chosen, then creates with the profileId', async () => {
      renderPage()
      await waitFor(() => expect(adminUsersList).toHaveBeenCalled())

      fireEvent.click(screen.getByText('Create User'))
      fireEvent.change(await screen.findByLabelText(/Email/), {
        target: { value: 'new@example.com' },
      })
      fireEvent.change(screen.getByLabelText(/First Name/), { target: { value: 'New' } })
      fireEvent.change(screen.getByLabelText(/Last Name/), { target: { value: 'User' } })

      // No profile chosen — the submit is rejected client-side, never hitting the API.
      fireEvent.click(screen.getByText('Create'))
      expect(await screen.findByText('Profile is required')).toBeInTheDocument()
      expect(adminUsersCreate).not.toHaveBeenCalled()

      fireEvent.change(screen.getByTestId('create-profile-select'), { target: { value: 'p2' } })
      fireEvent.click(screen.getByText('Create'))
      await waitFor(() =>
        expect(adminUsersCreate).toHaveBeenCalledWith(
          expect.objectContaining({ email: 'new@example.com', profileId: 'p2' })
        )
      )
    })
    it('keeps the search box mounted and filled while the next page of results loads', async () => {
      renderPage()
      expect(await screen.findByText('Full Listed')).toBeInTheDocument()

      adminUsersList.mockReturnValue(new Promise(() => {}))
      const search = screen.getByLabelText('Search users...')
      fireEvent.change(search, { target: { value: 'ful' } })

      expect(await screen.findByTestId('loading-spinner-label')).toBeInTheDocument()
      expect(screen.getByLabelText('Search users...')).toBe(search)
      expect(search).toHaveValue('ful')
      expect(adminUsersList).toHaveBeenLastCalledWith('ful', undefined, 0, 20)
    })

    it('deactivates a user after the confirm dialog', async () => {
      renderPage()
      fireEvent.click(await screen.findByText('Deactivate'))

      const confirm = await screen.findByTestId('confirm-dialog')
      expect(confirm).toHaveTextContent('Are you sure you want to deactivate Full Listed?')
      expect(adminUsersDeactivate).not.toHaveBeenCalled()

      fireEvent.click(within(confirm).getByTestId('confirm-dialog-confirm'))
      await waitFor(() => expect(adminUsersDeactivate).toHaveBeenCalledWith('au1'))
      await waitFor(() =>
        expect(sonnerToast.success).toHaveBeenCalledWith('User deactivated successfully')
      )
    })

    it('validates and sends a portal invite', async () => {
      renderPage()
      await waitFor(() => expect(adminUsersList).toHaveBeenCalled())
      fireEvent.click(screen.getByTestId('invite-portal-user'))

      const dialog = await screen.findByTestId('portal-invite-form-modal')
      fireEvent.click(within(dialog).getByTestId('portal-invite-form-submit'))
      expect(await within(dialog).findByText('Email is required')).toBeInTheDocument()
      expect(adminUsersInvitePortal).not.toHaveBeenCalled()

      fireEvent.change(within(dialog).getByLabelText(/Email/), {
        target: { value: 'portal@example.com' },
      })
      fireEvent.change(within(dialog).getByLabelText('First Name'), {
        target: { value: 'Pat' },
      })
      fireEvent.click(within(dialog).getByTestId('portal-invite-form-submit'))

      await waitFor(() =>
        expect(adminUsersInvitePortal).toHaveBeenCalledWith({
          email: 'portal@example.com',
          firstName: 'Pat',
          lastName: undefined,
        })
      )
      await waitFor(() =>
        expect(screen.queryByTestId('portal-invite-form-modal')).not.toBeInTheDocument()
      )
    })
  })
})
