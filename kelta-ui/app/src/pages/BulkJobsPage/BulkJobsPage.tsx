import React, { useState, useCallback, useMemo, useRef } from 'react'
import { toast } from 'sonner'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog } from '../../components'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import type { CreateBulkJobRequest } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

interface BulkJob {
  id: string
  collectionId: string
  operation: string
  status: string
  totalRecords: number
  processedRecords: number
  successRecords: number
  errorRecords: number
  externalIdField: string | null
  batchSize: number
  createdBy: string
  startedAt: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
}

/** Batch size as entered; blank or non-numeric input means 1, as the form always has. */
function toBatchSize(value: string): number {
  return parseInt(value, 10) || 1
}

const bulkJobSchema = z.object({
  collectionId: z.string().refine((v) => v.trim().length > 0, 'Collection ID is required'),
  operation: z.string(),
  externalIdField: z.string(),
  batchSize: z.string().refine((v) => {
    const size = toBatchSize(v)
    return size >= 1 && size <= 10000
  }, 'Batch size must be between 1 and 10000'),
})

type BulkJobFormData = z.infer<typeof bulkJobSchema>

const OPERATION_OPTIONS = [
  { value: 'INSERT', label: 'Insert' },
  { value: 'UPDATE', label: 'Update' },
  { value: 'UPSERT', label: 'Upsert' },
  { value: 'DELETE', label: 'Delete' },
]

export interface BulkJobsPageProps {
  testId?: string
}

function getStatusBadgeColor(status: string): string {
  switch (status) {
    case 'QUEUED':
      return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'
    case 'PROCESSING':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300'
    case 'COMPLETED':
      return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-300'
    case 'FAILED':
      return 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300'
    case 'ABORTED':
      return 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300'
    default:
      return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'
  }
}

/**
 * Optional data-file picker inside the create dialog. The file lives in page
 * state (not the form values) because it switches the submit to the multipart
 * upload endpoint.
 */
function BulkJobFileField({
  file,
  onChange,
  disabled,
}: {
  file: File | null
  onChange: (file: File | null) => void
  disabled: boolean
}): React.ReactElement {
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleRemove = useCallback(() => {
    onChange(null)
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }, [onChange])

  return (
    <div className="flex flex-col gap-1.5">
      <label
        htmlFor="bulk-job-file"
        className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
      >
        Data File
      </label>
      <input
        ref={fileInputRef}
        id="bulk-job-file"
        type="file"
        accept=".csv,.json"
        className="w-full rounded-md border border-border bg-background px-3 py-2.5 text-sm text-foreground file:mr-3 file:rounded-md file:border-0 file:bg-muted file:px-3 file:py-1 file:text-sm file:font-medium file:text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        onChange={(e) => onChange(e.target.files?.[0] ?? null)}
        disabled={disabled}
        data-testid="bulk-job-file-input"
      />
      {file ? (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span data-testid="bulk-job-file-name">{file.name}</span>
          <button
            type="button"
            className="font-medium text-destructive hover:underline disabled:opacity-50"
            onClick={handleRemove}
            disabled={disabled}
            data-testid="bulk-job-file-remove"
          >
            Remove
          </button>
        </div>
      ) : (
        <span className="text-xs text-muted-foreground">
          Optional. Upload a .csv or .json file to load records from the file.
        </span>
      )}
    </div>
  )
}

export function BulkJobsPage({ testId = 'bulk-jobs-page' }: BulkJobsPageProps): React.ReactElement {
  const { formatDate } = useI18n()
  const { keltaClient, apiClient } = useApi()

  const [file, setFile] = useState<File | null>(null)
  const [downloadingJobId, setDownloadingJobId] = useState<string | null>(null)

  const {
    data: bulkJobs,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['bulk-jobs'],
    queryFn: () => keltaClient.admin.bulkJobs.list(),
    refetchInterval: 5000,
  })

  const jobList: BulkJob[] = (bulkJobs ?? []) as unknown as BulkJob[]

  // Jobs are create + abort only: there is no edit, and the confirm-before-delete
  // path aborts the job (records already processed are not rolled back).
  const crud = useAdminCrud<BulkJob, BulkJobFormData>({
    queryKey: ['bulk-jobs'],
    create: (values) => {
      const data = {
        collectionId: values.collectionId,
        operation: values.operation,
        externalIdField: values.externalIdField,
        batchSize: toBatchSize(values.batchSize),
      }
      if (!file) {
        return keltaClient.admin.bulkJobs.create('', '', data as CreateBulkJobRequest)
      }
      const formData = new FormData()
      formData.append('file', file)
      formData.append('collectionId', data.collectionId)
      formData.append('operation', data.operation)
      if (data.externalIdField.trim()) {
        formData.append('externalIdField', data.externalIdField)
      }
      formData.append('batchSize', String(data.batchSize))
      return apiClient.postFormData('/api/bulk-jobs/upload', formData)
    },
    remove: (job) => keltaClient.admin.bulkJobs.abort(job.id),
    messages: {
      created: 'Bulk job created successfully',
      updated: 'Bulk job updated successfully',
      deleted: 'Bulk job aborted successfully',
      error: 'An error occurred',
    },
  })

  // Every open starts without a file, as the form itself starts from defaults.
  const handleCreate = () => {
    setFile(null)
    crud.openCreate()
  }

  const handleDownloadResults = useCallback(
    async (job: BulkJob) => {
      setDownloadingJobId(job.id)
      try {
        const csv = await apiClient.get<string>(`/api/bulk-jobs/${job.id}/results/download`)
        const blob = new Blob([typeof csv === 'string' ? csv : JSON.stringify(csv)], {
          type: 'text/csv',
        })
        const url = window.URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `bulk-job-${job.id}-results.csv`
        document.body.appendChild(a)
        a.click()
        window.URL.revokeObjectURL(url)
        document.body.removeChild(a)
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : 'Failed to download results'
        toast.error(message)
      } finally {
        setDownloadingJobId(null)
      }
    },
    [apiClient]
  )

  const columns = useMemo<AdminColumn<BulkJob>[]>(
    () => [
      { id: 'collectionId', header: 'Collection ID', cellClassName: 'text-foreground' },
      {
        id: 'operation',
        header: 'Operation',
        cell: (job) => (
          <span className="inline-block rounded-full bg-muted px-3 py-1 text-xs font-semibold uppercase tracking-wider text-primary">
            {job.operation}
          </span>
        ),
      },
      {
        id: 'status',
        header: 'Status',
        cell: (job) => (
          <span
            className={cn(
              'inline-block rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wider',
              getStatusBadgeColor(job.status)
            )}
          >
            {job.status}
          </span>
        ),
      },
      {
        id: 'progress',
        header: 'Progress',
        accessor: (job) => job.processedRecords,
        cell: (job) => {
          const progressPercent =
            job.totalRecords > 0 ? Math.round((job.processedRecords / job.totalRecords) * 100) : 0
          return (
            <div className="flex items-center gap-2">
              <div className="min-w-[60px] flex-1">
                <div className="h-1.5 overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full rounded-full bg-primary transition-all duration-300"
                    style={{ width: `${progressPercent}%` }}
                  />
                </div>
              </div>
              <span className="whitespace-nowrap text-xs text-muted-foreground">
                {job.processedRecords}/{job.totalRecords}
              </span>
            </div>
          )
        },
      },
      { id: 'successRecords', header: 'Success', cellClassName: 'text-foreground' },
      { id: 'errorRecords', header: 'Errors', cellClassName: 'text-foreground' },
      {
        id: 'createdAt',
        header: 'Created',
        cell: (job) =>
          formatDate(new Date(job.createdAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          }),
        cellClassName: 'text-foreground',
      },
    ],
    [formatDate]
  )

  return (
    <AdminCrudPage
      testId={testId}
      title="Bulk Jobs"
      actions={
        <Button
          type="button"
          onClick={handleCreate}
          aria-label="Create Bulk Job"
          data-testid="add-bulk-job-button"
        >
          Create Bulk Job
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={jobList.length === 0}
      emptyMessage="No bulk jobs found."
      list={
        <AdminDataTable
          tableId="bulk-jobs"
          testId="bulk-jobs-table"
          ariaLabel="Bulk Jobs"
          columns={columns}
          rows={jobList}
          rowKey={(job) => job.id}
          rowTestId={(_, index) => `bulk-job-row-${index}`}
          renderActions={(job, index) => {
            const canAbort = job.status === 'QUEUED' || job.status === 'PROCESSING'
            const canDownloadResults =
              job.status === 'COMPLETED' || (job.status === 'FAILED' && job.processedRecords > 0)
            return (
              <div className="flex justify-end gap-2">
                {canDownloadResults && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => handleDownloadResults(job)}
                    disabled={downloadingJobId === job.id}
                    aria-label={`Download results for job ${job.id}`}
                    data-testid={`download-results-button-${index}`}
                  >
                    {downloadingJobId === job.id ? 'Downloading...' : 'Download results'}
                  </Button>
                )}
                {canAbort && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="text-amber-600 hover:border-amber-600 hover:bg-amber-50 dark:text-amber-400 dark:hover:border-amber-400 dark:hover:bg-amber-950"
                    onClick={() => crud.requestDelete(job)}
                    aria-label={`Abort job ${job.id}`}
                    data-testid={`abort-button-${index}`}
                  >
                    Abort
                  </Button>
                )}
              </div>
            )
          }}
        />
      }
    >
      <CrudFormDialog<BulkJobFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title="Create Bulk Job"
        schema={bulkJobSchema}
        defaultValues={{
          collectionId: '',
          operation: 'INSERT',
          externalIdField: '',
          batchSize: '200',
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="bulk-job"
        submitLabel="Create Job"
        submittingLabel={file ? 'Uploading...' : 'Creating...'}
        className="sm:max-w-[600px]"
      >
        <CrudTextField<BulkJobFormData>
          name="collectionId"
          id="bulk-job-collection-id"
          label="Collection ID"
          required
          placeholder="Enter collection ID"
          testId="bulk-job-collection-id-input"
        />
        <CrudSelectField<BulkJobFormData>
          name="operation"
          id="bulk-job-operation"
          label="Operation"
          required
          options={OPERATION_OPTIONS}
          testId="bulk-job-operation-input"
        />
        <CrudTextField<BulkJobFormData>
          name="externalIdField"
          id="bulk-job-external-id"
          label="External ID Field"
          placeholder="Enter external ID field (optional)"
          hint="Required for UPSERT operations"
          testId="bulk-job-external-id-input"
        />
        <CrudTextField<BulkJobFormData>
          name="batchSize"
          id="bulk-job-batch-size"
          label="Batch Size"
          required
          type="number"
          testId="bulk-job-batch-size-input"
        />
        <BulkJobFileField file={file} onChange={setFile} disabled={crud.isSaving} />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Abort Bulk Job"
        message="Are you sure you want to abort this bulk job? Records already processed will not be rolled back."
        confirmLabel="Abort"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}
