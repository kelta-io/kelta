import React, { useState, useCallback, useEffect } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { Button } from '@/components/ui/button'

interface MfaPolicy {
  mfaRequired: boolean
  allowedMethods: string[]
  gracePeriodDays: number | null
}

const DEFAULTS: MfaPolicy = {
  mfaRequired: false,
  allowedMethods: ['TOTP'],
  gracePeriodDays: null,
}

const AVAILABLE_METHODS = [
  { key: 'TOTP', label: 'Authenticator App (TOTP)' },
  { key: 'SMS', label: 'SMS Verification' },
] as const

export function MfaPolicyPanel(): React.ReactElement {
  const queryClient = useQueryClient()
  const { keltaClient } = useApi()

  const { data: policy, isLoading } = useQuery({
    queryKey: ['mfa-policy'],
    queryFn: () => keltaClient.admin.mfa.getPolicy(),
  })

  const [form, setForm] = useState<MfaPolicy>(DEFAULTS)

  useEffect(() => {
    if (policy) {
      const raw = (policy as Record<string, unknown>).data ?? policy
      const p = raw as Record<string, unknown>
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setForm({
        mfaRequired: typeof p.mfaRequired === 'boolean' ? p.mfaRequired : DEFAULTS.mfaRequired,
        allowedMethods: Array.isArray(p.allowedMethods)
          ? p.allowedMethods
          : DEFAULTS.allowedMethods,
        gracePeriodDays:
          typeof p.gracePeriodDays === 'number' ? p.gracePeriodDays : DEFAULTS.gracePeriodDays,
      })
    }
  }, [policy])

  const updateMutation = useMutation({
    mutationFn: (data: MfaPolicy) =>
      keltaClient.admin.mfa.updatePolicy(data as unknown as { mfaRequired: boolean }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['mfa-policy'] })
      toast.success('MFA policy updated')
    },
    onError: (err: Error) => toast.error(err.message || 'Failed to update MFA policy'),
  })

  const handleMethodToggle = useCallback((method: string, checked: boolean) => {
    setForm((prev) => {
      const methods = checked
        ? [...prev.allowedMethods, method]
        : prev.allowedMethods.filter((m) => m !== method)
      return { ...prev, allowedMethods: methods }
    })
  }, [])

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      updateMutation.mutate(form)
    },
    [form, updateMutation]
  )

  if (isLoading) {
    return <div className="p-6 text-muted-foreground">Loading MFA policy...</div>
  }

  return (
    <div className="mx-auto max-w-[800px] space-y-6 p-6 lg:p-8" data-testid="mfa-policy-panel">
      <header>
        <h1 className="text-2xl font-semibold text-foreground">MFA Policy</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Configure multi-factor authentication requirements for this tenant.
        </p>
      </header>

      <form onSubmit={handleSubmit} className="space-y-8">
        {/* MFA Enforcement */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">Enforcement</h2>
          <label className="flex items-center gap-3 text-sm text-foreground">
            <input
              type="checkbox"
              checked={form.mfaRequired}
              onChange={(e) => setForm((prev) => ({ ...prev, mfaRequired: e.target.checked }))}
              className="h-4 w-4 rounded border-border"
              data-testid="mfa-required-checkbox"
            />
            Require MFA for all users
          </label>
          <p className="text-xs text-muted-foreground ml-7">
            When enabled, users without MFA enrollment will be prompted to set up MFA on their next
            login.
          </p>
        </section>

        {/* Allowed Methods */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">Allowed Methods</h2>
          <p className="text-xs text-muted-foreground">
            Select which MFA methods users can choose from during enrollment.
          </p>
          {AVAILABLE_METHODS.map(({ key, label }) => (
            <label key={key} className="flex items-center gap-3 text-sm text-foreground">
              <input
                type="checkbox"
                checked={form.allowedMethods.includes(key)}
                onChange={(e) => handleMethodToggle(key, e.target.checked)}
                className="h-4 w-4 rounded border-border"
                data-testid={`mfa-method-${key.toLowerCase()}-checkbox`}
              />
              {label}
            </label>
          ))}
        </section>

        {/* Grace Period */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">Enrollment grace period</h2>
          <div>
            <label htmlFor="gracePeriodDays" className="block text-sm font-medium text-foreground">
              Grace period (days, empty = immediate enforcement)
            </label>
            <input
              id="gracePeriodDays"
              type="number"
              min={0}
              max={90}
              value={form.gracePeriodDays ?? ''}
              onChange={(e) =>
                setForm((prev) => ({
                  ...prev,
                  gracePeriodDays: e.target.value ? parseInt(e.target.value) : null,
                }))
              }
              placeholder="Immediate"
              className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
              data-testid="grace-period-input"
            />
            <p className="mt-1 text-xs text-muted-foreground">
              Number of days users have to enroll in MFA after the policy is enabled. Leave empty to
              require immediate enrollment.
            </p>
          </div>
        </section>

        <div className="flex justify-end">
          <Button
            type="submit"
            disabled={updateMutation.isPending}
            data-testid="save-mfa-policy-button"
          >
            {updateMutation.isPending ? 'Saving...' : 'Save Policy'}
          </Button>
        </div>
      </form>
    </div>
  )
}

export default MfaPolicyPanel
