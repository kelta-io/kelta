/**
 * RecordActionsBar Component
 *
 * Provides a complete actions bar for record detail pages with:
 * - Back navigation button
 * - Primary edit button
 * - Favorite toggle button
 * - Dropdown menu with clone, submit for approval, and delete actions
 * - Approval status indicator pill
 *
 * Supports keyboard navigation, click-outside closing, and i18n.
 */

import React, { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ArrowLeft, Star, ChevronDown } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'
import { useRecordRoutes } from '../../context/RecordStackContext'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu'

import { useToast } from '../../components'
import type { ApiClient } from '../../services/apiClient'

/**
 * Approval instance interface for tracking approval status
 */
interface ApprovalInstance {
  id: string
  tenantId: string
  collectionId: string
  recordId: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | string
}

/**
 * Props for the RecordActionsBar component
 */
export interface RecordActionsBarProps {
  /** Collection name for the current record */
  collectionName: string
  /** ID of the current record */
  recordId: string
  /** Callback when edit button is clicked */
  onEdit: () => void
  /** Callback when delete action is selected */
  onDelete: () => void
  /** Callback when back button is clicked */
  onBack: () => void
  /** Whether this record is currently favorited */
  isFavorite: boolean
  /** Callback to toggle favorite status */
  onToggleFavorite: () => void
  /** API client instance for making requests */
  apiClient: ApiClient
}

/**
 * RecordActionsBar Component
 *
 * Renders a toolbar with back, edit, favorite, and dropdown actions
 * for a record detail page. Includes approval status display and
 * submission capability.
 */
export function RecordActionsBar({
  collectionName,
  recordId,
  onEdit,
  onDelete,
  onBack,
  isFavorite,
  onToggleFavorite,
  apiClient,
}: RecordActionsBarProps): React.ReactElement {
  const navigate = useNavigate()
  const { t } = useI18n()
  const { showToast } = useToast()
  const routes = useRecordRoutes(getTenantSlug())

  // Clone action — the create form of the record stack this bar renders in, seeded from it
  const handleClone = useCallback(() => {
    navigate(routes.clone(collectionName, recordId))
  }, [navigate, routes, collectionName, recordId])

  // Submit for approval mutation
  const submitApprovalMutation = useMutation({
    mutationFn: async () => {
      return apiClient.postResource(`/api/approval-instances`, {
        collectionId: collectionName,
        recordId: recordId,
        processId: '',
        userId: 'system',
      })
    },
    onSuccess: () => {
      showToast(t('recordActions.approvalSubmitted'), 'success')
    },
    onError: () => {
      showToast(t('recordActions.approvalError'), 'error')
    },
  })

  // Submit for approval action
  const handleSubmitForApproval = useCallback(() => {
    submitApprovalMutation.mutate()
  }, [submitApprovalMutation])

  // Query approval status
  const { data: approvalInstances } = useQuery({
    queryKey: ['approval-instances', collectionName, recordId],
    queryFn: async () => {
      const result = await apiClient.getList<ApprovalInstance>(`/api/approval-instances`)
      return result
    },
    enabled: !!collectionName && !!recordId,
  })

  // Find approval instance for this record
  const recordApproval = Array.isArray(approvalInstances)
    ? approvalInstances.find((instance) => instance.recordId === recordId)
    : undefined

  // Determine approval pill style and text
  const getApprovalBadgeVariant = (status: string) => {
    switch (status) {
      case 'APPROVED':
        return 'default' as const
      case 'REJECTED':
        return 'destructive' as const
      default:
        return 'secondary' as const
    }
  }

  const getApprovalPillText = (status: string): string => {
    switch (status) {
      case 'PENDING':
        return t('recordActions.pendingApproval')
      case 'APPROVED':
        return t('recordActions.approvedStatus')
      case 'REJECTED':
        return t('recordActions.rejectedStatus')
      default:
        return status
    }
  }

  return (
    <div
      className="flex justify-between items-center gap-4 flex-wrap max-md:flex-col max-md:items-stretch"
      data-testid="record-actions-bar"
    >
      {/* Left section - Back button */}
      <div className="flex items-center gap-2 max-md:order-2">
        <Button
          variant="outline"
          onClick={onBack}
          aria-label={t('common.back')}
          data-testid="actions-back-button"
          className="max-md:w-full"
        >
          <ArrowLeft size={16} /> {t('common.back')}
        </Button>

        {/* Approval status pill */}
        {recordApproval && (
          <Badge
            variant={getApprovalBadgeVariant(recordApproval.status)}
            className={cn(
              recordApproval.status === 'PENDING' &&
                'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
              recordApproval.status === 'APPROVED' &&
                'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
              recordApproval.status === 'REJECTED' &&
                'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200'
            )}
            data-testid="approval-status-pill"
          >
            {getApprovalPillText(recordApproval.status)}
          </Badge>
        )}
      </div>

      {/* Right section - Edit, Favorite, Dropdown */}
      <div className="flex items-center gap-2 max-md:order-1 max-md:justify-end">
        <Button
          onClick={onEdit}
          aria-label={t('recordActions.edit')}
          data-testid="actions-edit-button"
        >
          {t('recordActions.edit')}
        </Button>

        <Button
          variant="outline"
          size="icon"
          onClick={onToggleFavorite}
          aria-label={isFavorite ? t('favorites.remove') : t('favorites.add')}
          aria-pressed={isFavorite}
          data-testid="actions-favorite-button"
          title={isFavorite ? t('favorites.remove') : t('favorites.add')}
          className={cn(
            'hover:text-amber-500 hover:border-amber-500',
            isFavorite && 'text-amber-500'
          )}
        >
          <Star size={16} fill={isFavorite ? 'currentColor' : 'none'} />
        </Button>

        {/* Dropdown menu */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="outline"
              size="icon"
              aria-label={t('recordActions.moreActions')}
              data-testid="actions-dropdown-toggle"
            >
              <ChevronDown size={16} />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" data-testid="actions-dropdown-menu">
            {/* Clone */}
            <DropdownMenuItem onClick={handleClone} data-testid="actions-clone">
              {t('recordActions.clone')}
            </DropdownMenuItem>

            <DropdownMenuSeparator />

            {/* Submit for Approval */}
            <DropdownMenuItem
              onClick={handleSubmitForApproval}
              data-testid="actions-submit-approval"
            >
              {t('recordActions.submitForApproval')}
            </DropdownMenuItem>

            <DropdownMenuSeparator />

            {/* Delete */}
            <DropdownMenuItem variant="destructive" onClick={onDelete} data-testid="actions-delete">
              {t('recordActions.delete')}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}

export default RecordActionsBar
