import React, { useState, useCallback, useEffect } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../../context/ApiContext'
import { Button } from '@/components/ui/button'

interface PasswordPolicy {
  minLength: number
  maxLength: number
  requireUppercase: boolean
  requireLowercase: boolean
  requireDigit: boolean
  requireSpecial: boolean
  historyCount: number
  dictionaryCheck: boolean
  personalDataCheck: boolean
  lockoutThreshold: number
  lockoutDurationMinutes: number
  maxAgeDays: number | null
}

const DEFAULTS: PasswordPolicy = {
  minLength: 8,
  maxLength: 128,
  requireUppercase: false,
  requireLowercase: false,
  requireDigit: false,
  requireSpecial: false,
  historyCount: 3,
  dictionaryCheck: true,
  personalDataCheck: true,
  lockoutThreshold: 5,
  lockoutDurationMinutes: 30,
  maxAgeDays: null,
}

export function PasswordPolicyPanel(): React.ReactElement {
  const queryClient = useQueryClient()
  const { keltaClient } = useApi()

  const { data: policy, isLoading } = useQuery({
    queryKey: ['password-policy'],
    queryFn: () => keltaClient.admin.passwordPolicy.get(),
  })

  const [form, setForm] = useState<PasswordPolicy>(DEFAULTS)

  useEffect(() => {
    if (policy) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setForm(policy as unknown as PasswordPolicy)
    }
  }, [policy])

  const updateMutation = useMutation({
    mutationFn: (data: PasswordPolicy) =>
      keltaClient.admin.passwordPolicy.update(data as unknown as Record<string, unknown>),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['password-policy'] })
      toast.success('Password policy updated')
    },
    onError: (err: Error) => toast.error(err.message || 'Failed to update policy'),
  })

  const handleChange = useCallback(
    (field: keyof PasswordPolicy, value: number | boolean | null) => {
      setForm((prev) => ({ ...prev, [field]: value }))
    },
    []
  )

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      updateMutation.mutate(form)
    },
    [form, updateMutation]
  )

  if (isLoading) {
    return <div className="p-6 text-muted-foreground">Loading password policy...</div>
  }

  return (
    <div className="mx-auto max-w-[800px] space-y-6 p-6 lg:p-8" data-testid="password-policy-panel">
      <header>
        <h1 className="text-2xl font-semibold text-foreground">Password Policy</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Configure password requirements for this tenant. Defaults follow NIST SP 800-63B
          guidelines.
        </p>
      </header>

      <form onSubmit={handleSubmit} className="space-y-8">
        {/* Length */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">Length Requirements</h2>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="minLength" className="block text-sm font-medium text-foreground">
                Minimum Length
              </label>
              <input
                id="minLength"
                type="number"
                min={8}
                max={64}
                value={form.minLength}
                onChange={(e) => handleChange('minLength', parseInt(e.target.value) || 8)}
                className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                data-testid="min-length-input"
              />
            </div>
            <div>
              <label htmlFor="maxLength" className="block text-sm font-medium text-foreground">
                Maximum Length
              </label>
              <input
                id="maxLength"
                type="number"
                min={32}
                max={256}
                value={form.maxLength}
                onChange={(e) => handleChange('maxLength', parseInt(e.target.value) || 128)}
                className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                data-testid="max-length-input"
              />
            </div>
          </div>
        </section>

        {/* Complexity */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">Complexity Requirements</h2>
          {(
            ['requireUppercase', 'requireLowercase', 'requireDigit', 'requireSpecial'] as const
          ).map((field) => (
            <label key={field} className="flex items-center gap-3 text-sm text-foreground">
              <input
                type="checkbox"
                checked={form[field]}
                onChange={(e) => handleChange(field, e.target.checked)}
                className="h-4 w-4 rounded border-border"
                data-testid={`${field}-checkbox`}
              />
              {field === 'requireUppercase' && 'Require uppercase letter (A-Z)'}
              {field === 'requireLowercase' && 'Require lowercase letter (a-z)'}
              {field === 'requireDigit' && 'Require digit (0-9)'}
              {field === 'requireSpecial' && 'Require special character (!@#$...)'}
            </label>
          ))}
        </section>

        {/* History & Checks */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">History & Validation</h2>
          <div>
            <label htmlFor="historyCount" className="block text-sm font-medium text-foreground">
              Password History (prevent reuse of last N)
            </label>
            <input
              id="historyCount"
              type="number"
              min={0}
              max={24}
              value={form.historyCount}
              onChange={(e) => handleChange('historyCount', parseInt(e.target.value) || 0)}
              className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
              data-testid="history-count-input"
            />
          </div>
          <label className="flex items-center gap-3 text-sm text-foreground">
            <input
              type="checkbox"
              checked={form.dictionaryCheck}
              onChange={(e) => handleChange('dictionaryCheck', e.target.checked)}
              className="h-4 w-4 rounded border-border"
              data-testid="dictionary-check-checkbox"
            />
            Block common passwords (10k dictionary)
          </label>
          <label className="flex items-center gap-3 text-sm text-foreground">
            <input
              type="checkbox"
              checked={form.personalDataCheck}
              onChange={(e) => handleChange('personalDataCheck', e.target.checked)}
              className="h-4 w-4 rounded border-border"
              data-testid="personal-data-check-checkbox"
            />
            Block passwords containing user name or email
          </label>
        </section>

        {/* Lockout */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">Account Lockout</h2>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label
                htmlFor="lockoutThreshold"
                className="block text-sm font-medium text-foreground"
              >
                Failed attempts before lockout
              </label>
              <input
                id="lockoutThreshold"
                type="number"
                min={3}
                max={20}
                value={form.lockoutThreshold}
                onChange={(e) => handleChange('lockoutThreshold', parseInt(e.target.value) || 5)}
                className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                data-testid="lockout-threshold-input"
              />
            </div>
            <div>
              <label
                htmlFor="lockoutDuration"
                className="block text-sm font-medium text-foreground"
              >
                Lockout duration (minutes)
              </label>
              <input
                id="lockoutDuration"
                type="number"
                min={5}
                max={1440}
                value={form.lockoutDurationMinutes}
                onChange={(e) =>
                  handleChange('lockoutDurationMinutes', parseInt(e.target.value) || 30)
                }
                className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                data-testid="lockout-duration-input"
              />
            </div>
          </div>
        </section>

        {/* Expiration */}
        <section className="rounded-lg border border-border p-6 space-y-4">
          <h2 className="text-lg font-medium text-foreground">Password Expiration</h2>
          <div>
            <label htmlFor="maxAgeDays" className="block text-sm font-medium text-foreground">
              Maximum password age (days, empty = no expiration)
            </label>
            <input
              id="maxAgeDays"
              type="number"
              min={0}
              max={365}
              value={form.maxAgeDays ?? ''}
              onChange={(e) =>
                handleChange('maxAgeDays', e.target.value ? parseInt(e.target.value) : null)
              }
              placeholder="No expiration"
              className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
              data-testid="max-age-days-input"
            />
          </div>
        </section>

        <div className="flex justify-end">
          <Button
            type="submit"
            disabled={updateMutation.isPending}
            data-testid="save-policy-button"
          >
            {updateMutation.isPending ? 'Saving...' : 'Save Policy'}
          </Button>
        </div>
      </form>
    </div>
  )
}
