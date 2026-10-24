/**
 * EnvironmentsPage Component
 *
 * Admin page for Sandbox Environments and Metadata Promotion (gated on the
 * MANAGE_SANDBOXES permission):
 * - "Sandboxes" tab: list environments, create local sandboxes (with the
 *   one-time admin credentials dialog) or register remote targets, refresh
 *   and archive sandboxes, test remote connections.
 * - "Promotions" tab: promotion history plus the promotion wizard
 *   (diff → FULL/SELECTIVE → approve → execute → per-item results).
 */

import React, { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowUpRight } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { SandboxList } from './SandboxList'
import { PromotionHistory } from './PromotionHistory'
import { PromotionWizard } from './PromotionWizard'

/**
 * Tab type for the environments page
 */
type TabType = 'sandboxes' | 'promotions'

/**
 * Props for EnvironmentsPage component
 */
export interface EnvironmentsPageProps {
  /** Optional test ID for testing */
  testId?: string
}

export function EnvironmentsPage({ testId }: EnvironmentsPageProps): React.ReactElement {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [activeTab, setActiveTab] = useState<TabType>('sandboxes')
  const [showWizard, setShowWizard] = useState(false)

  const handleWizardClose = () => {
    setShowWizard(false)
    queryClient.invalidateQueries({ queryKey: ['promotions'] })
  }

  const tabClasses = (tab: TabType) =>
    cn(
      'px-6 py-2 text-sm font-medium text-muted-foreground bg-transparent border-none border-b-2 border-transparent -mb-[2px] cursor-pointer transition-all duration-200 max-md:px-4 max-md:whitespace-nowrap',
      'hover:text-foreground',
      'focus:outline-2 focus:outline-ring focus:outline-offset-2',
      activeTab === tab && 'text-primary border-b-primary'
    )

  return (
    <div
      className="flex flex-col h-full min-h-0 p-6 w-full max-lg:p-4 max-md:p-2"
      data-testid={testId || 'environments-page'}
    >
      <header className="mb-6">
        <h1 className="m-0 text-2xl font-semibold text-foreground max-md:text-xl">
          {t('environments.title')}
        </h1>
      </header>

      <div
        className="flex gap-1 border-b-2 border-border mb-6 max-md:overflow-x-auto max-md:[&::-webkit-scrollbar]:hidden"
        role="tablist"
        aria-label={t('environments.title')}
      >
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'sandboxes'}
          aria-controls="sandboxes-panel"
          className={tabClasses('sandboxes')}
          onClick={() => setActiveTab('sandboxes')}
          data-testid="tab-sandboxes"
        >
          {t('environments.tabSandboxes')}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'promotions'}
          aria-controls="promotions-panel"
          className={tabClasses('promotions')}
          onClick={() => setActiveTab('promotions')}
          data-testid="tab-promotions"
        >
          {t('environments.tabPromotions')}
        </button>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto">
        {activeTab === 'sandboxes' && (
          <div id="sandboxes-panel" role="tabpanel" aria-labelledby="tab-sandboxes">
            <SandboxList />
          </div>
        )}
        {activeTab === 'promotions' && (
          <div id="promotions-panel" role="tabpanel" aria-labelledby="tab-promotions">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="m-0 text-xl font-semibold text-foreground">
                {t('environments.history')}
              </h2>
              <button
                type="button"
                className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
                onClick={() => setShowWizard(true)}
                data-testid="start-promotion-button"
              >
                <ArrowUpRight size={16} />
                {t('environments.startPromotion')}
              </button>
            </div>
            <PromotionHistory />
          </div>
        )}
      </div>

      {showWizard && <PromotionWizard onClose={handleWizardClose} />}
    </div>
  )
}
