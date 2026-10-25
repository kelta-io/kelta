import React, { useState, useEffect, useCallback } from 'react'
import {
  SvixProvider,
  useEndpoints,
  useEndpointMessageAttempts,
  useEndpointFunctions,
  useEventTypes,
  useSvix,
} from 'svix-react'
import type { EndpointIn, EndpointOut, MessageAttemptOut } from 'svix'
import { useQuery } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogDescription,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { FieldLabel, StatusBadge } from '@/components/kelta'

export interface WebhooksPageProps {
  testId?: string
}

// ---------------------------------------------------------------------------
// Main page — fetches Svix credentials, wraps children in SvixProvider
// ---------------------------------------------------------------------------

export function WebhooksPage({ testId = 'webhooks-page' }: WebhooksPageProps): React.ReactElement {
  const { keltaClient } = useApi()
  const [svixCredentials, setSvixCredentials] = useState<{
    token: string
    appId: string
    serverUrl: string
  } | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    let cancelled = false

    async function fetchAccess() {
      try {
        setIsLoading(true)
        setError(null)
        const result = await keltaClient.admin.svix.getPortalAccess()
        if (!cancelled) {
          setSvixCredentials(result)
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err : new Error('Failed to load webhook portal'))
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    fetchAccess()
    return () => {
      cancelled = true
    }
  }, [keltaClient])

  if (isLoading) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label="Loading webhooks..." />
        </div>
      </div>
    )
  }

  if (error || !svixCredentials) {
    return (
      <div className="mx-auto max-w-[1400px] space-y-6 p-6 lg:p-8" data-testid={testId}>
        <ErrorMessage
          error={error ?? new Error('Unable to load webhooks')}
          onRetry={() => window.location.reload()}
        />
      </div>
    )
  }

  return (
    <SvixProvider
      token={svixCredentials.token}
      appId={svixCredentials.appId}
      options={{ serverUrl: svixCredentials.serverUrl }}
    >
      <WebhooksDashboard testId={testId} />
    </SvixProvider>
  )
}

// ---------------------------------------------------------------------------
// Dashboard — endpoint list + detail panel
// ---------------------------------------------------------------------------

function WebhooksDashboard({ testId }: { testId: string }) {
  const [showCreateDialog, setShowCreateDialog] = useState(false)
  const [selectedEndpoint, setSelectedEndpoint] = useState<EndpointOut | null>(null)

  return (
    <div className="flex h-full flex-col" data-testid={testId}>
      <header className="flex items-center justify-between border-b px-6 py-4 lg:px-8">
        <div>
          <h1 className="text-2xl font-semibold text-foreground">Webhooks</h1>
          <p className="text-sm text-muted-foreground">
            Manage webhook endpoints and monitor delivery status
          </p>
        </div>
        <Button onClick={() => setShowCreateDialog(true)}>Add Endpoint</Button>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <div className="w-full overflow-y-auto p-6 lg:p-8">
          {selectedEndpoint ? (
            <EndpointDetail endpoint={selectedEndpoint} onBack={() => setSelectedEndpoint(null)} />
          ) : (
            <EndpointList onSelect={setSelectedEndpoint} />
          )}
        </div>
      </div>

      <CreateEndpointDialog open={showCreateDialog} onOpenChange={setShowCreateDialog} />
    </div>
  )
}

// ---------------------------------------------------------------------------
// Endpoint list
// ---------------------------------------------------------------------------

function EndpointList({ onSelect }: { onSelect: (ep: EndpointOut) => void }) {
  const endpoints = useEndpoints({ limit: 50 })

  if (endpoints.loading) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <LoadingSpinner size="medium" label="Loading endpoints..." />
      </div>
    )
  }

  if (endpoints.error) {
    return <ErrorMessage error={endpoints.error} onRetry={endpoints.reload} />
  }

  if (!endpoints.data || endpoints.data.length === 0) {
    return (
      <div className="flex min-h-[300px] flex-col items-center justify-center gap-4 text-center">
        <div className="rounded-full bg-muted p-4">
          <svg
            className="h-8 w-8 text-muted-foreground"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m9.193-9.193a4.5 4.5 0 016.364 6.364l-4.5 4.5a4.5 4.5 0 01-7.244-1.242"
            />
          </svg>
        </div>
        <div>
          <h3 className="text-lg font-medium text-foreground">No webhook endpoints</h3>
          <p className="text-sm text-muted-foreground">
            Create an endpoint to start receiving webhook notifications.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        {endpoints.data.map((ep) => (
          <button
            key={ep.id}
            onClick={() => onSelect(ep)}
            className="flex w-full items-center justify-between rounded-lg border bg-card p-4 text-left transition-colors hover:bg-accent"
          >
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className="font-medium text-foreground truncate">
                  {ep.description || ep.url}
                </span>
                {ep.disabled && <StatusBadge variant="inactive" label="Disabled" />}
              </div>
              <p className="mt-1 text-sm text-muted-foreground truncate">{ep.url}</p>
              {ep.eventTypes && ep.eventTypes.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-1">
                  {ep.eventTypes.slice(0, 3).map((t) => (
                    <Badge key={t} variant="outline" className="text-xs">
                      {t}
                    </Badge>
                  ))}
                  {ep.eventTypes.length > 3 && (
                    <Badge variant="outline" className="text-xs">
                      +{ep.eventTypes.length - 3} more
                    </Badge>
                  )}
                </div>
              )}
              {ep.channels && ep.channels.length > 0 && (
                <div className="mt-1 flex flex-wrap gap-1">
                  <span className="text-xs text-muted-foreground mr-1">Collections:</span>
                  {ep.channels.slice(0, 3).map((ch) => (
                    <Badge key={ch} variant="secondary" className="text-xs">
                      {ch}
                    </Badge>
                  ))}
                  {ep.channels.length > 3 && (
                    <Badge variant="secondary" className="text-xs">
                      +{ep.channels.length - 3} more
                    </Badge>
                  )}
                </div>
              )}
            </div>
            <svg
              className="h-5 w-5 shrink-0 text-muted-foreground"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={1.5}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
            </svg>
          </button>
        ))}
      </div>

      {(endpoints.hasPrevPage || endpoints.hasNextPage) && (
        <div className="flex justify-center gap-2 pt-4">
          <Button
            variant="outline"
            size="sm"
            disabled={!endpoints.hasPrevPage}
            onClick={endpoints.prevPage}
          >
            Previous
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={!endpoints.hasNextPage}
            onClick={endpoints.nextPage}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Endpoint detail view
// ---------------------------------------------------------------------------

function EndpointDetail({ endpoint, onBack }: { endpoint: EndpointOut; onBack: () => void }) {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const { deleteEndpoint } = useEndpointFunctions(endpoint.id)
  const attempts = useEndpointMessageAttempts(endpoint.id, { limit: 20 })

  const handleDelete = useCallback(async () => {
    try {
      await deleteEndpoint()
      onBack()
    } catch {
      // error is surfaced by the hook
    }
  }, [deleteEndpoint, onBack])

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="sm" onClick={onBack}>
            <svg
              className="mr-1 h-4 w-4"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={1.5}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 19.5L8.25 12l7.5-7.5" />
            </svg>
            Back
          </Button>
          <div>
            <h2 className="text-xl font-semibold text-foreground">
              {endpoint.description || 'Endpoint'}
            </h2>
            <p className="text-sm text-muted-foreground">{endpoint.url}</p>
          </div>
        </div>
        <Button variant="destructive" size="sm" onClick={() => setShowDeleteConfirm(true)}>
          Delete
        </Button>
      </div>

      {/* Endpoint info */}
      <div className="grid gap-4 sm:grid-cols-2">
        <InfoCard label="URL" value={endpoint.url} />
        <InfoCard label="Status" value={endpoint.disabled ? 'Disabled' : 'Active'} />
        <InfoCard label="Created" value={new Date(endpoint.createdAt).toLocaleString()} />
        {endpoint.eventTypes && endpoint.eventTypes.length > 0 && (
          <div className="rounded-lg border bg-card p-4">
            <p className="kelta-field-label">Event types</p>
            <div className="mt-1 flex flex-wrap gap-1">
              {endpoint.eventTypes.map((t) => (
                <Badge key={t} variant="outline" className="text-xs">
                  {t}
                </Badge>
              ))}
            </div>
          </div>
        )}
        {endpoint.channels && endpoint.channels.length > 0 && (
          <div className="rounded-lg border bg-card p-4">
            <p className="kelta-field-label">Collection filter</p>
            <div className="mt-1 flex flex-wrap gap-1">
              {endpoint.channels.map((ch) => (
                <Badge key={ch} variant="secondary" className="text-xs">
                  {ch}
                </Badge>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Message attempts */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-lg font-medium text-foreground">Recent deliveries</h3>
          <Button variant="ghost" size="sm" onClick={attempts.reload}>
            Refresh
          </Button>
        </div>
        <AttemptTable attempts={attempts} />
      </div>

      {/* Delete confirmation */}
      <Dialog open={showDeleteConfirm} onOpenChange={setShowDeleteConfirm}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete endpoint</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete this endpoint? This action cannot be undone. All
              future webhook deliveries to this URL will stop.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDeleteConfirm(false)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={handleDelete}>
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function InfoCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border bg-card p-4">
      <p className="text-sm font-medium text-muted-foreground">{label}</p>
      <p className="mt-1 text-sm text-foreground break-all">{value}</p>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Attempt table
// ---------------------------------------------------------------------------

function AttemptTable({ attempts }: { attempts: ReturnType<typeof useEndpointMessageAttempts> }) {
  if (attempts.loading) {
    return (
      <div className="flex min-h-[100px] items-center justify-center">
        <LoadingSpinner size="small" label="Loading deliveries..." />
      </div>
    )
  }

  if (attempts.error) {
    return <ErrorMessage error={attempts.error} onRetry={attempts.reload} />
  }

  if (!attempts.data || attempts.data.length === 0) {
    return (
      <div className="rounded-lg border bg-muted/30 py-8 text-center">
        <p className="text-sm text-muted-foreground">No deliveries yet</p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="overflow-hidden rounded-lg border">
        <table className="w-full text-sm">
          <thead className="border-b bg-muted/50">
            <tr>
              <th className="px-4 py-2 text-left font-medium text-muted-foreground">Status</th>
              <th className="px-4 py-2 text-left font-medium text-muted-foreground">Event type</th>
              <th className="px-4 py-2 text-left font-medium text-muted-foreground">Response</th>
              <th className="px-4 py-2 text-left font-medium text-muted-foreground">Timestamp</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {attempts.data.map((attempt: MessageAttemptOut) => (
              <tr key={attempt.id} className="hover:bg-muted/30">
                <td className="px-4 py-2">
                  <AttemptStatusBadge status={attempt.status} />
                </td>
                <td className="px-4 py-2 text-foreground">
                  {attempt.msg?.eventId ?? attempt.msg?.eventType ?? '-'}
                </td>
                <td className="px-4 py-2 text-foreground">{attempt.responseStatusCode ?? '-'}</td>
                <td className="px-4 py-2 text-muted-foreground">
                  {new Date(attempt.timestamp).toLocaleString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {(attempts.hasPrevPage || attempts.hasNextPage) && (
        <div className="flex justify-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={!attempts.hasPrevPage}
            onClick={attempts.prevPage}
          >
            Previous
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={!attempts.hasNextPage}
            onClick={attempts.nextPage}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  )
}

function AttemptStatusBadge({ status }: { status: number }) {
  // Svix MessageStatus: 0 = Success, 1 = Pending, 2 = Fail, 3 = Sending
  switch (status) {
    case 0:
      return <StatusBadge variant="active" label="Success" />
    case 1:
      return <StatusBadge variant="pending" label="Pending" />
    case 2:
      return <StatusBadge variant="failed" label="Failed" />
    case 3:
      return <StatusBadge variant="pending" label="Sending" />
    default:
      return <StatusBadge variant="inactive" label="Unknown" />
  }
}

// ---------------------------------------------------------------------------
// Create endpoint dialog
// ---------------------------------------------------------------------------

function CreateEndpointDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { svix, appId } = useSvix()
  const { keltaClient } = useApi()
  const eventTypes = useEventTypes({ limit: 100 })
  const endpoints = useEndpoints({ limit: 50 })

  const [endpointUrl, setEndpointUrl] = useState('')
  const [endpointDescription, setEndpointDescription] = useState('')
  const [selectedEventTypes, setSelectedEventTypes] = useState<string[]>([])
  const [selectedChannels, setSelectedChannels] = useState<string[]>([])
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)

  const { data: collections } = useQuery({
    queryKey: ['collections-for-webhook-filter'],
    queryFn: async () => {
      const result = await keltaClient.admin.collections.list()
      return result
    },
  })

  const handleCreate = useCallback(async () => {
    if (!endpointUrl.trim()) {
      setCreateError('URL is required')
      return
    }
    setCreating(true)
    setCreateError(null)
    try {
      const endpointIn: EndpointIn = {
        url: endpointUrl,
        description: endpointDescription || undefined,
        eventTypes: selectedEventTypes.length > 0 ? selectedEventTypes : undefined,
        channels: selectedChannels.length > 0 ? selectedChannels : undefined,
      }
      await svix.endpoint.create(appId, endpointIn)
      onOpenChange(false)
      // Reset form
      setEndpointUrl('')
      setEndpointDescription('')
      setSelectedEventTypes([])
      setSelectedChannels([])
      // Reload endpoints list
      endpoints.reload()
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : 'Failed to create endpoint')
    } finally {
      setCreating(false)
    }
  }, [
    endpointUrl,
    endpointDescription,
    selectedEventTypes,
    selectedChannels,
    svix,
    appId,
    onOpenChange,
    endpoints,
  ])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Add webhook endpoint</DialogTitle>
          <DialogDescription>
            Create a new endpoint to receive webhook notifications.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <FieldLabel htmlFor="endpoint-url">Endpoint URL</FieldLabel>
            <Input
              id="endpoint-url"
              placeholder="https://example.com/webhooks"
              value={endpointUrl}
              onChange={(e) => setEndpointUrl(e.target.value)}
            />
          </div>

          <div className="space-y-2">
            <FieldLabel htmlFor="endpoint-description">Description</FieldLabel>
            <Input
              id="endpoint-description"
              placeholder="My webhook endpoint"
              value={endpointDescription}
              onChange={(e) => setEndpointDescription(e.target.value)}
            />
          </div>

          {eventTypes.data && eventTypes.data.length > 0 && (
            <div className="space-y-2">
              <FieldLabel>Event types</FieldLabel>
              <p className="text-xs text-muted-foreground">
                Select which events this endpoint should receive. Leave empty for all events.
              </p>
              <div className="max-h-[200px] space-y-1 overflow-y-auto rounded-md border p-3">
                {eventTypes.data.map((et) => (
                  <label
                    key={et.name}
                    className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm hover:bg-accent"
                  >
                    <input
                      type="checkbox"
                      className="rounded border-input"
                      checked={selectedEventTypes.includes(et.name)}
                      onChange={(e) => {
                        if (e.target.checked) {
                          setSelectedEventTypes([...selectedEventTypes, et.name])
                        } else {
                          setSelectedEventTypes(selectedEventTypes.filter((n) => n !== et.name))
                        }
                      }}
                    />
                    <span className="text-foreground">{et.name}</span>
                    {et.description && (
                      <span className="text-xs text-muted-foreground">- {et.description}</span>
                    )}
                  </label>
                ))}
              </div>
            </div>
          )}

          {collections && collections.length > 0 && (
            <div className="space-y-2">
              <FieldLabel>Collection filter</FieldLabel>
              <p className="text-xs text-muted-foreground">
                Only receive events for selected collections. Leave empty for all collections.
              </p>
              <div className="max-h-[200px] space-y-1 overflow-y-auto rounded-md border p-3">
                {(collections as Array<{ id: string; name: string; displayName?: string }>).map(
                  (col) => (
                    <label
                      key={col.id}
                      className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm hover:bg-accent"
                    >
                      <input
                        type="checkbox"
                        className="rounded border-input"
                        checked={selectedChannels.includes(col.name)}
                        onChange={(e) => {
                          if (e.target.checked) {
                            setSelectedChannels([...selectedChannels, col.name])
                          } else {
                            setSelectedChannels(selectedChannels.filter((n) => n !== col.name))
                          }
                        }}
                      />
                      <span className="text-foreground">{col.displayName || col.name}</span>
                      <span className="text-xs text-muted-foreground">({col.name})</span>
                    </label>
                  )
                )}
              </div>
            </div>
          )}

          {createError && <p className="text-sm text-destructive">{createError}</p>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleCreate} disabled={creating}>
            {creating ? 'Creating...' : 'Create Endpoint'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
