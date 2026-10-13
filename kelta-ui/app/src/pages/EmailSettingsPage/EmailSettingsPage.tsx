import React, { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
// EmailSettings types are not exported from the SDK; define locally to match the worker DTO.
interface EmailSettings {
  hasOverride: boolean
  fromName?: string | null
  fromAddress?: string | null
  smtp?: {
    host?: string
    port?: number
    useStartTls?: boolean
  }
  autoInviteOnCreate: boolean
}
interface EmailSettingsUpdate {
  host?: string
  port?: number
  username?: string
  password?: string
  useStartTls?: boolean
  fromAddress?: string | null
  fromName?: string | null
  autoInviteOnCreate?: boolean
  clear?: boolean
}
import { useApi } from '../../context/ApiContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { ErrorMessage, LoadingSpinner } from '../../components'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { FieldLabel } from '@/components/kelta'
import { Mail, Send } from 'lucide-react'
import { cn } from '@/lib/utils'

interface FormState {
  host: string
  port: string
  username: string
  password: string
  useStartTls: boolean
  fromAddress: string
  fromName: string
  autoInviteOnCreate: boolean
}

const EMPTY_FORM: FormState = {
  host: '',
  port: '587',
  username: '',
  password: '',
  useStartTls: true,
  fromAddress: '',
  fromName: '',
  autoInviteOnCreate: true,
}

function settingsToForm(s: EmailSettings | undefined): FormState {
  if (!s) return EMPTY_FORM
  return {
    host: s.smtp?.host ?? '',
    port: String(s.smtp?.port ?? 587),
    username: '',
    password: '',
    useStartTls: s.smtp?.useStartTls ?? true,
    fromAddress: s.fromAddress ?? '',
    fromName: s.fromName ?? '',
    autoInviteOnCreate: s.autoInviteOnCreate,
  }
}

export interface EmailSettingsPageProps {
  className?: string
}

export function EmailSettingsPage({ className }: EmailSettingsPageProps): React.ReactElement {
  const { keltaClient } = useApi()
  const { hasPermission } = useSystemPermissions()
  const queryClient = useQueryClient()
  const canManage = hasPermission('MANAGE_TENANTS')

  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [testAddress, setTestAddress] = useState('')

  const { data, isLoading, error } = useQuery({
    queryKey: ['email-settings'],
    queryFn: () => keltaClient.admin.emailSettings.get(),
  })

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setForm(settingsToForm(data as unknown as EmailSettings | undefined))
  }, [data])

  const save = useMutation({
    mutationFn: (update: EmailSettingsUpdate) =>
      keltaClient.admin.emailSettings.update(update as never),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['email-settings'] })
      toast.success('Email settings saved')
    },
    onError: () => toast.error('Failed to save email settings'),
  })

  const clear = useMutation({
    mutationFn: () => keltaClient.admin.emailSettings.update({ clear: true } as never),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['email-settings'] })
      toast.success('Reverted to platform default')
    },
    onError: () => toast.error('Failed to revert'),
  })

  const testSend = useMutation({
    mutationFn: (to: string) => keltaClient.admin.emailSettings.testSend(to),
    onSuccess: () => toast.success('Test email queued'),
    onError: () => toast.error('Failed to queue test email'),
  })

  const handleSave = () => {
    const update: EmailSettingsUpdate = {
      host: form.host || undefined,
      port: form.port ? Number(form.port) : undefined,
      username: form.username || undefined,
      password: form.password || undefined,
      useStartTls: form.useStartTls,
      fromAddress: form.fromAddress || null,
      fromName: form.fromName || null,
      autoInviteOnCreate: form.autoInviteOnCreate,
    }
    save.mutate(update)
  }

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error="Failed to load email settings" />

  const hasOverride = data?.hasOverride

  return (
    <div className={cn('p-6 space-y-6', className)}>
      <header className="flex items-center gap-3">
        <Mail className="h-6 w-6" />
        <div>
          <h1 className="text-2xl font-semibold">Email settings</h1>
          <p className="text-sm text-muted-foreground">
            Configure the SMTP server, From address, and invite behaviour for this tenant.
          </p>
        </div>
      </header>

      <Card>
        <CardHeader>
          <CardTitle>SMTP server</CardTitle>
          <CardDescription>
            {hasOverride
              ? 'This tenant is using a custom SMTP server.'
              : 'This tenant is using the platform default SMTP server.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <FieldLabel>Host</FieldLabel>
              <Input
                value={form.host}
                disabled={!canManage}
                onChange={(e) => setForm((prev) => ({ ...prev, host: e.target.value }))}
                placeholder="smtp.example.com"
              />
            </div>
            <div>
              <FieldLabel>Port</FieldLabel>
              <Input
                value={form.port}
                disabled={!canManage}
                onChange={(e) => setForm((prev) => ({ ...prev, port: e.target.value }))}
                placeholder="587"
                inputMode="numeric"
              />
            </div>
            <div>
              <FieldLabel>Username</FieldLabel>
              <Input
                value={form.username}
                disabled={!canManage}
                onChange={(e) => setForm((prev) => ({ ...prev, username: e.target.value }))}
                placeholder="(unchanged)"
                autoComplete="off"
              />
            </div>
            <div>
              <FieldLabel>Password</FieldLabel>
              <Input
                type="password"
                value={form.password}
                disabled={!canManage}
                onChange={(e) => setForm((prev) => ({ ...prev, password: e.target.value }))}
                placeholder="(unchanged)"
                autoComplete="new-password"
              />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Switch
              checked={form.useStartTls}
              onCheckedChange={(checked) => setForm((prev) => ({ ...prev, useStartTls: checked }))}
              disabled={!canManage}
            />
            <span className="text-sm">Use STARTTLS</span>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>From address</CardTitle>
          <CardDescription>
            Override the sender shown on outbound email. Leave blank to use the platform default.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-4">
          <div>
            <FieldLabel>From address</FieldLabel>
            <Input
              value={form.fromAddress}
              disabled={!canManage}
              onChange={(e) => setForm((prev) => ({ ...prev, fromAddress: e.target.value }))}
              placeholder="noreply@example.com"
            />
          </div>
          <div>
            <FieldLabel>From name</FieldLabel>
            <Input
              value={form.fromName}
              disabled={!canManage}
              onChange={(e) => setForm((prev) => ({ ...prev, fromName: e.target.value }))}
              placeholder="Example Co."
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>User invites</CardTitle>
          <CardDescription>
            When enabled, new users provisioned via SCIM or the admin UI receive an invite email
            automatically.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-2">
            <Switch
              checked={form.autoInviteOnCreate}
              onCheckedChange={(checked) =>
                setForm((prev) => ({ ...prev, autoInviteOnCreate: checked }))
              }
              disabled={!canManage}
            />
            <span className="text-sm">Send invite emails automatically on user creation</span>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Test delivery</CardTitle>
          <CardDescription>
            Sends a templated message using the saved settings. Save first if you've changed
            anything above.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex items-center gap-3">
          <Input
            value={testAddress}
            onChange={(e) => setTestAddress(e.target.value)}
            placeholder="me@example.com"
            className="max-w-md"
          />
          <Button
            variant="secondary"
            disabled={!canManage || !testAddress || testSend.isPending}
            onClick={() => testSend.mutate(testAddress)}
          >
            <Send className="h-4 w-4 mr-2" />
            Send test
          </Button>
        </CardContent>
      </Card>

      <div className="flex gap-3 justify-end">
        {hasOverride && canManage && (
          <Button variant="outline" onClick={() => clear.mutate()} disabled={clear.isPending}>
            Use platform default
          </Button>
        )}
        <Button onClick={handleSave} disabled={!canManage || save.isPending}>
          Save
        </Button>
      </div>
    </div>
  )
}
