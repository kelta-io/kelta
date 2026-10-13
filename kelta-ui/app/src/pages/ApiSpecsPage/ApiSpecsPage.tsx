import React, { useState } from 'react'
import { toast } from 'sonner'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { BookOpen, FileJson, Globe2, Plus, Trash2 } from 'lucide-react'
import type { ApiSpecSummary, ApiSpecValidateResult, ImportApiSpecRequest } from '@kelta/sdk'

import { useApi } from '../../context/ApiContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { FileDropzone } from '../../components/FileDropzone/FileDropzone'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Card, CardContent } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { FieldLabel, StatusBadge } from '@/components/kelta'
import { cn } from '@/lib/utils'

export interface ApiSpecsPageProps {
  className?: string
}

export function ApiSpecsPage({ className }: ApiSpecsPageProps): React.ReactElement {
  const { keltaClient } = useApi()
  const { hasPermission } = useSystemPermissions()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const canManage = hasPermission('MANAGE_API_SPECS')
  const [importOpen, setImportOpen] = useState(false)

  const specsQuery = useQuery({
    queryKey: ['api-specs'],
    queryFn: () => keltaClient.admin.apiSpecs.list(),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.apiSpecs.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['api-specs'] })
      toast.success('Spec deleted')
    },
    onError: () => toast.error('Delete failed'),
  })

  if (specsQuery.isLoading) return <LoadingSpinner />
  if (specsQuery.error) return <ErrorMessage error="Failed to load specs" />

  const specs = specsQuery.data ?? []

  return (
    <div className={cn('space-y-6', className)}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <BookOpen className="h-6 w-6 text-primary" />
          <div>
            <h1 className="text-[26px] font-bold tracking-[-0.01em]">API Specs</h1>
            <p className="text-sm text-muted-foreground">
              OpenAPI 3.x specs imported into the platform. Pick operations from these in the flow
              builder.
            </p>
          </div>
        </div>
        {canManage && (
          <Button onClick={() => setImportOpen(true)}>
            <Plus className="mr-2 h-4 w-4" />
            Import spec
          </Button>
        )}
      </div>

      {specs.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center gap-4 py-16 text-center">
            <FileJson className="h-12 w-12 text-muted-foreground" />
            <div className="space-y-1">
              <p className="text-base font-medium">No API specs yet</p>
              <p className="text-sm text-muted-foreground">
                Import an OpenAPI 3.x spec to start using its operations in workflows.
              </p>
            </div>
            {canManage && (
              <Button onClick={() => setImportOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Import your first spec
              </Button>
            )}
          </CardContent>
        </Card>
      ) : (
        <SpecList
          specs={specs}
          canManage={canManage}
          onOpen={(s) => navigate(`/${getTenantSlug()}/api-specs/${s.id}`)}
          onDelete={(s) => {
            if (
              confirm(
                `Delete spec '${s.name}'? Flows that reference its operations will fail until updated.`
              )
            ) {
              deleteMutation.mutate(s.id)
            }
          }}
        />
      )}

      {importOpen && (
        <ImportSpecDialog
          onClose={() => setImportOpen(false)}
          onImported={() => {
            queryClient.invalidateQueries({ queryKey: ['api-specs'] })
            setImportOpen(false)
          }}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Spec list
// ---------------------------------------------------------------------------

interface SpecListProps {
  specs: ApiSpecSummary[]
  canManage: boolean
  onOpen: (s: ApiSpecSummary) => void
  onDelete: (s: ApiSpecSummary) => void
}

function SpecList({ specs, canManage, onOpen, onDelete }: SpecListProps) {
  return (
    <Card>
      <CardContent className="p-0">
        <table className="w-full text-sm">
          <thead className="border-b bg-muted/30">
            <tr className="text-left">
              <th className="px-4 py-3 font-medium">Title</th>
              <th className="px-4 py-3 font-medium">Base URL</th>
              <th className="px-4 py-3 font-medium">Source</th>
              <th className="px-4 py-3 font-medium">Revision</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {specs.map((s) => (
              <tr key={s.id} className="border-b last:border-b-0">
                <td className="px-4 py-3">
                  <button onClick={() => onOpen(s)} className="text-left hover:underline">
                    <div className="font-medium">{s.apiTitle || s.name}</div>
                    <div className="text-xs text-muted-foreground font-mono">
                      {s.name} · v{s.apiVersion ?? '—'} · OpenAPI {s.specVersion}
                    </div>
                  </button>
                </td>
                <td className="px-4 py-3 text-xs font-mono text-muted-foreground">
                  {s.baseUrl ?? '—'}
                </td>
                <td className="px-4 py-3 text-xs">
                  <span className="inline-flex items-center gap-1">
                    {s.sourceType === 'URL' ? (
                      <>
                        <Globe2 className="h-3 w-3" /> URL
                      </>
                    ) : (
                      <>
                        <FileJson className="h-3 w-3" /> Inline
                      </>
                    )}
                  </span>
                </td>
                <td className="px-4 py-3 text-xs font-mono">{s.revision}</td>
                <td className="px-4 py-3">
                  <StatusBadge
                    variant={s.active ? 'active' : 'inactive'}
                    label={s.active ? 'Active' : 'Inactive'}
                  />
                </td>
                <td className="px-4 py-3">
                  <div className="flex gap-2 justify-end">
                    <Button size="sm" variant="outline" onClick={() => onOpen(s)}>
                      Open
                    </Button>
                    {canManage && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onDelete(s)}
                        title="Delete spec"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
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
// Import dialog
// ---------------------------------------------------------------------------

interface ImportSpecDialogProps {
  onClose: () => void
  onImported: () => void
}

function ImportSpecDialog({ onClose, onImported }: ImportSpecDialogProps) {
  const { keltaClient } = useApi()

  const [tab, setTab] = useState<'url' | 'paste' | 'file'>('url')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [sourceUrl, setSourceUrl] = useState('')
  const [raw, setRaw] = useState('')
  const [rawFormat, setRawFormat] = useState<'json' | 'yaml'>('json')
  const [validationResult, setValidationResult] = useState<ApiSpecValidateResult | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleFile = async (file: File) => {
    const text = await file.text()
    setRaw(text)
    setRawFormat(file.name.toLowerCase().endsWith('.json') ? 'json' : 'yaml')
    setTab('paste')
    setValidationResult(null)
  }

  const validate = async () => {
    try {
      const body: { raw?: string; sourceUrl?: string } = tab === 'url' ? { sourceUrl } : { raw }
      const result = await keltaClient.admin.apiSpecs.validate(body)
      setValidationResult(result)
    } catch (e) {
      setValidationResult({
        ok: false,
        error: e instanceof Error ? e.message : 'Validation failed',
      })
    }
  }

  const submit = async () => {
    if (!name.trim()) {
      toast.error('Name is required')
      return
    }
    setSubmitting(true)
    try {
      const request: ImportApiSpecRequest =
        tab === 'url'
          ? {
              name,
              description: description || undefined,
              sourceType: 'URL',
              sourceUrl,
            }
          : {
              name,
              description: description || undefined,
              sourceType: rawFormat === 'json' ? 'INLINE_JSON' : 'INLINE_YAML',
              raw,
              rawFormat,
            }
      const result = await keltaClient.admin.apiSpecs.importSpec(request)
      toast.success(
        `Imported '${result.spec.apiTitle ?? result.spec.name}' (${result.diff.added + result.diff.changed} operations)`
      )
      onImported()
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Import failed'
      toast.error(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Import OpenAPI spec</DialogTitle>
          <DialogDescription>
            Import an OpenAPI 3.x spec from a URL, paste it inline, or drop a file. The platform
            indexes every operation so they can be picked from the flow builder.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <FieldLabel>Name</FieldLabel>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="e.g., petstore"
              />
              <p className="text-xs text-muted-foreground">
                Tenant-unique identifier referenced from flows.
              </p>
            </div>
            <div className="space-y-1">
              <FieldLabel>Description (optional)</FieldLabel>
              <Input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Production Petstore catalog"
              />
            </div>
          </div>

          <Tabs value={tab} onValueChange={(v) => setTab(v as 'url' | 'paste' | 'file')}>
            <TabsList>
              <TabsTrigger value="url">From URL</TabsTrigger>
              <TabsTrigger value="paste">Paste</TabsTrigger>
              <TabsTrigger value="file">Drop file</TabsTrigger>
            </TabsList>
            <TabsContent value="url" className="space-y-1">
              <FieldLabel>Spec URL</FieldLabel>
              <Input
                value={sourceUrl}
                onChange={(e) => {
                  setSourceUrl(e.target.value)
                  setValidationResult(null)
                }}
                placeholder="https://petstore3.swagger.io/api/v3/openapi.json"
              />
              <p className="text-xs text-muted-foreground">
                The worker fetches the URL server-side; the spec is parsed and stored.
              </p>
            </TabsContent>
            <TabsContent value="paste" className="space-y-2">
              <div className="flex items-center gap-2">
                <FieldLabel>Format</FieldLabel>
                <select
                  value={rawFormat}
                  onChange={(e) => setRawFormat(e.target.value as 'json' | 'yaml')}
                  className="h-7 rounded border bg-background px-2 text-xs"
                >
                  <option value="json">JSON</option>
                  <option value="yaml">YAML</option>
                </select>
              </div>
              <Textarea
                rows={12}
                value={raw}
                onChange={(e) => {
                  setRaw(e.target.value)
                  setValidationResult(null)
                }}
                placeholder={
                  rawFormat === 'json'
                    ? '{ "openapi": "3.0.3", ... }'
                    : 'openapi: 3.0.3\\ninfo:\\n  title: ...'
                }
                className="font-mono text-xs"
              />
            </TabsContent>
            <TabsContent value="file">
              <FileDropzone
                accept=".json,.yaml,.yml,application/json,application/yaml,text/yaml"
                maxBytes={5_000_000}
                onFile={handleFile}
                onError={(msg) => toast.error(msg)}
                hint="OpenAPI 3.x JSON or YAML, up to 5 MB"
              />
              {raw && (
                <p className="mt-2 text-xs text-muted-foreground">
                  {(raw.length / 1024).toFixed(1)} KB loaded · format: {rawFormat}
                </p>
              )}
            </TabsContent>
          </Tabs>

          <div className="flex items-center gap-3">
            <Button variant="outline" onClick={validate}>
              Validate
            </Button>
            {validationResult && (
              <span
                className={cn('text-sm', validationResult.ok ? 'text-green-600' : 'text-red-600')}
              >
                {validationResult.ok
                  ? `${validationResult.title ?? 'Spec'} v${validationResult.version ?? '?'} — ${validationResult.operations} operations, base ${validationResult.baseUrl || '—'}`
                  : `Invalid: ${validationResult.error}`}
              </span>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={submitting}>
            {submitting ? 'Importing…' : 'Import'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
