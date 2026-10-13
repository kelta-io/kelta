import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { BrowserRouter } from 'react-router-dom'
import { I18nProvider } from '../../context/I18nContext'
import { Toaster } from 'sonner'
import { AnalyticsPage } from './AnalyticsPage'

// Mock URL.createObjectURL and revokeObjectURL for the export download flow
global.URL.createObjectURL = vi.fn(() => 'blob:mock-url')
global.URL.revokeObjectURL = vi.fn()

// Mock useApi directly to bypass KeltaClient initialization
const mockListDashboards = vi.fn()
const mockGetGuestToken = vi.fn()
const mockListReports = vi.fn()
const mockApiGet = vi.fn()
const mockGetBlob = vi.fn()

vi.mock('../../context/ApiContext', () => ({
  ApiProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useApi: () => ({
    keltaClient: {
      admin: {
        superset: {
          listDashboards: mockListDashboards,
          getGuestToken: mockGetGuestToken,
        },
        reports: {
          list: mockListReports,
        },
      },
    },
    apiClient: {
      get: mockApiGet,
      getBlob: mockGetBlob,
    },
  }),
}))

function createSimpleWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })

  return function Wrapper({ children }: { children: React.ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <I18nProvider>
            <>
              {children}
              <Toaster />
            </>
          </I18nProvider>
        </BrowserRouter>
      </QueryClientProvider>
    )
  }
}

const sampleReport = {
  id: 'rep-1',
  name: 'Open Deals',
  description: 'Pipeline by stage',
  reportType: 'TABULAR',
  primaryCollectionId: 'col-1',
  relatedJoins: '[]',
  columns: '[]',
  filters: '[]',
  rowGroupings: '[]',
  columnGroupings: '[]',
  sortOrder: '[]',
  scope: 'ALL_RECORDS',
  accessLevel: 'PUBLIC',
  createdBy: 'user-1',
  createdAt: '2026-06-01T10:00:00Z',
  updatedAt: '2026-06-01T10:00:00Z',
}

describe('AnalyticsPage', () => {
  beforeEach(() => {
    mockListDashboards.mockReset()
    mockGetGuestToken.mockReset()
    mockListReports.mockReset()
    mockApiGet.mockReset()
    mockGetBlob.mockReset()
    vi.mocked(global.URL.createObjectURL).mockClear()
    vi.mocked(global.URL.revokeObjectURL).mockClear()
    // Default: no reports, so dashboard-focused tests are unaffected
    mockListReports.mockResolvedValue([])
  })

  it('renders the page title', async () => {
    mockListDashboards.mockResolvedValue([])

    render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Analytics')).toBeInTheDocument()
    })
  })

  it('shows empty state when no dashboards exist', async () => {
    mockListDashboards.mockResolvedValue([])

    render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

    await waitFor(() => {
      expect(screen.getByText('No dashboards available')).toBeInTheDocument()
    })
  })

  it('renders dashboard cards when dashboards exist', async () => {
    mockListDashboards.mockResolvedValue([
      {
        id: 1,
        dashboard_title: 'Sales Overview',
        url: '/superset/dashboard/1/',
        status: 'published',
        published: true,
        changed_on_utc: '2024-06-15T10:00:00Z',
      },
      {
        id: 2,
        dashboard_title: 'Customer Metrics',
        url: '/superset/dashboard/2/',
        status: 'draft',
        published: false,
        changed_on_utc: '2024-06-14T08:00:00Z',
      },
    ])

    render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Sales Overview')).toBeInTheDocument()
      expect(screen.getByText('Customer Metrics')).toBeInTheDocument()
    })
  })

  it('shows published/draft badge for dashboards', async () => {
    mockListDashboards.mockResolvedValue([
      {
        id: 1,
        dashboard_title: 'Published Dashboard',
        status: 'published',
        published: true,
        changed_on_utc: '2024-06-15T10:00:00Z',
      },
    ])

    render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Published')).toBeInTheDocument()
    })
  })

  it('has a link to open Superset directly', async () => {
    mockListDashboards.mockResolvedValue([])

    render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

    await waitFor(() => {
      const link = screen.getByText('Open in Superset')
      expect(link.closest('a')).toHaveAttribute('target', '_blank')
    })
  })

  it('shows error state when API call fails', async () => {
    mockListDashboards.mockRejectedValue(new Error('Network error'))

    render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

    await waitFor(() => {
      expect(screen.getByText(/Failed to load dashboards/)).toBeInTheDocument()
    })
  })

  describe('report exports', () => {
    it('shows empty state when no reports exist', async () => {
      mockListDashboards.mockResolvedValue([])

      render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

      await waitFor(() => {
        expect(screen.getByText('No reports have been created yet.')).toBeInTheDocument()
      })
    })

    it('renders report rows with export actions', async () => {
      mockListDashboards.mockResolvedValue([])
      mockListReports.mockResolvedValue([sampleReport])

      render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

      await waitFor(() => {
        expect(screen.getByText('Open Deals')).toBeInTheDocument()
      })
      expect(screen.getByTestId('report-export-csv-rep-1')).toBeInTheDocument()
      expect(screen.getByTestId('report-export-pdf-rep-1')).toBeInTheDocument()
    })

    it('exports CSV via /api/reports/{id}/export?format=csv and triggers a download', async () => {
      const user = userEvent.setup()
      mockListDashboards.mockResolvedValue([])
      mockListReports.mockResolvedValue([sampleReport])
      mockApiGet.mockResolvedValue('name,stage\nAcme,Open\n')

      render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('report-export-csv-rep-1')).toBeInTheDocument()
      })
      await user.click(screen.getByTestId('report-export-csv-rep-1'))

      await waitFor(() => {
        expect(mockApiGet).toHaveBeenCalledWith('/api/reports/rep-1/export?format=csv')
        expect(global.URL.createObjectURL).toHaveBeenCalled()
      })
      expect(mockGetBlob).not.toHaveBeenCalled()
    })

    it('exports PDF via /api/reports/{id}/export?format=pdf and triggers a download', async () => {
      const user = userEvent.setup()
      mockListDashboards.mockResolvedValue([])
      mockListReports.mockResolvedValue([sampleReport])
      mockGetBlob.mockResolvedValue(new Blob(['%PDF-1.7'], { type: 'application/pdf' }))

      render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('report-export-pdf-rep-1')).toBeInTheDocument()
      })
      await user.click(screen.getByTestId('report-export-pdf-rep-1'))

      await waitFor(() => {
        expect(mockGetBlob).toHaveBeenCalledWith('/api/reports/rep-1/export?format=pdf')
        expect(global.URL.createObjectURL).toHaveBeenCalled()
      })
      expect(mockApiGet).not.toHaveBeenCalled()
    })

    it('shows an error toast with the server detail when an export fails', async () => {
      const user = userEvent.setup()
      mockListDashboards.mockResolvedValue([])
      mockListReports.mockResolvedValue([sampleReport])
      mockGetBlob.mockRejectedValue(new Error('Unsupported export format: pdf. Supported: csv'))

      render(<AnalyticsPage />, { wrapper: createSimpleWrapper() })

      await waitFor(() => {
        expect(screen.getByTestId('report-export-pdf-rep-1')).toBeInTheDocument()
      })
      await user.click(screen.getByTestId('report-export-pdf-rep-1'))

      await waitFor(() => {
        expect(
          screen.getByText('Unsupported export format: pdf. Supported: csv')
        ).toBeInTheDocument()
      })
      expect(global.URL.createObjectURL).not.toHaveBeenCalled()
    })
  })
})
