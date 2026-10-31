/**
 * External modules on the Modules page (`specs/module-platform/9-external-modules.md` part 9e):
 * install by URL behind a consent screen, upgrade re-consent, the uninstall plan, and health.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  createAxiosError,
  createTestWrapper,
  mockAxios,
  resetMockAxios,
  setupAuthMocks,
} from '../../test/testUtils'
import { ModulePlanReview } from './ModulePlanReview'
import { ExternalModuleInstall } from './ExternalModuleInstall'
import { ExternalModuleUpgradeDialog } from './ExternalModuleUpgradeDialog'
import { ExternalModuleUninstallDialog } from './ExternalModuleUninstallDialog'
import { ExternalModuleHealth } from './ExternalModuleHealth'
import { ModulesPage } from './ModulesPage'
import { metadataCounts, objectGrantLabel, type InstallPlan } from './externalModulePlan'

const BASE_URL = 'https://billing.example.test'
const BUNDLE_SHA = 'a'.repeat(64)

function installPlan(overrides: Partial<InstallPlan> = {}): InstallPlan {
  return {
    baseUrl: BASE_URL,
    moduleId: 'acme-billing',
    name: 'Billing',
    description: 'Plans and subscriptions',
    author: 'Example Ltd',
    mode: 'INSTALL',
    fromVersion: null,
    toVersion: '1.0.0',
    manifestSha256: 'm'.repeat(64),
    grants: {
      ownedCollections: ['billing_plans'],
      objects: [{ collection: 'accounts', create: false, read: true, edit: true, delete: false }],
      fields: [{ collection: 'accounts', field: 'tax_id', visibility: 'MASKED' }],
      system: ['VIEW_SETUP'],
      credentials: ['stripe'],
    },
    capabilities: {
      actions: [{ key: 'refund', name: 'Refund', timeoutMs: 1500 }],
      hooks: [
        { collection: 'accounts', events: ['beforeUpdate'], order: 100, failurePolicy: 'fail' },
      ],
      events: [{ collection: 'accounts', types: ['created', 'updated'] }],
      services: [{ port: 'entitlements', cacheTtlSeconds: 60 }],
      routes: [{ path: 'me', methods: ['GET'], permission: 'VIEW_SETUP' }],
      schedules: [{ action: 'refund', cron: '0 3 * * *' }],
      webhook: true,
      ui: { bundlePath: '/ui/bundle.js', sha256: BUNDLE_SHA },
    },
    uiBundle: {
      bundlePath: '/ui/bundle.js',
      sha256: BUNDLE_SHA,
      fetchedSha256: BUNDLE_SHA,
      sizeBytes: 1024,
      installedSha256: null,
      changed: false,
    },
    metadata: [
      { type: 'COLLECTION', naturalKey: 'billing_plans', action: 'CREATE' },
      { type: 'FIELD', naturalKey: 'billing_plans.code', action: 'CREATE' },
      { type: 'COLLECTION', naturalKey: 'accounts', action: 'ADOPT' },
    ],
    blockers: [],
    planHash: 'p'.repeat(64),
    ...overrides,
  }
}

function planDocument(plan: InstallPlan) {
  return { data: { data: { type: 'module-install-plans', id: plan.moduleId, attributes: plan } } }
}

describe('external modules', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  describe('plan helpers', () => {
    it('counts metadata by action and spells out object access', () => {
      expect(metadataCounts(installPlan().metadata)).toEqual({ CREATE: 2, ADOPT: 1 })
      expect(metadataCounts(undefined)).toEqual({})
      expect(
        objectGrantLabel({ collection: 'a', create: true, read: true, edit: false, delete: true })
      ).toBe('create, read, delete')
      expect(
        objectGrantLabel({
          collection: 'a',
          create: false,
          read: false,
          edit: false,
          delete: false,
        })
      ).toBe('no access')
    })
  })

  describe('ModulePlanReview', () => {
    it('shows grants, capabilities, metadata counts, the UI bundle hash and the planHash', () => {
      render(<ModulePlanReview plan={installPlan()} />, { wrapper: createTestWrapper() })

      const grants = screen.getByTestId('module-plan-grants')
      expect(within(grants).getByText('billing_plans')).toBeInTheDocument()
      expect(within(grants).getByText('read, edit')).toBeInTheDocument()
      expect(within(grants).getByText('accounts.tax_id')).toBeInTheDocument()
      expect(within(grants).getByText('VIEW_SETUP')).toBeInTheDocument()
      expect(within(grants).getByText('stripe')).toBeInTheDocument()

      const capabilities = screen.getByTestId('module-plan-capabilities')
      expect(capabilities).toHaveTextContent('1 flow action: refund')
      expect(capabilities).toHaveTextContent('Runs before saves on accounts')
      expect(capabilities).toHaveTextContent('Receives accounts changes (created, updated)')
      expect(capabilities).toHaveTextContent('Receives webhooks')

      expect(screen.getByTestId('module-plan-metadata-counts')).toHaveTextContent('CREATE 2')
      expect(screen.getByTestId('module-plan-metadata-counts')).toHaveTextContent('ADOPT 1')
      expect(screen.getByTestId('module-plan-ui-bundle')).toHaveTextContent(BUNDLE_SHA)
      expect(screen.getByTestId('module-plan-hash')).toHaveTextContent('p'.repeat(64))
      expect(screen.queryByTestId('module-plan-blockers')).not.toBeInTheDocument()
      expect(screen.queryByTestId('module-plan-permissions-widened')).not.toBeInTheDocument()
    })

    it('lists blockers', () => {
      render(
        <ModulePlanReview
          plan={installPlan({
            mode: 'BLOCKED',
            blockers: [{ code: 'UI_BUNDLE_MISMATCH', message: 'The UI bundle has SHA-256 x' }],
          })}
        />,
        { wrapper: createTestWrapper() }
      )

      expect(screen.getByTestId('module-plan-blockers')).toHaveTextContent(
        'UI_BUNDLE_MISMATCH: The UI bundle has SHA-256 x'
      )
    })

    it('flags an upgrade that widens permissions or replaces the UI bundle', () => {
      render(
        <ModulePlanReview
          plan={installPlan({
            mode: 'UPGRADE',
            fromVersion: '1.0.0',
            toVersion: '2.0.0',
            uiBundle: {
              bundlePath: '/ui/bundle.js',
              sha256: BUNDLE_SHA,
              fetchedSha256: BUNDLE_SHA,
              sizeBytes: 10,
              installedSha256: 'b'.repeat(64),
              changed: true,
            },
            changes: {
              actions: { added: ['charge'], removed: [] },
              routes: { added: [], removed: [] },
              services: { added: [], removed: [] },
              hooks: { added: [], removed: [] },
              events: { added: [], removed: ['accounts [deleted]'] },
              schedules: { added: [], removed: [] },
              permissions: {
                added: ['object accounts:delete'],
                removed: ['credential stripe'],
                widened: true,
              },
            },
          })}
        />,
        { wrapper: createTestWrapper() }
      )

      expect(screen.getByTestId('module-plan-version')).toHaveTextContent('v1.0.0 → v2.0.0')
      const widened = screen.getByTestId('module-plan-permissions-widened')
      expect(widened).toHaveTextContent('object accounts:delete')
      expect(widened).toHaveTextContent('It no longer needs: credential stripe')
      expect(screen.getByTestId('module-plan-ui-bundle-changed')).toHaveTextContent('b'.repeat(64))
      expect(screen.getByTestId('module-plan-capability-changes')).toHaveTextContent(
        '+ action charge'
      )
      expect(screen.getByTestId('module-plan-capability-changes')).toHaveTextContent(
        '− event accounts [deleted]'
      )
    })
  })

  describe('ExternalModuleInstall', () => {
    it('reviews the plan, then installs with its planHash', async () => {
      const onDone = vi.fn()
      mockAxios.post.mockImplementation(async (url: string) => {
        if (url === '/api/modules/plan') return planDocument(installPlan())
        return { data: { data: { type: 'modules', id: 'row-1', attributes: {} } } }
      })
      const user = userEvent.setup()
      render(<ExternalModuleInstall onDone={onDone} />, { wrapper: createTestWrapper() })

      expect(screen.getByTestId('module-approve-install')).toBeDisabled()
      await user.type(screen.getByLabelText('Module URL *'), BASE_URL)
      await user.click(screen.getByTestId('module-review-plan'))

      await screen.findByTestId('module-plan-review')
      expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/plan', { baseUrl: BASE_URL })
      await user.click(screen.getByTestId('module-approve-install'))

      await waitFor(() => expect(onDone).toHaveBeenCalled())
      expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/install', {
        baseUrl: BASE_URL,
        planHash: 'p'.repeat(64),
      })
    })

    it('cannot approve a blocked plan', async () => {
      mockAxios.post.mockResolvedValue(
        planDocument(
          installPlan({ mode: 'BLOCKED', blockers: [{ code: 'PLATFORM_TOO_OLD', message: 'old' }] })
        )
      )
      const user = userEvent.setup()
      render(<ExternalModuleInstall onDone={vi.fn()} />, { wrapper: createTestWrapper() })

      await user.type(screen.getByLabelText('Module URL *'), BASE_URL)
      await user.click(screen.getByTestId('module-review-plan'))

      await screen.findByTestId('module-plan-blockers')
      expect(screen.getByTestId('module-approve-install')).toBeDisabled()
    })

    it('shows the new plan when the reviewed one went stale (409), installing nothing', async () => {
      const onDone = vi.fn()
      let plans = 0
      mockAxios.post.mockImplementation(async (url: string) => {
        if (url === '/api/modules/plan') {
          plans += 1
          return planDocument(
            installPlan({ planHash: plans === 1 ? 'p'.repeat(64) : 'q'.repeat(64) })
          )
        }
        throw createAxiosError(409, {
          errors: [
            { status: '409', code: 'PlanChanged', title: 'Plan Changed', detail: 'changed' },
          ],
        })
      })
      const user = userEvent.setup()
      render(<ExternalModuleInstall onDone={onDone} />, { wrapper: createTestWrapper() })

      await user.type(screen.getByLabelText('Module URL *'), BASE_URL)
      await user.click(screen.getByTestId('module-review-plan'))
      await screen.findByTestId('module-plan-review')
      await user.click(screen.getByTestId('module-approve-install'))

      await screen.findByTestId('module-plan-changed')
      expect(screen.getByTestId('module-plan-hash')).toHaveTextContent('q'.repeat(64))
      expect(onDone).not.toHaveBeenCalled()
    })

    it('says an installed module is upgraded from its card', async () => {
      mockAxios.post.mockResolvedValue(
        planDocument(installPlan({ mode: 'UPGRADE', fromVersion: '0.9.0' }))
      )
      const user = userEvent.setup()
      render(<ExternalModuleInstall onDone={vi.fn()} />, { wrapper: createTestWrapper() })

      await user.type(screen.getByLabelText('Module URL *'), BASE_URL)
      await user.click(screen.getByTestId('module-review-plan'))

      await screen.findByText(/already installed/)
      expect(screen.getByTestId('module-approve-install')).toBeDisabled()
    })
  })

  describe('ExternalModuleUpgradeDialog', () => {
    it('plans from the stored base URL and upgrades with the reviewed planHash', async () => {
      const onClose = vi.fn()
      mockAxios.post.mockImplementation(async (url: string) => {
        if (url === '/api/modules/plan') {
          return planDocument(installPlan({ mode: 'UPGRADE', fromVersion: '0.9.0' }))
        }
        return { data: {} }
      })
      const user = userEvent.setup()
      render(
        <ExternalModuleUpgradeDialog
          moduleId="acme-billing"
          name="Billing"
          baseUrl={BASE_URL}
          onClose={onClose}
        />,
        { wrapper: createTestWrapper() }
      )

      await screen.findByTestId('module-plan-review')
      await user.click(screen.getByTestId('module-approve-upgrade'))

      await waitFor(() => expect(onClose).toHaveBeenCalled())
      expect(mockAxios.post).toHaveBeenCalledWith('/api/modules/acme-billing/upgrade', {
        planHash: 'p'.repeat(64),
      })
    })

    it('says so when there is nothing to upgrade', async () => {
      mockAxios.post.mockResolvedValue(planDocument(installPlan({ mode: 'NO_OP' })))
      render(
        <ExternalModuleUpgradeDialog
          moduleId="acme-billing"
          name="Billing"
          baseUrl={BASE_URL}
          onClose={vi.fn()}
        />,
        { wrapper: createTestWrapper() }
      )

      await screen.findByTestId('module-upgrade-up-to-date')
      expect(screen.getByTestId('module-approve-upgrade')).toBeDisabled()
    })
  })

  describe('ExternalModuleUninstallDialog', () => {
    const uninstallPlan = (purgeData: boolean) => ({
      data: {
        data: {
          type: 'module-uninstall-plans',
          id: 'acme-billing',
          attributes: {
            moduleId: 'acme-billing',
            installedVersion: '1.0.0',
            purgeData,
            planHash: purgeData ? 'y'.repeat(64) : 'x'.repeat(64),
            entries: [
              {
                resourceType: 'COLLECTION',
                naturalKey: 'billing_plans',
                ownership: 'CREATED',
                decision: purgeData ? 'REMOVE' : 'KEEP_HAS_DATA',
              },
              {
                resourceType: 'FIELD',
                naturalKey: 'accounts.plan',
                ownership: 'CREATED',
                decision: 'REMOVE',
              },
            ],
          },
        },
      },
    })

    it('shows what is removed and kept, re-plans for purgeData, and deletes with that planHash', async () => {
      const onClose = vi.fn()
      mockAxios.get.mockImplementation(async (url: string) =>
        uninstallPlan(url.includes('purgeData=true'))
      )
      mockAxios.delete.mockResolvedValue({ data: {} })
      const user = userEvent.setup()
      render(
        <ExternalModuleUninstallDialog moduleId="acme-billing" name="Billing" onClose={onClose} />,
        {
          wrapper: createTestWrapper(),
        }
      )

      expect(await screen.findByTestId('module-uninstall-summary')).toHaveTextContent(
        '1 removed, 1 kept'
      )
      expect(screen.getByText('Kept: it holds records (purge data to remove)')).toBeInTheDocument()

      await user.click(screen.getByTestId('module-uninstall-purge'))
      await waitFor(() =>
        expect(screen.getByTestId('module-uninstall-summary')).toHaveTextContent(
          '2 removed, 0 kept'
        )
      )
      await user.click(screen.getByTestId('module-confirm-uninstall'))

      await waitFor(() => expect(onClose).toHaveBeenCalled())
      expect(mockAxios.delete).toHaveBeenCalledWith(
        `/api/modules/acme-billing?purgeData=true&planHash=${'y'.repeat(64)}`,
        undefined
      )
    })
  })

  describe('ExternalModuleHealth', () => {
    it('checks health only when asked and shows the breaker', async () => {
      mockAxios.get.mockResolvedValue({
        data: {
          moduleHealth: { status: 'unreachable', error: 'connect refused' },
          breakerState: 'OPEN',
          consecutiveFailures: 5,
          lastFailure: 'POST /kelta/v1/hooks answered 503',
          podName: 'worker-0',
        },
      })
      const user = userEvent.setup()
      render(<ExternalModuleHealth moduleId="acme-billing" />, { wrapper: createTestWrapper() })

      expect(mockAxios.get).not.toHaveBeenCalled()
      await user.click(screen.getByRole('button', { name: /check health/i }))

      expect(await screen.findByTestId('module-health-answer-acme-billing')).toHaveTextContent(
        'unreachable: connect refused'
      )
      expect(screen.getByText('OPEN, 5 failure(s) in a row')).toBeInTheDocument()
      expect(screen.getByText('POST /kelta/v1/hooks answered 503')).toBeInTheDocument()
      expect(mockAxios.get).toHaveBeenCalledWith('/api/modules/acme-billing/health')
    })
  })

  describe('ModulesPage', () => {
    const external = {
      id: 'row-1',
      moduleId: 'acme-billing',
      name: 'Billing',
      version: '1.0.0',
      description: null,
      status: 'DEGRADED',
      installedBy: 'admin',
      installedAt: '2026-10-30T12:00:00Z',
      updatedAt: '2026-10-30T12:00:00Z',
      actions: [],
      baseUrl: BASE_URL,
      protocolVersion: 1,
      uiBundleSha256: BUNDLE_SHA,
      lastError: 'circuit breaker opened after 5 consecutive failures',
    }

    it('reads the JSON:API module list and marks an external module with its DEGRADED reason', async () => {
      mockAxios.get.mockResolvedValue({
        data: { data: [{ type: 'modules', id: 'row-1', attributes: external }] },
      })
      const user = userEvent.setup()
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      expect(await screen.findByTestId('module-external-acme-billing')).toBeInTheDocument()
      expect(screen.getByTestId('module-last-error-acme-billing')).toHaveTextContent(
        'Not answering, calls are failing fast: circuit breaker opened'
      )
      expect(screen.getByTestId('module-check-upgrade-acme-billing')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Uninstall Billing' }))
      expect(await screen.findByTestId('module-uninstall-dialog')).toBeInTheDocument()
    })

    it('opens install by URL by default', async () => {
      mockAxios.get.mockResolvedValue({ data: [] })
      const user = userEvent.setup()
      render(<ModulesPage />, { wrapper: createTestWrapper() })

      await user.click((await screen.findAllByRole('button', { name: /install module/i }))[0])

      expect(screen.getByTestId('external-module-install')).toBeInTheDocument()
      await user.click(screen.getByTestId('install-mode-jar'))
      expect(screen.getByLabelText('Module Manifest (JSON) *')).toBeInTheDocument()
    })
  })
})
