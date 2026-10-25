/**
 * WebhooksPage tests — endpoint event-type filters on the svix 2.x SDK, which renamed
 * `EndpointIn`/`EndpointOut.filterTypes` to `eventTypes` (svix ChangeLog, 2.0.0-rc.1).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type React from 'react'
import type { EndpointIn, EndpointOut, EventTypeOut } from 'svix'
import { WebhooksPage } from './WebhooksPage'
import { I18nProvider } from '../../context/I18nContext'

const endpoint: EndpointOut = {
  id: 'ep_1',
  url: 'https://example.com/hooks',
  description: 'Orders hook',
  metadata: {},
  eventTypes: [
    'record.created',
    'record.updated',
    'record.deleted',
    'flow.completed',
    'flow.failed',
  ],
  createdAt: new Date('2026-10-01T00:00:00Z'),
  updatedAt: new Date('2026-10-01T00:00:00Z'),
}

const eventTypeList: EventTypeOut[] = ['record.created', 'record.updated'].map((name) => ({
  name,
  description: '',
  archived: false,
  deprecated: false,
  createdAt: new Date('2026-10-01T00:00:00Z'),
  updatedAt: new Date('2026-10-01T00:00:00Z'),
}))

function paged<T>(data: T[]) {
  return {
    data,
    loading: false,
    error: undefined,
    reload: vi.fn(),
    hasPrevPage: false,
    hasNextPage: false,
    prevPage: vi.fn(),
    nextPage: vi.fn(),
  }
}

const createMock = vi.fn<(appId: string, endpointIn: EndpointIn) => Promise<EndpointOut>>()

vi.mock('svix-react', () => ({
  SvixProvider: ({ children }: { children: React.ReactNode }) => children,
  useSvix: () => ({ svix: { endpoint: { create: createMock } }, appId: 'app_1' }),
  useEndpoints: () => paged([endpoint]),
  useEventTypes: () => paged(eventTypeList),
  useEndpointMessageAttempts: () => paged([]),
  useEndpointFunctions: () => ({ deleteEndpoint: vi.fn() }),
}))

const keltaClient = {
  admin: {
    svix: {
      getPortalAccess: async () => ({
        token: 'tok',
        appId: 'app_1',
        serverUrl: 'https://svix.example.com',
      }),
    },
    collections: { list: async () => [] },
  },
}
vi.mock('../../context/ApiContext', () => ({ useApi: () => ({ keltaClient }) }))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <WebhooksPage />
      </I18nProvider>
    </QueryClientProvider>
  )
}

describe('WebhooksPage event-type filters', () => {
  beforeEach(() => {
    createMock.mockReset()
    createMock.mockResolvedValue(endpoint)
  })

  it('lists the first three event types of an endpoint plus "+N more"', async () => {
    renderPage()

    const row = (await screen.findByText('Orders hook')).closest('button') as HTMLElement
    expect(within(row).getByText('record.created')).toBeInTheDocument()
    expect(within(row).getByText('record.updated')).toBeInTheDocument()
    expect(within(row).getByText('record.deleted')).toBeInTheDocument()
    expect(within(row).queryByText('flow.completed')).not.toBeInTheDocument()
    expect(within(row).queryByText('flow.failed')).not.toBeInTheDocument()
    expect(within(row).getByText('+2 more')).toBeInTheDocument()
  })

  it('shows every event type in the endpoint detail view', async () => {
    renderPage()

    fireEvent.click(await screen.findByText('Orders hook'))

    expect(await screen.findByText('Event types')).toBeInTheDocument()
    for (const type of endpoint.eventTypes ?? []) {
      expect(screen.getByText(type)).toBeInTheDocument()
    }
    expect(screen.queryByText('+2 more')).not.toBeInTheDocument()
  })

  it('creates an endpoint with the selected types in EndpointIn.eventTypes', async () => {
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Add Endpoint' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Endpoint URL'), {
      target: { value: 'https://example.com/new' },
    })
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /record\.updated/ }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create Endpoint' }))

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1))
    const [appId, endpointIn] = createMock.mock.calls[0]
    expect(appId).toBe('app_1')
    expect(endpointIn.url).toBe('https://example.com/new')
    expect(endpointIn.eventTypes).toEqual(['record.updated'])
    expect(endpointIn).not.toHaveProperty('filterTypes')
  })
})
