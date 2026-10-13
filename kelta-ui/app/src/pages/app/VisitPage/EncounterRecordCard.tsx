import { toast } from 'sonner'
import { FileText, Download } from 'lucide-react'
import { useI18n } from '@/context/I18nContext'
import { useArchives, useArchiveDetail, type ArchiveSummary } from '../../../hooks/useArchives'

/**
 * Encounter-record card on the visit page (slice 7): lists the archived
 * encounter records (chat transcript + video visit summary) for one
 * appointment, each with a download. Rendered read-only; downloads use the
 * presigned URLs from the archive detail endpoint (server-side audited).
 */
export function EncounterRecordCard({
  appointmentId,
  testId = 'encounter-record',
}: {
  appointmentId: string
  testId?: string
}) {
  const { t } = useI18n()
  const archives = useArchives({}, true)
  const own = (archives.data ?? []).filter((a) => a.appointmentId === appointmentId)

  return (
    <div className="rounded-[10px] border border-border bg-card p-4" data-testid={testId}>
      <div className="mb-3 flex items-center gap-2">
        <FileText size={16} className="text-primary" />
        <div>
          <h2 className="m-0 text-sm font-semibold">{t('telehealth.archive.encounterTitle')}</h2>
          <p className="text-xs text-muted-foreground">
            {t('telehealth.archive.encounterSubtitle')}
          </p>
        </div>
      </div>
      {archives.isLoading ? (
        <p className="text-xs text-muted-foreground">{t('common.loading', 'Loading…')}</p>
      ) : own.length === 0 ? (
        <p className="text-xs text-muted-foreground">{t('telehealth.archive.noEncounter')}</p>
      ) : (
        <ul className="flex flex-col gap-2" data-testid={`${testId}-list`}>
          {own.map((archive) => (
            <EncounterRow key={archive.id} archive={archive} testId={`${testId}-${archive.id}`} />
          ))}
        </ul>
      )}
    </div>
  )
}

function EncounterRow({ archive, testId }: { archive: ArchiveSummary; testId: string }) {
  const { t, formatDate } = useI18n()
  const detail = useArchiveDetail(archive.id)

  const label =
    archive.sourceType === 'CONVERSATION'
      ? t('telehealth.archive.typeConversation')
      : t('telehealth.archive.typeVideo')

  const download = (contentType: string) => {
    const match = (detail.data?.artifacts ?? []).find((a) => a.contentType === contentType)
    if (match?.downloadUrl) {
      window.open(match.downloadUrl, '_blank', 'noopener,noreferrer')
    } else {
      toast.error(t('telehealth.archive.downloadError'))
    }
  }

  return (
    <li
      className="flex items-center justify-between rounded-md border border-border/60 px-3 py-2"
      data-testid={testId}
    >
      <span className="text-xs">
        <span className="font-medium">{label}</span>
        {archive.archivedAt && (
          <span className="text-muted-foreground">
            {' · '}
            {formatDate(new Date(archive.archivedAt), { dateStyle: 'medium' })}
          </span>
        )}
      </span>
      <div className="flex gap-2">
        <button
          className="flex cursor-pointer items-center gap-1 rounded-md border border-border bg-card px-2 py-1 text-xs hover:bg-muted disabled:opacity-50"
          onClick={() => download('application/pdf')}
          disabled={detail.isLoading}
          data-testid={`${testId}-pdf`}
        >
          <Download size={12} />
          {t('telehealth.archive.downloadPdf')}
        </button>
      </div>
    </li>
  )
}
