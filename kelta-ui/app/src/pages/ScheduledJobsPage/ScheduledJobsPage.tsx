import React, { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useFormContext, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useToast, ConfirmDialog, ExecutionLogModal } from '../../components'
import type { LogColumn } from '../../components'
import type { CreateScheduledJobRequest } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'

interface ScheduledJobConfig {
  recipients?: string[]
}

interface ScheduledJob {
  id: string
  name: string
  description: string | null
  jobType: string
  jobReferenceId: string | null
  cronExpression: string
  timezone: string
  active: boolean
  config?: ScheduledJobConfig | null
  lastRun: string | null
  lastStatus: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

interface ScheduledJobSubmitData {
  name: string
  description: string
  jobType: string
  jobReferenceId: string
  cronExpression: string
  timezone: string
  active: boolean
  config?: ScheduledJobConfig
}

interface JobExecutionLog {
  id: string
  jobId: string
  status: string
  recordsProcessed: number
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
}

export interface ScheduledJobsPageProps {
  testId?: string
}

function parseRecipients(text: string): string[] {
  const seen = new Set<string>()
  const recipients: string[] = []
  for (const raw of text.split(/[\n,]/)) {
    const entry = raw.trim()
    if (!entry) continue
    const key = entry.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    recipients.push(entry)
  }
  return recipients
}

const scheduledJobSchema = z
  .object({
    name: z
      .string()
      .refine((v) => v.trim().length > 0, 'Name is required')
      .refine((v) => v.length <= 100, 'Name must be 100 characters or fewer'),
    description: z.string().max(500, 'Description must be 500 characters or fewer'),
    jobType: z.string(),
    jobReferenceId: z.string(),
    cronExpression: z.string().refine((v) => v.trim().length > 0, 'Cron expression is required'),
    timezone: z.string(),
    active: z.boolean(),
    recipientsText: z.string(),
  })
  .superRefine((data, ctx) => {
    if (data.jobType !== 'REPORT_EXPORT') return
    const invalid = parseRecipients(data.recipientsText).filter((entry) => !entry.includes('@'))
    if (invalid.length > 0) {
      ctx.addIssue({
        code: 'custom',
        path: ['recipientsText'],
        message: `Invalid email addresses: ${invalid.join(', ')}`,
      })
    }
  })

type ScheduledJobFormData = z.infer<typeof scheduledJobSchema>

const JOB_TYPE_OPTIONS = [
  { value: 'FLOW', label: 'Flow' },
  { value: 'SCRIPT', label: 'Script' },
  { value: 'REPORT_EXPORT', label: 'Report Export' },
]

/**
 * Maps the form to the API payload. Sends config whenever the job type
 * supports it — an explicit empty recipients list clears previously saved
 * delivery config on edit (omitting config would leave the old value in place).
 */
function toSubmitData(formData: ScheduledJobFormData): ScheduledJobSubmitData {
  const { recipientsText, ...rest } = formData
  if (formData.jobType === 'REPORT_EXPORT') {
    return { ...rest, config: { recipients: parseRecipients(recipientsText) } }
  }
  return { ...rest }
}

/** Email recipients textarea — only shown for REPORT_EXPORT jobs. */
function RecipientsField(): React.ReactElement | null {
  const { control } = useFormContext<ScheduledJobFormData>()
  const jobType = useWatch({ control, name: 'jobType' })
  if (jobType !== 'REPORT_EXPORT') return null
  return (
    <CrudTextField<ScheduledJobFormData>
      name="recipientsText"
      id="scheduled-job-recipients"
      label="Email recipients"
      multiline
      placeholder="email1@example.com, email2@example.com"
      hint="Report CSV is emailed to these addresses after each run; leave empty to skip delivery"
      testId="scheduled-job-recipients-input"
      errorTestId="scheduled-job-recipients-error"
    />
  )
}

const STATUS_PILL =
  'inline-block rounded-full bg-muted px-3 py-1 text-xs font-semibold uppercase tracking-wider text-primary'

export function ScheduledJobsPage({
  testId = 'scheduled-jobs-page',
}: ScheduledJobsPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()
  const { showToast } = useToast()

  const [logsItemId, setLogsItemId] = useState<string | null>(null)
  const [_logsItemName, setLogsItemName] = useState('')

  const {
    data: logs,
    isLoading: logsLoading,
    error: logsError,
  } = useQuery({
    queryKey: ['scheduled-job-logs', logsItemId],
    queryFn: () => keltaClient.admin.scheduledJobs.listLogs(logsItemId!),
    enabled: !!logsItemId,
  })

  const logColumns: LogColumn<JobExecutionLog>[] = [
    { key: 'status', header: 'Status' },
    { key: 'recordsProcessed', header: 'Records' },
    {
      key: 'durationMs',
      header: 'Duration',
      render: (v) => (v != null ? `${v}ms` : '-'),
    },
    { key: 'errorMessage', header: 'Error' },
    {
      key: 'startedAt',
      header: 'Started At',
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
      header: 'Completed At',
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
  void logColumns

  const {
    data: scheduledJobs,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['scheduled-jobs'],
    queryFn: () => keltaClient.admin.scheduledJobs.list(),
  })

  const scheduledJobList = useMemo(
    () => (scheduledJobs ?? []) as unknown as ScheduledJob[],
    [scheduledJobs]
  )

  const crud = useAdminCrud<ScheduledJob, ScheduledJobFormData>({
    queryKey: ['scheduled-jobs'],
    create: (data) =>
      keltaClient.admin.scheduledJobs.create(
        '',
        '',
        toSubmitData(data) as CreateScheduledJobRequest
      ),
    update: (scheduledJob, data) =>
      keltaClient.admin.scheduledJobs.update(
        scheduledJob.id,
        toSubmitData(data) as Partial<CreateScheduledJobRequest>
      ),
    remove: (scheduledJob) => keltaClient.admin.scheduledJobs.delete(scheduledJob.id),
    messages: {
      created: 'Scheduled job created successfully',
      updated: 'Scheduled job updated successfully',
      deleted: 'Scheduled job deleted successfully',
      error: 'An error occurred',
    },
  })

  const pauseMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.scheduledJobs.pause(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] })
      showToast('Scheduled job paused', 'success')
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to pause job', 'error')
    },
  })

  const resumeMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.scheduledJobs.resume(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] })
      showToast('Scheduled job resumed', 'success')
    },
    onError: (err: Error) => {
      showToast(err.message || 'Failed to resume job', 'error')
    },
  })

  // `cell` receives no row index, so the recipients badge keys its test id off
  // the job's position in the list as returned by the API.
  const jobIndex = useMemo(
    () => new Map(scheduledJobList.map((job, index) => [job.id, index])),
    [scheduledJobList]
  )

  const columns = useMemo<AdminColumn<ScheduledJob>[]>(
    () => [
      { id: 'name', header: 'Name', cellClassName: 'text-foreground' },
      {
        id: 'jobType',
        header: 'Job Type',
        cell: (job) => {
          const recipientCount = job.config?.recipients?.length ?? 0
          return (
            <>
              <span className={STATUS_PILL}>{job.jobType}</span>
              {job.jobType === 'REPORT_EXPORT' && recipientCount > 0 && (
                <span
                  className="ml-2 inline-block rounded-full bg-muted px-2 py-1 text-xs font-semibold text-muted-foreground"
                  data-testid={`recipients-badge-${jobIndex.get(job.id)}`}
                >
                  {recipientCount} {recipientCount === 1 ? 'recipient' : 'recipients'}
                </span>
              )}
            </>
          )
        },
      },
      { id: 'cronExpression', header: 'Cron Expression', cellClassName: 'text-foreground' },
      {
        id: 'active',
        header: 'Active',
        cell: (job) => (
          <span
            className={cn(
              'inline-block rounded-full px-3 py-1 text-xs font-semibold',
              job.active
                ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                : 'bg-muted text-muted-foreground'
            )}
          >
            {job.active ? 'Yes' : 'No'}
          </span>
        ),
      },
      {
        id: 'lastRun',
        header: 'Last Run',
        cell: (job) =>
          job.lastRun
            ? formatDate(new Date(job.lastRun), {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })
            : '-',
        cellClassName: 'text-foreground',
      },
      {
        id: 'lastStatus',
        header: 'Last Status',
        cell: (job) =>
          job.lastStatus ? <span className={STATUS_PILL}>{job.lastStatus}</span> : '-',
        cellClassName: 'text-foreground',
      },
    ],
    [formatDate, jobIndex]
  )

  const editing = crud.editing

  return (
    <AdminCrudPage
      testId={testId}
      title="Scheduled jobs"
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label="Create Scheduled Job"
          data-testid="add-scheduled-job-button"
        >
          Create Scheduled Job
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={scheduledJobList.length === 0}
      emptyMessage="No scheduled jobs found."
      list={
        <AdminDataTable
          tableId="scheduled-jobs"
          testId="scheduled-jobs-table"
          ariaLabel="Scheduled Jobs"
          actionsHeader="Actions"
          columns={columns}
          rows={scheduledJobList}
          rowKey={(job) => job.id}
          rowTestId={(_, index) => `scheduled-job-row-${index}`}
          renderActions={(scheduledJob, index) => (
            <div className="flex justify-end gap-2">
              {scheduledJob.active ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => pauseMutation.mutate(scheduledJob.id)}
                  disabled={pauseMutation.isPending}
                  aria-label={`Pause ${scheduledJob.name}`}
                  data-testid={`pause-button-${index}`}
                >
                  Pause
                </Button>
              ) : (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => resumeMutation.mutate(scheduledJob.id)}
                  disabled={resumeMutation.isPending}
                  aria-label={`Resume ${scheduledJob.name}`}
                  data-testid={`resume-button-${index}`}
                >
                  Resume
                </Button>
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setLogsItemId(scheduledJob.id)
                  setLogsItemName(scheduledJob.name)
                }}
                aria-label={`View logs for ${scheduledJob.name}`}
                data-testid={`logs-button-${index}`}
              >
                Logs
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(scheduledJob)}
                aria-label={`Edit ${scheduledJob.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(scheduledJob)}
                aria-label={`Delete ${scheduledJob.name}`}
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
        <ExecutionLogModal<JobExecutionLog>
          title="Scheduled Job Logs"
          subtitle={_logsItemName}
          columns={logColumns}
          data={(logs ?? []) as unknown as JobExecutionLog[]}
          isLoading={logsLoading}
          error={logsError instanceof Error ? logsError : null}
          onClose={() => setLogsItemId(null)}
          emptyMessage="No execution logs found."
        />
      )}

      <CrudFormDialog<ScheduledJobFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? 'Edit Scheduled Job' : 'Create Scheduled Job'}
        schema={scheduledJobSchema}
        defaultValues={{
          name: editing?.name ?? '',
          description: editing?.description ?? '',
          jobType: editing?.jobType ?? 'FLOW',
          jobReferenceId: editing?.jobReferenceId ?? '',
          cronExpression: editing?.cronExpression ?? '',
          timezone: editing?.timezone ?? 'UTC',
          active: editing?.active ?? true,
          recipientsText: editing?.config?.recipients?.join(', ') ?? '',
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="scheduled-job"
        className="sm:max-w-[600px]"
      >
        <CrudTextField<ScheduledJobFormData>
          name="name"
          id="scheduled-job-name"
          label="Name"
          required
          placeholder="Enter job name"
          testId="scheduled-job-name-input"
        />
        <CrudTextField<ScheduledJobFormData>
          name="description"
          id="scheduled-job-description"
          label="Description"
          multiline
          placeholder="Enter job description"
          testId="scheduled-job-description-input"
        />
        <CrudSelectField<ScheduledJobFormData>
          name="jobType"
          id="scheduled-job-type"
          label="Job Type"
          required
          options={JOB_TYPE_OPTIONS}
          testId="scheduled-job-type-input"
        />
        <CrudTextField<ScheduledJobFormData>
          name="jobReferenceId"
          id="scheduled-job-reference-id"
          label="Job Reference ID"
          placeholder="Enter job reference ID"
          testId="scheduled-job-reference-id-input"
        />
        <RecipientsField />
        <CrudTextField<ScheduledJobFormData>
          name="cronExpression"
          id="scheduled-job-cron-expression"
          label="Cron Expression"
          required
          placeholder="0 0 * * *"
          testId="scheduled-job-cron-expression-input"
        />
        <CrudTextField<ScheduledJobFormData>
          name="timezone"
          id="scheduled-job-timezone"
          label="Timezone"
          placeholder="UTC"
          testId="scheduled-job-timezone-input"
        />
        <CrudCheckboxField<ScheduledJobFormData>
          name="active"
          id="scheduled-job-active"
          label="Active"
          testId="scheduled-job-active-input"
        />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Scheduled Job"
        message="Are you sure you want to delete this scheduled job? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}

export default ScheduledJobsPage
