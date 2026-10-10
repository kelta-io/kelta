/**
 * ProfileDialog tests — the caller's own profile against a mocked /api/me/profile:
 * the form loads the saved values, a stored region locale maps onto the UI language,
 * save PATCHes only the attributes the user changed, and server errors surface.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const { mockGet, mockPatch, mockSetLocale, mockToast } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  mockPatch: vi.fn(),
  mockSetLocale: vi.fn(),
  mockToast: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: { get: mockGet, patch: mockPatch } }),
}))

vi.mock('sonner', () => ({ toast: mockToast }))

const supportedLocales = ['en', 'ar', 'fr', 'de', 'es', 'pt']
vi.mock('@/context/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en',
    setLocale: mockSetLocale,
    t: (_key: string, fallback?: string) => fallback ?? _key,
    supportedLocales,
    getLocaleDisplayName: (code: string) =>
      ({ en: 'English', pt: 'Português', fr: 'Français' })[code] ?? code,
  }),
}))

import { ProfileDialog } from './ProfileDialog'

function profileDoc(attributes: Record<string, unknown>) {
  return {
    data: {
      type: 'users',
      id: 'user-1',
      attributes: {
        email: 'sam@example.com',
        firstName: 'Sam',
        lastName: 'Member',
        locale: 'en_US',
        timezone: 'Europe/Lisbon',
        userType: 'PORTAL',
        ...attributes,
      },
    },
  }
}

function renderDialog(onOpenChange = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ProfileDialog open onOpenChange={onOpenChange} />
    </QueryClientProvider>
  )
  return { onOpenChange }
}

describe('ProfileDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads the saved profile into the form', async () => {
    mockGet.mockResolvedValue(profileDoc({}))
    renderDialog()

    expect(await screen.findByTestId('profile-first-name')).toHaveValue('Sam')
    expect(mockGet).toHaveBeenCalledWith('/api/me/profile')
    expect(screen.getByTestId('profile-last-name')).toHaveValue('Member')
    // en_US maps onto the "en" UI language
    expect(screen.getByTestId('profile-locale')).toHaveValue('en')
    expect(screen.getByTestId('profile-timezone')).toHaveValue('Europe/Lisbon')
    expect(screen.getByText('sam@example.com')).toBeInTheDocument()
  })

  it('saves only the changed attributes and closes', async () => {
    const user = userEvent.setup()
    mockGet.mockResolvedValue(profileDoc({}))
    mockPatch.mockResolvedValue(profileDoc({ firstName: 'Alex', locale: 'pt', timezone: 'UTC' }))
    const { onOpenChange } = renderDialog()

    const firstName = await screen.findByTestId('profile-first-name')
    await user.clear(firstName)
    await user.type(firstName, 'Alex')
    await user.selectOptions(screen.getByTestId('profile-locale'), 'pt')
    await user.selectOptions(screen.getByTestId('profile-timezone'), 'UTC')
    await user.click(screen.getByTestId('profile-save'))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(mockPatch).toHaveBeenCalledWith('/api/me/profile', {
      data: {
        type: 'users',
        attributes: { firstName: 'Alex', locale: 'pt', timezone: 'UTC' },
      },
    })
    expect(mockSetLocale).toHaveBeenCalledWith('pt')
    expect(mockToast.success).toHaveBeenCalledWith('Profile saved')
  })

  it('keeps an unrecognised stored locale selectable', async () => {
    mockGet.mockResolvedValue(profileDoc({ locale: 'ja_JP' }))
    renderDialog()

    expect(await screen.findByTestId('profile-locale')).toHaveValue('ja_JP')
  })

  it('shows the server error and stays open when the save is rejected', async () => {
    const user = userEvent.setup()
    mockGet.mockResolvedValue(profileDoc({}))
    mockPatch.mockRejectedValue(new Error('timezone must be an IANA time zone'))
    const { onOpenChange } = renderDialog()

    await user.click(await screen.findByTestId('profile-save'))

    await waitFor(() =>
      expect(mockToast.error).toHaveBeenCalledWith('timezone must be an IANA time zone')
    )
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('shows a load error and disables save when the profile cannot be read', async () => {
    mockGet.mockRejectedValue(new Error('404'))
    renderDialog()

    expect(await screen.findByTestId('profile-load-error')).toBeInTheDocument()
    expect(screen.getByTestId('profile-save')).toBeDisabled()
  })
})
