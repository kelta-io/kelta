/**
 * AttachmentsSection Component
 *
 * Displays file attachments associated with a record on the detail page.
 *
 * Features:
 * - Fetches attachments from the API with graceful 404 fallback ("coming soon")
 * - Compact list view with file-type icon or image thumbnail, name, size, and date
 * - Click-to-preview dialog for browser-renderable file types
 * - Download link per attachment
 * - Delete action per attachment
 * - Upload button
 * - Accessible with ARIA attributes and data-testid markers
 */

import React, { useState, useCallback, useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Download, Trash2, Upload } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import type { ApiClient } from '../../services/apiClient'
import { getFileTypeInfo, isImageType, formatFileSize } from './fileTypeUtils'
import { FileViewer } from './FileViewer'
import type { FileViewerAttachment } from './FileViewer'
import type { Attachment } from '../../hooks/useRecordContext'
import { formatRelativeTime } from '../../utils/formatRelativeTime'

/**
 * Props for the AttachmentsSection component
 */
export interface AttachmentsSectionProps {
  /** UUID of the collection */
  collectionId: string
  /** UUID of the record */
  recordId: string
  /** Authenticated API client instance */
  apiClient: ApiClient
  /** Pre-fetched attachments data (from useRecordContext) */
  attachments?: Attachment[]
  /** Callback to invalidate the parent query cache */
  onMutate?: () => void
}

/**
 * Renders the file icon area: an image thumbnail for image types, or
 * a colored file-type icon for other types.
 */
function AttachmentIcon({ attachment }: { attachment: Attachment }) {
  const [imgError, setImgError] = useState(false)
  const info = getFileTypeInfo(attachment.contentType)
  const Icon = info.icon

  // For images with a valid download URL, show a thumbnail
  if (isImageType(attachment.contentType) && attachment.downloadUrl && !imgError) {
    return (
      <div
        className="flex items-center justify-center w-8 h-8 shrink-0 rounded overflow-hidden bg-muted"
        aria-hidden="true"
      >
        <img
          src={attachment.downloadUrl}
          alt=""
          className="w-8 h-8 object-cover"
          loading="lazy"
          onError={() => setImgError(true)}
          data-testid={`attachment-thumbnail-${attachment.id}`}
        />
      </div>
    )
  }

  // For non-image types or image load failures, show a colored icon
  return (
    <div
      className={cn(
        'flex items-center justify-center w-8 h-8 shrink-0 rounded bg-muted',
        info.color
      )}
      aria-hidden="true"
      data-testid={`attachment-icon-${attachment.id}`}
    >
      <Icon size={16} />
    </div>
  )
}

/**
 * AttachmentsSection Component
 *
 * Displays and manages file attachments for a specific record. If the
 * backend attachments API is not yet available (404), a "coming soon"
 * placeholder is shown instead.
 *
 * @example
 * ```tsx
 * <AttachmentsSection
 *   collectionId="abc-123"
 *   recordId="def-456"
 *   apiClient={apiClient}
 * />
 * ```
 */
export function AttachmentsSection({
  collectionId,
  recordId,
  apiClient,
  attachments: attachmentsProp,
  onMutate,
}: AttachmentsSectionProps): React.ReactElement {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [viewerAttachment, setViewerAttachment] = useState<FileViewerAttachment | null>(null)

  // Use pre-fetched data from props (via useRecordContext)
  const attachments = attachmentsProp

  const invalidateCache = useCallback(() => {
    // Invalidate both the combined record-context and legacy attachments queries
    queryClient.invalidateQueries({ queryKey: ['record-context', collectionId, recordId] })
    queryClient.invalidateQueries({ queryKey: ['attachments', collectionId, recordId] })
    onMutate?.()
  }, [queryClient, collectionId, recordId, onMutate])

  // Delete attachment mutation
  const deleteMutation = useMutation({
    mutationFn: async (attachmentId: string) => {
      return apiClient.deleteResource(`/api/attachments/${attachmentId}`)
    },
    onSuccess: () => {
      invalidateCache()
    },
  })

  // Upload attachment mutation — 3-step presigned URL flow
  const uploadMutation = useMutation({
    mutationFn: async (file: File) => {
      // Step 1: request presigned S3 PUT URL
      const uploadUrlResp = await apiClient.post<{
        data: {
          id: string
          attributes: { uploadUrl: string; headers: Record<string, string> }
        }
      }>('/api/attachments/upload-url', {
        fileName: file.name,
        contentType: file.type || 'application/octet-stream',
        fileSize: file.size,
        collectionId,
        recordId,
      })
      const { id: attachmentId, attributes } = uploadUrlResp.data
      const { uploadUrl, headers } = attributes

      // Step 2: PUT bytes directly to S3 via raw fetch
      // (apiClient would attach tenant/auth headers that S3 would reject)
      const s3Resp = await fetch(uploadUrl, {
        method: 'PUT',
        headers,
        body: file,
      })
      if (!s3Resp.ok) {
        throw new Error(`S3 upload failed: ${s3Resp.status} ${s3Resp.statusText}`)
      }

      // Step 3: finalize
      return apiClient.post<Attachment>(`/api/attachments/${attachmentId}/finalize`)
    },
    onSuccess: () => {
      invalidateCache()
    },
  })

  const handleDelete = useCallback(
    (attachmentId: string) => {
      if (window.confirm(t('attachments.confirmDelete'))) {
        deleteMutation.mutate(attachmentId)
      }
    },
    [deleteMutation, t]
  )

  const handleFileChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0]
      if (file) {
        uploadMutation.mutate(file)
        // Reset input so the same file can be uploaded again
        event.target.value = ''
      }
    },
    [uploadMutation]
  )

  const handleUploadClick = useCallback(() => {
    fileInputRef.current?.click()
  }, [])

  const handleDownload = useCallback(
    async (attachment: Attachment | FileViewerAttachment) => {
      if (attachment.downloadUrl) {
        window.open(attachment.downloadUrl, '_blank')
      } else {
        try {
          const response = await apiClient.getOne<{ url: string }>(
            `/api/attachments/${attachment.id}`
          )
          if (response?.url) {
            window.open(response.url, '_blank')
          }
        } catch {
          // Fallback: attempt direct navigation
          window.open(`/api/attachments/${attachment.id}`, '_blank')
        }
      }
    },
    [apiClient]
  )

  const handlePreview = useCallback((attachment: Attachment) => {
    setViewerAttachment(attachment)
  }, [])

  const handleCloseViewer = useCallback(() => {
    setViewerAttachment(null)
  }, [])

  // Sort attachments by upload date (newest first)
  const attachmentsList = Array.isArray(attachments) ? attachments : []
  const sortedAttachments = [...attachmentsList].sort(
    (a, b) => new Date(b.uploadedAt).getTime() - new Date(a.uploadedAt).getTime()
  )

  return (
    <section
      className="bg-background border border-border rounded-lg overflow-hidden"
      aria-labelledby="attachments-heading"
      data-testid="attachments-section"
    >
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={handleFileChange}
        data-testid="attachments-file-input"
      />

      {/* Header */}
      <div className="flex justify-between items-center p-4 border-b border-border bg-muted/50 max-md:flex-col max-md:items-start max-md:gap-2">
        <h3 id="attachments-heading" className="m-0 text-base font-semibold text-foreground">
          {t('attachments.title')}
        </h3>
        <Button
          variant="outline"
          size="xs"
          onClick={handleUploadClick}
          disabled={uploadMutation.isPending}
          data-testid="attachments-upload-button"
          title={t('attachments.upload')}
        >
          <Upload size={14} aria-hidden="true" />
          {uploadMutation.isPending ? t('attachments.uploading') : t('attachments.upload')}
        </Button>
      </div>

      {/* Attachments List */}
      {sortedAttachments.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center px-6 py-8 text-center"
          data-testid="attachments-empty"
        >
          <p className="m-0 text-sm text-muted-foreground">{t('attachments.empty')}</p>
        </div>
      ) : (
        <div className="flex flex-col" role="list" aria-label={t('attachments.title')}>
          {sortedAttachments.map((attachment, index) => (
            <div
              key={attachment.id}
              className={cn(
                'flex items-center gap-2 px-4 py-2 max-md:px-2',
                'transition-colors motion-reduce:transition-none hover:bg-muted/50',
                index < sortedAttachments.length - 1 && 'border-b border-border'
              )}
              role="listitem"
              data-testid={`attachment-${attachment.id}`}
            >
              {/* File Icon / Thumbnail — clickable to preview */}
              <button
                type="button"
                className="flex items-center gap-2 min-w-0 flex-1 bg-transparent border-0 p-0 cursor-pointer text-left"
                onClick={() => handlePreview(attachment)}
                data-testid={`attachment-preview-${attachment.id}`}
              >
                <AttachmentIcon attachment={attachment} />

                {/* File Info */}
                <div className="flex flex-col gap-0.5 min-w-0 flex-1">
                  <span className="text-sm font-medium text-foreground whitespace-nowrap overflow-hidden text-ellipsis">
                    {attachment.fileName}
                  </span>
                  <span className="flex items-center gap-1 text-xs text-muted-foreground max-md:flex-wrap">
                    {formatFileSize(attachment.fileSize)}
                    <span>&middot;</span>
                    <time dateTime={attachment.uploadedAt}>
                      {formatRelativeTime(attachment.uploadedAt, { style: 'long' })}
                    </time>
                    <span>&middot;</span>
                    <span>{attachment.uploadedBy}</span>
                  </span>
                </div>
              </button>

              {/* Actions */}
              <div className="flex gap-1 shrink-0">
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={() => handleDownload(attachment)}
                  disabled={!attachment.downloadUrl}
                  aria-label={`${t('attachments.download')} ${attachment.fileName}`}
                  data-testid={`attachment-download-${attachment.id}`}
                  title={t('attachments.download')}
                >
                  <Download size={14} />
                </Button>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  className="hover:text-destructive hover:bg-destructive/10"
                  onClick={() => handleDelete(attachment.id)}
                  aria-label={`${t('attachments.delete')} ${attachment.fileName}`}
                  data-testid={`attachment-delete-${attachment.id}`}
                  title={t('attachments.delete')}
                >
                  <Trash2 size={14} />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* File Viewer Dialog */}
      <FileViewer
        attachment={viewerAttachment}
        onClose={handleCloseViewer}
        onDownload={handleDownload}
      />
    </section>
  )
}
