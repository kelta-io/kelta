import React, { useState } from 'react'
import { toast } from 'sonner'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { LoadingSpinner } from '../../components'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable/AdminDataTable'
import {
  fetchUninstallPlan,
  isPlanChanged,
  type UninstallPlan,
  type UninstallPlanEntry,
} from './externalModulePlan'

export interface ExternalModuleUninstallDialogProps {
  moduleId: string
  name: string
  onClose: () => void
}

/** Plain-language reading of each uninstall decision (`ModuleUninstaller`). */
const DECISIONS: Record<string, string> = {
  REMOVE: 'Removed',
  KEEP_ADOPTED: 'Kept: it was yours before the module adopted it',
  KEEP_UNVERIFIABLE: 'Kept: cannot confirm it is unchanged',
  KEEP_MODIFIED: 'Kept: changed since the module created it',
  KEEP_HAS_DATA: 'Kept: it holds records (purge data to remove)',
  KEEP_HAS_DEPENDENTS: 'Kept: other metadata depends on it',
}

const columns: AdminColumn<UninstallPlanEntry>[] = [
  { id: 'resourceType', header: 'Type', sortable: true },
  { id: 'naturalKey', header: 'Name', sortable: true },
  {
    id: 'decision',
    header: 'On uninstall',
    sortable: true,
    cell: (entry) => (
      <span className={entry.decision === 'REMOVE' ? 'text-red-700' : 'text-gray-600'}>
        {DECISIONS[entry.decision] ?? entry.decision}
      </span>
    ),
  },
]

/**
 * Uninstalling an external module: shows the uninstall plan (what goes, what stays and why) for
 * the chosen `purgeData`, and deletes with that plan's `planHash`, so only what was shown is
 * removed. A plan that changed meanwhile (409) is re-read and shown again.
 */
export function ExternalModuleUninstallDialog({
  moduleId,
  name,
  onClose,
}: ExternalModuleUninstallDialogProps): React.ReactElement {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  const [purgeData, setPurgeData] = useState(false)
  const [planChanged, setPlanChanged] = useState(false)

  // Keyed on purgeData, so toggling it never leaves the other choice's plan on screen.
  const planQuery = useQuery({
    queryKey: ['module-uninstall-plan', moduleId, purgeData],
    queryFn: () => fetchUninstallPlan(apiClient, moduleId, purgeData),
    retry: false,
  })
  const plan = planQuery.data ?? null
  const loadError = planQuery.error
    ? planQuery.error.message || 'Could not plan the uninstall'
    : null

  const uninstallMutation = useMutation({
    mutationFn: (reviewed: UninstallPlan) =>
      apiClient.delete(
        `/api/modules/${encodeURIComponent(moduleId)}?purgeData=${reviewed.purgeData}` +
          `&planHash=${encodeURIComponent(reviewed.planHash)}`
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['modules'] })
      toast.success('Module uninstalled')
      onClose()
    },
    onError: async (err: Error) => {
      if (isPlanChanged(err)) {
        setPlanChanged(true)
        await planQuery.refetch()
        return
      }
      toast.error(err.message || 'Failed to uninstall module')
    },
  })

  const removed = plan?.entries.filter((e) => e.decision === 'REMOVE').length ?? 0

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        className="max-h-[90vh] max-w-2xl overflow-y-auto"
        data-testid="module-uninstall-dialog"
      >
        <DialogHeader>
          <DialogTitle>Uninstall {name}</DialogTitle>
          <DialogDescription>
            The module stops running at once. Below is what happens to what it provisioned.
          </DialogDescription>
        </DialogHeader>
        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={purgeData}
            onChange={(e) => setPurgeData(e.target.checked)}
            data-testid="module-uninstall-purge"
          />
          Also delete collections it created that hold records (purge data)
        </label>
        {loadError && (
          <p className="text-sm text-red-700" role="alert">
            {loadError}
          </p>
        )}
        {!plan && !loadError && <LoadingSpinner />}
        {plan && (
          <div className="space-y-2">
            {planChanged && (
              <p
                data-testid="module-plan-changed"
                className="rounded-md bg-amber-50 p-2 text-xs text-amber-800"
                role="status"
              >
                The plan changed since you reviewed it. Review this one before uninstalling.
              </p>
            )}
            <p className="text-sm text-gray-600" data-testid="module-uninstall-summary">
              {removed} removed, {plan.entries.length - removed} kept
            </p>
            <AdminDataTable
              tableId="module-uninstall-plan"
              testId="module-uninstall-plan"
              ariaLabel="Uninstall plan"
              columns={columns}
              rows={plan.entries}
              rowKey={(e) => `${e.resourceType}:${e.naturalKey}`}
              filterable={false}
              emptyMessage="The module provisioned nothing"
            />
            <p className="text-xs text-gray-400">
              Plan <code>{plan.planHash}</code>
            </p>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => plan && uninstallMutation.mutate(plan)}
            disabled={!plan || planQuery.isFetching || uninstallMutation.isPending}
            data-testid="module-confirm-uninstall"
          >
            {uninstallMutation.isPending ? 'Uninstalling…' : 'Uninstall'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
