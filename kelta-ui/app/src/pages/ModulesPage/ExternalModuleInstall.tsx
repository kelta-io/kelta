import React, { useState } from 'react'
import { toast } from 'sonner'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { Button } from '@/components/ui/button'
import { ModulePlanReview } from './ModulePlanReview'
import { fetchInstallPlan, isPlanChanged, type InstallPlan } from './externalModulePlan'

export interface ExternalModuleInstallProps {
  onDone: () => void
}

/**
 * Install an external module by URL: fetch the plan, show it for consent, then install with the
 * reviewed `planHash`. If the plan changed in between (the module redeployed, metadata changed)
 * the worker answers 409 and the new plan is fetched and shown instead; nothing was installed.
 */
export function ExternalModuleInstall({ onDone }: ExternalModuleInstallProps): React.ReactElement {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  const [baseUrl, setBaseUrl] = useState('')
  const [plan, setPlan] = useState<InstallPlan | null>(null)
  const [planChanged, setPlanChanged] = useState(false)

  const planMutation = useMutation({
    mutationFn: (url: string) => fetchInstallPlan(apiClient, url),
    onSuccess: (fetched) => setPlan(fetched),
    onError: (err: Error) => toast.error(err.message || 'Could not read the module'),
  })

  const installMutation = useMutation({
    mutationFn: (reviewed: InstallPlan) =>
      apiClient.post('/api/modules/install', {
        baseUrl: reviewed.baseUrl,
        planHash: reviewed.planHash,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['modules'] })
      toast.success('Module installed')
      onDone()
    },
    onError: async (err: Error) => {
      if (isPlanChanged(err) && plan) {
        setPlanChanged(true)
        try {
          setPlan(await fetchInstallPlan(apiClient, plan.baseUrl))
        } catch (refetchError) {
          toast.error((refetchError as Error).message || 'Could not read the module')
        }
        return
      }
      toast.error(err.message || 'Failed to install module')
    },
  })

  const reviewPlan = () => {
    if (!baseUrl.trim()) {
      toast.error('The module URL is required')
      return
    }
    setPlanChanged(false)
    planMutation.mutate(baseUrl.trim())
  }

  const blocked = !plan || plan.blockers.length > 0 || plan.mode === 'BLOCKED'
  const alreadyInstalled = plan?.mode === 'UPGRADE' || plan?.mode === 'NO_OP'

  return (
    <div data-testid="external-module-install" className="space-y-4">
      <div>
        <label htmlFor="module-base-url" className="mb-1 block text-sm font-medium text-gray-700">
          Module URL *
        </label>
        <div className="flex gap-2">
          <input
            id="module-base-url"
            type="url"
            value={baseUrl}
            onChange={(e) => {
              setBaseUrl(e.target.value)
              setPlan(null)
            }}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            placeholder="https://billing.example.com"
          />
          <Button
            variant="outline"
            onClick={reviewPlan}
            disabled={planMutation.isPending}
            data-testid="module-review-plan"
          >
            {planMutation.isPending ? 'Reading…' : 'Review'}
          </Button>
        </div>
        <p className="mt-1 text-xs text-gray-500">
          The module&apos;s base URL. Kelta reads its manifest from
          <code> /.well-known/kelta-module.json</code> and shows you what it would be granted before
          anything is installed.
        </p>
      </div>

      {plan && (
        <div className="rounded-md border border-gray-200 p-4">
          {planChanged && (
            <p
              data-testid="module-plan-changed"
              className="mb-3 rounded-md bg-amber-50 p-2 text-xs text-amber-800"
              role="status"
            >
              The plan changed since you reviewed it. Review this one before installing.
            </p>
          )}
          <ModulePlanReview plan={plan} />
          {alreadyInstalled && (
            <p className="mt-3 text-xs text-gray-600">
              This module is already installed; upgrade it from its card instead.
            </p>
          )}
        </div>
      )}

      <div className="flex justify-end gap-3">
        <Button variant="outline" onClick={onDone}>
          Cancel
        </Button>
        <Button
          onClick={() => plan && installMutation.mutate(plan)}
          disabled={blocked || alreadyInstalled || installMutation.isPending}
          data-testid="module-approve-install"
        >
          {installMutation.isPending ? 'Installing…' : 'Approve and install'}
        </Button>
      </div>
    </div>
  )
}
