/**
 * BootstrapTokenDialog
 *
 * Mints a short-lived personal access token inside a managed tenant, as that tenant's
 * seeded System Administrator (`POST /api/tenants/{id}/bootstrap-token`). The token is
 * shown once with a copy button; closing the dialog discards it.
 */

import React, { useCallback, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import type { TenantBootstrapTokenResponse } from '@kelta/sdk'
import { useApi } from '../../context/ApiContext'
import { useToast } from '../../components'

const LIFETIMES = [
  { value: '1h', label: '1 hour' },
  { value: '4h', label: '4 hours' },
  { value: '8h', label: '8 hours' },
  { value: '24h', label: '24 hours (maximum)' },
]

export interface BootstrapTokenDialogProps {
  tenant: { id: string; slug: string; name: string }
  onClose: () => void
}

export function BootstrapTokenDialog({
  tenant,
  onClose,
}: BootstrapTokenDialogProps): React.ReactElement {
  const { keltaClient } = useApi()
  const { showToast } = useToast()
  const [expiresIn, setExpiresIn] = useState('1h')
  const [minted, setMinted] = useState<TenantBootstrapTokenResponse | null>(null)
  const [copied, setCopied] = useState(false)

  const mintMutation = useMutation({
    mutationFn: () => keltaClient.admin.tenants.bootstrapToken(tenant.id, { expiresIn }),
    onSuccess: (result) => setMinted(result),
    onError: (error: Error) => {
      showToast(error.message || 'Failed to mint the bootstrap token.', 'error')
    },
  })

  const handleCopy = useCallback(() => {
    if (!minted) return
    navigator.clipboard.writeText(minted.token).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }, [minted])

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      mintMutation.mutate()
    },
    [mintMutation]
  )

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={(e) => e.target === e.currentTarget && onClose()}
      onKeyDown={(e) => e.key === 'Escape' && onClose()}
      role="presentation"
    >
      <div
        className="w-full max-w-[560px] rounded-lg bg-card p-6 shadow-xl"
        role="dialog"
        aria-modal="true"
        aria-labelledby="bootstrap-token-title"
        data-testid="bootstrap-token-dialog"
      >
        <h2 id="bootstrap-token-title" className="mb-2 text-xl font-semibold">
          Bootstrap token — {tenant.name}
        </h2>

        {minted ? (
          <div className="space-y-4">
            <div className="rounded-lg border border-amber-300 bg-amber-50 p-4 dark:border-amber-700 dark:bg-amber-950">
              <p
                className="mb-3 text-sm font-medium text-amber-800 dark:text-amber-300"
                data-testid="bootstrap-token-warning"
              >
                Copy this token now. It will not be shown again. It acts as the tenant&apos;s
                administrator on <span className="font-mono">{tenant.slug}</span> only, until{' '}
                {new Date(minted.expiresAt).toLocaleString()}.
              </p>
              <div className="flex items-center gap-2">
                <code
                  className="flex-1 break-all rounded bg-muted px-2 py-1.5 font-mono text-xs text-foreground"
                  data-testid="bootstrap-token-value"
                >
                  {minted.token}
                </code>
                <button
                  type="button"
                  className="shrink-0 rounded-md border border-border bg-secondary px-3 py-1.5 text-xs font-medium text-foreground hover:bg-muted"
                  onClick={handleCopy}
                  data-testid="bootstrap-token-copy"
                >
                  {copied ? 'Copied!' : 'Copy'}
                </button>
              </div>
            </div>
            <div className="flex justify-end">
              <button
                type="button"
                className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
                onClick={onClose}
                data-testid="bootstrap-token-done"
              >
                Done
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <p className="mb-4 text-sm text-muted-foreground">
              Mints a personal access token for this tenant&apos;s System Administrator so
              automation can configure it before anyone claims the account. The token works only on
              this tenant and the mint is recorded in the security audit log.
            </p>
            <label className="mb-1 block text-sm font-medium" htmlFor="bootstrap-token-expires-in">
              Expires in
            </label>
            <select
              id="bootstrap-token-expires-in"
              value={expiresIn}
              onChange={(e) => setExpiresIn(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
              data-testid="bootstrap-token-expires-in"
            >
              {LIFETIMES.map((lifetime) => (
                <option key={lifetime.value} value={lifetime.value}>
                  {lifetime.label}
                </option>
              ))}
            </select>
            <div className="mt-6 flex justify-end gap-2">
              <button
                type="button"
                className="cursor-pointer rounded-md border border-border bg-muted px-4 py-2 text-sm text-foreground"
                onClick={onClose}
                data-testid="bootstrap-token-cancel"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="cursor-pointer rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                disabled={mintMutation.isPending}
                data-testid="bootstrap-token-submit"
              >
                {mintMutation.isPending ? 'Minting...' : 'Mint token'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}
