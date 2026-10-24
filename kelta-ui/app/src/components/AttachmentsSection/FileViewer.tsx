/**
 * FileViewer Component
 *
 * A dialog that renders file content inline using native browser
 * capabilities. Supports images, PDFs, video, audio, and text/code
 * files without any additional dependencies.
 */

import { Download } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogDescription,
} from '@/components/ui/dialog'
import { getFileTypeInfo, formatFileSize } from './fileTypeUtils'

/**
 * Attachment data for the file viewer.
 */
export interface FileViewerAttachment {
  id: string
  fileName: string
  fileSize: number
  contentType: string
  downloadUrl?: string | null
}

/**
 * Props for the FileViewer component.
 */
export interface FileViewerProps {
  /** The attachment to preview, or null to close the dialog */
  attachment: FileViewerAttachment | null
  /** Callback when the dialog is closed */
  onClose: () => void
  /** Callback when the download button is clicked */
  onDownload: (attachment: FileViewerAttachment) => void
}

/**
 * Render the preview content based on file type.
 */
function PreviewContent({
  attachment,
  t,
}: {
  attachment: FileViewerAttachment
  t: (key: string) => string
}) {
  const { category } = getFileTypeInfo(attachment.contentType)
  const url = attachment.downloadUrl

  if (!url) {
    return <NoPreview attachment={attachment} t={t} />
  }

  switch (category) {
    case 'image':
      return (
        <div
          className="flex items-center justify-center min-h-[200px] max-h-[65vh] overflow-auto"
          data-testid="file-viewer-image"
        >
          <img
            src={url}
            alt={attachment.fileName}
            className="max-w-full max-h-[65vh] object-contain"
            loading="eager"
          />
        </div>
      )

    case 'pdf':
      return (
        <div className="min-h-[400px] h-[65vh]" data-testid="file-viewer-pdf">
          <iframe
            src={url}
            title={attachment.fileName}
            className="w-full h-full border-0 rounded"
          />
        </div>
      )

    case 'video':
      return (
        <div
          className="flex items-center justify-center min-h-[200px] max-h-[65vh]"
          data-testid="file-viewer-video"
        >
          {/* eslint-disable-next-line jsx-a11y/media-has-caption -- user-uploaded content, no captions available */}
          <video controls className="max-w-full max-h-[65vh] rounded" preload="metadata">
            <source src={url} type={attachment.contentType} />
            {t('fileViewer.videoNotSupported')}
          </video>
        </div>
      )

    case 'audio': {
      const info = getFileTypeInfo(attachment.contentType)
      const Icon = info.icon
      return (
        <div
          className="flex flex-col items-center justify-center gap-6 py-8"
          data-testid="file-viewer-audio"
        >
          <div className={`${info.color}`}>
            <Icon size={64} />
          </div>
          {/* eslint-disable-next-line jsx-a11y/media-has-caption -- user-uploaded content, no captions available */}
          <audio controls preload="metadata">
            <source src={url} type={attachment.contentType} />
            {t('fileViewer.audioNotSupported')}
          </audio>
        </div>
      )
    }

    case 'text':
    case 'code':
      return (
        <div className="min-h-[300px] h-[60vh]" data-testid="file-viewer-text">
          <iframe
            src={url}
            title={attachment.fileName}
            className="w-full h-full border border-border rounded bg-muted/30"
            // Empty is the most restrictive value, not the least: it drops the frame into an
            // opaque origin with scripting, forms and navigation all off.
            //
            // This previously read `allow-same-origin`, on a same-origin /api/files/** URL. That
            // handed the framed document this app's own origin. Scripts were still blocked, since
            // `allow-scripts` was absent — but the two tokens together are the documented sandbox
            // escape, and the missing one is exactly what someone adds to make a preview render
            // properly. Uploaded file content must never hold the app's origin, whatever else is
            // switched off alongside it.
            sandbox=""
            referrerPolicy="no-referrer"
          />
        </div>
      )

    default:
      return <NoPreview attachment={attachment} t={t} />
  }
}

/**
 * Fallback content when a file type cannot be previewed inline.
 */
function NoPreview({
  attachment,
  t,
}: {
  attachment: FileViewerAttachment
  t: (key: string) => string
}) {
  const info = getFileTypeInfo(attachment.contentType)
  const Icon = info.icon
  return (
    <div
      className="flex flex-col items-center justify-center gap-4 py-12"
      data-testid="file-viewer-no-preview"
    >
      <div className={`${info.color}`}>
        <Icon size={48} />
      </div>
      <p className="text-sm text-muted-foreground">{t('fileViewer.noPreview')}</p>
    </div>
  )
}

/**
 * FileViewer renders a dialog that previews files inline using
 * native browser capabilities.
 *
 * @example
 * ```tsx
 * <FileViewer
 *   attachment={selectedAttachment}
 *   onClose={() => setSelectedAttachment(null)}
 *   onDownload={handleDownload}
 * />
 * ```
 */
export function FileViewer({ attachment, onClose, onDownload }: FileViewerProps) {
  const { t } = useI18n()

  if (!attachment) return null

  return (
    <Dialog open={!!attachment} onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        className="sm:max-w-[90vw] max-h-[85vh] flex flex-col"
        data-testid="file-viewer-dialog"
      >
        <DialogHeader>
          <DialogTitle className="truncate pr-8">{attachment.fileName}</DialogTitle>
          <DialogDescription className="sr-only">
            {t('fileViewer.preview').replace('{{name}}', attachment.fileName)}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-auto min-h-0">
          <PreviewContent attachment={attachment} t={t} />
        </div>

        <DialogFooter className="sm:justify-between items-center">
          <span className="text-xs text-muted-foreground">
            {formatFileSize(attachment.fileSize)}
            {attachment.contentType && ` \u00B7 ${attachment.contentType}`}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onDownload(attachment)}
            data-testid="file-viewer-download"
          >
            <Download size={14} />
            {t('attachments.download')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
