import React, { useState, useCallback, useMemo, useRef } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { get, useFormContext, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { Braces } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog, ExecutionLogModal } from '../../components'
import type { LogColumn } from '../../components'
import type { CreateApprovalProcessRequest } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { FieldExpressionPicker } from '../../components/FieldExpressionPicker'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudNumberField,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
  useCrudFormState,
} from '../../components/AdminCrud'

interface ApprovalProcess {
  id: string
  name: string
  description: string | null
  collectionId: string | null
  entryCriteria: string | null
  recordEditability: string
  allowRecall: boolean
  active: boolean
  executionOrder: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

interface StepInstance {
  id: string
  stepId: string
  assignedTo: string
  status: string
  comments: string | null
  actedAt: string | null
}

interface ApprovalInstance {
  id: string
  approvalProcessId: string
  approvalProcessName: string
  collectionId: string
  recordId: string
  submittedBy: string
  currentStepNumber: number
  status: string
  submittedAt: string
  completedAt: string | null
  stepInstances: StepInstance[]
}

export interface ApprovalProcessesPageProps {
  testId?: string
}

const approvalProcessSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 100, 'Name must be 100 characters or fewer'),
  description: z.string().max(500, 'Description must be 500 characters or fewer'),
  collectionId: z.string().max(100, 'Collection ID must be 100 characters or fewer'),
  entryCriteria: z.string().max(1000, 'Entry criteria must be 1000 characters or fewer'),
  recordEditability: z.string(),
  allowRecall: z.boolean(),
  active: z.boolean(),
  executionOrder: z.number().min(0, 'Execution order must be 0 or greater'),
})

type ApprovalProcessFormData = z.infer<typeof approvalProcessSchema>

const RECORD_EDITABILITY_OPTIONS = [
  { value: 'LOCKED', label: 'Locked' },
  { value: 'ADMIN_ONLY', label: 'Admin Only' },
]

/** Matches the CrudTextField label (DESIGN.md uppercase-11px field label). */
const FIELD_LABEL_CLASS =
  'text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground'

function YesNoBadge({ value }: { value: boolean }): React.ReactElement {
  return (
    <span
      className={cn(
        'inline-block rounded-full px-3 py-1 text-xs font-semibold',
        value
          ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
          : 'bg-muted text-muted-foreground'
      )}
    >
      {value ? 'Yes' : 'No'}
    </span>
  )
}

/**
 * Entry-criteria textarea with an "Insert field" button that opens the
 * FieldExpressionPicker scoped to the form's collection and inserts the chosen
 * token at the caret.
 */
function EntryCriteriaField(): React.ReactElement {
  const {
    register,
    setValue,
    control,
    formState: { errors },
  } = useFormContext<ApprovalProcessFormData>()
  const { isSubmitting } = useCrudFormState()
  const collectionId = useWatch({ control, name: 'collectionId' })
  const [pickerOpen, setPickerOpen] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const { ref: registerRef, ...registration } = register('entryCriteria')
  const error = get(errors, 'entryCriteria')?.message as string | undefined

  const handleInsert = useCallback(
    (token: string) => {
      const el = textareaRef.current
      const start = el?.selectionStart ?? el?.value.length ?? 0
      const end = el?.selectionEnd ?? el?.value.length ?? 0
      const current = el?.value ?? ''
      const next = current.slice(0, start) + token + current.slice(end)
      setValue('entryCriteria', next, { shouldDirty: true })
      setPickerOpen(false)
      requestAnimationFrame(() => {
        el?.focus()
        el?.setSelectionRange(start + token.length, start + token.length)
      })
    },
    [setValue]
  )

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <label htmlFor="approval-process-entry-criteria" className={FIELD_LABEL_CLASS}>
          Entry Criteria
        </label>
        <button
          type="button"
          onClick={() => setPickerOpen(true)}
          disabled={isSubmitting}
          className="flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
          data-testid="entry-criteria-insert-field"
        >
          <Braces className="h-3.5 w-3.5" />
          Insert field
        </button>
      </div>
      <Textarea
        id="approval-process-entry-criteria"
        rows={3}
        className="resize-y"
        placeholder="Enter entry criteria formula"
        disabled={isSubmitting}
        aria-invalid={!!error}
        aria-describedby={error ? 'approval-process-entry-criteria-error' : undefined}
        data-testid="approval-process-entry-criteria-input"
        {...registration}
        ref={(el) => {
          registerRef(el)
          textareaRef.current = el
        }}
      />
      {error && (
        <span
          id="approval-process-entry-criteria-error"
          className="text-xs text-destructive"
          role="alert"
        >
          {error}
        </span>
      )}
      <FieldExpressionPicker
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        rootCollectionId={collectionId || null}
        mode="expression"
        allowedTypes={['boolean']}
        onInsert={handleInsert}
        title="Insert field or function"
        testId="approval-entry-criteria-picker"
      />
    </div>
  )
}

export function ApprovalProcessesPage({
  testId = 'approval-processes-page',
}: ApprovalProcessesPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { keltaClient } = useApi()

  const [instancesItemId, setInstancesItemId] = useState<string | null>(null)
  const [_instancesItemName, setInstancesItemName] = useState('')

  const {
    data: approvalProcesses,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['approvalProcesses'],
    queryFn: () => keltaClient.admin.approvals.listProcesses(),
  })

  const approvalProcessList: ApprovalProcess[] = (approvalProcesses ??
    []) as unknown as ApprovalProcess[]

  const crud = useAdminCrud<ApprovalProcess, ApprovalProcessFormData>({
    queryKey: ['approvalProcesses'],
    create: (data) =>
      keltaClient.admin.approvals.createProcess(
        '',
        data as unknown as CreateApprovalProcessRequest
      ),
    update: (approvalProcess, data) =>
      keltaClient.admin.approvals.updateProcess(
        approvalProcess.id,
        data as unknown as Partial<CreateApprovalProcessRequest>
      ),
    remove: (approvalProcess) => keltaClient.admin.approvals.deleteProcess(approvalProcess.id),
    messages: {
      created: 'Approval process created successfully',
      updated: 'Approval process updated successfully',
      deleted: 'Approval process deleted successfully',
      error: 'An error occurred',
    },
  })

  // Instances query — fetches from JSON:API endpoint
  const {
    data: allInstances,
    isLoading: _instancesLoading,
    error: _instancesError,
  } = useQuery({
    queryKey: ['approval-instances'],
    queryFn: () => keltaClient.admin.approvals.listInstances(''),
    enabled: !!instancesItemId,
  })

  const filteredInstances = ((allInstances ?? []) as ApprovalInstance[]).filter(
    (i: ApprovalInstance) => i.approvalProcessId === instancesItemId
  )
  void filteredInstances

  // Approve mutation — graceful degradation (action endpoint not available in JSON:API)
  const approveMutation = useMutation({
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    mutationFn: (_stepInstanceId: string) =>
      Promise.reject(
        new Error('Approval actions are temporarily unavailable during platform migration')
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approval-instances'] })
      toast.success(t('approvals.approved'))
    },
    onError: (err: Error) => {
      toast.error(err.message || 'An error occurred')
    },
  })

  // Reject mutation — graceful degradation (action endpoint not available in JSON:API)
  const rejectMutation = useMutation({
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    mutationFn: (_stepInstanceId: string) =>
      Promise.reject(
        new Error('Rejection actions are temporarily unavailable during platform migration')
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approval-instances'] })
      toast.success(t('approvals.rejected'))
    },
    onError: (err: Error) => {
      toast.error(err.message || 'An error occurred')
    },
  })

  // Recall mutation — graceful degradation (action endpoint not available in JSON:API)
  const recallMutation = useMutation({
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    mutationFn: (_instanceId: string) =>
      Promise.reject(
        new Error('Recall actions are temporarily unavailable during platform migration')
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approval-instances'] })
      toast.success(t('approvals.recalled'))
    },
    onError: (err: Error) => {
      toast.error(err.message || 'An error occurred')
    },
  })

  // Instance columns for ExecutionLogModal
  const instanceColumns: LogColumn<ApprovalInstance>[] = [
    {
      key: 'status',
      header: t('logs.status'),
      render: (v) => {
        const status = v as string
        const colorMap: Record<string, { color: string; bg: string }> = {
          PENDING: { color: '#92400e', bg: '#fef3c7' },
          APPROVED: { color: '#065f46', bg: '#d1fae5' },
          REJECTED: { color: '#991b1b', bg: '#fee2e2' },
          RECALLED: { color: '#6b7280', bg: '#f3f4f6' },
        }
        const style = colorMap[status] ?? { color: '#6b7280', bg: '#f3f4f6' }
        return (
          <span
            style={{
              display: 'inline-block',
              padding: '0.125rem 0.5rem',
              borderRadius: '9999px',
              fontSize: '0.75rem',
              fontWeight: 600,
              color: style.color,
              backgroundColor: style.bg,
            }}
          >
            {status}
          </span>
        )
      },
    },
    { key: 'submittedBy', header: t('approvals.submittedBy') },
    { key: 'recordId', header: t('logs.recordId') },
    {
      key: 'currentStepNumber',
      header: t('approvals.currentStep'),
      render: (v) => (v != null ? String(v) : '-'),
    },
    {
      key: 'submittedAt',
      header: t('approvals.submittedAt'),
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
    {
      key: 'completedAt',
      header: t('logs.completedAt'),
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
    {
      key: 'actions',
      header: '',
      render: (_v: unknown, row: ApprovalInstance) => {
        if (row.status === 'PENDING') {
          const pendingStep = row.stepInstances?.find((s) => s.status === 'PENDING')
          return (
            <div className="flex justify-end gap-2">
              {pendingStep && (
                <>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => approveMutation.mutate(pendingStep.id)}
                    disabled={approveMutation.isPending}
                    className="h-auto px-2 py-1 text-xs"
                  >
                    {t('approvals.approve')}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => rejectMutation.mutate(pendingStep.id)}
                    disabled={rejectMutation.isPending}
                    className="h-auto border-destructive/30 px-2 py-1 text-xs text-destructive hover:bg-destructive/10"
                  >
                    {t('approvals.reject')}
                  </Button>
                </>
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => recallMutation.mutate(row.id)}
                disabled={recallMutation.isPending}
                className="h-auto px-2 py-1 text-xs"
              >
                {t('approvals.recall')}
              </Button>
            </div>
          )
        }
        return null
      },
    },
  ]
  void instanceColumns

  const columns = useMemo<AdminColumn<ApprovalProcess>[]>(
    () => [
      { id: 'name', header: 'Name', cellClassName: 'text-foreground' },
      {
        id: 'collectionId',
        header: 'Collection ID',
        cell: (ap) => (
          <span className="max-w-[300px] truncate text-muted-foreground">
            {ap.collectionId || '-'}
          </span>
        ),
      },
      {
        id: 'recordEditability',
        header: 'Record Editability',
        cell: (ap) => (
          <span className="inline-block rounded-full bg-muted px-3 py-1 text-xs font-semibold uppercase tracking-wider text-primary">
            {ap.recordEditability}
          </span>
        ),
      },
      { id: 'active', header: 'Active', cell: (ap) => <YesNoBadge value={ap.active} /> },
      {
        id: 'allowRecall',
        header: 'Allow Recall',
        cell: (ap) => <YesNoBadge value={ap.allowRecall} />,
      },
      { id: 'executionOrder', header: 'Order', cellClassName: 'text-foreground' },
    ],
    []
  )

  const editing = crud.editing

  return (
    <AdminCrudPage
      testId={testId}
      title="Approval processes"
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label="Create Approval Process"
          data-testid="add-approval-process-button"
        >
          Create Approval Process
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={approvalProcessList.length === 0}
      emptyMessage="No approval processes found."
      list={
        <AdminDataTable
          tableId="approval-processes"
          testId="approval-processes-table"
          ariaLabel="Approval Processes"
          actionsHeader="Actions"
          columns={columns}
          rows={approvalProcessList}
          rowKey={(ap) => ap.id}
          rowTestId={(_, index) => `approval-process-row-${index}`}
          renderActions={(approvalProcess, index) => (
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setInstancesItemId(approvalProcess.id)
                  setInstancesItemName(approvalProcess.name)
                }}
                aria-label={`View instances for ${approvalProcess.name}`}
                data-testid={`instances-button-${index}`}
              >
                {t('approvals.instances')}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(approvalProcess)}
                aria-label={`Edit ${approvalProcess.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(approvalProcess)}
                aria-label={`Delete ${approvalProcess.name}`}
                data-testid={`delete-button-${index}`}
              >
                Delete
              </Button>
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<ApprovalProcessFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? 'Edit Approval Process' : 'Create Approval Process'}
        schema={approvalProcessSchema}
        defaultValues={{
          name: editing?.name ?? '',
          description: editing?.description ?? '',
          collectionId: editing?.collectionId ?? '',
          entryCriteria: editing?.entryCriteria ?? '',
          recordEditability: editing?.recordEditability ?? 'LOCKED',
          allowRecall: editing?.allowRecall ?? false,
          active: editing?.active ?? true,
          executionOrder: editing?.executionOrder ?? 0,
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="approval-process"
        className="sm:max-w-[600px]"
      >
        <CrudTextField<ApprovalProcessFormData>
          name="name"
          id="approval-process-name"
          label="Name"
          required
          placeholder="Enter approval process name"
          testId="approval-process-name-input"
        />
        <CrudTextField<ApprovalProcessFormData>
          name="description"
          id="approval-process-description"
          label="Description"
          multiline
          placeholder="Enter approval process description"
          testId="approval-process-description-input"
        />
        <CrudTextField<ApprovalProcessFormData>
          name="collectionId"
          id="approval-process-collection-id"
          label="Collection ID"
          placeholder="Enter collection ID"
          testId="approval-process-collection-id-input"
        />
        <EntryCriteriaField />
        <CrudSelectField<ApprovalProcessFormData>
          name="recordEditability"
          id="approval-process-record-editability"
          label="Record Editability"
          required
          options={RECORD_EDITABILITY_OPTIONS}
          testId="approval-process-record-editability-input"
        />
        <CrudNumberField<ApprovalProcessFormData>
          name="executionOrder"
          id="approval-process-execution-order"
          label="Execution Order"
          required
          min={0}
          parse={(raw) => parseInt(raw, 10) || 0}
          testId="approval-process-execution-order-input"
        />
        <CrudCheckboxField<ApprovalProcessFormData>
          name="allowRecall"
          id="approval-process-allow-recall"
          label="Allow Recall"
          testId="approval-process-allow-recall-input"
        />
        <CrudCheckboxField<ApprovalProcessFormData>
          name="active"
          id="approval-process-active"
          label="Active"
          testId="approval-process-active-input"
        />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Approval Process"
        message="Are you sure you want to delete this approval process? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />

      {instancesItemId && (
        <ExecutionLogModal<ApprovalInstance>
          title={t('approvals.approvalInstances')}
          subtitle={_instancesItemName}
          columns={instanceColumns}
          data={filteredInstances}
          isLoading={_instancesLoading}
          error={_instancesError instanceof Error ? _instancesError : null}
          onClose={() => setInstancesItemId(null)}
          emptyMessage={t('approvals.noInstances')}
        />
      )}
    </AdminCrudPage>
  )
}

export default ApprovalProcessesPage
