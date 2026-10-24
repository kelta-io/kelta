import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { ActivityTimeline } from './ActivityTimeline'
import type { ActivityTimelineProps } from './ActivityTimeline'
import type { ApiClient } from '../../services/apiClient'

// Mock I18n — echo the key so assertions can target stable strings. Version
// update keys (the only ones interpolating a `fields` param) append the resolved
// field display names so tests can assert schema display-name wiring.
vi.mock('../../context/I18nContext', () => ({
  useI18n: vi.fn(() => ({
    locale: 'en',
    setLocale: vi.fn(),
    t: (key: string, params?: Record<string, string | number> | string) => {
      if (params && typeof params === 'object' && 'fields' in params) {
        return `${key} fields=${params.fields}`
      }
      return key
    },
  })),
}))

function renderTimeline(apiClient: Partial<ApiClient>, props: Partial<ActivityTimelineProps> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    React.createElement(
      QueryClientProvider,
      { client: qc },
      React.createElement(ActivityTimeline, {
        collectionId: 'col-1',
        collectionName: 'orders',
        recordId: 'rec-1',
        recordCreatedAt: '2026-01-01T00:00:00Z',
        apiClient: apiClient as ApiClient,
        ...props,
      })
    )
  )
}

describe('ActivityTimeline', () => {
  it('merges a note from /api/notes into the feed', async () => {
    const getList = vi.fn((url: string) => {
      if (url.startsWith('/api/notes')) {
        return Promise.resolve([
          { id: 'n1', content: 'Called the customer', createdAt: '2026-02-01T00:00:00Z' },
        ])
      }
      return Promise.resolve([]) // approvals + shares empty
    })

    renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

    await waitFor(() => expect(screen.getByText('Called the customer')).toBeInTheDocument())
    // The notes endpoint was queried for this record.
    expect(getList).toHaveBeenCalledWith(
      expect.stringContaining(
        '/api/notes?filter[collectionId][eq]=col-1&filter[recordId][eq]=rec-1'
      )
    )
  })

  it('truncates long note content', async () => {
    const long = 'x'.repeat(200)
    const getList = vi.fn((url: string) =>
      url.startsWith('/api/notes')
        ? Promise.resolve([{ id: 'n1', content: long, createdAt: '2026-02-01T00:00:00Z' }])
        : Promise.resolve([])
    )

    renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

    await waitFor(() => expect(screen.getByText(/x{140}…/)).toBeInTheDocument())
  })

  it('merges an attachment from /api/attachments into the feed', async () => {
    const getList = vi.fn((url: string) => {
      if (url.startsWith('/api/attachments')) {
        return Promise.resolve([
          { id: 'a1', fileName: 'contract.pdf', uploadedAt: '2026-02-02T00:00:00Z' },
        ])
      }
      return Promise.resolve([])
    })

    renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

    await waitFor(() => expect(screen.getByText(/contract\.pdf/)).toBeInTheDocument())
    expect(getList).toHaveBeenCalledWith(
      expect.stringContaining(
        '/api/attachments?filter[collectionId][eq]=col-1&filter[recordId][eq]=rec-1'
      )
    )
  })

  it('merges a flow run from /api/flows/record-executions into the feed', async () => {
    const getList = vi.fn((url: string) => {
      if (url.startsWith('/api/flows/record-executions')) {
        return Promise.resolve([
          {
            id: 'fx1',
            flowId: 'flow-1',
            flowName: 'Send Welcome',
            status: 'COMPLETED',
            startedAt: '2026-02-03T00:00:00Z',
            completedAt: '2026-02-03T00:00:05Z',
            durationMs: 5000,
            errorMessage: null,
          },
        ])
      }
      return Promise.resolve([])
    })

    renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

    await waitFor(() => expect(screen.getByText('activity.flowRun')).toBeInTheDocument())
    expect(getList).toHaveBeenCalledWith(
      expect.stringContaining('/api/flows/record-executions?recordId=rec-1&limit=20')
    )
  })

  it('merges an email log from /api/email/logs into the feed', async () => {
    const getList = vi.fn((url: string) => {
      if (url.startsWith('/api/email/logs')) {
        return Promise.resolve([
          {
            id: 'em1',
            recipientEmail: 'jane@example.com',
            subject: 'Order shipped',
            status: 'SENT',
            sentAt: '2026-02-04T00:00:00Z',
            createdAt: '2026-02-03T23:59:00Z',
          },
        ])
      }
      return Promise.resolve([])
    })

    renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

    await waitFor(() => expect(screen.getByText('activity.emailSent')).toBeInTheDocument())
    // The collection lets the backend check the caller can read the record first.
    expect(getList).toHaveBeenCalledWith(
      expect.stringContaining('/api/email/logs?collection=orders&recordId=rec-1&limit=20')
    )
  })

  it('sorts flow runs and emails into the merged feed newest-first', async () => {
    const getList = vi.fn((url: string) => {
      if (url.startsWith('/api/flows/record-executions')) {
        return Promise.resolve([
          {
            id: 'fx1',
            flowId: 'flow-1',
            flowName: 'Send Welcome',
            status: 'FAILED',
            startedAt: '2026-03-01T00:00:00Z',
            completedAt: null,
            durationMs: null,
            errorMessage: 'boom',
          },
        ])
      }
      if (url.startsWith('/api/email/logs')) {
        return Promise.resolve([
          {
            id: 'em1',
            recipientEmail: 'jane@example.com',
            subject: 'Order shipped',
            status: 'QUEUED',
            sentAt: null,
            createdAt: '2026-02-01T00:00:00Z',
          },
        ])
      }
      if (url.startsWith('/api/notes')) {
        return Promise.resolve([
          { id: 'n1', content: 'Middle note', createdAt: '2026-02-15T00:00:00Z' },
        ])
      }
      return Promise.resolve([])
    })

    renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

    await waitFor(() => expect(screen.getByText('activity.flowRun')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('activity.emailSent')).toBeInTheDocument())

    const items = screen.getAllByRole('listitem')
    const ids = items.map((item) => item.getAttribute('data-testid'))
    // Newest first: flow run (Mar 1) > note (Feb 15) > email queued (Feb 1, falls
    // back to createdAt when sentAt is null) > record created (Jan 1).
    expect(ids).toEqual([
      'activity-entry-flow-run-fx1',
      'activity-entry-note-n1',
      'activity-entry-email-em1',
      'activity-entry-record-created',
    ])
  })

  it('still renders lifecycle events when there are no notes', async () => {
    const getList = vi.fn(() => Promise.resolve([]))
    renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

    await waitFor(() => expect(screen.getByText('activity.recordCreated')).toBeInTheDocument())
  })

  describe('record versions (historyEnabled)', () => {
    const versionRows = [
      {
        id: 'ver-1',
        collectionId: 'col-1',
        recordId: 'rec-1',
        versionNumber: 1,
        changeType: 'CREATED',
        snapshot: { id: 'rec-1', name: 'Acme' },
        changedFields: [],
        changedBy: 'user-1',
        changedAt: '2026-01-01T00:00:00Z',
        changeSource: 'UI',
      },
      {
        id: 'ver-2',
        collectionId: 'col-1',
        recordId: 'rec-1',
        versionNumber: 2,
        changeType: 'UPDATED',
        snapshot: { id: 'rec-1', name: 'Acme Corp' },
        changedFields: ['name'],
        changedBy: 'user-1',
        changedAt: '2026-01-02T00:00:00Z',
        changeSource: 'UI',
      },
    ]

    const historyProps: Partial<ActivityTimelineProps> = {
      historyEnabled: true,
      recordUpdatedAt: '2026-01-02T00:00:00Z',
      schemaFields: [
        { id: 'f1', name: 'name', displayName: 'Full Name', type: 'string', required: false },
      ],
      getUserDisplay: (userId: string) => (userId === 'user-1' ? { name: 'Jane Doe' } : null),
      onOpenHistory: vi.fn(),
    }

    function versionGetList() {
      return vi.fn((url: string) =>
        url.startsWith('/api/record-versions') ? Promise.resolve(versionRows) : Promise.resolve([])
      )
    }

    it('renders one entry per version with the author on its own line and suppresses synthesized entries', async () => {
      const getList = versionGetList()

      renderTimeline({ getList: getList as unknown as ApiClient['getList'] }, historyProps)

      await waitFor(() => expect(screen.getByText('activity.versionCreated')).toBeInTheDocument())
      // Single changed field uses the singular key; fields render as schema display names.
      expect(screen.getByText('activity.versionUpdatedOne fields=Full Name')).toBeInTheDocument()

      // The author renders on its own line under the timestamp for each version entry.
      const userLines = screen.getAllByTestId('activity-entry-user')
      expect(userLines).toHaveLength(2)
      expect(userLines[0]).toHaveTextContent('Jane Doe')

      // Synthesized created/updated entries are suppressed — the version feed covers them.
      expect(screen.queryByText('activity.recordCreated')).not.toBeInTheDocument()
      expect(screen.queryByText('activity.recordUpdated')).not.toBeInTheDocument()

      expect(getList).toHaveBeenCalledWith(
        expect.stringContaining(
          '/api/record-versions?filter[collectionId][eq]=col-1&filter[recordId][eq]=rec-1'
        )
      )
    })

    it('calls onOpenHistory with the version number when a version entry is clicked', async () => {
      const onOpenHistory = vi.fn()
      const getList = versionGetList()

      renderTimeline(
        { getList: getList as unknown as ApiClient['getList'] },
        { ...historyProps, onOpenHistory }
      )

      await waitFor(() => expect(screen.getAllByTestId('activity-version-link')).toHaveLength(2))

      // Newest first: the first link is v2.
      const links = screen.getAllByTestId('activity-version-link')
      expect(links[0]).toHaveTextContent('activity.versionUpdatedOne')
      fireEvent.click(links[0])

      expect(onOpenHistory).toHaveBeenCalledWith(2)
    })

    it('does not fetch record versions and keeps synthesized entries when historyEnabled is off', async () => {
      const getList = vi.fn<(url: string) => Promise<unknown[]>>(() => Promise.resolve([]))

      renderTimeline({ getList: getList as unknown as ApiClient['getList'] })

      await waitFor(() => expect(screen.getByText('activity.recordCreated')).toBeInTheDocument())
      const versionCalls = getList.mock.calls.filter((call) =>
        String(call[0]).startsWith('/api/record-versions')
      )
      expect(versionCalls).toHaveLength(0)
    })
  })
})
