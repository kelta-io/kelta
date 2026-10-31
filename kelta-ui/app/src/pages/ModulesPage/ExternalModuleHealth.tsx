import React, { useState } from 'react'
import { useApi } from '../../context/ApiContext'
import { Button } from '@/components/ui/button'
import { Activity } from 'lucide-react'
import type { ExternalModuleHealth as Health } from './externalModulePlan'

export interface ExternalModuleHealthProps {
  moduleId: string
}

/**
 * Live health of an external module, on request: the module's own `GET /kelta/v1/health` answer
 * and the answering pod's circuit breaker. Fetched only when the admin asks, never on page load.
 */
export function ExternalModuleHealth({ moduleId }: ExternalModuleHealthProps): React.ReactElement {
  const { apiClient } = useApi()
  const [health, setHealth] = useState<Health | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const check = async () => {
    setLoading(true)
    setError(null)
    try {
      setHealth(await apiClient.get<Health>(`/api/modules/${encodeURIComponent(moduleId)}/health`))
    } catch (err) {
      setError((err as Error).message || 'Could not check health')
    } finally {
      setLoading(false)
    }
  }

  const answer = health?.moduleHealth
  return (
    <div data-testid={`module-health-${moduleId}`} className="space-y-2">
      <div className="flex items-center justify-between">
        <h4 className="text-sm font-medium text-gray-700">Health</h4>
        <Button variant="outline" size="sm" onClick={check} disabled={loading} className="gap-1">
          <Activity size={14} />
          {loading ? 'Checking…' : 'Check health'}
        </Button>
      </div>
      {error && (
        <p className="text-xs text-red-700" role="alert">
          {error}
        </p>
      )}
      {health && (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
          <dt className="text-gray-500">Module answered</dt>
          <dd data-testid={`module-health-answer-${moduleId}`} className="text-gray-900">
            {answer ? (answer.status ?? 'ok') : 'not called (module is not running)'}
            {answer?.error ? `: ${answer.error}` : ''}
          </dd>
          <dt className="text-gray-500">
            Circuit breaker{health.podName ? ` (${health.podName})` : ''}
          </dt>
          <dd className="text-gray-900">
            {health.breakerState ?? 'unknown'}
            {health.consecutiveFailures
              ? `, ${health.consecutiveFailures} failure(s) in a row`
              : ''}
          </dd>
          {health.lastFailure && (
            <>
              <dt className="text-gray-500">Last failure</dt>
              <dd className="text-red-700">{health.lastFailure}</dd>
            </>
          )}
          {health.breakerRetryAt && (
            <>
              <dt className="text-gray-500">Next probe</dt>
              <dd className="text-gray-900">{new Date(health.breakerRetryAt).toLocaleString()}</dd>
            </>
          )}
        </dl>
      )}
    </div>
  )
}
