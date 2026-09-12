/**
 * UserMenu Component Tests
 *
 * Tests for the shared UserMenu component used by both
 * admin Header and app TopNavBar.
 *
 * We mock the shadcn DropdownMenu components to avoid Radix/floating-ui
 * ResizeObserver issues in jsdom. The dropdown components render their
 * children directly, letting us test the UserMenu's logic and rendering.
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { User } from '../../types/auth'

// Mock react-router-dom
const mockNavigate = vi.fn()
vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ tenantSlug: 'default' }),
}))

// Mock I18nContext
const mockSetLocale = vi.fn()
vi.mock('../../context/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en',
    setLocale: mockSetLocale,
    // Honour the fallback argument the real t() takes: items added with
    // t('key', 'Fallback') render the fallback, not the key.
    t: (key: string, fallback?: string) => {
      const translations: Record<string, string> = {
        'userMenu.language': 'Language',
        'userMenu.theme': 'Theme',
        'userMenu.profile': 'Profile',
        'userMenu.logout': 'Log Out',
        'userMenu.switchToSetup': 'Switch to Setup',
        'userMenu.backToApp': 'Back to App',
        'userMenu.lightMode': 'Light',
        'userMenu.darkMode': 'Dark',
        'userMenu.systemMode': 'System',
      }
      return translations[key] || fallback || key
    },
    supportedLocales: ['en', 'ar', 'fr', 'de', 'es', 'pt'],
    getLocaleDisplayName: (code: string) => {
      const names: Record<string, string> = {
        en: 'English',
        ar: 'العربية',
        fr: 'Français',
        de: 'Deutsch',
        es: 'Español',
        pt: 'Português',
      }
      return names[code] || code
    },
  }),
}))

// Mock ThemeContext
const mockSetMode = vi.fn()
vi.mock('../../context/ThemeContext', () => ({
  useTheme: () => ({
    mode: 'light',
    setMode: mockSetMode,
    resolvedMode: 'light',
  }),
}))

// Mock useSystemPermissions. Tests that need a different grant mutate `granted`; the default
// mirrors a plain app user who can reach Setup and nothing else.
const granted = new Set<string>(['VIEW_SETUP'])
vi.mock('../../hooks/useSystemPermissions', () => ({
  useSystemPermissions: () => ({
    hasPermission: (perm: string) => granted.has(perm),
  }),
}))

// Mock Gravatar utility
vi.mock('../../utils/gravatar', () => ({
  getGravatarUrl: vi.fn(() => null),
}))

// Mock shadcn DropdownMenu components to render children directly
// This avoids Radix/floating-ui issues with ResizeObserver in jsdom
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: { children: React.ReactNode; asChild?: boolean }) => (
    <div>{children}</div>
  ),
  DropdownMenuContent: ({
    children,
    ...props
  }: React.PropsWithChildren<Record<string, unknown>>) => <div {...props}>{children}</div>,
  DropdownMenuItem: ({
    children,
    onClick,
    ...props
  }: React.PropsWithChildren<{ onClick?: () => void } & Record<string, unknown>>) => (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/interactive-supports-focus
    <div role="menuitem" onClick={onClick} {...props}>
      {children}
    </div>
  ),
  DropdownMenuLabel: ({ children, ...props }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div {...props}>{children}</div>
  ),
  DropdownMenuSeparator: () => <hr />,
  DropdownMenuSub: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuSubTrigger: ({
    children,
    ...props
  }: React.PropsWithChildren<Record<string, unknown>>) => <div {...props}>{children}</div>,
  DropdownMenuSubContent: ({
    children,
    ...props
  }: React.PropsWithChildren<Record<string, unknown>>) => <div {...props}>{children}</div>,
}))

// Mock Avatar components
vi.mock('@/components/ui/avatar', () => ({
  Avatar: ({ children, ...props }: React.PropsWithChildren<Record<string, unknown>>) => (
    <div {...props}>{children}</div>
  ),
  AvatarImage: ({
    src,
    onError,
    ...props
  }: { src: string; onError?: () => void } & Record<string, unknown>) => (
    <img src={src} alt="" onError={onError} {...props} />
  ),
  AvatarFallback: ({ children, ...props }: React.PropsWithChildren<Record<string, unknown>>) => (
    <span {...props}>{children}</span>
  ),
}))

// Mock Button
vi.mock('@/components/ui/button', () => ({
  Button: ({
    children,
    onClick,
    ...props
  }: React.PropsWithChildren<{ onClick?: () => void } & Record<string, unknown>>) => (
    <button onClick={onClick} {...props}>
      {children}
    </button>
  ),
}))

import { getGravatarUrl } from '../../utils/gravatar'
const mockGetGravatarUrl = vi.mocked(getGravatarUrl)

// Import component AFTER mocks
import { UserMenu, type UserMenuProps } from './UserMenu'

describe('UserMenu', () => {
  const defaultUser: User = {
    id: 'user-1',
    email: 'john.doe@example.com',
    name: 'John Doe',
    picture: undefined,
  }

  const defaultProps: UserMenuProps = {
    user: defaultUser,
    onLogout: vi.fn(),
    variant: 'app',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    mockGetGravatarUrl.mockReturnValue(null)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('Avatar Display', () => {
    it('should render user menu button', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('user-menu-button')).toBeInTheDocument()
    })

    it('should display user initials when no picture is provided', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('user-avatar-initials')).toBeInTheDocument()
      expect(screen.getByTestId('user-avatar-initials')).toHaveTextContent('JD')
    })

    it('should display OIDC picture when provided', () => {
      const userWithPicture = { ...defaultUser, picture: '/oidc-avatar.jpg' }
      render(<UserMenu {...defaultProps} user={userWithPicture} />)
      const avatarImage = screen.getByTestId('user-avatar-image')
      expect(avatarImage).toBeInTheDocument()
      expect(avatarImage).toHaveAttribute('src', '/oidc-avatar.jpg')
    })

    it('should display Gravatar when available', () => {
      mockGetGravatarUrl.mockReturnValue('https://www.gravatar.com/avatar/abc?s=64&d=404')
      render(<UserMenu {...defaultProps} />)
      const avatarImage = screen.getByTestId('user-avatar-image')
      expect(avatarImage).toBeInTheDocument()
      expect(avatarImage).toHaveAttribute('src', 'https://www.gravatar.com/avatar/abc?s=64&d=404')
    })

    it('should prefer OIDC picture over Gravatar', () => {
      mockGetGravatarUrl.mockReturnValue('https://www.gravatar.com/avatar/abc?s=64&d=404')
      const userWithPicture = { ...defaultUser, picture: '/oidc-avatar.jpg' }
      render(<UserMenu {...defaultProps} user={userWithPicture} />)
      const avatarImage = screen.getByTestId('user-avatar-image')
      expect(avatarImage).toHaveAttribute('src', '/oidc-avatar.jpg')
    })

    it('should display email initials when name is not provided', () => {
      const userWithoutName = { ...defaultUser, name: undefined }
      render(<UserMenu {...defaultProps} user={userWithoutName} />)
      expect(screen.getByTestId('user-avatar-initials')).toHaveTextContent('JO')
    })

    it('should fall back to initials when Gravatar fails to load', () => {
      mockGetGravatarUrl.mockReturnValue('https://www.gravatar.com/avatar/abc?s=64&d=404')
      render(<UserMenu {...defaultProps} />)

      // Initially shows Gravatar image
      const avatarImage = screen.getByTestId('user-avatar-image')
      expect(avatarImage).toBeInTheDocument()

      // Simulate image load error
      fireEvent.error(avatarImage)

      // Should now show initials only (no image)
      expect(screen.getByTestId('user-avatar-initials')).toHaveTextContent('JD')
      expect(screen.queryByTestId('user-avatar-image')).not.toBeInTheDocument()
    })
  })

  describe('User Name Display', () => {
    it('should display user name by default', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('user-name')).toHaveTextContent('John Doe')
    })

    it('should hide user name when compact is true', () => {
      render(<UserMenu {...defaultProps} compact={true} />)
      expect(screen.queryByTestId('user-name')).not.toBeInTheDocument()
    })

    it('should display email as fallback when name is not provided', () => {
      const userWithoutName = { ...defaultUser, name: undefined }
      render(<UserMenu {...defaultProps} user={userWithoutName} />)
      expect(screen.getByTestId('user-name')).toHaveTextContent('john.doe@example.com')
    })
  })

  describe('Dropdown Content', () => {
    it('should render dropdown content with user info', () => {
      render(<UserMenu {...defaultProps} />)
      const dropdown = screen.getByTestId('user-dropdown-menu')
      expect(dropdown).toBeInTheDocument()
      expect(dropdown).toHaveTextContent('John Doe')
      expect(dropdown).toHaveTextContent('john.doe@example.com')
    })

    it('should render logout button', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('logout-button')).toBeInTheDocument()
      expect(screen.getByTestId('logout-button')).toHaveTextContent('Log Out')
    })

    it('should call onLogout when logout is clicked', () => {
      const onLogout = vi.fn()
      render(<UserMenu {...defaultProps} onLogout={onLogout} />)

      fireEvent.click(screen.getByTestId('logout-button'))
      expect(onLogout).toHaveBeenCalledTimes(1)
    })
  })

  describe('Language Selector', () => {
    it('should show language submenu trigger', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('language-menu-trigger')).toBeInTheDocument()
      expect(screen.getByTestId('language-menu-trigger')).toHaveTextContent('Language')
    })

    it('should render all language options', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('language-option-en')).toHaveTextContent('English')
      expect(screen.getByTestId('language-option-ar')).toHaveTextContent('العربية')
      expect(screen.getByTestId('language-option-fr')).toHaveTextContent('Français')
      expect(screen.getByTestId('language-option-de')).toHaveTextContent('Deutsch')
      expect(screen.getByTestId('language-option-es')).toHaveTextContent('Español')
      expect(screen.getByTestId('language-option-pt')).toHaveTextContent('Português')
    })

    it('should call setLocale when a language is selected', () => {
      render(<UserMenu {...defaultProps} />)
      fireEvent.click(screen.getByTestId('language-option-fr'))
      expect(mockSetLocale).toHaveBeenCalledWith('fr')
    })
  })

  describe('Theme Selector', () => {
    it('should show theme submenu trigger', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('theme-menu-trigger')).toBeInTheDocument()
      expect(screen.getByTestId('theme-menu-trigger')).toHaveTextContent('Theme')
    })

    it('should render theme options', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('theme-option-light')).toHaveTextContent('Light')
      expect(screen.getByTestId('theme-option-dark')).toHaveTextContent('Dark')
      expect(screen.getByTestId('theme-option-system')).toHaveTextContent('System')
    })

    it('should call setMode when a theme is selected', () => {
      render(<UserMenu {...defaultProps} />)
      fireEvent.click(screen.getByTestId('theme-option-dark'))
      expect(mockSetMode).toHaveBeenCalledWith('dark')
    })
  })

  describe('Variant-Specific Items', () => {
    it('should show "Switch to Setup" for app variant when user has VIEW_SETUP permission', () => {
      render(<UserMenu {...defaultProps} variant="app" />)
      expect(screen.getByTestId('switch-to-setup')).toBeInTheDocument()
      expect(screen.getByTestId('switch-to-setup')).toHaveTextContent('Switch to Setup')
    })

    it('should show "Back to App" for admin variant', () => {
      render(<UserMenu {...defaultProps} variant="admin" />)
      expect(screen.getByTestId('back-to-app-menu')).toBeInTheDocument()
      expect(screen.getByTestId('back-to-app-menu')).toHaveTextContent('Back to App')
    })

    it('shows "Support mailbox" for the app variant only with VIEW_SUPPORT_MAILBOX', () => {
      // Without the grant the item must be absent, not disabled: a visible entry to a console
      // the user cannot open reads as a broken feature.
      render(<UserMenu {...defaultProps} variant="app" />)
      expect(screen.queryByTestId('mailbox-menu-item')).not.toBeInTheDocument()
    })

    it('navigates to the support console when the user holds VIEW_SUPPORT_MAILBOX', () => {
      granted.add('VIEW_SUPPORT_MAILBOX')
      try {
        render(<UserMenu {...defaultProps} variant="app" />)
        const item = screen.getByTestId('mailbox-menu-item')
        expect(item).toHaveTextContent('Support mailbox')
        fireEvent.click(item)
        expect(mockNavigate).toHaveBeenCalledWith('/default/app/mailbox')
      } finally {
        granted.delete('VIEW_SUPPORT_MAILBOX')
      }
    })

    it('should not show "Switch to Setup" for admin variant', () => {
      render(<UserMenu {...defaultProps} variant="admin" />)
      expect(screen.queryByTestId('switch-to-setup')).not.toBeInTheDocument()
    })

    it('should not show "Back to App" for app variant', () => {
      render(<UserMenu {...defaultProps} variant="app" />)
      expect(screen.queryByTestId('back-to-app-menu')).not.toBeInTheDocument()
    })

    it('should show profile item', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('profile-menu-item')).toBeInTheDocument()
      expect(screen.getByTestId('profile-menu-item')).toHaveTextContent('Profile')
    })

    it('should navigate to setup when "Switch to Setup" is clicked', () => {
      render(<UserMenu {...defaultProps} variant="app" />)
      fireEvent.click(screen.getByTestId('switch-to-setup'))
      expect(mockNavigate).toHaveBeenCalledWith('/default/setup')
    })

    it('should navigate to app when "Back to App" is clicked', () => {
      render(<UserMenu {...defaultProps} variant="admin" />)
      fireEvent.click(screen.getByTestId('back-to-app-menu'))
      expect(mockNavigate).toHaveBeenCalledWith('/default/app')
    })
  })

  describe('Initials Generation', () => {
    it('should generate initials from full name', () => {
      render(<UserMenu {...defaultProps} />)
      expect(screen.getByTestId('user-avatar-initials')).toHaveTextContent('JD')
    })

    it('should generate initials from single name', () => {
      const userWithSingleName = { ...defaultUser, name: 'John' }
      render(<UserMenu {...defaultProps} user={userWithSingleName} />)
      expect(screen.getByTestId('user-avatar-initials')).toHaveTextContent('JO')
    })

    it('should generate initials from three-part name', () => {
      const userWithThreePartName = { ...defaultUser, name: 'John Michael Doe' }
      render(<UserMenu {...defaultProps} user={userWithThreePartName} />)
      expect(screen.getByTestId('user-avatar-initials')).toHaveTextContent('JM')
    })
  })
})
