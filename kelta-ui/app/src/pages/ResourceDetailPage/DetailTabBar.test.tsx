/**
 * DetailTabBar tests
 *
 * Covers the History tab gating (renders only when historyContent is
 * provided, placed immediately before System Information) and the
 * always-controlled Tabs behavior (internal state by default, `activeTab`
 * prop wins, `onTabChange` fires on user tab clicks).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DetailTabBar, HISTORY_TAB } from './DetailTabBar'
import { scrollDetailTabBarIntoView } from './detailTabBarScroll'
import type { DetailTabBarProps } from './DetailTabBar'
import type { ApiClient } from '@/services/apiClient'

// Mock I18n — echo the key so assertions can target stable strings.
vi.mock('@/context/I18nContext', () => ({
  useI18n: vi.fn(() => ({
    locale: 'en',
    setLocale: vi.fn(),
    t: (key: string) => key,
    formatDate: (date: Date) => date.toISOString(),
  })),
}))

const { mockToast } = vi.hoisted(() => ({
  mockToast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
}))
vi.mock('sonner', async (importOriginal) => ({
  ...(await importOriginal<typeof import('sonner')>()),
  toast: mockToast,
}))

// Stub the heavy tab-content components — this suite exercises the tab bar itself.
vi.mock('@/components/NotesSection/NotesSection', () => ({
  NotesSection: () => <div data-testid="notes-section-stub" />,
}))
vi.mock('@/components/AttachmentsSection/AttachmentsSection', () => ({
  AttachmentsSection: () => <div data-testid="attachments-section-stub" />,
}))
vi.mock('@/components/RelatedList', () => ({
  RelatedList: () => null,
}))

function renderTabBar(props: Partial<DetailTabBarProps> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DetailTabBar
          relatedLists={[]}
          recordId="rec-1"
          collectionId="col-1"
          tenantSlug="acme"
          resource={{ id: 'rec-1' }}
          notes={[]}
          attachments={[]}
          apiClient={{} as ApiClient}
          invalidateRecordContext={vi.fn()}
          getUserDisplay={() => null}
          {...props}
        />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('DetailTabBar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('History tab gating', () => {
    it('hides the History tab when historyContent is not provided', () => {
      renderTabBar()

      expect(screen.queryByTestId('detail-tab-history')).not.toBeInTheDocument()
      expect(screen.queryByTestId('record-history-panel')).not.toBeInTheDocument()
      expect(screen.getByTestId('detail-tab-system')).toBeInTheDocument()
    })

    it('shows the History tab immediately before System Information when historyContent is provided', () => {
      renderTabBar({ historyContent: <div data-testid="history-stub" /> })

      expect(screen.getByTestId('detail-tab-history')).toBeInTheDocument()

      const tabIds = screen.getAllByRole('tab').map((tab) => tab.getAttribute('data-testid'))
      expect(tabIds).toEqual([
        'detail-tab-notes',
        'detail-tab-attachments',
        'detail-tab-history',
        'detail-tab-system',
      ])
    })

    it('shows the history panel when the History tab is clicked (uncontrolled)', async () => {
      const user = userEvent.setup()
      renderTabBar({ historyContent: <div data-testid="history-stub" /> })

      // Inactive by default — the panel is mounted but hidden.
      expect(screen.getByTestId('record-history-panel')).not.toBeVisible()

      await user.click(screen.getByTestId('detail-tab-history'))

      expect(screen.getByTestId('record-history-panel')).toBeVisible()
      expect(screen.getByTestId('history-stub')).toBeVisible()
    })
  })

  describe('controlled tabs', () => {
    it('shows the history panel when activeTab is the History tab', () => {
      renderTabBar({
        historyContent: <div data-testid="history-stub" />,
        activeTab: HISTORY_TAB,
        onTabChange: vi.fn(),
      })

      expect(screen.getByTestId('record-history-panel')).toBeVisible()
      expect(screen.getByTestId('history-stub')).toBeVisible()
      expect(screen.getByTestId('detail-tab-history')).toHaveAttribute('data-state', 'active')
    })

    it('fires onTabChange when the user clicks another tab', async () => {
      const user = userEvent.setup()
      const onTabChange = vi.fn()
      renderTabBar({
        historyContent: <div data-testid="history-stub" />,
        activeTab: HISTORY_TAB,
        onTabChange,
      })

      await user.click(screen.getByTestId('detail-tab-notes'))

      expect(onTabChange).toHaveBeenCalledWith('__notes__')
    })
  })

  describe('scrollDetailTabBarIntoView', () => {
    it('scrolls the rendered tab bar into view on the next frame', async () => {
      renderTabBar()
      const tabBar = screen.getByTestId('detail-tab-bar')
      const scrollIntoView = vi.fn()
      Object.defineProperty(tabBar, 'scrollIntoView', { value: scrollIntoView })

      scrollDetailTabBarIntoView()

      // Deferred a frame — nothing yet.
      expect(scrollIntoView).not.toHaveBeenCalled()
      await new Promise((resolve) => requestAnimationFrame(() => resolve(undefined)))
      expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
    })

    it('does not throw when no tab bar is mounted', async () => {
      expect(() => scrollDetailTabBarIntoView()).not.toThrow()
      await new Promise((resolve) => requestAnimationFrame(() => resolve(undefined)))
    })
  })
})
