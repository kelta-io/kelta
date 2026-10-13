/**
 * CreateEnvironmentDialog Component
 *
 * Modal for creating a new environment. Two variants:
 * - Local sandbox: name + description → POST type SANDBOX (202). The response
 *   carries one-time admin credentials which are shown ONCE in a follow-up
 *   view — the initial password cannot be retrieved again.
 * - Remote environment: registers a remote Kelta environment (base URL,
 *   tenant slug, credential ref) as a promotion target → POST type PRODUCTION
 *   (201). Connection can be tested afterwards from the environment list.
 */

import React, { useState } from 'react'
import { toast } from 'sonner'
import { useMutation } from '@tanstack/react-query'
import { AlertTriangle, Copy } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ErrorMessage } from '../../components'
import { createEnvironment } from './types'
import type { CreatedEnvironment, CreateEnvironmentRequest } from './types'

type EnvironmentVariant = 'local' | 'remote'

export interface CreateEnvironmentDialogProps {
  /** Called when the dialog should close */
  onClose: () => void
  /** Called after an environment has been created (invalidate the list) */
  onCreated: () => void
}

/**
 * One-time credential row with a copy button.
 */
function CredentialRow({
  label,
  value,
  testId,
}: {
  label: string
  value?: string
  testId: string
}): React.ReactElement {
  const { t } = useI18n()

  const handleCopy = async () => {
    if (!value) return
    try {
      await navigator.clipboard.writeText(value)
      toast.success(t('environments.copied'))
    } catch {
      toast.error(t('errors.generic'))
    }
  }

  return (
    <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-muted/30 px-3 py-2">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
          {label}
        </span>
        <span className="truncate font-mono text-sm text-foreground" data-testid={testId}>
          {value || '—'}
        </span>
      </div>
      <button
        type="button"
        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border bg-background text-muted-foreground hover:bg-muted hover:text-foreground"
        onClick={handleCopy}
        aria-label={t('environments.copy')}
        data-testid={`copy-${testId}`}
      >
        <Copy size={14} />
      </button>
    </div>
  )
}

export function CreateEnvironmentDialog({
  onClose,
  onCreated,
}: CreateEnvironmentDialogProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const [variant, setVariant] = useState<EnvironmentVariant>('local')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [remoteBaseUrl, setRemoteBaseUrl] = useState('')
  const [remoteTenantSlug, setRemoteTenantSlug] = useState('')
  const [credentialRef, setCredentialRef] = useState('')
  const [createdCredentials, setCreatedCredentials] = useState<CreatedEnvironment | null>(null)

  const createMutation = useMutation({
    mutationFn: (request: CreateEnvironmentRequest) => createEnvironment(apiClient, request),
    onSuccess: (created) => {
      onCreated()
      if (created.adminInitialPassword) {
        // Local sandbox — show the one-time credentials before closing
        setCreatedCredentials(created)
      } else {
        toast.success(t('environments.createdToast'))
        onClose()
      }
    },
  })

  const isFormValid =
    name.trim() !== '' &&
    (variant === 'local' ||
      (remoteBaseUrl.trim() !== '' &&
        remoteTenantSlug.trim() !== '' &&
        credentialRef.trim() !== ''))

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!isFormValid || createMutation.isPending) return
    if (variant === 'local') {
      createMutation.mutate({
        name: name.trim(),
        description: description.trim() || undefined,
        type: 'SANDBOX',
      })
    } else {
      createMutation.mutate({
        name: name.trim(),
        description: description.trim() || undefined,
        type: 'PRODUCTION',
        remoteBaseUrl: remoteBaseUrl.trim(),
        remoteTenantSlug: remoteTenantSlug.trim(),
        credentialRef: credentialRef.trim(),
      })
    }
  }

  // While the one-time credentials are shown, only the explicit Done button
  // closes the dialog — an accidental overlay click must not lose them.
  const handleOverlayClose = () => {
    if (!createdCredentials) {
      onClose()
    }
  }

  const inputClasses =
    'rounded-md border border-border bg-background px-3.5 py-2.5 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-[3px] focus:ring-primary/10 placeholder:text-muted-foreground'

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={handleOverlayClose}
      onKeyDown={(e) => e.key === 'Escape' && handleOverlayClose()}
      role="presentation"
      data-testid="create-environment-modal"
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        className="w-full max-w-[560px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="create-environment-title"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="create-environment-title" className="m-0 text-lg font-semibold text-foreground">
            {createdCredentials
              ? t('environments.credentialsTitle')
              : t('environments.createTitle')}
          </h2>
          {!createdCredentials && (
            <button
              type="button"
              className="flex h-8 w-8 items-center justify-center rounded-md border-none bg-transparent text-xl text-muted-foreground hover:bg-muted hover:text-foreground"
              onClick={onClose}
              aria-label={t('common.close')}
              data-testid="close-create-environment-button"
            >
              ×
            </button>
          )}
        </div>

        {createdCredentials ? (
          <div className="p-6" data-testid="sandbox-credentials-dialog">
            <div className="mb-4 flex items-start gap-3 rounded-md border border-amber-500/40 bg-amber-500/10 p-4">
              <AlertTriangle
                size={16}
                className="mt-0.5 shrink-0 text-amber-600 dark:text-amber-400"
              />
              <p className="m-0 text-sm text-foreground">{t('environments.credentialsWarning')}</p>
            </div>

            <div className="flex flex-col gap-3">
              <CredentialRow
                label={t('environments.sandboxSlug')}
                value={createdCredentials.sandboxSlug}
                testId="credential-slug"
              />
              <CredentialRow
                label={t('environments.adminUsername')}
                value={createdCredentials.adminUsername}
                testId="credential-username"
              />
              <CredentialRow
                label={t('environments.adminEmail')}
                value={createdCredentials.adminEmail}
                testId="credential-email"
              />
              <CredentialRow
                label={t('environments.adminInitialPassword')}
                value={createdCredentials.adminInitialPassword}
                testId="credential-password"
              />
            </div>

            <div className="mt-6 flex items-center justify-end border-t border-border pt-6">
              <button
                type="button"
                className="rounded-md bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
                onClick={onClose}
                data-testid="credentials-done-button"
              >
                {t('environments.done')}
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="flex flex-col gap-5 p-6">
              {/* Variant switch */}
              <div className="grid grid-cols-2 gap-3 max-sm:grid-cols-1" role="radiogroup">
                {(
                  [
                    ['local', t('environments.variantLocal'), t('environments.variantLocalHint')],
                    [
                      'remote',
                      t('environments.variantRemote'),
                      t('environments.variantRemoteHint'),
                    ],
                  ] as Array<[EnvironmentVariant, string, string]>
                ).map(([key, label, hint]) => (
                  <button
                    key={key}
                    type="button"
                    role="radio"
                    aria-checked={variant === key}
                    className={cn(
                      'flex flex-col items-start gap-1 rounded-md border p-3 text-left transition-colors',
                      variant === key
                        ? 'border-primary bg-primary/5'
                        : 'border-border bg-background hover:bg-muted'
                    )}
                    onClick={() => setVariant(key)}
                    data-testid={`variant-${key}`}
                  >
                    <span className="text-sm font-medium text-foreground">{label}</span>
                    <span className="text-xs text-muted-foreground">{hint}</span>
                  </button>
                ))}
              </div>

              <div className="flex flex-col gap-2">
                <label
                  htmlFor="environment-name"
                  className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                >
                  {t('environments.nameLabel')}
                  <span className="ml-0.5 text-destructive">*</span>
                </label>
                <input
                  id="environment-name"
                  type="text"
                  className={inputClasses}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('environments.namePlaceholder')}
                  data-testid="environment-name-input"
                  required
                />
              </div>

              <div className="flex flex-col gap-2">
                <label
                  htmlFor="environment-description"
                  className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                >
                  {t('environments.descriptionLabel')}
                </label>
                <textarea
                  id="environment-description"
                  className={cn(inputClasses, 'min-h-[64px] resize-y')}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('environments.descriptionPlaceholder')}
                  rows={2}
                  data-testid="environment-description-input"
                />
              </div>

              {variant === 'remote' && (
                <>
                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="environment-remote-url"
                      className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                    >
                      {t('environments.remoteBaseUrl')}
                      <span className="ml-0.5 text-destructive">*</span>
                    </label>
                    <input
                      id="environment-remote-url"
                      type="url"
                      className={inputClasses}
                      value={remoteBaseUrl}
                      onChange={(e) => setRemoteBaseUrl(e.target.value)}
                      placeholder={t('environments.remoteBaseUrlPlaceholder')}
                      data-testid="environment-remote-url-input"
                    />
                  </div>

                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="environment-remote-slug"
                      className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                    >
                      {t('environments.remoteTenantSlug')}
                      <span className="ml-0.5 text-destructive">*</span>
                    </label>
                    <input
                      id="environment-remote-slug"
                      type="text"
                      className={inputClasses}
                      value={remoteTenantSlug}
                      onChange={(e) => setRemoteTenantSlug(e.target.value)}
                      placeholder={t('environments.remoteTenantSlugPlaceholder')}
                      data-testid="environment-remote-slug-input"
                    />
                  </div>

                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="environment-credential-ref"
                      className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground"
                    >
                      {t('environments.credentialRef')}
                      <span className="ml-0.5 text-destructive">*</span>
                    </label>
                    <input
                      id="environment-credential-ref"
                      type="text"
                      className={inputClasses}
                      value={credentialRef}
                      onChange={(e) => setCredentialRef(e.target.value)}
                      placeholder={t('environments.credentialRefPlaceholder')}
                      data-testid="environment-credential-ref-input"
                    />
                  </div>
                </>
              )}

              {createMutation.error && (
                <div
                  className="rounded-md border border-destructive/30 bg-destructive/10 p-4"
                  data-testid="create-environment-error"
                >
                  <ErrorMessage error={createMutation.error as Error} />
                </div>
              )}
            </div>

            <div className="flex items-center justify-end gap-3 border-t border-border p-6">
              <button
                type="button"
                className="rounded-md border border-border bg-background px-5 py-2.5 text-sm font-medium text-foreground hover:bg-muted"
                onClick={onClose}
                data-testid="cancel-create-environment-button"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                className="rounded-md bg-primary px-6 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                disabled={!isFormValid || createMutation.isPending}
                data-testid="submit-create-environment-button"
              >
                {createMutation.isPending ? t('common.loading') : t('environments.createButton')}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

export default CreateEnvironmentDialog
