/**
 * MapSettingsPage
 *
 * Tenant-level map / location settings. Currently:
 * - Mapbox public token — when set, AddressMap uses Mapbox Static Images
 *   (and the optional interactive map style) instead of the OpenStreetMap
 *   staticmap fallback.
 * - Mapbox style id / URL — controls the style for both static + interactive
 *   maps. Defaults to `mapbox/dark-v11` when blank.
 *
 * Persists into `tenant.settings.map` JSONB. Future map-related settings
 * (default zoom, marker tone, attribution overrides) land in the same
 * sub-object without schema migrations.
 */

import React, { useState } from 'react'
import { toast } from 'sonner'
import { Map, Save } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { FieldLabel } from '@/components/kelta'
import { LoadingSpinner, ErrorMessage } from '@/components'
import { useTenantSettings } from '@/hooks/useTenantSettings'

export interface MapSettingsPageProps {
  testId?: string
}

export function MapSettingsPage({
  testId = 'map-settings-page',
}: MapSettingsPageProps): React.ReactElement {
  const { settings, isLoading, error, save, isSaving } = useTenantSettings()

  if (isLoading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={error} />

  return <MapSettingsForm settings={settings} save={save} isSaving={isSaving} testId={testId} />
}

interface MapSettingsFormProps {
  settings: { map?: { token?: string; style?: string }; [key: string]: unknown }
  save: (next: MapSettingsFormProps['settings']) => Promise<void>
  isSaving: boolean
  testId: string
}

function MapSettingsForm({
  settings,
  save,
  isSaving,
  testId,
}: MapSettingsFormProps): React.ReactElement {
  const [token, setToken] = useState<string>(settings.map?.token ?? '')
  const [style, setStyle] = useState<string>(settings.map?.style ?? '')

  const handleSave = async (): Promise<void> => {
    const nextMap: Record<string, string> = {}
    if (token.trim()) nextMap.token = token.trim()
    if (style.trim()) nextMap.style = style.trim()
    try {
      await save({
        ...settings,
        map: Object.keys(nextMap).length > 0 ? nextMap : undefined,
      })
      toast.success('Map settings saved')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Save failed')
    }
  }

  return (
    <div className="space-y-6 p-6" data-testid={testId}>
      <div className="flex items-center gap-3">
        <Map className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
        <h1 className="kelta-page-title">Map settings</h1>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Mapbox</CardTitle>
          <CardDescription>
            Configure a Mapbox account for higher-quality static + interactive map tiles. When the
            token is blank, AddressMap falls back to free OpenStreetMap tiles — no admin action
            required.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-1">
            <FieldLabel htmlFor="mapbox-token">Public access token</FieldLabel>
            <Input
              id="mapbox-token"
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="pk.eyJ1Ijoi…"
              autoComplete="off"
              data-testid="mapbox-token-input"
            />
            <p className="text-xs text-muted-foreground">
              Find your public token under{' '}
              <a
                href="https://account.mapbox.com/access-tokens/"
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary hover:underline"
              >
                Mapbox → Access tokens
              </a>
              . Use a public token; secret tokens leak when embedded in the SPA.
            </p>
          </div>

          <div className="space-y-1">
            <FieldLabel htmlFor="mapbox-style">Style</FieldLabel>
            <Input
              id="mapbox-style"
              value={style}
              onChange={(e) => setStyle(e.target.value)}
              placeholder="mapbox/dark-v11"
              data-testid="mapbox-style-input"
            />
            <p className="text-xs text-muted-foreground">
              Mapbox style id (e.g. <code>mapbox/streets-v12</code>) or a full maplibre style URL.
              Defaults to <code>mapbox/dark-v11</code> when blank.
            </p>
          </div>
        </CardContent>
      </Card>

      <div className="flex justify-end">
        <Button onClick={handleSave} disabled={isSaving} data-testid="save-map-settings">
          <Save className="mr-2 h-4 w-4" aria-hidden="true" />
          {isSaving ? 'Saving…' : 'Save changes'}
        </Button>
      </div>
    </div>
  )
}
