import React, { useState } from 'react'
import { toast } from 'sonner'
import { ShieldCheck, Archive, Lock, Unlock } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { useSystemPermissions } from '../../hooks/useSystemPermissions'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { FieldLabel, StatusBadge } from '@/components/kelta'
import {
  useArchives,
  useRetentionSettings,
  useSetLegalHold,
  useUpdateRetentionSettings,
  type ArchiveSummary,
  type RetentionSettings,
} from '../../hooks/useArchives'

/**
 * Telehealth retention admin (slice 7). Two sections: the per-tenant retention
 * settings (archive/retain/purge windows) and the tenant archive list with a
 * per-row legal-hold toggle. Writes require the MANAGE_DATA system permission —
 * the same gate the server enforces; without it the form and toggles are
 * read-only so a delegated admin cannot shorten retention.
 */
export function TelehealthSettingsPage({
  testId = 'telehealth-settings',
}: {
  testId?: string
}): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { hasPermission } = useSystemPermissions()
  const canManage = hasPermission('MANAGE_DATA')

  const settingsQuery = useRetentionSettings()
  const archivesQuery = useArchives()
  const updateSettings = useUpdateRetentionSettings()
  const setLegalHold = useSetLegalHold()

  // Field edits are held as an overlay merged over the loaded settings — no
  // effect-driven seeding (that trips the set-state-in-effect rule).
  const [edits, setEdits] = useState<Partial<RetentionSettings>>({})

  if (settingsQuery.isLoading) return <LoadingSpinner />
  if (settingsQuery.error) return <ErrorMessage error={t('telehealth.archive.saveError')} />

  const current: RetentionSettings | null = settingsQuery.data
    ? { ...settingsQuery.data, ...edits }
    : null

  const handleSave = () => {
    if (!current) return
    updateSettings.mutate(current, {
      onSuccess: () => toast.success(t('telehealth.archive.saveSuccess')),
      onError: () => toast.error(t('telehealth.archive.saveError')),
    })
  }

  const handleLegalHold = (archive: ArchiveSummary) => {
    const enabling = !archive.legalHold
    const message = enabling
      ? t('telehealth.archive.legalHoldConfirmEnable')
      : t('telehealth.archive.legalHoldConfirmDisable')
    if (!window.confirm(message)) return
    setLegalHold.mutate(
      { id: archive.id, enabled: enabling },
      {
        onSuccess: () => toast.success(t('telehealth.archive.legalHoldChanged')),
        onError: () => toast.error(t('telehealth.archive.legalHoldError')),
      }
    )
  }

  const numberField = (
    key: keyof RetentionSettings,
    label: string,
    help: string
  ): React.ReactElement => (
    <div className="space-y-2">
      <FieldLabel>{label}</FieldLabel>
      <Input
        type="number"
        min={key === 'retentionYears' ? 1 : 0}
        disabled={!canManage}
        value={current ? String(current[key]) : ''}
        onChange={(e) => setEdits((prev) => ({ ...prev, [key]: Number(e.target.value) }))}
        data-testid={`${testId}-${key}`}
      />
      <p className="text-xs text-muted-foreground">{help}</p>
    </div>
  )

  return (
    <div className="space-y-6" data-testid={testId}>
      <div className="flex items-center gap-3">
        <ShieldCheck className="h-6 w-6 text-primary" />
        <div>
          <h1 className="text-[26px] font-bold tracking-[-0.01em]">
            {t('telehealth.archive.settingsTitle')}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t('telehealth.archive.settingsSubtitle')}
          </p>
        </div>
      </div>

      {/* Retention settings */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('telehealth.archive.retentionSection')}</CardTitle>
          <CardDescription>{t('telehealth.archive.retentionHelp')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-3">
            {numberField(
              'archiveAfterDays',
              t('telehealth.archive.archiveAfterDays'),
              t('telehealth.archive.archiveAfterDaysHelp')
            )}
            {numberField(
              'retentionYears',
              t('telehealth.archive.retentionYears'),
              t('telehealth.archive.retentionYearsHelp')
            )}
            {numberField(
              'purgeLiveAfterDays',
              t('telehealth.archive.purgeLiveAfterDays'),
              t('telehealth.archive.purgeLiveAfterDaysHelp')
            )}
          </div>
          {canManage && (
            <div className="flex justify-end">
              <Button
                onClick={handleSave}
                disabled={updateSettings.isPending}
                data-testid={`${testId}-save`}
              >
                {updateSettings.isPending
                  ? t('common.loading', 'Saving…')
                  : t('telehealth.archive.saveSettings')}
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Archive list */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Archive className="h-4 w-4" />
            {t('telehealth.archive.listTitle')}
          </CardTitle>
          <CardDescription>{t('telehealth.archive.listSubtitle')}</CardDescription>
        </CardHeader>
        <CardContent>
          {archivesQuery.isLoading ? (
            <LoadingSpinner />
          ) : (archivesQuery.data ?? []).length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('telehealth.archive.emptyList')}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid={`${testId}-list`}>
                <thead>
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-4 font-medium">{t('telehealth.archive.colType')}</th>
                    <th className="py-2 pr-4 font-medium">
                      {t('telehealth.archive.colArchivedAt')}
                    </th>
                    <th className="py-2 pr-4 font-medium">
                      {t('telehealth.archive.colRetentionUntil')}
                    </th>
                    <th className="py-2 pr-4 font-medium">{t('telehealth.archive.colStatus')}</th>
                    <th className="py-2 pr-4 font-medium">
                      {t('telehealth.archive.colLegalHold')}
                    </th>
                    <th className="py-2 font-medium" />
                  </tr>
                </thead>
                <tbody>
                  {(archivesQuery.data ?? []).map((archive) => (
                    <tr
                      key={archive.id}
                      className="border-b border-border/60"
                      data-testid={`${testId}-row-${archive.id}`}
                    >
                      <td className="py-2 pr-4">
                        {archive.sourceType === 'CONVERSATION'
                          ? t('telehealth.archive.typeConversation')
                          : t('telehealth.archive.typeVideo')}
                      </td>
                      <td className="py-2 pr-4">
                        {archive.archivedAt
                          ? formatDate(new Date(archive.archivedAt), { dateStyle: 'medium' })
                          : '—'}
                      </td>
                      <td className="py-2 pr-4">
                        {archive.retentionUntil
                          ? formatDate(new Date(archive.retentionUntil), { dateStyle: 'medium' })
                          : '—'}
                      </td>
                      <td className="py-2 pr-4">
                        {archive.purgedAt ? (
                          <StatusBadge
                            variant="inactive"
                            label={t('telehealth.archive.statusPurged')}
                          />
                        ) : (
                          <StatusBadge
                            variant="active"
                            label={t('telehealth.archive.statusActive')}
                          />
                        )}
                      </td>
                      <td className="py-2 pr-4">
                        {archive.legalHold ? (
                          <StatusBadge
                            variant="pending"
                            label={t('telehealth.archive.legalHoldOn')}
                          />
                        ) : (
                          <span className="text-muted-foreground">
                            {t('telehealth.archive.legalHoldOff')}
                          </span>
                        )}
                      </td>
                      <td className="py-2">
                        {canManage && (
                          <button
                            className="flex cursor-pointer items-center gap-1 rounded-md border border-border bg-card px-2 py-1 text-xs hover:bg-muted disabled:opacity-50"
                            onClick={() => handleLegalHold(archive)}
                            disabled={setLegalHold.isPending}
                            data-testid={`${testId}-hold-${archive.id}`}
                          >
                            {archive.legalHold ? <Unlock size={12} /> : <Lock size={12} />}
                            {archive.legalHold
                              ? t('telehealth.archive.legalHoldDisable')
                              : t('telehealth.archive.legalHoldEnable')}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
