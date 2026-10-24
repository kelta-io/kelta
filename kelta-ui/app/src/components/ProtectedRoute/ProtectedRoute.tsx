/**
 * ProtectedRoute Component
 *
 * A route guard component that checks authentication and authorization
 * before rendering protected routes.
 *
 * Requirements:
 * - 2.1: Redirect unauthenticated users to OIDC provider login page
 * - 2.2: Display provider selection page for multiple providers
 */

import React from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { LoadingSpinner } from '../LoadingSpinner'

/**
 * Props for the ProtectedRoute component
 */
export interface ProtectedRouteProps {
  /** Child components to render when authenticated and authorized */
  children: React.ReactNode
  /** Optional policies required to access this route */
  requiredPolicies?: string[]
  /** Custom redirect path for unauthenticated users (defaults to /login) */
  loginPath?: string
  /** Custom redirect path for unauthorized users (defaults to /unauthorized) */
  unauthorizedPath?: string
  /** Custom loading component */
  loadingComponent?: React.ReactNode
  /** Callback when authorization check fails */
  onUnauthorized?: () => void
}

/**
 * Check if user has any of the required policies
 */
// eslint-disable-next-line react-refresh/only-export-components
export function hasRequiredPolicies(
  userPolicies: string[] | undefined,
  requiredPolicies: string[]
): boolean {
  if (!requiredPolicies || requiredPolicies.length === 0) {
    return true
  }
  if (!userPolicies || userPolicies.length === 0) {
    return false
  }
  return requiredPolicies.some((policy) => userPolicies.includes(policy))
}

/**
 * ProtectedRoute Component
 *
 * Wraps routes that require authentication and optionally authorization.
 * Shows loading state while checking auth, redirects to login if not authenticated,
 * and redirects to unauthorized page if user lacks required permissions.
 */
export function ProtectedRoute({
  children,
  requiredPolicies = [],
  loginPath = '/login',
  unauthorizedPath = '/unauthorized',
  loadingComponent,
  onUnauthorized,
}: ProtectedRouteProps): React.ReactElement {
  const { user, isAuthenticated, isLoading } = useAuth()
  const location = useLocation()

  // Show loading state while checking authentication
  if (isLoading) {
    return (
      <div
        role="status"
        aria-live="polite"
        aria-label="Checking authentication"
        data-testid="protected-route-loading"
        className="flex justify-center items-center min-h-[200px]"
      >
        {loadingComponent || <LoadingSpinner size="large" label="Checking authentication..." />}
      </div>
    )
  }

  // Redirect to login if not authenticated
  if (!isAuthenticated) {
    const loginTarget = location.search ? `${loginPath}${location.search}` : loginPath
    return (
      <Navigate
        to={loginTarget}
        state={{ from: location }}
        replace
        data-testid="protected-route-redirect-login"
      />
    )
  }

  // Check policy-based authorization
  const userPolicies = (user?.claims?.policies as string[]) || []
  const hasPolicies = hasRequiredPolicies(userPolicies, requiredPolicies)

  // Redirect to unauthorized page if user lacks required permissions
  if (!hasPolicies) {
    if (onUnauthorized) {
      onUnauthorized()
    }
    return (
      <Navigate
        to={unauthorizedPath}
        state={{
          from: location,
          requiredPolicies: requiredPolicies.length > 0 ? requiredPolicies : undefined,
        }}
        replace
        data-testid="protected-route-redirect-unauthorized"
      />
    )
  }

  // User is authenticated and authorized, render children
  return <>{children}</>
}
