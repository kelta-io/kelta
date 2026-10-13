import { useState } from 'react'
import { toast } from 'sonner'
import { Archive, Download } from 'lucide-react'
import { useI18n } from '../../../context/I18nContext'
import { useArchiveDetail, type ArchiveSummary } from '../../../hooks/useArchives'

/**
 * Read-only banner shown at the top of an ARCHIVED conversation thread (slice
 * 7). States the archive/retention dates and offers PDF/JSON downloads via the
 * presigned URLs returned by the archive detail endpoint (each fetch is
 * server-side audited). Rendered only when an archive row exists for the
 * conversation.
 */
export function ArchivedThreadBanner({
  archive,
  testId = 'chat-console-archive-banner',
}: {
  archive: ArchiveSummary
  testId?: string
}) {
  const { t, formatDate } = useI18n()
  const [wantDetail, setWantDetail] = useState(false)
  const detail = useArchiveDetail(wantDetail ? archive.id : null)

  const banner = t('telehealth.archive.bannerArchived', {
    archivedAt: archive.archivedAt
      ? formatDate(new Date(archive.archivedAt), { dateStyle: 'medium' })
      : '—',
    retentionUntil: archive.retentionUntil
      ? formatDate(new Date(archive.retentionUntil), { dateStyle: 'medium' })
      : '—',
  })

  const download = (contentType: string) => {
    setWantDetail(true)
    const artifacts = detail.data?.artifacts ?? []
    const match = artifacts.find((a) => a.contentType === contentType)
    if (match?.downloadUrl) {
      window.open(match.downloadUrl, '_blank', 'noopener,noreferrer')
    } else if (detail.data) {
      // Detail loaded but the artifact/URL is unavailable (e.g. S3 disabled).
      toast.error(t('telehealth.archive.downloadError'))
    }
    // If detail hasn't loaded yet, the next click (after fetch resolves) opens it.
  }

  return (
    <div
      className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-muted/40 px-4 py-2"
      data-testid={testId}
    >
      <span className="flex items-center gap-2 text-xs text-muted-foreground">
        <Archive size={14} />
        {banner}
      </span>
      <div className="flex gap-2">
        <button
          className="flex cursor-pointer items-center gap-1 rounded-md border border-border bg-card px-2 py-1 text-xs hover:bg-muted disabled:opacity-50"
          onClick={() => download('application/pdf')}
          disabled={detail.isFetching}
          data-testid={`${testId}-pdf`}
        >
          <Download size={12} />
          {t('telehealth.archive.downloadPdf')}
        </button>
        <button
          className="flex cursor-pointer items-center gap-1 rounded-md border border-border bg-card px-2 py-1 text-xs hover:bg-muted disabled:opacity-50"
          onClick={() => download('application/json')}
          disabled={detail.isFetching}
          data-testid={`${testId}-json`}
        >
          <Download size={12} />
          {t('telehealth.archive.downloadJson')}
        </button>
      </div>
    </div>
  )
}
