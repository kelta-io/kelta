import React, { useState, useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import type {
  Campaign as SdkCampaign,
  CreateCampaignRequest,
  CampaignStats,
  EmailSuppression,
} from '@kelta/sdk'
import { Button } from '@/components/ui/button'
import { AdminDataTable, type AdminColumn } from '@/components/AdminDataTable'
import {
  AdminCrudPage,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'

type Campaign = SdkCampaign

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const campaignSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 100, 'Name must be 100 characters or fewer'),
  description: z.string(),
  subject: z
    .string()
    .refine((v) => v.trim().length > 0, 'Subject is required')
    .refine((v) => v.length <= 200, 'Subject must be 200 characters or fewer'),
  bodyHtml: z.string(),
  targetCollection: z.string().refine((v) => v.trim().length > 0, 'Target collection is required'),
  recipientEmailField: z
    .string()
    .refine((v) => v.trim().length > 0, 'Recipient email field is required'),
  fromName: z.string(),
  fromAddress: z.string().refine((v) => !v || EMAIL_PATTERN.test(v), 'Enter a valid email address'),
  scheduledAt: z.string(),
})

type CampaignFormData = z.infer<typeof campaignSchema>

interface CollectionOption {
  id: string
  name: string
  displayName: string
}

export interface CampaignsPageProps {
  testId?: string
}

/** Statuses in which a campaign can still be edited. */
const EDITABLE_STATUSES = new Set(['DRAFT', 'SCHEDULED'])

function toFormData(campaign?: Campaign): CampaignFormData {
  return {
    name: campaign?.name ?? '',
    description: campaign?.description ?? '',
    subject: campaign?.subject ?? '',
    bodyHtml: campaign?.bodyHtml ?? '',
    targetCollection: campaign?.targetCollection ?? '',
    recipientEmailField: campaign?.recipientEmailField ?? '',
    fromName: campaign?.fromName ?? '',
    fromAddress: campaign?.fromAddress ?? '',
    scheduledAt: campaign?.scheduledAt ? campaign.scheduledAt.slice(0, 16) : '',
  }
}

function buildRequest(data: CampaignFormData): CreateCampaignRequest {
  return {
    name: data.name,
    description: data.description || undefined,
    subject: data.subject,
    bodyHtml: data.bodyHtml || undefined,
    targetCollection: data.targetCollection,
    recipientEmailField: data.recipientEmailField,
    fromName: data.fromName || undefined,
    fromAddress: data.fromAddress || undefined,
    scheduledAt: data.scheduledAt ? new Date(data.scheduledAt).toISOString() : undefined,
  }
}

interface StatsModalProps {
  campaignName: string
  stats?: CampaignStats
  isLoading: boolean
  error: Error | null
  onClose: () => void
}

function StatsModal({
  campaignName,
  stats,
  isLoading,
  error,
  onClose,
}: StatsModalProps): React.ReactElement {
  const rows: Array<{ label: string; value: number | undefined }> = [
    { label: 'Total recipients', value: stats?.totalRecipients },
    { label: 'Sent', value: stats?.sent },
    { label: 'Failed', value: stats?.failed },
    { label: 'Opens', value: stats?.opens },
    { label: 'Clicks', value: stats?.clicks },
    { label: 'Unsubscribes', value: stats?.unsubscribes },
  ]

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onClose()}
      role="presentation"
      data-testid="campaign-stats-overlay"
    >
      <div
        className="flex max-h-[80vh] w-full max-w-[480px] flex-col overflow-hidden rounded-lg bg-background shadow-xl"
        role="dialog"
        aria-modal="true"
        aria-label={`Stats for ${campaignName}`}
        data-testid="campaign-stats-modal"
      >
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <h2 className="text-lg font-semibold text-foreground">Campaign stats</h2>
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            Close
          </Button>
        </div>
        <div className="p-6">
          {isLoading ? (
            <div className="flex min-h-[120px] items-center justify-center">
              <LoadingSpinner size="medium" label="Loading stats..." />
            </div>
          ) : error ? (
            <ErrorMessage error={error} />
          ) : (
            <>
              <p className="mb-4 text-sm text-muted-foreground">
                Status: <span className="font-medium text-foreground">{stats?.status ?? '-'}</span>
              </p>
              <dl className="grid grid-cols-2 gap-3">
                {rows.map((r) => (
                  <div key={r.label} className="rounded-md border border-border bg-card px-3 py-2">
                    <dt className="text-[11px] uppercase tracking-wide text-muted-foreground">
                      {r.label}
                    </dt>
                    <dd className="text-lg font-semibold text-foreground">{r.value ?? 0}</dd>
                  </div>
                ))}
              </dl>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

interface SuppressionsSectionProps {
  suppressions: EmailSuppression[]
  isLoading: boolean
  error: Error | null
  onAdd: (email: string, reason: string) => void
  onRemove: (email: string) => void
  isMutating: boolean
}

function SuppressionsSection({
  suppressions,
  isLoading,
  error,
  onAdd,
  onRemove,
  isMutating,
}: SuppressionsSectionProps): React.ReactElement {
  const [email, setEmail] = useState('')
  const [reason, setReason] = useState('')

  const handleAdd = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      if (email.trim()) {
        onAdd(email.trim(), reason.trim())
        setEmail('')
        setReason('')
      }
    },
    [email, reason, onAdd]
  )

  return (
    <section className="space-y-4" data-testid="suppressions-section">
      <h2 className="text-lg font-semibold text-foreground">Suppressions</h2>
      <p className="text-sm text-muted-foreground">
        Suppressed addresses are excluded from all campaign sends.
      </p>
      <form className="flex flex-wrap items-end gap-3" onSubmit={handleAdd}>
        <div className="flex flex-col gap-1">
          <label htmlFor="suppression-email" className="text-xs font-medium text-foreground">
            Email
          </label>
          <input
            id="suppression-email"
            type="email"
            className="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="user@example.com"
            data-testid="suppression-email-input"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="suppression-reason" className="text-xs font-medium text-foreground">
            Reason
          </label>
          <input
            id="suppression-reason"
            type="text"
            className="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Optional"
            data-testid="suppression-reason-input"
          />
        </div>
        <Button
          type="submit"
          disabled={isMutating || !email.trim()}
          data-testid="add-suppression-button"
        >
          Add
        </Button>
      </form>

      {isLoading ? (
        <div className="flex min-h-[80px] items-center justify-center">
          <LoadingSpinner size="small" label="Loading suppressions..." />
        </div>
      ) : error ? (
        <ErrorMessage error={error} />
      ) : suppressions.length === 0 ? (
        <div
          className="rounded-lg border border-border bg-card py-8 text-center text-muted-foreground"
          data-testid="suppressions-empty-state"
        >
          <p>No suppressed addresses.</p>
        </div>
      ) : (
        <ul
          className="divide-y divide-border rounded-lg border border-border bg-card"
          data-testid="suppressions-list"
        >
          {suppressions.map((s) => (
            <li key={s.id} className="flex items-center justify-between px-4 py-2 text-sm">
              <span className="text-foreground">
                {s.email}
                {s.reason ? <span className="ml-2 text-muted-foreground">({s.reason})</span> : null}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => onRemove(s.email)}
                disabled={isMutating}
                aria-label={`Remove suppression ${s.email}`}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

export function CampaignsPage({
  testId = 'campaigns-page',
}: CampaignsPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()

  const [statsCampaign, setStatsCampaign] = useState<Campaign | null>(null)

  const {
    data: campaigns,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['campaigns'],
    queryFn: () => keltaClient.admin.campaigns.list(),
  })

  const { data: collectionsData } = useQuery({
    queryKey: ['campaign-collections'],
    queryFn: () => keltaClient.admin.collections.list(),
    staleTime: 5 * 60 * 1000,
  })

  const {
    data: statsData,
    isLoading: statsLoading,
    error: statsError,
  } = useQuery({
    queryKey: ['campaign-stats', statsCampaign?.id],
    queryFn: () => keltaClient.admin.campaigns.stats(statsCampaign!.id),
    enabled: !!statsCampaign,
  })

  const {
    data: suppressionsData,
    isLoading: suppressionsLoading,
    error: suppressionsError,
  } = useQuery({
    queryKey: ['campaign-suppressions'],
    queryFn: () => keltaClient.admin.campaigns.listSuppressions(),
  })

  const collections: CollectionOption[] = useMemo(() => {
    return (
      (collectionsData ?? []) as Array<{
        id?: string
        name: string
        displayName?: string
      }>
    ).map((c) => ({
      id: c.id ?? c.name,
      name: c.name,
      displayName: c.displayName ?? c.name,
    }))
  }, [collectionsData])

  const targetCollectionOptions = useMemo(
    () => collections.map((c) => ({ value: c.name, label: c.displayName })),
    [collections]
  )

  const campaignList: Campaign[] = campaigns ?? []
  const suppressionList: EmailSuppression[] = suppressionsData ?? []

  const invalidateCampaigns = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['campaigns'] })
  }, [queryClient])

  const crud = useAdminCrud<Campaign, CampaignFormData>({
    queryKey: ['campaigns'],
    create: (data) => keltaClient.admin.campaigns.create(buildRequest(data)),
    update: (campaign, data) => keltaClient.admin.campaigns.update(campaign.id, buildRequest(data)),
    remove: (campaign) => keltaClient.admin.campaigns.delete(campaign.id),
    messages: {
      created: 'Campaign created successfully',
      updated: 'Campaign updated successfully',
      deleted: 'Campaign deleted successfully',
      error: 'An error occurred',
    },
  })

  const sendMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.campaigns.send(id),
    onSuccess: () => {
      invalidateCampaigns()
      toast.success('Campaign queued for sending')
    },
    onError: (err: Error) => toast.error(err.message || 'An error occurred'),
  })

  const scheduleMutation = useMutation({
    mutationFn: ({ id, scheduledAt }: { id: string; scheduledAt: string }) =>
      keltaClient.admin.campaigns.schedule(id, scheduledAt),
    onSuccess: () => {
      invalidateCampaigns()
      toast.success('Campaign scheduled')
    },
    onError: (err: Error) => toast.error(err.message || 'An error occurred'),
  })

  const cancelMutation = useMutation({
    mutationFn: (id: string) => keltaClient.admin.campaigns.cancel(id),
    onSuccess: () => {
      invalidateCampaigns()
      toast.success('Campaign cancelled')
    },
    onError: (err: Error) => toast.error(err.message || 'An error occurred'),
  })

  const testMutation = useMutation({
    mutationFn: ({ id, email }: { id: string; email: string }) =>
      keltaClient.admin.campaigns.test(id, email),
    onSuccess: () => toast.success('Test email sent'),
    onError: (err: Error) => toast.error(err.message || 'An error occurred'),
  })

  const addSuppressionMutation = useMutation({
    mutationFn: ({ email, reason }: { email: string; reason: string }) =>
      keltaClient.admin.campaigns.addSuppression(email, reason || undefined),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['campaign-suppressions'] })
      toast.success('Suppression added')
    },
    onError: (err: Error) => toast.error(err.message || 'An error occurred'),
  })

  const removeSuppressionMutation = useMutation({
    mutationFn: (email: string) => keltaClient.admin.campaigns.removeSuppression(email),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['campaign-suppressions'] })
      toast.success('Suppression removed')
    },
    onError: (err: Error) => toast.error(err.message || 'An error occurred'),
  })

  const handleSchedule = useCallback(
    (campaign: Campaign) => {
      const input = window.prompt(
        'Schedule send date/time (ISO, e.g. 2026-08-01T09:00):',
        campaign.scheduledAt ?? ''
      )
      if (input) {
        const iso = new Date(input).toISOString()
        scheduleMutation.mutate({ id: campaign.id, scheduledAt: iso })
      }
    },
    [scheduleMutation]
  )

  const handleTest = useCallback(
    (campaign: Campaign) => {
      const email = window.prompt('Send a test to which email address?')
      if (email && email.trim()) {
        testMutation.mutate({ id: campaign.id, email: email.trim() })
      }
    },
    [testMutation]
  )

  const columns = useMemo<AdminColumn<Campaign>[]>(
    () => [
      { id: 'name', header: 'Name', accessor: (r) => r.name },
      {
        id: 'targetCollection',
        header: 'Target',
        accessor: (r) => r.targetCollection,
      },
      {
        id: 'status',
        header: 'Status',
        accessor: (r) => r.status,
        cell: (r) => (
          <span className="inline-block rounded-full bg-muted px-3 py-1 text-xs font-semibold text-muted-foreground">
            {r.status}
          </span>
        ),
      },
      {
        id: 'sent',
        header: 'Sent',
        accessor: (r) => r.sentCount ?? 0,
        cell: (r) => String(r.sentCount ?? 0),
      },
      {
        id: 'opens',
        header: 'Opens',
        accessor: (r) => r.openCount ?? 0,
        cell: (r) => String(r.openCount ?? 0),
      },
      {
        id: 'clicks',
        header: 'Clicks',
        accessor: (r) => r.clickCount ?? 0,
        cell: (r) => String(r.clickCount ?? 0),
      },
      {
        id: 'scheduledAt',
        header: 'Scheduled',
        accessor: (r) => r.scheduledAt ?? '',
        cell: (r) =>
          r.scheduledAt
            ? formatDate(new Date(r.scheduledAt), {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
              })
            : '-',
      },
    ],
    [formatDate]
  )

  const isSuppressionMutating =
    addSuppressionMutation.isPending || removeSuppressionMutation.isPending

  return (
    <AdminCrudPage
      testId={testId}
      title="Campaigns"
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label="New Campaign"
          data-testid="add-campaign-button"
        >
          New Campaign
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={campaignList.length === 0}
      emptyMessage="No campaigns found."
      list={
        <AdminDataTable
          tableId="campaigns"
          testId="campaigns-table"
          ariaLabel="Campaigns"
          rows={campaignList}
          rowKey={(c) => c.id}
          columns={columns}
          renderActions={(campaign, index) => {
            const editable = EDITABLE_STATUSES.has(campaign.status)
            const cancellable = campaign.status === 'SCHEDULED' || campaign.status === 'QUEUED'
            return (
              <div className="flex flex-wrap justify-end gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => sendMutation.mutate(campaign.id)}
                  aria-label={`Send ${campaign.name}`}
                  data-testid={`send-button-${index}`}
                >
                  Send now
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => handleSchedule(campaign)}
                  aria-label={`Schedule ${campaign.name}`}
                  data-testid={`schedule-button-${index}`}
                >
                  Schedule
                </Button>
                {cancellable && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => cancelMutation.mutate(campaign.id)}
                    aria-label={`Cancel ${campaign.name}`}
                    data-testid={`cancel-button-${index}`}
                  >
                    Cancel
                  </Button>
                )}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setStatsCampaign(campaign)}
                  aria-label={`View stats for ${campaign.name}`}
                  data-testid={`stats-button-${index}`}
                >
                  Stats
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => handleTest(campaign)}
                  aria-label={`Send test for ${campaign.name}`}
                  data-testid={`test-button-${index}`}
                >
                  Send test
                </Button>
                {editable && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => crud.openEdit(campaign)}
                    aria-label={`Edit ${campaign.name}`}
                    data-testid={`edit-button-${index}`}
                  >
                    Edit
                  </Button>
                )}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="border-destructive/30 text-destructive hover:bg-destructive/10"
                  onClick={() => crud.requestDelete(campaign)}
                  aria-label={`Delete ${campaign.name}`}
                  data-testid={`delete-button-${index}`}
                >
                  Delete
                </Button>
              </div>
            )
          }}
        />
      }
    >
      <SuppressionsSection
        suppressions={suppressionList}
        isLoading={suppressionsLoading}
        error={suppressionsError instanceof Error ? suppressionsError : null}
        onAdd={(email, reason) => addSuppressionMutation.mutate({ email, reason })}
        onRemove={(email) => removeSuppressionMutation.mutate(email)}
        isMutating={isSuppressionMutating}
      />

      <CrudFormDialog<CampaignFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={crud.editing ? 'Edit Campaign' : 'New Campaign'}
        schema={campaignSchema}
        defaultValues={toFormData(crud.editing)}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="campaign"
        submittingLabel="Saving…"
        className="sm:max-w-[720px]"
      >
        <CrudTextField<CampaignFormData>
          name="name"
          id="campaign-name"
          label="Name"
          required
          placeholder="Enter campaign name"
          testId="campaign-name-input"
        />
        <CrudTextField<CampaignFormData>
          name="description"
          id="campaign-description"
          label="Description"
          multiline
          rows={2}
          placeholder="Enter description (optional)"
          testId="campaign-description-input"
        />
        <CrudTextField<CampaignFormData>
          name="subject"
          id="campaign-subject"
          label="Subject"
          required
          placeholder="Enter email subject"
          testId="campaign-subject-input"
        />
        <CrudTextField<CampaignFormData>
          name="bodyHtml"
          id="campaign-body"
          label="Body"
          multiline
          rows={5}
          placeholder="Compose your email body (HTML supported)"
          testId="campaign-body-input"
        />
        <div className="grid grid-cols-2 gap-4">
          <CrudSelectField<CampaignFormData>
            name="targetCollection"
            id="campaign-target-collection"
            label="Target collection"
            required
            placeholder="— Select —"
            options={targetCollectionOptions}
            testId="campaign-target-collection-input"
          />
          <CrudTextField<CampaignFormData>
            name="recipientEmailField"
            id="campaign-recipient-field"
            label="Recipient email field"
            required
            placeholder="e.g. email"
            testId="campaign-recipient-field-input"
          />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <CrudTextField<CampaignFormData>
            name="fromName"
            id="campaign-from-name"
            label="From name"
            placeholder="e.g. Acme Team"
            testId="campaign-from-name-input"
          />
          <CrudTextField<CampaignFormData>
            name="fromAddress"
            id="campaign-from-address"
            label="From address"
            type="email"
            placeholder="e.g. hello@acme.com"
            testId="campaign-from-address-input"
          />
        </div>
        <CrudTextField<CampaignFormData>
          name="scheduledAt"
          id="campaign-scheduled-at"
          label="Schedule for (optional)"
          type="datetime-local"
          hint="Leave empty to keep the campaign as a draft."
          testId="campaign-scheduled-at-input"
        />
      </CrudFormDialog>

      {statsCampaign && (
        <StatsModal
          campaignName={statsCampaign.name}
          stats={statsData}
          isLoading={statsLoading}
          error={statsError instanceof Error ? statsError : null}
          onClose={() => setStatsCampaign(null)}
        />
      )}

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Campaign"
        message="Are you sure you want to delete this campaign? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}
