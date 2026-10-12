import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { CheckSquare, Inbox } from 'lucide-react'
import { Badge } from '../../../components/ui/badge'
import { Button } from '../../../components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../../components/ui/tabs'
import { ApprovalActionDialog } from '../../../components/ApprovalActionDialog'
import { ConfirmDialog } from '../../../components/ConfirmDialog/ConfirmDialog'
import { useApi } from '../../../context/ApiContext'
import { useI18n } from '../../../context/I18nContext'
import { useMyIdentity } from '../../../hooks/useMyIdentity'
import { recordRoutes } from '../../../utils/recordRoutes'
import {
  useMyApprovals,
  type PendingApprovalRow,
  type SubmissionRow,
} from '../../../hooks/useMyApprovals'

interface ActionTarget {
  instanceId: string
  mode: 'approve' | 'reject'
}

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'destructive'> = {
  PENDING: 'secondary',
  APPROVED: 'default',
  REJECTED: 'destructive',
  RECALLED: 'secondary',
}

/**
 * End-user approvals inbox (/app/approvals): steps pending on the viewer and the
 * approvals they submitted, with approve/reject (comment dialog) and recall.
 */
export function ApprovalsInboxPage() {
  const { apiClient } = useApi()
  const { t, formatDate } = useI18n()
  const navigate = useNavigate()
  const { tenantSlug } = useParams<{ tenantSlug: string }>()
  const { identity } = useMyIdentity()
  const { pending, submissions, pendingCount, isLoading, invalidate } = useMyApprovals(
    identity?.userId
  )

  const [actionTarget, setActionTarget] = useState<ActionTarget | null>(null)
  const [recallTarget, setRecallTarget] = useState<string | null>(null)

  const actionMutation = useMutation({
    mutationFn: async (args: {
      instanceId: string
      mode: 'approve' | 'reject'
      comment?: string
    }) =>
      apiClient.post(`/api/approvals/${args.instanceId}/${args.mode}`, {
        comments: args.comment,
      }),
    onSuccess: (_data, args) => {
      toast.success(
        args.mode === 'approve'
          ? t('approvals.approved', 'Approved successfully')
          : t('approvals.rejected', 'Rejected successfully')
      )
      setActionTarget(null)
      invalidate()
    },
    onError: () => {
      toast.error(
        t('approvalsInbox.actionFailed', 'Action failed — the step may no longer be pending')
      )
      setActionTarget(null)
      invalidate()
    },
  })

  const recallMutation = useMutation({
    mutationFn: async (instanceId: string) =>
      apiClient.post(`/api/approvals/${instanceId}/recall`, {}),
    onSuccess: () => {
      toast.success(t('approvals.recalled', 'Recalled successfully'))
      setRecallTarget(null)
      invalidate()
    },
    onError: () => {
      toast.error(
        t('approvalsInbox.actionFailed', 'Action failed — the step may no longer be pending')
      )
      setRecallTarget(null)
      invalidate()
    },
  })

  const openRecord = (row: { collectionName: string | null; recordId: string | null }) => {
    if (row.collectionName && row.recordId) {
      navigate(recordRoutes('enduser', tenantSlug ?? '').detail(row.collectionName, row.recordId))
    }
  }

  const renderPendingRow = (row: PendingApprovalRow) => (
    <div
      key={row.stepInstanceId}
      role="button"
      tabIndex={0}
      className="flex items-center justify-between gap-4 border-b border-border px-4 py-3 hover:bg-primary/10 cursor-pointer"
      onClick={() => openRecord(row)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          openRecord(row)
        }
      }}
      data-testid="pending-approval-row"
    >
      <div className="min-w-0">
        <div className="text-sm font-medium truncate">
          {row.collectionName ?? t('approvalsInbox.unknownCollection', 'Record')}
          {row.recordId ? ` · ${row.recordId.slice(0, 8)}` : ''}
        </div>
        <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
          {row.submittedAt
            ? t('approvalsInbox.waitingSince', {
                time: formatDate(new Date(row.submittedAt), {
                  dateStyle: 'medium',
                  timeStyle: 'short',
                }),
              })
            : '—'}
        </div>
      </div>
      <div className="flex shrink-0 gap-2">
        <Button
          size="sm"
          onClick={(e) => {
            e.stopPropagation()
            setActionTarget({ instanceId: row.instanceId, mode: 'approve' })
          }}
          data-testid="approve-button"
        >
          {t('approvals.approve', 'Approve')}
        </Button>
        <Button
          size="sm"
          variant="destructive"
          onClick={(e) => {
            e.stopPropagation()
            setActionTarget({ instanceId: row.instanceId, mode: 'reject' })
          }}
          data-testid="reject-button"
        >
          {t('approvals.reject', 'Reject')}
        </Button>
      </div>
    </div>
  )

  const renderSubmissionRow = (row: SubmissionRow) => (
    <div
      key={row.instanceId}
      role="button"
      tabIndex={0}
      className="flex items-center justify-between gap-4 border-b border-border px-4 py-3 hover:bg-primary/10 cursor-pointer"
      onClick={() => openRecord(row)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          openRecord(row)
        }
      }}
      data-testid="submission-row"
    >
      <div className="min-w-0">
        <div className="text-sm font-medium truncate">
          {row.collectionName ?? t('approvalsInbox.unknownCollection', 'Record')}
          {row.recordId ? ` · ${row.recordId.slice(0, 8)}` : ''}
        </div>
        <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
          {row.submittedAt
            ? formatDate(new Date(row.submittedAt), { dateStyle: 'medium', timeStyle: 'short' })
            : '—'}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <Badge variant={STATUS_VARIANT[row.status] ?? 'secondary'}>{row.status}</Badge>
        {row.status === 'PENDING' && (
          <Button
            size="sm"
            variant="outline"
            onClick={(e) => {
              e.stopPropagation()
              setRecallTarget(row.instanceId)
            }}
            data-testid="recall-button"
          >
            {t('approvals.recall', 'Recall')}
          </Button>
        )}
      </div>
    </div>
  )

  return (
    <main role="main" className="mx-auto w-full max-w-[1180px] px-4 py-6">
      <div className="mb-4 flex items-center gap-2">
        <CheckSquare className="h-5 w-5" aria-hidden />
        <h1 className="text-[26px] font-bold tracking-[-0.01em]">
          {t('approvalsInbox.title', 'Approvals')}
        </h1>
      </div>

      <Tabs defaultValue="pending">
        <TabsList>
          <TabsTrigger value="pending" data-testid="tab-pending">
            {t('approvalsInbox.pendingTab', 'Pending on me')}
            {pendingCount > 0 ? ` (${pendingCount})` : ''}
          </TabsTrigger>
          <TabsTrigger value="submissions" data-testid="tab-submissions">
            {t('approvalsInbox.submissionsTab', 'My submissions')}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="pending">
          <div className="rounded-[10px] border border-border bg-card overflow-hidden">
            {isLoading ? (
              <div className="p-8 text-center text-sm text-muted-foreground">
                {t('common.loading', 'Loading...')}
              </div>
            ) : pending.length === 0 ? (
              <div
                className="p-8 text-center text-sm text-muted-foreground"
                data-testid="pending-empty"
              >
                <Inbox className="mx-auto mb-2 h-6 w-6" aria-hidden />
                {t('approvalsInbox.pendingEmpty', 'No approvals waiting on you.')}
              </div>
            ) : (
              pending.map(renderPendingRow)
            )}
          </div>
        </TabsContent>

        <TabsContent value="submissions">
          <div className="rounded-[10px] border border-border bg-card overflow-hidden">
            {isLoading ? (
              <div className="p-8 text-center text-sm text-muted-foreground">
                {t('common.loading', 'Loading...')}
              </div>
            ) : submissions.length === 0 ? (
              <div
                className="p-8 text-center text-sm text-muted-foreground"
                data-testid="submissions-empty"
              >
                {t(
                  'approvalsInbox.submissionsEmpty',
                  'You have not submitted anything for approval.'
                )}
              </div>
            ) : (
              submissions.map(renderSubmissionRow)
            )}
          </div>
        </TabsContent>
      </Tabs>

      <ApprovalActionDialog
        open={actionTarget !== null}
        mode={actionTarget?.mode ?? 'approve'}
        isPending={actionMutation.isPending}
        onConfirm={(comment) =>
          actionTarget &&
          actionMutation.mutate({
            instanceId: actionTarget.instanceId,
            mode: actionTarget.mode,
            comment,
          })
        }
        onCancel={() => setActionTarget(null)}
      />
      <ConfirmDialog
        open={recallTarget !== null}
        title={t('approvals.recall', 'Recall')}
        message={t('approvalsInbox.recallConfirm', 'Withdraw this record from approval?')}
        confirmLabel={t('approvals.recall', 'Recall')}
        onConfirm={() => recallTarget && recallMutation.mutate(recallTarget)}
        onCancel={() => setRecallTarget(null)}
      />
    </main>
  )
}
