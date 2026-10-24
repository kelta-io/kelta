import { Outlet, NavLink, Navigate, useLocation, useParams } from 'react-router-dom'
import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'
import {
  BarChart3,
  FileText,
  ScrollText,
  AlertTriangle,
  Zap,
  Users,
  Settings,
  HeartPulse,
} from 'lucide-react'

const TABS = [
  { path: 'overview', labelKey: 'monitoring.tabs.overview', icon: BarChart3 },
  { path: 'requests', labelKey: 'monitoring.tabs.requests', icon: FileText },
  { path: 'logs', labelKey: 'monitoring.tabs.logs', icon: ScrollText },
  { path: 'errors', labelKey: 'monitoring.tabs.errors', icon: AlertTriangle },
  { path: 'performance', labelKey: 'monitoring.tabs.performance', icon: Zap },
  { path: 'activity', labelKey: 'monitoring.tabs.activity', icon: Users },
  { path: 'health', labelKey: 'monitoring.tabs.health', icon: HeartPulse },
  { path: 'settings', labelKey: 'monitoring.tabs.settings', icon: Settings },
] as const

export function MonitoringLayout() {
  const { t } = useI18n()
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const location = useLocation()

  // Redirect bare /monitoring to /monitoring/overview
  const isExactMonitoring =
    location.pathname === `/${tenantSlug}/monitoring` ||
    location.pathname === `/${tenantSlug}/monitoring/`
  if (isExactMonitoring) {
    return <Navigate to="overview" replace />
  }

  return (
    <div className="mx-auto max-w-[1400px] p-6" data-testid="monitoring-layout">
      {/* Header */}
      <div className="mb-4">
        <h1 className="m-0 text-2xl font-semibold text-foreground">{t('monitoring.title')}</h1>
      </div>

      {/* Tab navigation */}
      <div
        className="mb-6 flex gap-1 overflow-x-auto border-b border-border"
        data-testid="monitoring-tabs"
        role="tablist"
      >
        {TABS.map(({ path, labelKey, icon: Icon }) => (
          <NavLink
            key={path}
            to={path}
            end={path === 'overview'}
            role="tab"
            data-testid={`monitoring-tab-${path}`}
            className={({ isActive }) =>
              cn(
                'inline-flex items-center gap-1.5 whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition-colors',
                isActive
                  ? 'border-foreground text-foreground'
                  : 'border-transparent text-muted-foreground hover:border-border hover:text-foreground'
              )
            }
          >
            <Icon size={16} />
            {t(labelKey)}
          </NavLink>
        ))}
      </div>

      {/* Sub-page content */}
      <Outlet />
    </div>
  )
}
