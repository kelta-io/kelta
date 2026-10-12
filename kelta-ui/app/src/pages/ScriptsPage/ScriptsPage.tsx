import React, { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog, ExecutionLogModal } from '../../components'
import type { LogColumn } from '../../components'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import type { CreateScriptRequest } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { SYSTEM_PERMISSIONS } from '@/components/SecurityEditor/SystemPermissionChecklist'

interface Script {
  id: string
  name: string
  description: string | null
  scriptType: string
  language: string
  sourceCode: string
  active: boolean
  version: number
  requiredPermission: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

const scriptSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 100, 'Name must be 100 characters or fewer'),
  description: z.string().max(500, 'Description must be 500 characters or fewer'),
  scriptType: z.string(),
  language: z.string(),
  sourceCode: z.string().refine((v) => v.trim().length > 0, 'Source code is required'),
  active: z.boolean(),
  /** System permission required to execute the script; empty string = no extra gate. */
  requiredPermission: z.string(),
})

type ScriptFormData = z.infer<typeof scriptSchema>

const SCRIPT_TYPE_OPTIONS = [
  { value: 'BEFORE_TRIGGER', label: 'Before Trigger' },
  { value: 'AFTER_TRIGGER', label: 'After Trigger' },
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'API_ENDPOINT', label: 'API Endpoint' },
  { value: 'VALIDATION', label: 'Validation' },
  { value: 'EVENT_HANDLER', label: 'Event Handler' },
  { value: 'EMAIL_HANDLER', label: 'Email Handler' },
]

const REQUIRED_PERMISSION_OPTIONS = SYSTEM_PERMISSIONS.map((perm) => ({
  value: perm.name,
  label: perm.label,
}))

/** Resolves a system-permission name to its display label ('—' when unset). */
function permissionLabel(name: string | null | undefined): string {
  if (!name) {
    return '—'
  }
  return SYSTEM_PERMISSIONS.find((perm) => perm.name === name)?.label ?? name
}

interface ScriptExecutionLog {
  id: string
  scriptId: string
  status: string
  triggerType: string | null
  recordId: string | null
  durationMs: number | null
  cpuMs: number | null
  queriesExecuted: number | null
  dmlRows: number | null
  callouts: number | null
  errorMessage: string | null
  logOutput: string | null
  executedAt: string
}

export interface ScriptsPageProps {
  testId?: string
}

export function ScriptsPage({ testId = 'scripts-page' }: ScriptsPageProps): React.ReactElement {
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()

  const [logsItemId, setLogsItemId] = useState<string | null>(null)
  const [logsItemName, setLogsItemName] = useState('')

  const {
    data: logs,
    isLoading: logsLoading,
    error: logsError,
  } = useQuery({
    queryKey: ['script-logs', logsItemId],
    queryFn: () => keltaClient.admin.scripts.listLogsByScript(logsItemId!),
    enabled: !!logsItemId,
  })

  const logColumns: LogColumn<ScriptExecutionLog>[] = [
    { key: 'status', header: 'Status' },
    { key: 'triggerType', header: 'Trigger' },
    {
      key: 'durationMs',
      header: 'Duration',
      render: (v) => (v != null ? `${v}ms` : '-'),
    },
    {
      key: 'cpuMs',
      header: 'CPU (ms)',
      render: (v) => (v != null ? String(v) : '-'),
    },
    {
      key: 'queriesExecuted',
      header: 'Queries',
      render: (v) => (v != null ? String(v) : '-'),
    },
    {
      key: 'dmlRows',
      header: 'DML Rows',
      render: (v) => (v != null ? String(v) : '-'),
    },
    { key: 'errorMessage', header: 'Error' },
    {
      key: 'executedAt',
      header: 'Executed At',
      render: (v) =>
        v
          ? formatDate(new Date(v as string), {
              year: 'numeric',
              month: 'short',
              day: 'numeric',
              hour: '2-digit',
              minute: '2-digit',
            })
          : '-',
    },
  ]

  const {
    data: scripts,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['scripts'],
    queryFn: () => keltaClient.admin.scripts.list(),
  })

  const scriptList: Script[] = (scripts ?? []) as unknown as Script[]

  const crud = useAdminCrud<Script, ScriptFormData>({
    queryKey: ['scripts'],
    create: (data) =>
      keltaClient.admin.scripts.create('', '', data as unknown as CreateScriptRequest),
    update: (script, data) =>
      keltaClient.admin.scripts.update(script.id, data as unknown as Partial<CreateScriptRequest>),
    remove: (script) => keltaClient.admin.scripts.delete(script.id),
    messages: {
      created: 'Script created successfully',
      updated: 'Script updated successfully',
      deleted: 'Script deleted successfully',
      error: 'An error occurred',
    },
  })

  const columns = useMemo<AdminColumn<Script>[]>(
    () => [
      { id: 'name', header: 'Name', cellClassName: 'text-foreground' },
      {
        id: 'scriptType',
        header: 'Script Type',
        cell: (script) => (
          <span className="inline-block rounded-full bg-muted px-3 py-1 text-xs font-semibold uppercase tracking-wider text-primary">
            {script.scriptType}
          </span>
        ),
      },
      { id: 'language', header: 'Language', cellClassName: 'text-foreground' },
      { id: 'version', header: 'Version', cellClassName: 'text-foreground' },
      {
        id: 'active',
        header: 'Active',
        cell: (script) => (
          <span
            className={cn(
              'inline-block rounded-full px-3 py-1 text-xs font-semibold',
              script.active
                ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                : 'bg-muted text-muted-foreground'
            )}
          >
            {script.active ? 'Yes' : 'No'}
          </span>
        ),
      },
      {
        id: 'requiredPermission',
        header: 'Run Permission',
        accessor: (script) => permissionLabel(script.requiredPermission),
        cell: (script, index) => (
          <span data-testid={`required-permission-cell-${index}`}>
            {permissionLabel(script.requiredPermission)}
          </span>
        ),
        cellClassName: 'text-foreground',
      },
      {
        id: 'createdAt',
        header: 'Created',
        cell: (script) =>
          formatDate(new Date(script.createdAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          }),
        cellClassName: 'text-foreground',
      },
    ],
    [formatDate]
  )

  const editing = crud.editing

  return (
    <AdminCrudPage
      testId={testId}
      title="Scripts"
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label="Create Script"
          data-testid="add-script-button"
        >
          Create Script
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={scriptList.length === 0}
      emptyMessage="No scripts found."
      list={
        <AdminDataTable
          tableId="scripts"
          testId="scripts-table"
          ariaLabel="Scripts"
          columns={columns}
          rows={scriptList}
          rowKey={(script) => script.id}
          rowTestId={(_, index) => `script-row-${index}`}
          renderActions={(script, index) => (
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setLogsItemId(script.id)
                  setLogsItemName(script.name)
                }}
                aria-label={`View logs for ${script.name}`}
                data-testid={`logs-button-${index}`}
              >
                Logs
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(script)}
                aria-label={`Edit ${script.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(script)}
                aria-label={`Delete ${script.name}`}
                data-testid={`delete-button-${index}`}
              >
                Delete
              </Button>
            </div>
          )}
        />
      }
    >
      {logsItemId && (
        <ExecutionLogModal<ScriptExecutionLog>
          title="Script Execution Logs"
          subtitle={logsItemName}
          columns={logColumns}
          data={(logs ?? []) as unknown as ScriptExecutionLog[]}
          isLoading={logsLoading}
          error={logsError instanceof Error ? logsError : null}
          onClose={() => setLogsItemId(null)}
          emptyMessage="No execution logs found."
        />
      )}

      <CrudFormDialog<ScriptFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? 'Edit Script' : 'Create Script'}
        schema={scriptSchema}
        defaultValues={{
          name: editing?.name ?? '',
          description: editing?.description ?? '',
          scriptType: editing?.scriptType ?? 'BEFORE_TRIGGER',
          language: editing?.language ?? 'javascript',
          sourceCode: editing?.sourceCode ?? '',
          active: editing?.active ?? true,
          requiredPermission: editing?.requiredPermission ?? '',
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="script"
        className="sm:max-w-[600px]"
      >
        <CrudTextField<ScriptFormData>
          name="name"
          id="script-name"
          label="Name"
          required
          placeholder="Enter script name"
          testId="script-name-input"
        />
        <CrudTextField<ScriptFormData>
          name="description"
          id="script-description"
          label="Description"
          multiline
          placeholder="Enter script description"
          testId="script-description-input"
        />
        <CrudSelectField<ScriptFormData>
          name="scriptType"
          id="script-type"
          label="Script Type"
          required
          options={SCRIPT_TYPE_OPTIONS}
          testId="script-type-input"
        />
        <CrudTextField<ScriptFormData>
          name="language"
          id="script-language"
          label="Language"
          placeholder="Enter language"
          testId="script-language-input"
        />
        <CrudSelectField<ScriptFormData>
          name="requiredPermission"
          id="script-required-permission"
          label="Required permission to execute"
          placeholder="None — any API user"
          options={REQUIRED_PERMISSION_OPTIONS}
          hint="When set, executing this script via the API requires the selected system permission."
          testId="script-required-permission-input"
        />
        <CrudTextField<ScriptFormData>
          name="sourceCode"
          id="script-source-code"
          label="Source Code"
          required
          multiline
          rows={10}
          placeholder="Enter source code"
          testId="script-source-code-input"
        />
        <CrudCheckboxField<ScriptFormData>
          name="active"
          id="script-active"
          label="Active"
          testId="script-active-input"
        />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Script"
        message="Are you sure you want to delete this script? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}

export default ScriptsPage
