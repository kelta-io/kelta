/**
 * UnauthorizedPage Component
 *
 * Displays when a user attempts to access a resource they don't have permission for.
 */

import React from 'react'
import { useNavigate, useLocation, Link } from 'react-router-dom'
import { ShieldOff } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useAuth } from '../../context/AuthContext'
import { cn } from '@/lib/utils'

/**
 * Props for the UnauthorizedPage component
 */
export interface UnauthorizedPageProps {
  /** Optional custom title */
  title?: string
  /** Optional custom message */
  message?: string
}

/**
 * UnauthorizedPage Component
 *
 * Shows an error message when the user lacks required permissions.
 * Provides options to go back, go home, or log out.
 */
export function UnauthorizedPage({ title, message }: UnauthorizedPageProps): React.ReactElement {
  const { t } = useI18n()
  const { logout, user } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  // Get required permissions from location state
  const state = location.state as {
    from?: { pathname: string }
    requiredPolicies?: string[]
  } | null

  const handleGoBack = () => {
    navigate(-1)
  }

  const handleGoHome = () => {
    navigate(`/${getTenantSlug()}`)
  }

  const handleLogout = async () => {
    await logout()
  }

  return (
    <div
      className="flex min-h-screen items-center justify-center bg-background p-6"
      data-testid="unauthorized-page"
    >
      <div className="w-full max-w-[500px] rounded-lg bg-card p-8 text-center shadow-md max-[480px]:p-6">
        {/* Icon */}
        <div className="mb-4 text-6xl max-[480px]:text-5xl" aria-hidden="true">
          <ShieldOff size={48} />
        </div>

        {/* Title */}
        <h1 className="mb-2 text-2xl font-semibold text-destructive">
          {title || t('unauthorized.title')}
        </h1>

        {/* Message */}
        <p className="mb-4 text-base text-muted-foreground">
          {message || t('unauthorized.message')}
        </p>

        {/* User info */}
        {user && (
          <p className="mb-6 text-sm text-muted-foreground">
            {t('unauthorized.loggedInAs', { email: user.email || user.name || 'Unknown' })}
          </p>
        )}

        {/* Required permissions info */}
        {state?.requiredPolicies && state.requiredPolicies.length > 0 && (
          <div className="mb-6 rounded-lg bg-muted p-4 text-left">
            <p className="mb-2 font-medium text-foreground">
              {t('unauthorized.requiredPermissions')}
            </p>
            <div className="mb-2 text-sm text-muted-foreground">
              <strong>{t('unauthorized.requiredPolicies')}:</strong>
              <ul className="mt-1 pl-6">
                {state.requiredPolicies.map((policy) => (
                  <li key={policy} className="mb-1">
                    {policy}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}

        {/* Actions */}
        <div className="mb-6 flex flex-col gap-2">
          <button
            type="button"
            className={cn(
              'rounded-lg bg-primary px-6 py-2 text-base font-medium text-primary-foreground',
              'transition-colors duration-200',
              'hover:bg-primary/90',
              'focus:outline-2 focus:outline-offset-2 focus:outline-ring'
            )}
            onClick={handleGoHome}
          >
            {t('unauthorized.goHome')}
          </button>
          <button
            type="button"
            className={cn(
              'rounded-lg border border-border bg-transparent px-6 py-2 text-base font-medium text-foreground',
              'transition-colors duration-200',
              'hover:bg-accent hover:border-primary',
              'focus:outline-2 focus:outline-offset-2 focus:outline-ring'
            )}
            onClick={handleGoBack}
          >
            {t('unauthorized.goBack')}
          </button>
          <button
            type="button"
            className={cn(
              'border-none bg-transparent p-2 text-sm text-muted-foreground',
              'cursor-pointer transition-colors duration-200',
              'hover:text-foreground',
              'focus:outline-2 focus:outline-offset-2 focus:outline-ring'
            )}
            onClick={handleLogout}
          >
            {t('unauthorized.logout')}
          </button>
        </div>

        {/* Help link */}
        <p className="m-0 text-sm text-muted-foreground">
          {t('unauthorized.helpText')}{' '}
          <Link
            to={`/${getTenantSlug()}/help`}
            className="text-primary no-underline hover:underline"
          >
            {t('unauthorized.contactSupport')}
          </Link>
        </p>
      </div>
    </div>
  )
}
