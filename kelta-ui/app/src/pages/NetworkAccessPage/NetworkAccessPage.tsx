/**
 * NetworkAccessPage
 *
 * Tenant-level IP allowlist. When enabled, non-admin users may only reach the
 * tenant's data (`/api/**`) from a source IP inside one of the configured CIDR
 * ranges — e.g. the egress IPs of a Twingate gateway pool. Account admins
 * (MANAGE_TENANTS) always bypass, so a misconfigured range cannot lock them out.
 *
 * Persists into the `ipAllowlistEnabled` / `ipAllowlistCidrs` columns on the
 * tenant resource; the worker validates the CIDRs and broadcasts the change to
 * every gateway pod over NATS. Enforcement lives in the gateway
 * (TenantIpAllowlistFilter).
 */

import React, { useState } from 'react'
import { toast } from 'sonner'
import { ShieldCheck, Save, Plus, Trash2 } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { FieldLabel } from '@/components/kelta'
import { LoadingSpinner, ErrorMessage } from '@/components'
import { useTenantIpAllowlist, type TenantIpAllowlist } from '@/hooks/useTenantIpAllowlist'
import { isValidCidr } from '@/utils'

export interface NetworkAccessPageProps {
  testId?: string
}

export function NetworkAccessPage({
  testId = 'network-access-page',
}: NetworkAccessPageProps): React.ReactElement {
  const { allowlist, isLoading, error, save, isSaving } = useTenantIpAllowlist()

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={error} />

  return <NetworkAccessForm allowlist={allowlist} save={save} isSaving={isSaving} testId={testId} />
}

interface NetworkAccessFormProps {
  allowlist: TenantIpAllowlist
  save: (next: TenantIpAllowlist) => Promise<void>
  isSaving: boolean
  testId: string
}

function NetworkAccessForm({
  allowlist,
  save,
  isSaving,
  testId,
}: NetworkAccessFormProps): React.ReactElement {
  const [enabled, setEnabled] = useState<boolean>(allowlist.enabled)
  const [cidrs, setCidrs] = useState<string[]>(allowlist.cidrs)
  const [draft, setDraft] = useState<string>('')

  const draftValid = draft.trim() === '' ? null : isValidCidr(draft.trim())

  const addCidr = (): void => {
    const value = draft.trim()
    if (!value || !isValidCidr(value)) return
    if (!cidrs.includes(value)) {
      setCidrs([...cidrs, value])
    }
    setDraft('')
  }

  const removeCidr = (index: number): void => {
    setCidrs(cidrs.filter((_, i) => i !== index))
  }

  const handleSave = async (): Promise<void> => {
    if (enabled && cidrs.length === 0) {
      toast.error('Add at least one allowed range, or turn off the restriction')
      return
    }
    try {
      await save({ enabled, cidrs })
      toast.success('Network access settings saved')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Save failed')
    }
  }

  return (
    <div className="space-y-6 p-6" data-testid={testId}>
      <div className="flex items-center gap-3">
        <ShieldCheck className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
        <h1 className="kelta-page-title">Network access</h1>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>IP allowlist</CardTitle>
          <CardDescription>
            Restrict access to this tenant&rsquo;s data to specific source networks — for example
            the egress ranges of your Twingate gateway pool. When enabled, non-admin users can only
            reach data from an allowed range. Account administrators always retain access, so a
            wrong range can never lock you out.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <FieldLabel htmlFor="ip-allowlist-enabled">Restrict by IP range</FieldLabel>
              <p className="text-xs text-muted-foreground">
                Off by default. Turning this on with no ranges saved blocks all non-admin users.
              </p>
            </div>
            <Switch
              id="ip-allowlist-enabled"
              checked={enabled}
              onCheckedChange={setEnabled}
              data-testid="ip-allowlist-enabled"
            />
          </div>

          <div className="space-y-2">
            <FieldLabel htmlFor="cidr-input">Allowed ranges (CIDR)</FieldLabel>
            <div className="flex items-start gap-2">
              <div className="flex-1 space-y-1">
                <Input
                  id="cidr-input"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      addCidr()
                    }
                  }}
                  placeholder="e.g. 10.0.0.0/8 or 2001:db8::/32"
                  aria-invalid={draftValid === false}
                  data-testid="cidr-input"
                />
                {draftValid === false && (
                  <p className="text-xs text-destructive" data-testid="cidr-error">
                    Enter a valid IPv4 or IPv6 CIDR range.
                  </p>
                )}
              </div>
              <Button
                type="button"
                variant="outline"
                onClick={addCidr}
                disabled={draftValid !== true}
                data-testid="add-cidr"
              >
                <Plus className="mr-1 h-4 w-4" aria-hidden="true" />
                Add
              </Button>
            </div>

            {cidrs.length === 0 ? (
              <p className="text-sm text-muted-foreground" data-testid="cidr-empty">
                No ranges added yet.
              </p>
            ) : (
              <ul className="divide-y rounded-md border">
                {cidrs.map((cidr, idx) => (
                  <li
                    key={cidr}
                    className="flex items-center justify-between px-3 py-2"
                    data-testid="cidr-row"
                  >
                    <code className="text-sm">{cidr}</code>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => removeCidr(idx)}
                      aria-label={`Remove ${cidr}`}
                      data-testid="remove-cidr"
                    >
                      <Trash2 className="h-4 w-4" aria-hidden="true" />
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </CardContent>
      </Card>

      <div className="flex justify-end">
        <Button onClick={handleSave} disabled={isSaving} data-testid="save-network-access">
          <Save className="mr-2 h-4 w-4" aria-hidden="true" />
          {isSaving ? 'Saving…' : 'Save changes'}
        </Button>
      </div>
    </div>
  )
}
