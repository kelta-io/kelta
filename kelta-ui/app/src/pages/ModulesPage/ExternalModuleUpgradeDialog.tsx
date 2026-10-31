import React, { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { useMutation, useQueryClient } from '@tanstack/react-query'
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
import { ModulePlanReview } from './ModulePlanReview'
import { fetchInstallPlan, isPlanChanged, type InstallPlan } from './externalModulePlan'

export interface ExternalModuleUpgradeDialogProps {
  moduleId: string
  name: string
  baseUrl: string
  onClose: () => void
}

/**
 * Checks an installed external module for an upgrade: plans against what its base URL serves now
 * and, when that is a newer version, shows the plan for re-consent. An upgrade that asks for more
 * access is flagged by the plan itself (`changes.permissions.widened`).
 */
export function ExternalModuleUpgradeDialog({
  moduleId,
  name,
  baseUrl,
  onClose,
}: ExternalModuleUpgradeDialogProps): React.ReactElement {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  const [plan, setPlan] = useState<InstallPlan | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [planChanged, setPlanChanged] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetchInstallPlan(apiClient, baseUrl)
      .then((fetched) => !cancelled && setPlan(fetched))
      .catch((err: Error) => !cancelled && setLoadError(err.message || 'Could not read the module'))
    return () => {
      cancelled = true
    }
  }, [apiClient, baseUrl])

  const upgradeMutation = useMutation({
    mutationFn: (reviewed: InstallPlan) =>
      apiClient.post(`/api/modules/${encodeURIComponent(moduleId)}/upgrade`, {
        planHash: reviewed.planHash,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['modules'] })
      toast.success('Module upgraded')
      onClose()
    },
    onError: async (err: Error) => {
      if (isPlanChanged(err)) {
        setPlanChanged(true)
        try {
          setPlan(await fetchInstallPlan(apiClient, baseUrl))
        } catch (refetchError) {
          toast.error((refetchError as Error).message || 'Could not read the module')
        }
        return
      }
      toast.error(err.message || 'Failed to upgrade module')
    },
  })

  const upToDate = plan?.mode === 'NO_OP'
  const canUpgrade = plan?.mode === 'UPGRADE' && plan.blockers.length === 0

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        className="max-h-[90vh] max-w-3xl overflow-y-auto"
        data-testid="module-upgrade-dialog"
      >
        <DialogHeader>
          <DialogTitle>Upgrade {name}</DialogTitle>
          <DialogDescription>
            What upgrading to the version {baseUrl} serves now would change.
          </DialogDescription>
        </DialogHeader>
        {loadError && (
          <p className="text-sm text-red-700" role="alert">
            {loadError}
          </p>
        )}
        {!plan && !loadError && <LoadingSpinner />}
        {upToDate && (
          <p data-testid="module-upgrade-up-to-date" className="text-sm text-gray-600">
            The installed version is the one the module serves; there is nothing to upgrade.
          </p>
        )}
        {plan && !upToDate && (
          <>
            {planChanged && (
              <p
                data-testid="module-plan-changed"
                className="rounded-md bg-amber-50 p-2 text-xs text-amber-800"
                role="status"
              >
                The plan changed since you reviewed it. Review this one before upgrading.
              </p>
            )}
            <ModulePlanReview plan={plan} />
          </>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
          <Button
            onClick={() => plan && upgradeMutation.mutate(plan)}
            disabled={!canUpgrade || upgradeMutation.isPending}
            data-testid="module-approve-upgrade"
          >
            {upgradeMutation.isPending ? 'Upgrading…' : 'Approve and upgrade'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
