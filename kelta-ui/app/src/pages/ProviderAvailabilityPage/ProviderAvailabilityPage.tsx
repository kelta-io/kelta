import React, { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarClock, CalendarOff, Plus, Save, Trash2 } from 'lucide-react'

import { useApi } from '../../context/ApiContext'
import { useI18n } from '../../context/I18nContext'
import { LoadingSpinner, ErrorMessage } from '../../components'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { FieldLabel } from '@/components/kelta'

/** Wire shapes for the scoped self-service endpoint. */
interface AvailabilityDto {
  timezone: string
  rules: { weekday: number; startTime: string; endTime: string }[]
  exceptions: { exceptionDate: string; closed: boolean; startTime: string; endTime: string }[]
}

interface RuleRow {
  key: string
  weekday: number
  start: string
  end: string
}

interface ExceptionRow {
  key: string
  date: string
  closed: boolean
  start: string
  end: string
}

// Display Monday first; weekday values stay Sunday=0..Saturday=6 (server contract).
const DISPLAY_WEEKDAYS = [1, 2, 3, 4, 5, 6, 0]

const uid = (): string =>
  globalThis.crypto?.randomUUID?.() ?? `k-${Math.random().toString(36).slice(2)}`
const hhmm = (value: string | undefined): string => (value ? value.slice(0, 5) : '')

export function ProviderAvailabilityPage(): React.ReactElement {
  const { apiClient } = useApi()
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()

  const { data, isLoading, error } = useQuery({
    queryKey: ['telehealth-availability-me'],
    queryFn: () => apiClient.get<AvailabilityDto>('/api/telehealth/availability/me'),
  })

  const [timezone, setTimezone] = useState('Europe/Lisbon')
  const [rules, setRules] = useState<RuleRow[]>([])
  const [exceptions, setExceptions] = useState<ExceptionRow[]>([])

  // Seed the editable state from the fetched availability during render (the
  // "adjusting state when props change" pattern) — seeding via useEffect trips
  // react-hooks/set-state-in-effect and forces a cascading re-render.
  const [seededFrom, setSeededFrom] = useState<AvailabilityDto | null>(null)
  if (data && data !== seededFrom) {
    setSeededFrom(data)
    setTimezone(data.timezone || 'Europe/Lisbon')
    setRules(
      (data.rules ?? []).map((r) => ({
        key: uid(),
        weekday: r.weekday,
        start: hhmm(r.startTime),
        end: hhmm(r.endTime),
      }))
    )
    setExceptions(
      (data.exceptions ?? []).map((e) => ({
        key: uid(),
        date: e.exceptionDate,
        closed: e.closed,
        start: hhmm(e.startTime),
        end: hhmm(e.endTime),
      }))
    )
  }

  // Localized weekday names without hardcoding translations (2024-01-07 is a Sunday).
  const weekdayLabel = useMemo(() => {
    const fmt = new Intl.DateTimeFormat(locale || 'en', { weekday: 'long', timeZone: 'UTC' })
    return (weekday: number) => fmt.format(new Date(Date.UTC(2024, 0, 7 + weekday)))
  }, [locale])

  const save = useMutation({
    mutationFn: () =>
      apiClient.put('/api/telehealth/availability/me', {
        timezone,
        rules: rules.map((r) => ({ weekday: r.weekday, startTime: r.start, endTime: r.end })),
        exceptions: exceptions.map((e) => ({
          exceptionDate: e.date,
          closed: e.closed,
          startTime: e.closed ? '' : e.start,
          endTime: e.closed ? '' : e.end,
        })),
      }),
    onSuccess: () => {
      toast.success(t('availability.saved'))
      void queryClient.invalidateQueries({ queryKey: ['telehealth-availability-me'] })
    },
    onError: (err: unknown) => {
      const detail = (err as { response?: { data?: { message?: string; error?: string } } })
        ?.response?.data
      toast.error(detail?.message || detail?.error || t('availability.saveFailed'))
    },
  })

  const addWindow = (weekday: number) =>
    setRules((prev) => [...prev, { key: uid(), weekday, start: '09:00', end: '17:00' }])

  const updateRule = (key: string, patch: Partial<RuleRow>) =>
    setRules((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)))

  const removeRule = (key: string) => setRules((prev) => prev.filter((r) => r.key !== key))

  const addException = () =>
    setExceptions((prev) => [...prev, { key: uid(), date: '', closed: true, start: '', end: '' }])

  const updateException = (key: string, patch: Partial<ExceptionRow>) =>
    setExceptions((prev) => prev.map((e) => (e.key === key ? { ...e, ...patch } : e)))

  const removeException = (key: string) =>
    setExceptions((prev) => prev.filter((e) => e.key !== key))

  const invalid = useMemo(() => {
    const badRule = rules.some((r) => !r.start || !r.end || r.end <= r.start)
    const badException = exceptions.some(
      (e) => !e.date || (!e.closed && (!e.start || !e.end || e.end <= e.start))
    )
    return badRule || badException
  }, [rules, exceptions])

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={t('availability.loadFailed')} />

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-2 text-xl font-semibold text-foreground">
            <CalendarClock className="h-5 w-5 text-primary" />
            {t('availability.title')}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t('availability.subtitle', { timezone })}
          </p>
        </div>
        <Button onClick={() => save.mutate()} disabled={save.isPending || invalid}>
          <Save className="mr-2 h-4 w-4" />
          {save.isPending ? t('availability.saving') : t('availability.save')}
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>{t('availability.weekly')}</CardTitle>
          <CardDescription>{t('availability.weeklyHelp')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {DISPLAY_WEEKDAYS.map((weekday) => {
            const dayRules = rules.filter((r) => r.weekday === weekday)
            return (
              <div
                key={weekday}
                className="grid grid-cols-[8rem_1fr] items-start gap-3 border-b border-border pb-4 last:border-0 last:pb-0"
              >
                <FieldLabel className="pt-2">{weekdayLabel(weekday)}</FieldLabel>
                <div className="space-y-2">
                  {dayRules.length === 0 ? (
                    <p className="pt-2 text-sm text-muted-foreground">{t('availability.closed')}</p>
                  ) : (
                    dayRules.map((rule) => (
                      <div key={rule.key} className="flex items-center gap-2">
                        <Input
                          type="time"
                          aria-label={t('availability.start')}
                          value={rule.start}
                          onChange={(e) => updateRule(rule.key, { start: e.target.value })}
                          className="w-32"
                        />
                        <span className="text-muted-foreground">–</span>
                        <Input
                          type="time"
                          aria-label={t('availability.end')}
                          value={rule.end}
                          onChange={(e) => updateRule(rule.key, { end: e.target.value })}
                          className="w-32"
                        />
                        <Button
                          variant="ghost"
                          size="icon"
                          aria-label={t('availability.remove')}
                          onClick={() => removeRule(rule.key)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    ))
                  )}
                  <Button variant="outline" size="sm" onClick={() => addWindow(weekday)}>
                    <Plus className="mr-1 h-3.5 w-3.5" />
                    {t('availability.addHours')}
                  </Button>
                </div>
              </div>
            )
          })}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <CalendarOff className="h-4 w-4 text-primary" />
            {t('availability.exceptions')}
          </CardTitle>
          <CardDescription>{t('availability.exceptionsHelp')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {exceptions.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('availability.noExceptions')}</p>
          ) : (
            exceptions.map((ex) => (
              <div key={ex.key} className="flex flex-wrap items-center gap-2">
                <Input
                  type="date"
                  aria-label={t('availability.date')}
                  value={ex.date}
                  onChange={(e) => updateException(ex.key, { date: e.target.value })}
                  className="w-44"
                />
                <label className="flex items-center gap-2 text-sm text-foreground">
                  <Checkbox
                    checked={ex.closed}
                    onCheckedChange={(checked) =>
                      updateException(ex.key, { closed: checked === true })
                    }
                  />
                  {t('availability.closedAllDay')}
                </label>
                {!ex.closed && (
                  <>
                    <Input
                      type="time"
                      aria-label={t('availability.start')}
                      value={ex.start}
                      onChange={(e) => updateException(ex.key, { start: e.target.value })}
                      className="w-32"
                    />
                    <span className="text-muted-foreground">–</span>
                    <Input
                      type="time"
                      aria-label={t('availability.end')}
                      value={ex.end}
                      onChange={(e) => updateException(ex.key, { end: e.target.value })}
                      className="w-32"
                    />
                  </>
                )}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={t('availability.remove')}
                  onClick={() => removeException(ex.key)}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))
          )}
          <Button variant="outline" size="sm" onClick={addException}>
            <Plus className="mr-1 h-3.5 w-3.5" />
            {t('availability.addException')}
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}

export default ProviderAvailabilityPage
