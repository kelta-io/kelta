import React from 'react'
import { AlertTriangle, ShieldAlert, XCircle } from 'lucide-react'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable/AdminDataTable'
import {
  durationLabel,
  metadataCounts,
  objectGrantLabel,
  type FieldGrant,
  type InstallPlan,
  type MetadataPlanItem,
  type ObjectGrant,
  type PlanDiff,
} from './externalModulePlan'

export interface ModulePlanReviewProps {
  plan: InstallPlan
}

const objectColumns: AdminColumn<ObjectGrant>[] = [
  { id: 'collection', header: 'Collection', sortable: true },
  { id: 'access', header: 'Access', accessor: objectGrantLabel, hideable: false },
]

const fieldColumns: AdminColumn<FieldGrant>[] = [
  {
    id: 'field',
    header: 'Field',
    accessor: (g) => `${g.collection}.${g.field}`,
    sortable: true,
  },
  { id: 'visibility', header: 'Visibility', sortable: true },
]

const metadataColumns: AdminColumn<MetadataPlanItem>[] = [
  { id: 'type', header: 'Type', sortable: true },
  { id: 'naturalKey', header: 'Name', sortable: true },
  { id: 'action', header: 'Action', sortable: true },
]

function Section({
  title,
  testId,
  children,
}: {
  title: string
  testId?: string
  children: React.ReactNode
}): React.ReactElement {
  return (
    <section data-testid={testId} className="space-y-2">
      <h4 className="text-sm font-medium text-gray-700">{title}</h4>
      {children}
    </section>
  )
}

function Chips({ values, empty }: { values: string[]; empty: string }): React.ReactElement {
  if (values.length === 0) {
    return <p className="text-xs text-gray-400">{empty}</p>
  }
  return (
    <div className="flex flex-wrap gap-1.5">
      {values.map((value) => (
        <code key={value} className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-700">
          {value}
        </code>
      ))}
    </div>
  )
}

function diffLines(label: string, diff: PlanDiff | undefined): string[] {
  if (!diff) return []
  return [
    ...diff.added.map((item) => `+ ${label} ${item}`),
    ...diff.removed.map((item) => `− ${label} ${item}`),
  ]
}

/**
 * The consent screen for an external module install or upgrade: everything the module would be
 * granted and would do, and every blocker, exactly as the worker planned it. The admin approves
 * this plan by its `planHash`, which is shown so it can be checked against what the API returned.
 */
export function ModulePlanReview({ plan }: ModulePlanReviewProps): React.ReactElement {
  const grants = plan.grants
  const capabilities = plan.capabilities
  const permissions = plan.changes?.permissions
  const counts = metadataCounts(plan.metadata)
  const capabilityChanges = [
    ...diffLines('action', plan.changes?.actions),
    ...diffLines('hook', plan.changes?.hooks),
    ...diffLines('event', plan.changes?.events),
    ...diffLines('route', plan.changes?.routes),
    ...diffLines('service', plan.changes?.services),
    ...diffLines('schedule', plan.changes?.schedules),
  ]

  return (
    <div data-testid="module-plan-review" className="space-y-5">
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-base font-medium text-gray-900">{plan.name ?? plan.moduleId}</h3>
          <code className="text-xs text-gray-500">{plan.moduleId}</code>
          <span className="text-xs text-gray-500" data-testid="module-plan-version">
            {plan.fromVersion ? `v${plan.fromVersion} → v${plan.toVersion}` : `v${plan.toVersion}`}
          </span>
          <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
            {plan.mode}
          </span>
        </div>
        {plan.description && <p className="mt-0.5 text-sm text-gray-500">{plan.description}</p>}
        <p className="mt-0.5 text-xs text-gray-400">
          From <code>{plan.baseUrl}</code>
          {plan.author ? ` · by ${plan.author}` : ''}
        </p>
      </div>

      {plan.blockers.length > 0 && (
        <div
          data-testid="module-plan-blockers"
          className="rounded-md border border-red-200 bg-red-50 p-3"
          role="alert"
        >
          <p className="mb-1 flex items-center gap-1 text-sm font-medium text-red-800">
            <XCircle size={14} /> This module cannot be installed until these are resolved
          </p>
          <ul className="space-y-1 text-xs text-red-700">
            {plan.blockers.map((blocker) => (
              <li key={`${blocker.code}:${blocker.message}`}>
                <code className="font-medium">{blocker.code}</code>: {blocker.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {permissions?.widened && (
        <div
          data-testid="module-plan-permissions-widened"
          className="rounded-md border border-amber-200 bg-amber-50 p-3"
          role="alert"
        >
          <p className="mb-1 flex items-center gap-1 text-sm font-medium text-amber-800">
            <ShieldAlert size={14} /> This upgrade asks for more access than you approved
          </p>
          <Chips values={permissions.added} empty="" />
          {permissions.removed.length > 0 && (
            <p className="mt-2 text-xs text-amber-700">
              It no longer needs: {permissions.removed.join(', ')}
            </p>
          )}
        </div>
      )}

      {grants && (
        <Section title="Access requested" testId="module-plan-grants">
          {grants.ownedCollections.length > 0 && (
            <p className="text-xs text-gray-600">
              Full access to the collections it creates:{' '}
              <span className="font-medium">{grants.ownedCollections.join(', ')}</span>
            </p>
          )}
          <AdminDataTable
            tableId="module-plan-objects"
            testId="module-plan-objects"
            ariaLabel="Collection access"
            columns={objectColumns}
            rows={grants.objects}
            rowKey={(g) => g.collection}
            filterable={false}
            emptyMessage="No access to existing collections"
          />
          {grants.fields.length > 0 && (
            <AdminDataTable
              tableId="module-plan-fields"
              testId="module-plan-fields"
              ariaLabel="Field visibility"
              columns={fieldColumns}
              rows={grants.fields}
              rowKey={(g) => `${g.collection}.${g.field}`}
              filterable={false}
            />
          )}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <p className="mb-1 text-xs font-medium text-gray-600">System permissions</p>
              <Chips values={grants.system} empty="None" />
            </div>
            <div>
              <p className="mb-1 text-xs font-medium text-gray-600">Credentials</p>
              <Chips values={grants.credentials} empty="None" />
            </div>
          </div>
        </Section>
      )}

      {capabilities && (
        <Section title="What it adds" testId="module-plan-capabilities">
          <ul className="space-y-1 text-xs text-gray-600">
            <li>
              {capabilities.actions.length} flow action
              {capabilities.actions.length === 1 ? '' : 's'}
              {capabilities.actions.length > 0 &&
                `: ${capabilities.actions.map((a) => a.key).join(', ')}`}
            </li>
            {capabilities.actions
              .filter((action) => action.maxDurationSeconds != null)
              .map((action) => (
                <li key={`long-running:${action.key}`}>
                  <code>{action.key}</code> runs in the background: a flow step waits for it up to{' '}
                  {durationLabel(action.maxDurationSeconds as number)}
                </li>
              ))}
            {capabilities.hooks.map((hook) => (
              <li key={`hook:${hook.collection}:${hook.order}`}>
                Runs before saves on <code>{hook.collection}</code> ({hook.events.join(', ')}; on
                failure: {hook.failurePolicy})
              </li>
            ))}
            {capabilities.events.map((event) => (
              <li key={`event:${event.collection}`}>
                Receives <code>{event.collection}</code> changes ({event.types.join(', ')})
              </li>
            ))}
            {capabilities.routes.map((route) => (
              <li key={`route:${route.path}`}>
                API route {route.methods.join(', ')} <code>{route.path}</code>
                {route.permission ? ` (requires ${route.permission})` : ''}
              </li>
            ))}
            {capabilities.schedules.map((schedule) => (
              <li key={`schedule:${schedule.action}:${schedule.cron}`}>
                Runs <code>{schedule.action}</code> on <code>{schedule.cron}</code>
              </li>
            ))}
            {capabilities.services.map((service) => (
              <li key={`service:${service.port}`}>
                Provides the <code>{service.port}</code> service
              </li>
            ))}
            {capabilities.webhook && <li>Receives webhooks from external providers</li>}
          </ul>
          {capabilityChanges.length > 0 && (
            <div data-testid="module-plan-capability-changes">
              <p className="mb-1 text-xs font-medium text-gray-600">Changes from the installed</p>
              <Chips values={capabilityChanges} empty="" />
            </div>
          )}
        </Section>
      )}

      {plan.uiBundle && (
        <Section title="Admin UI bundle" testId="module-plan-ui-bundle">
          <p className="text-xs text-gray-600">
            Runs in your admin session. Kelta stores this exact file at install and serves only it;
            the module cannot change it without an upgrade.
          </p>
          {plan.uiBundle.sha256 ? (
            <p className="text-xs text-gray-600">
              <code>{plan.uiBundle.bundlePath}</code> · SHA-256{' '}
              <code className="break-all">{plan.uiBundle.sha256}</code>
            </p>
          ) : (
            <p className="text-xs text-gray-600">This version ships no admin UI.</p>
          )}
          {plan.uiBundle.changed && (
            <p
              data-testid="module-plan-ui-bundle-changed"
              className="flex items-center gap-1 text-xs text-amber-700"
            >
              <AlertTriangle size={12} />
              Replaces the installed bundle
              {plan.uiBundle.installedSha256 ? ` (${plan.uiBundle.installedSha256})` : ''}
            </p>
          )}
        </Section>
      )}

      <Section title="Metadata" testId="module-plan-metadata">
        <div data-testid="module-plan-metadata-counts" className="flex flex-wrap gap-1.5">
          {Object.entries(counts).map(([action, count]) => (
            <span
              key={action}
              className="rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700"
            >
              {action} {count}
            </span>
          ))}
        </div>
        <AdminDataTable
          tableId="module-plan-metadata"
          testId="module-plan-metadata-table"
          ariaLabel="Metadata plan"
          columns={metadataColumns}
          rows={plan.metadata ?? []}
          rowKey={(item) => `${item.type}:${item.naturalKey}`}
          filterable={false}
          emptyMessage="The module provisions no metadata"
        />
        {(plan.orphanedByUpgrade?.length ?? 0) > 0 && (
          <p className="text-xs text-gray-500">
            No longer declared (kept): {plan.orphanedByUpgrade?.map((o) => o.naturalKey).join(', ')}
          </p>
        )}
      </Section>

      <p className="text-xs text-gray-400">
        Plan <code data-testid="module-plan-hash">{plan.planHash}</code>
      </p>
    </div>
  )
}
