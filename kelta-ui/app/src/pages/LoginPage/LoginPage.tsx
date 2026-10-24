/**
 * LoginPage Component
 *
 * Displays the login page with OIDC provider selection.
 * Redirects to the OIDC provider for authentication.
 *
 * Requirements:
 * - 2.1: Redirect unauthenticated users to OIDC provider login page
 * - 2.2: Display provider selection page for multiple providers
 */

import React, { useEffect, useRef, useState, useMemo } from 'react'
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom'
import { KeyRound } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useConfig } from '../../context/ConfigContext'
import { useI18n } from '../../context/I18nContext'
import { LoadingSpinner } from '../../components/LoadingSpinner'
import { ErrorMessage } from '../../components/ErrorMessage'
import { cn } from '@/lib/utils'

/**
 * Props for the LoginPage component
 */
export interface LoginPageProps {
  /** Optional custom title */
  title?: string
}

/**
 * LoginPage Component
 *
 * Handles user authentication via OIDC providers.
 * Shows provider selection when multiple providers are configured.
 */
export function LoginPage({ title }: LoginPageProps): React.ReactElement {
  const { isAuthenticated, isLoading: authLoading, login, error: authError } = useAuth()
  const { config, isLoading: configLoading } = useConfig()
  const { t } = useI18n()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const [selectedProvider, setSelectedProvider] = useState<string | null>(null)
  const [loginError, setLoginError] = useState<Error | null>(null)
  const [isLoggingIn, setIsLoggingIn] = useState(false)

  // Check if user just logged out — skip auto-login so they can pick a different account.
  // Uses sessionStorage flag (set by AuthContext.logout) as primary signal, because
  // the OIDC provider may strip query params from the post_logout_redirect_uri.
  // Also checks URL param as fallback.
  const justLoggedOut = useMemo(
    () =>
      sessionStorage.getItem('kelta_auth_just_logged_out') === 'true' ||
      searchParams.get('logged_out') === 'true',
    [searchParams]
  )

  // Get the redirect path from location state
  const from =
    (location.state as { from?: { pathname: string } })?.from?.pathname || `/${getTenantSlug()}/app`

  // Redirect if already authenticated
  useEffect(() => {
    if (isAuthenticated && !authLoading) {
      navigate(from, { replace: true })
    }
  }, [isAuthenticated, authLoading, navigate, from])

  // Get OIDC providers from config — split internal from external.
  // All logins route through the internal kelta-auth provider; external
  // providers are surfaced as one-click buttons that pass an idp_hint.
  const providers = config?.oidcProviders || []
  const internalProvider = providers.find((p) => p.isInternal)
  const externalProviders = providers.filter((p) => !p.isInternal)

  // Track whether auto-login has been attempted to prevent duplicate calls
  const autoLoginAttempted = useRef(false)

  // Auto-login when there is a single sensible choice:
  //  - exactly one external provider (one-click SSO via kelta-auth federation), OR
  //  - only the internal provider (redirect straight to kelta-auth's login form).
  useEffect(() => {
    if (
      authLoading ||
      configLoading ||
      isAuthenticated ||
      authError ||
      autoLoginAttempted.current ||
      justLoggedOut
    ) {
      return
    }

    let targetId: string | null = null
    if (externalProviders.length === 1) {
      targetId = externalProviders[0].id
    } else if (externalProviders.length === 0 && internalProvider) {
      targetId = internalProvider.id
    }
    if (!targetId) return

    autoLoginAttempted.current = true
    sessionStorage.removeItem('kelta_auth_login_error')
    login(targetId).catch(() => {
      // Error will be shown via authError from AuthContext
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authLoading, configLoading, isAuthenticated, authError, justLoggedOut])

  /**
   * Handle login with a specific provider
   */
  const handleLogin = async (providerId: string) => {
    setIsLoggingIn(true)
    setLoginError(null)
    setSelectedProvider(providerId)

    // Clear any persisted login error and logout flag (user is explicitly logging in)
    sessionStorage.removeItem('kelta_auth_login_error')
    sessionStorage.removeItem('kelta_auth_just_logged_out')

    try {
      await login(providerId)
    } catch (err) {
      setLoginError(err instanceof Error ? err : new Error('Login failed'))
      setIsLoggingIn(false)
    }
  }

  // Show loading while checking auth or config
  if (authLoading || configLoading) {
    return (
      <div
        className="flex min-h-screen items-center justify-center bg-background p-6"
        data-testid="login-page"
      >
        <div className="w-full max-w-[400px] rounded-lg bg-card p-8 shadow-md">
          <LoadingSpinner size="large" label={t('login.checking')} />
        </div>
      </div>
    )
  }

  // Show error if auth error occurred
  const error = loginError || authError

  return (
    <div
      className="flex min-h-screen items-center justify-center bg-background p-6"
      data-testid="login-page"
    >
      <div className="w-full max-w-[400px] rounded-lg bg-card p-8 shadow-md max-[480px]:p-6">
        {/* Logo and branding */}
        <div className="mb-8 text-center">
          {config?.branding?.logoUrl && (
            <img
              src={config.branding.logoUrl}
              alt={config.branding.applicationName || 'Application logo'}
              className="mb-4 inline-block max-h-[60px] max-w-[120px]"
            />
          )}
          <h1 className="m-0 text-2xl font-semibold text-foreground">
            {title || config?.branding?.applicationName || t('login.title')}
          </h1>
        </div>

        {/* Error message */}
        {error && (
          <div className="mb-6">
            <ErrorMessage
              error={error}
              onRetry={() => {
                setLoginError(null)
                if (selectedProvider) {
                  handleLogin(selectedProvider)
                }
              }}
            />
          </div>
        )}

        {/* Provider selection */}
        <div className="mb-6">
          <h2 className="mb-4 text-center text-base font-medium text-muted-foreground">
            {t('login.selectProvider')}
          </h2>

          {providers.length === 0 ? (
            <p className="p-6 text-center text-muted-foreground">{t('login.noProviders')}</p>
          ) : (
            <div className="flex flex-col gap-2">
              {[...externalProviders, ...(internalProvider ? [internalProvider] : [])].map(
                (provider) => (
                  <button
                    key={provider.id}
                    type="button"
                    className={cn(
                      'flex w-full items-center gap-2 rounded-lg border border-border bg-muted p-4 text-left',
                      'transition-colors duration-200',
                      'hover:bg-accent hover:border-primary',
                      'focus:outline-2 focus:outline-offset-2 focus:outline-ring',
                      'disabled:cursor-not-allowed disabled:opacity-70'
                    )}
                    onClick={() => handleLogin(provider.id)}
                    disabled={isLoggingIn}
                    aria-busy={isLoggingIn && selectedProvider === provider.id}
                  >
                    {isLoggingIn && selectedProvider === provider.id ? (
                      <LoadingSpinner size="small" />
                    ) : (
                      <span className="text-2xl" aria-hidden="true">
                        <KeyRound size={20} />
                      </span>
                    )}
                    <span className="flex-1 font-medium text-foreground">{provider.name}</span>
                    <span className="max-w-[150px] truncate text-sm text-muted-foreground max-[480px]:hidden">
                      {provider.issuer}
                    </span>
                  </button>
                )
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <footer className="border-t border-border pt-4 text-center">
          <p className="m-0 text-sm text-muted-foreground">{t('login.footer')}</p>
        </footer>
      </div>
    </div>
  )
}
