import React, { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { showToast } from '@/utils/toast'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Key, Plus, ShieldCheck, Trash2, RefreshCw, CheckCircle2, AlertCircle } from 'lucide-react'
import type {
  CredentialRecord,
  CredentialTemplateDescriptor,
  CredentialTestResultPayload,
  CredentialTypeDescriptor,
} from '@kelta/sdk'

import { useApi } from '../../context/ApiContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { FieldLabel, StatusBadge } from '@/components/kelta'
import { cn } from '@/lib/utils'

export interface CredentialsPageProps {
  className?: string
}

interface SchemaProperty {
  type?: string | string[]
  title?: string
  description?: string
  format?: string
  default?: unknown
  enum?: string[]
  items?: SchemaProperty
  minLength?: number
  maxLength?: number
}

interface InputSchema {
  required?: string[]
  properties?: Record<string, SchemaProperty>
}

type FormValues = Record<string, string | boolean | number | string[]>

const TYPE_BADGE_LABELS: Record<string, string> = {
  api_key: 'API Key',
  bearer_token: 'Bearer',
  basic_auth: 'Basic',
  oauth2_client_credentials: 'OAuth (Client)',
  oauth2_authorization_code: 'OAuth (Auth Code)',
  smtp: 'SMTP',
  custom: 'Custom',
}

function isSecretField(prop: SchemaProperty | undefined): boolean {
  return prop?.format === 'secret'
}

function isObjectField(prop: SchemaProperty | undefined): boolean {
  if (!prop) return false
  const baseType = Array.isArray(prop.type) ? prop.type[0] : prop.type
  return baseType === 'object'
}

export function CredentialsPage({ className }: CredentialsPageProps): React.ReactElement {
  const { keltaClient } = useApi()
  const { hasPermission } = useSystemPermissions()
  const queryClient = useQueryClient()

  const canManage = hasPermission('MANAGE_CREDENTIALS')

  const [dialogState, setDialogState] = useState<
    { mode: 'create' } | { mode: 'edit'; credential: CredentialRecord } | null
  >(null)

  const credentialsQuery = useQuery({
    queryKey: ['credentials'],
    queryFn: () => keltaClient.admin.credentials.list(),
  })
  const typesQuery = useQuery({
    queryKey: ['credential-types'],
    queryFn: () => keltaClient.admin.credentials.types(),
  })
  const templatesQuery = useQuery({
    queryKey: ['credential-templates'],
    queryFn: () => keltaClient.admin.credentials.templates(),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.credentials.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['credentials'] })
      toast.success('Credential deleted')
    },
    onError: () => toast.error('Delete failed'),
  })

  const testMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.credentials.test(id),
    onSuccess: (result, id) => {
      const r = result as { ok: boolean; message?: string }
      queryClient.invalidateQueries({ queryKey: ['credentials'] })
      showToast(
        r.ok ? 'Connection succeeded' : `Connection failed: ${r.message}`,
        r.ok ? 'success' : 'error'
      )
      void id
    },
    onError: () => toast.error('Test failed'),
  })

  const credentials = (credentialsQuery.data ?? []) as unknown as CredentialRecord[]
  const types = typesQuery.data ?? []
  const templates = templatesQuery.data ?? []

  if (credentialsQuery.isLoading || typesQuery.isLoading) {
    return <LoadingSpinner />
  }
  if (credentialsQuery.error) {
    return <ErrorMessage error="Failed to load credentials" />
  }

  return (
    <div className={cn('space-y-6', className)}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <ShieldCheck className="h-6 w-6 text-primary" />
          <div>
            <h1 className="text-[26px] font-bold tracking-[-0.01em]">Credentials</h1>
            <p className="text-sm text-muted-foreground">
              Reusable secrets for outbound API calls, email, and integrations.
            </p>
          </div>
        </div>
        {canManage && (
          <Button onClick={() => setDialogState({ mode: 'create' })}>
            <Plus className="mr-2 h-4 w-4" />
            New credential
          </Button>
        )}
      </div>

      {credentials.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center gap-4 py-16 text-center">
            <Key className="h-12 w-12 text-muted-foreground" />
            <div className="space-y-1">
              <p className="text-base font-medium">No credentials yet</p>
              <p className="text-sm text-muted-foreground">
                Add an API key, OAuth credential, or SMTP login to use in workflows.
              </p>
            </div>
            {canManage && (
              <Button onClick={() => setDialogState({ mode: 'create' })}>
                <Plus className="mr-2 h-4 w-4" />
                Create your first credential
              </Button>
            )}
          </CardContent>
        </Card>
      ) : (
        <CredentialsList
          credentials={credentials}
          canManage={canManage}
          onEdit={(c) => setDialogState({ mode: 'edit', credential: c })}
          onDelete={(c) => {
            if (confirm(`Delete credential '${c.name}'? Flows that reference it will fail.`)) {
              deleteMutation.mutate(c.id)
            }
          }}
          onTest={(c) => testMutation.mutate(c.id)}
        />
      )}

      {dialogState && (
        <CredentialDialog
          mode={dialogState.mode}
          credential={dialogState.mode === 'edit' ? dialogState.credential : null}
          types={types}
          templates={templates}
          onClose={() => setDialogState(null)}
          onSaved={() => {
            queryClient.invalidateQueries({ queryKey: ['credentials'] })
            setDialogState(null)
          }}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// List view
// ---------------------------------------------------------------------------

interface CredentialsListProps {
  credentials: CredentialRecord[]
  canManage: boolean
  onEdit: (c: CredentialRecord) => void
  onDelete: (c: CredentialRecord) => void
  onTest: (c: CredentialRecord) => void
}

function CredentialsList({
  credentials,
  canManage,
  onEdit,
  onDelete,
  onTest,
}: CredentialsListProps): React.ReactElement {
  return (
    <Card>
      <CardContent className="p-0">
        <table className="w-full text-sm">
          <thead className="border-b bg-muted/30">
            <tr className="text-left">
              <th className="px-4 py-3 font-medium">Name</th>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 font-medium">Last test</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {credentials.map((c) => (
              <tr key={c.id} className="border-b last:border-b-0">
                <td className="px-4 py-3">
                  <div className="font-medium">{c.displayName || c.name}</div>
                  {c.description ? (
                    <div className="text-xs text-muted-foreground">{c.description}</div>
                  ) : null}
                  <div className="text-xs text-muted-foreground font-mono">{c.name}</div>
                </td>
                <td className="px-4 py-3 align-top">
                  <span className="rounded-md bg-muted px-2 py-0.5 text-xs">
                    {TYPE_BADGE_LABELS[c.type] ?? c.type}
                  </span>
                </td>
                <td className="px-4 py-3 align-top text-xs text-muted-foreground">
                  {c.lastTestAt ? new Date(c.lastTestAt).toLocaleString() : '—'}
                  {c.lastTestError ? <div className="text-red-600">{c.lastTestError}</div> : null}
                </td>
                <td className="px-4 py-3 align-top">
                  {c.lastTestStatus === 'OK' ? (
                    <StatusBadge variant="active" label="Working" />
                  ) : c.lastTestStatus === 'FAILED' ? (
                    <StatusBadge variant="failed" label="Failed" />
                  ) : (
                    <StatusBadge variant="pending" label="Untested" />
                  )}
                </td>
                <td className="px-4 py-3 align-top">
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => onTest(c)}
                      title="Test connection"
                    >
                      <RefreshCw className="h-3.5 w-3.5" />
                    </Button>
                    {canManage && (
                      <>
                        <Button size="sm" variant="outline" onClick={() => onEdit(c)}>
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => onDelete(c)}
                          title="Delete"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Create / edit dialog
// ---------------------------------------------------------------------------

interface CredentialDialogProps {
  mode: 'create' | 'edit'
  credential: CredentialRecord | null
  types: CredentialTypeDescriptor[]
  templates: CredentialTemplateDescriptor[]
  onClose: () => void
  onSaved: () => void
}

function CredentialDialog({
  mode,
  credential,
  types,
  templates,
  onClose,
  onSaved,
}: CredentialDialogProps): React.ReactElement {
  const { keltaClient } = useApi()

  const [name, setName] = useState(credential?.name ?? '')
  const [displayName, setDisplayName] = useState(credential?.displayName ?? '')
  const [description, setDescription] = useState(credential?.description ?? '')
  const [type, setType] = useState(credential?.type ?? '')
  const [providerTemplate, setProviderTemplate] = useState(credential?.providerTemplate ?? '')
  const [values, setValues] = useState<FormValues>(() => {
    const initial: FormValues = {}
    const metadata = credential?.metadata ?? {}
    Object.entries(metadata).forEach(([key, value]) => {
      if (typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number') {
        initial[key] = value as never
      } else if (Array.isArray(value)) {
        initial[key] = value as string[]
      }
    })
    return initial
  })
  const [testResult, setTestResult] = useState<CredentialTestResultPayload | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const selectedType = useMemo(() => types.find((t) => t.key === type) ?? null, [type, types])
  const inputSchema = (selectedType?.inputSchema as InputSchema | undefined) ?? null

  const orderedFields = useMemo(() => {
    if (!inputSchema?.properties) return [] as Array<[string, SchemaProperty]>
    const required = new Set(inputSchema.required ?? [])
    return Object.entries(inputSchema.properties).sort(([a], [b]) => {
      const aReq = required.has(a)
      const bReq = required.has(b)
      if (aReq !== bReq) return aReq ? -1 : 1
      return a.localeCompare(b)
    })
  }, [inputSchema])

  const compatibleTemplates = useMemo(
    () => templates.filter((t) => !type || t.type === type),
    [templates, type]
  )

  const applyTemplate = (templateKey: string) => {
    const template = templates.find((t) => t.key === templateKey)
    if (!template) {
      setProviderTemplate('')
      return
    }
    setProviderTemplate(template.key)
    if (!type || type === template.type) {
      setType(template.type)
    }
    if (template.defaults) {
      const next: FormValues = { ...values }
      Object.entries(template.defaults).forEach(([key, value]) => {
        if (typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number') {
          next[key] = value as never
        } else if (Array.isArray(value)) {
          next[key] = value as string[]
        }
      })
      setValues(next)
    }
  }

  const updateField = (field: string, value: string | boolean | number | string[]) => {
    setValues((prev) => ({ ...prev, [field]: value }))
    setTestResult(null)
  }

  const buildPayload = (): Record<string, unknown> => {
    const payload: Record<string, unknown> = {
      name,
      displayName: displayName || undefined,
      description: description || undefined,
      type,
      active: true,
    }
    if (providerTemplate) {
      payload.providerTemplate = providerTemplate
    }
    Object.entries(values).forEach(([key, value]) => {
      const prop = inputSchema?.properties?.[key]
      // Skip empty optional secret fields on edit (don't overwrite stored value).
      if (mode === 'edit' && isSecretField(prop) && !value) {
        return
      }
      if (value === '' || value === undefined) {
        return
      }
      if (isObjectField(prop) && typeof value === 'string') {
        try {
          payload[key] = JSON.parse(value)
        } catch (err) {
          throw new Error(
            `Field "${prop?.title ?? key}" must be valid JSON: ${
              err instanceof Error ? err.message : String(err)
            }`
          )
        }
        return
      }
      payload[key] = value
    })
    return payload
  }

  const test = async () => {
    if (!type) return
    try {
      const data: Record<string, unknown> = {}
      Object.entries(values).forEach(([k, v]) => {
        if (v === '' || v === undefined) return
        const prop = inputSchema?.properties?.[k]
        if (isObjectField(prop) && typeof v === 'string') {
          try {
            data[k] = JSON.parse(v)
            return
          } catch (err) {
            throw new Error(
              `Field "${prop?.title ?? k}" must be valid JSON: ${
                err instanceof Error ? err.message : String(err)
              }`
            )
          }
        }
        data[k] = v
      })
      const result = await keltaClient.admin.credentials.testInline(type, data)
      setTestResult(result)
    } catch (e) {
      setTestResult({
        ok: false,
        message: e instanceof Error ? e.message : 'Test failed',
      })
    }
  }

  const submit = async () => {
    if (!name.trim()) {
      toast.error('Name is required')
      return
    }
    if (!type) {
      toast.error('Pick a credential type')
      return
    }
    setSubmitting(true)
    try {
      const payload = buildPayload()
      if (mode === 'create') {
        await keltaClient.admin.credentials.create(payload)
        toast.success('Credential created')
      } else if (credential) {
        await keltaClient.admin.credentials.update(credential.id, payload)
        toast.success('Credential updated')
      }
      onSaved()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Save failed')
    } finally {
      setSubmitting(false)
    }
  }

  const isOAuthAuthCode = type === 'oauth2_authorization_code'

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {mode === 'create' ? 'New credential' : `Edit ${credential?.name}`}
          </DialogTitle>
          <DialogDescription>
            {mode === 'create'
              ? 'Add a credential to use in workflows. Secret material is encrypted at rest.'
              : 'Update credential details. Leave secret fields blank to keep the existing value.'}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <FieldLabel>Name</FieldLabel>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="e.g., salesforce-prod"
                disabled={mode === 'edit'}
              />
              <p className="text-xs text-muted-foreground">
                Referenced by workflows. Lowercase, hyphenated. Cannot be changed after creation.
              </p>
            </div>
            <div className="space-y-2">
              <FieldLabel>Display name</FieldLabel>
              <Input
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="Salesforce production"
              />
            </div>
          </div>

          <div className="space-y-2">
            <FieldLabel>Description</FieldLabel>
            <Textarea
              rows={2}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="What is this credential used for?"
            />
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <FieldLabel>Type</FieldLabel>
              <Select
                value={type}
                onValueChange={(v) => {
                  setType(v)
                  setValues({})
                  setProviderTemplate('')
                }}
                disabled={mode === 'edit'}
              >
                <SelectTrigger>
                  <SelectValue placeholder="Pick a type" />
                </SelectTrigger>
                <SelectContent>
                  {types.map((t) => (
                    <SelectItem key={t.key} value={t.key}>
                      {t.displayName}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {selectedType?.description && (
                <p className="text-xs text-muted-foreground">{selectedType.description}</p>
              )}
            </div>

            {compatibleTemplates.length > 0 && (
              <div className="space-y-2">
                <FieldLabel>Provider template</FieldLabel>
                <Select
                  value={providerTemplate || 'none'}
                  onValueChange={(v) => applyTemplate(v === 'none' ? '' : v)}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Optional" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="none">— Custom configuration —</SelectItem>
                    {compatibleTemplates.map((t) => (
                      <SelectItem key={t.key} value={t.key}>
                        {t.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  Prefills auth URLs and scopes for popular providers.
                </p>
              </div>
            )}
          </div>

          {selectedType && (
            <Card className="bg-muted/20">
              <CardHeader className="pb-3">
                <CardTitle className="text-sm">Credential details</CardTitle>
                <CardDescription>
                  {mode === 'edit'
                    ? 'Leave secret fields blank to keep the existing value.'
                    : 'Secret values are encrypted before storage.'}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                {orderedFields.map(([fieldName, prop]) => (
                  <SchemaField
                    key={fieldName}
                    name={fieldName}
                    schema={prop}
                    required={(inputSchema?.required ?? []).includes(fieldName)}
                    value={values[fieldName]}
                    onChange={(v) => updateField(fieldName, v)}
                    isEdit={mode === 'edit'}
                  />
                ))}
              </CardContent>
            </Card>
          )}

          {selectedType && !isOAuthAuthCode && (
            <div className="flex items-center gap-3">
              <Button variant="outline" onClick={test} disabled={!type}>
                <RefreshCw className="mr-2 h-3.5 w-3.5" />
                Test connection
              </Button>
              {testResult && (
                <span
                  className={cn(
                    'text-sm flex items-center gap-1.5',
                    testResult.ok ? 'text-green-600' : 'text-red-600'
                  )}
                >
                  {testResult.ok ? (
                    <CheckCircle2 className="h-4 w-4" />
                  ) : (
                    <AlertCircle className="h-4 w-4" />
                  )}
                  {testResult.message}
                </span>
              )}
            </div>
          )}

          {isOAuthAuthCode && mode === 'create' && (
            <Card className="border-amber-200 bg-amber-50/40 dark:bg-amber-950/20">
              <CardContent className="py-3 text-sm">
                Authorization Code flow requires a browser sign-in. Save the credential first, then
                start the OAuth dance from the credential row. (Coming in PR 4.)
              </CardContent>
            </Card>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={submitting}>
            {submitting ? 'Saving…' : mode === 'create' ? 'Create credential' : 'Save'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Schema-driven field renderer (covers all 7 credential type schemas)
// ---------------------------------------------------------------------------

interface SchemaFieldProps {
  name: string
  schema: SchemaProperty
  required: boolean
  value: string | boolean | number | string[] | undefined
  onChange: (v: string | boolean | number | string[]) => void
  isEdit: boolean
}

function SchemaField({ name, schema, required, value, onChange, isEdit }: SchemaFieldProps) {
  const label = schema.title ?? name
  const description = schema.description
  const isSecret = isSecretField(schema)
  const baseType = Array.isArray(schema.type) ? schema.type[0] : schema.type

  if (baseType === 'boolean') {
    return (
      <div className="flex items-center justify-between rounded-md border bg-background p-3">
        <div className="space-y-0.5">
          <FieldLabel>{label}</FieldLabel>
          {description ? <p className="text-xs text-muted-foreground">{description}</p> : null}
        </div>
        <Switch checked={Boolean(value ?? schema.default)} onCheckedChange={(v) => onChange(v)} />
      </div>
    )
  }

  if (baseType === 'integer' || baseType === 'number') {
    return (
      <div className="space-y-1">
        <FieldLabel>
          {label}
          {required ? <span className="text-red-500"> *</span> : null}
        </FieldLabel>
        <Input
          type="number"
          value={(value as number | string | undefined) ?? schema.default?.toString() ?? ''}
          onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
        />
        {description ? <p className="text-xs text-muted-foreground">{description}</p> : null}
      </div>
    )
  }

  if (schema.enum) {
    return (
      <div className="space-y-1">
        <FieldLabel>
          {label}
          {required ? <span className="text-red-500"> *</span> : null}
        </FieldLabel>
        <Select value={String(value ?? schema.default ?? '')} onValueChange={(v) => onChange(v)}>
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {schema.enum.map((opt) => (
              <SelectItem key={opt} value={opt}>
                {opt}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {description ? <p className="text-xs text-muted-foreground">{description}</p> : null}
      </div>
    )
  }

  if (baseType === 'object') {
    const text = (value as string | undefined) ?? ''
    let parseError: string | null = null
    if (text.trim()) {
      try {
        JSON.parse(text)
      } catch (err) {
        parseError = err instanceof Error ? err.message : String(err)
      }
    }
    return (
      <div className="space-y-1">
        <FieldLabel>
          {label}
          {required ? <span className="text-red-500"> *</span> : null}
        </FieldLabel>
        <Textarea
          value={text}
          onChange={(e) => onChange(e.target.value)}
          placeholder={
            isSecret && isEdit ? '{ ... } (leave blank to keep existing)' : '{\n  "key": "value"\n}'
          }
          className="font-mono text-xs min-h-[120px]"
          autoComplete="off"
          spellCheck={false}
        />
        {parseError ? (
          <p className="text-xs text-red-600">Invalid JSON: {parseError}</p>
        ) : description ? (
          <p className="text-xs text-muted-foreground">{description}</p>
        ) : null}
      </div>
    )
  }

  if (baseType === 'array') {
    const arrValue = Array.isArray(value) ? (value as string[]) : []
    return (
      <div className="space-y-1">
        <FieldLabel>{label}</FieldLabel>
        <Input
          placeholder="Comma-separated values"
          value={arrValue.join(', ')}
          onChange={(e) =>
            onChange(
              e.target.value
                .split(',')
                .map((s) => s.trim())
                .filter(Boolean)
            )
          }
        />
        {description ? <p className="text-xs text-muted-foreground">{description}</p> : null}
      </div>
    )
  }

  // string default
  return (
    <div className="space-y-1">
      <FieldLabel>
        {label}
        {required ? <span className="text-red-500"> *</span> : null}
      </FieldLabel>
      <Input
        type={isSecret ? 'password' : 'text'}
        value={(value as string | undefined) ?? ''}
        onChange={(e) => onChange(e.target.value)}
        placeholder={isSecret && isEdit ? '•••••••• (leave blank to keep existing)' : undefined}
        autoComplete={isSecret ? 'off' : undefined}
      />
      {description ? <p className="text-xs text-muted-foreground">{description}</p> : null}
    </div>
  )
}
