/**
 * NotFoundPage Component
 *
 * Displays a 404 error page when a route is not found.
 */

import React from 'react'
import { useNavigate, useLocation, Link } from 'react-router-dom'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { cn } from '@/lib/utils'

/**
 * Props for the NotFoundPage component
 */
export interface NotFoundPageProps {
  /** Optional custom title */
  title?: string
  /** Optional custom message */
  message?: string
}

/**
 * NotFoundPage Component
 *
 * Shows a 404 error message with navigation options.
 */
export function NotFoundPage({ title, message }: NotFoundPageProps): React.ReactElement {
  const { t } = useI18n()
  const navigate = useNavigate()
  const location = useLocation()

  const handleGoBack = () => {
    navigate(-1)
  }

  const handleGoHome = () => {
    navigate(`/${getTenantSlug()}`)
  }

  return (
    <div
      className="flex min-h-screen items-center justify-center bg-background p-6"
      data-testid="not-found-page"
    >
      <div className="w-full max-w-[500px] rounded-lg bg-card p-8 text-center shadow-md max-[480px]:p-6">
        {/* Error code */}
        <p className="m-0 text-[6rem] font-bold leading-none text-muted-foreground max-[480px]:text-[4rem]">
          404
        </p>

        {/* Title */}
        <h1 className="mb-2 mt-4 text-2xl font-semibold text-foreground">
          {title || t('notFound.title')}
        </h1>

        {/* Message */}
        <p className="mb-6 text-base text-muted-foreground">
          {message || t('notFound.message')}
          <code className="mt-2 block break-all rounded-lg bg-muted p-2 font-mono text-sm text-muted-foreground">
            {location.pathname}
          </code>
        </p>

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
            {t('notFound.goHome')}
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
            {t('notFound.goBack')}
          </button>
        </div>

        {/* Suggestions */}
        <div className="rounded-lg bg-muted p-4 text-left">
          <p className="mb-2 font-medium text-foreground">{t('notFound.suggestions')}</p>
          <ul className="m-0 space-y-1 pl-6">
            <li>
              <Link
                to={`/${getTenantSlug()}`}
                className="text-primary no-underline hover:underline"
              >
                {t('notFound.dashboard')}
              </Link>
            </li>
            <li>
              <Link
                to={`/${getTenantSlug()}/collections`}
                className="text-primary no-underline hover:underline"
              >
                {t('notFound.collections')}
              </Link>
            </li>
            <li>
              <Link
                to={`/${getTenantSlug()}/resources`}
                className="text-primary no-underline hover:underline"
              >
                {t('notFound.resources')}
              </Link>
            </li>
          </ul>
        </div>
      </div>
    </div>
  )
}
