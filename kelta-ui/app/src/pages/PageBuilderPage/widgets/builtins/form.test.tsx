/**
 * `form` widget tests. At runtime the widget embeds the app's shared `RecordForm`; these tests mount the
 * REAL widget → `RecordForm` → `useRecordFormModel` / `useRecordFormState` → data hooks → `ApiClient`,
 * faking only the HTTP transport (an axios stand-in behind a real `ApiClient`). Auth and plugin
 * contexts are stubbed because their real providers bootstrap the session; nothing in the form is.
 *
 * Regression guard: the widget used to mount `@kelta/components`' `ResourceForm`, which needs a
 * `KeltaProvider` the app never mounts — every page with a form widget crashed to the app's root error
 * boundary ("useKeltaClient must be used within an KeltaProvider"). The old tests mocked
 * `ResourceForm` away and could not see it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { AxiosInstance } from 'axios'
import { I18nProvider } from '@/context/I18nContext'
import { ToastProvider } from '@/components/Toast'
import { CollectionStoreProvider } from '@/context/CollectionStoreContext'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { ApiClient } from '@/services/apiClient'
import { MY_PERMISSIONS_QUERY_KEY } from '@/hooks/useSystemPermissions'
import { PageTreeRenderer } from '@/pages/app/CustomPage/PageTreeRenderer'
import type { BindingScope } from '../../model/bindingScope'
import type { RenderNode } from '../types'

// ── The fake network: a minimal axios stand-in the real ApiClient drives ────────────────────────
interface FakeResponse {
  data: unknown
  headers?: Record<string, string>
}
const http = {
  get: vi.fn<(url: string) => Promise<FakeResponse>>(),
  post: vi.fn<(url: string, body: unknown) => Promise<FakeResponse>>(),
  put: vi.fn<(url: string, body: unknown, config?: unknown) => Promise<FakeResponse>>(),
}
const apiClient = new ApiClient(http as unknown as AxiosInstance)

vi.mock('@/context/ApiContext', () => ({ useApi: () => ({ apiClient }) }))
vi.mock('@/context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, isAuthenticated: true }),
}))
vi.mock('@/context/PluginContext', () => ({
  usePlugins: () => ({ getFieldRenderer: () => undefined }),
}))

const ORDERS_SCHEMA = {
  data: {
    type: 'collections',
    id: 'col-orders',
    attributes: { name: 'orders', displayName: 'Orders' },
  },
  included: [
    {
      type: 'fields',
      id: 'f-name',
      attributes: { name: 'name', displayName: 'Name', type: 'string', required: true },
    },
    {
      type: 'fields',
      id: 'f-notes',
      attributes: { name: 'notes', displayName: 'Notes', type: 'string', required: false },
    },
  ],
}

/** The record GET /api/orders/rec-1 serves, and its version. */
let served: { attributes: Record<string, unknown>; etag: string }

function routeGet(url: string): FakeResponse {
  if (url.startsWith('/api/collections/orders')) return { data: ORDERS_SCHEMA }
  if (url === '/api/orders/rec-1') {
    return {
      data: { data: { type: 'orders', id: 'rec-1', attributes: served.attributes } },
      headers: { etag: served.etag },
    }
  }
  // record types, validation rules, layout assignments, picklists: none configured
  return { data: { data: [] } }
}

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>
}

function renderPage(nodes: RenderNode[], scope: BindingScope = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // The end-user shell loads /api/me/permissions; an empty grant map = the permissive defaults.
  queryClient.setQueryData(MY_PERMISSIONS_QUERY_KEY, {
    systemPermissions: {},
    objectPermissions: {},
    fieldPermissions: {},
  })
  const result = render(
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <CollectionStoreProvider>
          <I18nProvider>
            <ToastProvider>
              <MemoryRouter initialEntries={['/acme/app/p/orders']}>
                <Routes>
                  <Route
                    path="/acme/app/p/orders"
                    element={
                      <PageTreeRenderer
                        components={nodes as never}
                        tenantSlug="acme"
                        scope={scope}
                      />
                    }
                  />
                  <Route path="*" element={<LocationProbe />} />
                </Routes>
              </MemoryRouter>
            </ToastProvider>
          </I18nProvider>
        </CollectionStoreProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  )
  return { ...result, queryClient }
}

/** GETs the form itself made (the shell's collection-store preload is not the form's). */
function ordersRequests(): string[] {
  return http.get.mock.calls
    .map(([url]) => url)
    .filter((url) => url.startsWith('/api/collections/orders') || url.startsWith('/api/orders'))
}

/** The JSON:API body of the n-th write call. */
function writtenAttributes(call: unknown[] | undefined): Record<string, unknown> {
  return ((call?.[1] as { data: { attributes: Record<string, unknown> } }).data ?? {}).attributes
}

const orderForm = (props: Record<string, unknown>, events?: RenderNode['events']): RenderNode => ({
  id: 'f',
  type: 'form',
  props: { dataView: { collection: 'orders' }, mode: 'create', readOnly: false, ...props },
  ...(events ? { events } : {}),
})

beforeEach(() => {
  served = { attributes: { name: 'Widget', notes: 'Ship soon' }, etag: '"v1"' }
  http.get.mockReset().mockImplementation(async (url) => routeGet(url))
  http.post.mockReset().mockImplementation(async (_url, body) => ({
    data: {
      data: {
        type: 'orders',
        id: 'new-1',
        attributes: (body as { data: { attributes: Record<string, unknown> } }).data.attributes,
      },
    },
  }))
  http.put.mockReset().mockImplementation(async (_url, body) => ({
    data: {
      data: {
        type: 'orders',
        id: 'rec-1',
        attributes: (body as { data: { attributes: Record<string, unknown> } }).data.attributes,
      },
    },
  }))
})

describe('form widget — runtime (embedded RecordForm)', () => {
  it('renders the record form next to its sibling widgets (no provider crash)', async () => {
    renderPage([
      { id: 'h', type: 'heading', props: { text: 'Orders', level: 'h1' } },
      orderForm({}),
    ])
    expect(await screen.findByTestId('field-name')).toBeInTheDocument()
    expect(screen.getByTestId('field-notes')).toBeInTheDocument()
    expect(screen.getByText('Orders', { selector: 'h1' })).toBeInTheDocument()
    expect(screen.queryByTestId('error-boundary-fallback')).not.toBeInTheDocument()
    const form = screen.getByTestId('page-form')
    expect(form).toHaveAttribute('data-presentation', 'embedded')
    // Embedded chrome: a section heading (the page owns the h1), no Cancel.
    expect(screen.getByTestId('record-form-title')).toHaveTextContent('New Orders')
    expect(screen.getByTestId('record-form-title').tagName).toBe('H2')
    expect(screen.queryByTestId('cancel-button')).not.toBeInTheDocument()
  })

  it('creates the record, stays on the page, resets, and runs onSubmit with the saved record', async () => {
    const user = userEvent.setup()
    renderPage([
      orderForm(
        {},
        {
          onSubmit: [
            { action: 'showToast', level: 'info', message: { $bind: 'record.id' } as never },
          ],
        }
      ),
    ])
    await user.type(await screen.findByTestId('field-name'), 'Gadget')
    await user.click(screen.getByTestId('save-button'))

    await waitFor(() => expect(http.post).toHaveBeenCalledTimes(1))
    expect(http.post.mock.calls[0][0]).toBe('/api/orders')
    expect(writtenAttributes(http.post.mock.calls[0])).toEqual({ name: 'Gadget' })
    // The shared form's success toast, then the onSubmit action resolving record.id → the new id.
    expect(await screen.findByText('Record created successfully.')).toBeInTheDocument()
    expect(await screen.findByText('new-1')).toBeInTheDocument()
    // Still on the page, with a blank form for the next entry.
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue(''))
  })

  it('refetches page tables bound to the collection after a save', async () => {
    const user = userEvent.setup()
    const { queryClient } = renderPage([orderForm({})])
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    await user.type(await screen.findByTestId('field-name'), 'Gadget')
    await user.click(screen.getByTestId('save-button'))
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['page-table', 'orders'] })
    )
  })

  it('edits the bound record with If-Match and re-reads it after the save', async () => {
    const user = userEvent.setup()
    renderPage([orderForm({ mode: 'edit', recordId: { $bind: 'vars.selected' } })], {
      vars: { selected: 'rec-1' },
    })
    const name = await screen.findByTestId('field-name')
    await waitFor(() => expect(name).toHaveValue('Widget'))
    expect(screen.getByTestId('record-form-title')).toHaveTextContent('Edit Orders')

    await user.clear(name)
    await user.type(name, 'Widget v2')
    served = { attributes: { name: 'Widget v2', notes: 'Ship soon' }, etag: '"v2"' }
    await user.click(screen.getByTestId('save-button'))

    await waitFor(() => expect(http.put).toHaveBeenCalledTimes(1))
    const [url, , config] = http.put.mock.calls[0]
    expect(url).toBe('/api/orders/rec-1')
    expect(writtenAttributes(http.put.mock.calls[0])).toMatchObject({ name: 'Widget v2' })
    expect(config).toEqual({ headers: { 'If-Match': '"v1"' } })
    expect(await screen.findByText('Record updated successfully.')).toBeInTheDocument()

    // The re-read pins the new version: the next save sends v2, not the stale v1.
    await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue('Widget v2'))
    await user.type(screen.getByTestId('field-notes'), '!')
    await user.click(screen.getByTestId('save-button'))
    await waitFor(() => expect(http.put).toHaveBeenCalledTimes(2))
    expect(http.put.mock.calls[1][2]).toEqual({ headers: { 'If-Match': '"v2"' } })
  })

  it('treats a record id as edit even in the default create mode (saved 2f configs)', async () => {
    renderPage([orderForm({ recordId: 'rec-1' })])
    await waitFor(() => expect(screen.getByTestId('field-name')).toHaveValue('Widget'))
    expect(screen.getByTestId('record-form-title')).toHaveTextContent('Edit Orders')
  })

  it('shows "no record selected" for edit mode without a record id, and never creates', async () => {
    renderPage([orderForm({ mode: 'edit', recordId: { $bind: 'vars.selected' } })])
    expect(await screen.findByTestId('page-node-form')).toHaveTextContent('no record selected')
    expect(screen.queryByTestId('save-button')).not.toBeInTheDocument()
    expect(ordersRequests()).toEqual([])
  })

  it('renders read-only without a Save button', async () => {
    renderPage([orderForm({ recordId: 'rec-1', readOnly: true })])
    const name = await screen.findByTestId('field-name')
    await waitFor(() => expect(name).toHaveValue('Widget'))
    expect(name).toBeDisabled()
    expect(screen.queryByTestId('save-button')).not.toBeInTheDocument()
    expect(screen.getByTestId('record-form-title')).toHaveTextContent('Orders')
  })

  it('limits the form to dataView.fields, in that order, and saves only those', async () => {
    const user = userEvent.setup()
    renderPage([orderForm({ dataView: { collection: 'orders', fields: ['notes'] } })])
    await user.type(await screen.findByTestId('field-notes'), 'Hello')
    expect(screen.queryByTestId('field-name')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('save-button'))
    await waitFor(() => expect(http.post).toHaveBeenCalledTimes(1))
    expect(writtenAttributes(http.post.mock.calls[0])).toEqual({ notes: 'Hello' })
  })

  it('shows the "no data source" box when the collection is unset', () => {
    renderPage([{ id: 'f', type: 'form', props: {} }])
    expect(screen.getByTestId('page-node-form')).toHaveTextContent(/no data source/i)
    expect(ordersRequests()).toEqual([])
  })
})

describe('form widget — editor (builder canvas)', () => {
  it('renders the static placeholder and loads nothing', async () => {
    const { RenderTree } = await import('../renderTree')
    render(
      <I18nProvider>
        <RenderTree components={[orderForm({})]} tenantSlug="acme" mode="editor" />
      </I18nProvider>
    )
    expect(screen.getByTestId('page-node-form')).toHaveTextContent('Form')
    expect(screen.queryByTestId('page-form')).not.toBeInTheDocument()
    expect(ordersRequests()).toEqual([])
  })
})
