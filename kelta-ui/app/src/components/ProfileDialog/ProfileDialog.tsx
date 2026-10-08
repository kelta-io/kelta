import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { useApi } from '@/context/ApiContext'
import { useI18n, type SupportedLocale } from '@/context/I18nContext'

export interface MyProfileAttributes {
  email: string | null
  firstName: string | null
  lastName: string | null
  locale: string | null
  timezone: string | null
  userType: string | null
}

interface MyProfileDocument {
  data: { type: 'users'; id: string; attributes: MyProfileAttributes }
}

interface ProfileDraft {
  firstName: string
  lastName: string
  locale: string
  timezone: string
}

export interface ProfileDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

const PROFILE_URL = '/api/me/profile'
const MAX_NAME_LENGTH = 100

// eslint-disable-next-line react-refresh/only-export-components
export const MY_PROFILE_QUERY_KEY = ['my-profile'] as const

function timeZones(current: string): string[] {
  const zones =
    typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : []
  const all = new Set(['UTC', ...zones])
  if (current) all.add(current)
  return [...all].sort()
}

/** Maps a stored locale ("en_US", "pt-BR") onto the UI language it starts with, if any. */
function matchSupportedLocale(stored: string, supported: string[]): string {
  const language = stored.toLowerCase().split(/[_-]/)[0]
  return supported.find((l) => l.toLowerCase() === language) ?? stored
}

const inputClass =
  'w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground'

/**
 * The caller's own profile (first/last name, language, time zone), backed by
 * GET/PATCH /api/me/profile. The server owns the allow-list; this form sends
 * only those four attributes.
 */
export function ProfileDialog({ open, onOpenChange }: ProfileDialogProps) {
  const { t, locale: uiLocale, supportedLocales, getLocaleDisplayName, setLocale } = useI18n()
  const { apiClient } = useApi()
  const queryClient = useQueryClient()

  const { data, isLoading, isError } = useQuery({
    queryKey: MY_PROFILE_QUERY_KEY,
    queryFn: () => apiClient.get<MyProfileDocument>(PROFILE_URL),
    enabled: open,
  })

  // Edits on top of the loaded profile; null until the user changes something.
  const [draft, setDraft] = useState<ProfileDraft | null>(null)
  const profile = data?.data.attributes
  const loaded: ProfileDraft = {
    firstName: profile?.firstName ?? '',
    lastName: profile?.lastName ?? '',
    locale: matchSupportedLocale(profile?.locale || uiLocale, supportedLocales),
    timezone: profile?.timezone ?? 'UTC',
  }
  const { firstName, lastName, locale, timezone } = draft ?? loaded
  const edit = (patch: Partial<ProfileDraft>) =>
    setDraft((current) => ({ ...(current ?? loaded), ...patch }))

  const isSupported = (loc: string): loc is SupportedLocale =>
    (supportedLocales as string[]).includes(loc)

  const zones = useMemo(() => timeZones(timezone), [timezone])
  const localeOptions = useMemo(
    () =>
      locale && !(supportedLocales as string[]).includes(locale)
        ? [...supportedLocales, locale]
        : supportedLocales,
    [locale, supportedLocales]
  )

  const save = useMutation({
    mutationFn: () =>
      apiClient.patch<MyProfileDocument>(PROFILE_URL, {
        data: {
          type: 'users',
          attributes: { firstName, lastName, locale, timezone },
        },
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(MY_PROFILE_QUERY_KEY, updated)
      setDraft(null)
      if (isSupported(locale)) setLocale(locale)
      toast.success(t('profileDialog.saved', 'Profile saved'))
      onOpenChange(false)
    },
    onError: (error: Error) => {
      toast.error(error.message || t('profileDialog.saveFailed', 'Could not save your profile'))
    },
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent data-testid="profile-dialog">
        <DialogHeader>
          <DialogTitle>{t('profileDialog.title', 'Your profile')}</DialogTitle>
          <DialogDescription>{profile?.email ?? ''}</DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <p className="text-sm text-muted-foreground">{t('common.loading', 'Loading...')}</p>
        ) : isError ? (
          <p className="text-sm text-destructive" role="alert" data-testid="profile-load-error">
            {t('profileDialog.loadFailed', 'Could not load your profile')}
          </p>
        ) : (
          <form
            id="profile-form"
            className="grid gap-4"
            onSubmit={(e) => {
              e.preventDefault()
              save.mutate()
            }}
          >
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="grid gap-1 text-sm font-medium">
                {t('profileDialog.firstName', 'First name')}
                <input
                  type="text"
                  className={inputClass}
                  value={firstName}
                  maxLength={MAX_NAME_LENGTH}
                  onChange={(e) => edit({ firstName: e.target.value })}
                  data-testid="profile-first-name"
                />
              </label>
              <label className="grid gap-1 text-sm font-medium">
                {t('profileDialog.lastName', 'Last name')}
                <input
                  type="text"
                  className={inputClass}
                  value={lastName}
                  maxLength={MAX_NAME_LENGTH}
                  onChange={(e) => edit({ lastName: e.target.value })}
                  data-testid="profile-last-name"
                />
              </label>
            </div>
            <label className="grid gap-1 text-sm font-medium">
              {t('profileDialog.language', 'Language')}
              <select
                className={inputClass}
                value={locale}
                onChange={(e) => edit({ locale: e.target.value })}
                data-testid="profile-locale"
              >
                {localeOptions.map((loc) => (
                  <option key={loc} value={loc}>
                    {getLocaleDisplayName(loc)}
                  </option>
                ))}
              </select>
            </label>
            <label className="grid gap-1 text-sm font-medium">
              {t('profileDialog.timezone', 'Time zone')}
              <select
                className={inputClass}
                value={timezone}
                onChange={(e) => edit({ timezone: e.target.value })}
                data-testid="profile-timezone"
              >
                {zones.map((zone) => (
                  <option key={zone} value={zone}>
                    {zone}
                  </option>
                ))}
              </select>
            </label>
          </form>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={save.isPending}>
            {t('common.cancel', 'Cancel')}
          </Button>
          <Button
            type="submit"
            form="profile-form"
            disabled={isLoading || isError || save.isPending}
            data-testid="profile-save"
          >
            {t('common.save', 'Save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
