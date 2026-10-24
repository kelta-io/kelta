import React, { useState, useCallback, useRef, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useController, useFormContext, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog, ExecutionLogModal } from '../../components'
import type { LogColumn } from '../../components'
import type { CreateEmailTemplateRequest, EmailTemplate as SdkEmailTemplate } from '@kelta/sdk'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { AdminDataTable, type AdminColumn } from '@/components/AdminDataTable'
import {
  AdminCrudPage,
  CRUD_FIELD_LABEL_CLASS,
  CrudCheckboxField,
  CrudFormDialog,
  CrudSelectField,
  CrudTextField,
  useAdminCrud,
  useCrudFormState,
} from '../../components/AdminCrud'
import { Plus } from 'lucide-react'
import { FieldExpressionPicker, type StaticNamespace } from '../../components/FieldExpressionPicker'
import {
  RichTextEditor,
  tokenizeMergeTags,
  type RichTextEditorHandle,
} from '../../components/RichTextEditor'

type EmailTemplate = SdkEmailTemplate

interface EmailLog {
  id: string
  templateId: string
  recipientEmail: string
  subject: string
  status: string
  source: string | null
  sourceId: string | null
  errorMessage: string | null
  sentAt: string | null
}

interface CollectionOption {
  id: string
  name: string
  displayName: string
}

export interface EmailTemplatesPageProps {
  testId?: string
}

const ENVELOPE_NAMESPACES: StaticNamespace[] = [
  {
    name: 'recipient',
    label: 'Recipient',
    fields: [
      { name: 'email', displayName: 'Email', type: 'email' },
      { name: 'firstName', displayName: 'First name', type: 'string' },
      { name: 'lastName', displayName: 'Last name', type: 'string' },
      { name: 'fullName', displayName: 'Full name', type: 'string' },
    ],
  },
  {
    name: 'currentUser',
    label: 'Current user',
    fields: [
      { name: 'email', displayName: 'Email', type: 'email' },
      { name: 'firstName', displayName: 'First name', type: 'string' },
      { name: 'lastName', displayName: 'Last name', type: 'string' },
      { name: 'fullName', displayName: 'Full name', type: 'string' },
    ],
  },
]

const emailTemplateSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, 'Name is required')
    .refine((v) => v.length <= 100, 'Name must be 100 characters or fewer'),
  description: z.string().max(500, 'Description must be 500 characters or fewer'),
  subject: z
    .string()
    .refine((v) => v.trim().length > 0, 'Subject is required')
    .refine((v) => v.length <= 200, 'Subject must be 200 characters or fewer'),
  bodyHtml: z.string().refine((v) => v.trim().length > 0, 'Body HTML is required'),
  bodyText: z.string(),
  relatedCollectionId: z.string(),
  folder: z.string().max(100, 'Folder must be 100 characters or fewer'),
  active: z.boolean(),
})

type EmailTemplateFormData = z.infer<typeof emailTemplateSchema>

function templateDefaults(template: EmailTemplate | undefined): EmailTemplateFormData {
  return {
    name: template?.name ?? '',
    description: template?.description ?? '',
    subject: template?.subject ?? '',
    bodyHtml: template?.bodyHtml ?? '',
    bodyText: template?.bodyText ?? '',
    relatedCollectionId: template?.relatedCollectionId ?? '',
    folder: template?.folder ?? '',
    active: template?.active ?? true,
  }
}

type PickerTarget = 'subject' | 'body' | 'bodyText' | null

const SUBJECT_INPUT_ID = 'email-template-subject'
const BODY_TEXT_INPUT_ID = 'email-template-body-text'

function InsertFieldButton({
  onClick,
  testId,
}: {
  onClick: () => void
  testId: string
}): React.ReactElement {
  const { isSubmitting } = useCrudFormState()
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="gap-1 px-2 text-xs"
      onClick={onClick}
      disabled={isSubmitting}
      data-testid={testId}
    >
      <Plus className="h-3 w-3" />
      Insert field
    </Button>
  )
}

/** The rich-text body, bound to the form's `bodyHtml`. */
function BodyHtmlField({
  editorRef,
  onInsertFieldClick,
}: {
  editorRef: React.RefObject<RichTextEditorHandle | null>
  onInsertFieldClick: () => void
}): React.ReactElement {
  const { isSubmitting } = useCrudFormState()
  const { field, fieldState } = useController<EmailTemplateFormData, 'bodyHtml'>({
    name: 'bodyHtml',
  })
  const error = fieldState.error?.message
  return (
    <div className="flex flex-col gap-1.5">
      <div className={CRUD_FIELD_LABEL_CLASS} id="email-template-body-html-label">
        Body HTML
        <span className="ml-0.5 text-destructive" aria-hidden="true">
          *
        </span>
      </div>
      <RichTextEditor
        ref={editorRef}
        value={field.value}
        onChange={field.onChange}
        onInsertFieldClick={onInsertFieldClick}
        placeholder="Compose your email…"
        disabled={isSubmitting}
        testId="email-template-body-html"
        aria-required
        aria-invalid={!!error}
        aria-describedby={error ? 'email-template-body-html-error' : undefined}
      />
      {error && (
        <span id="email-template-body-html-error" className="text-xs text-destructive" role="alert">
          {error}
        </span>
      )}
      <details className="mt-1 rounded-md border border-border bg-muted/20 px-3 py-2 text-xs">
        <summary className="cursor-pointer font-medium text-foreground">
          How merge tags work
        </summary>
        <div className="mt-2 space-y-1 text-muted-foreground">
          <p>
            Use <code className="font-mono">{'{{path}}'}</code> to insert a value from the related
            collection. Click <strong>Insert field</strong> to pick one — it inserts the correct
            token for you.
          </p>
          <ul className="list-inside list-disc space-y-0.5 pl-2">
            <li>
              Plain field: <code className="font-mono">{'{{firstName}}'}</code>
            </li>
            <li>
              Across a relationship: <code className="font-mono">{'{{account_id.name}}'}</code>
            </li>
            <li>
              With a function: <code className="font-mono">{'{{TEXT(amount)}}'}</code>
            </li>
            <li>
              Envelope variables: <code className="font-mono">{'{{recipient.firstName}}'}</code>,{' '}
              <code className="font-mono">{'{{currentUser.email}}'}</code>
            </li>
          </ul>
        </div>
      </details>
    </div>
  )
}

/**
 * The template editor's fields. Owns the merge-tag picker: "Insert field" on
 * the subject or plain-text body splices `{{token}}` in at the caret; on the
 * rich-text body it inserts a merge-tag chip.
 */
function EmailTemplateFields({
  collections,
}: {
  collections: CollectionOption[]
}): React.ReactElement {
  const { control, getValues, setValue } = useFormContext<EmailTemplateFormData>()
  const relatedCollectionId = useWatch({ control, name: 'relatedCollectionId' })
  const [pickerTarget, setPickerTarget] = useState<PickerTarget>(null)
  const editorRef = useRef<RichTextEditorHandle>(null)

  const collectionOptions = useMemo(
    () => collections.map((c) => ({ value: c.id, label: c.displayName })),
    [collections]
  )

  const insertAtCaret = useCallback(
    (field: 'subject' | 'bodyText', inputId: string, insertion: string) => {
      const el = document.getElementById(inputId) as HTMLInputElement | HTMLTextAreaElement | null
      const current = getValues(field)
      const start = el?.selectionStart ?? current.length
      const end = el?.selectionEnd ?? current.length
      setValue(field, current.slice(0, start) + insertion + current.slice(end), {
        shouldDirty: true,
        shouldValidate: true,
      })
      requestAnimationFrame(() => {
        if (el) {
          const caret = start + insertion.length
          el.focus()
          el.setSelectionRange(caret, caret)
        }
      })
    },
    [getValues, setValue]
  )

  const handlePickerInsert = useCallback(
    (token: string) => {
      const wrapped = `{{${token}}}`
      if (pickerTarget === 'subject') insertAtCaret('subject', SUBJECT_INPUT_ID, wrapped)
      else if (pickerTarget === 'bodyText') insertAtCaret('bodyText', BODY_TEXT_INPUT_ID, wrapped)
      else if (pickerTarget === 'body') editorRef.current?.insertMergeTag(token)
      setPickerTarget(null)
    },
    [pickerTarget, insertAtCaret]
  )

  return (
    <>
      <div className="grid grid-cols-2 gap-4">
        <CrudTextField<EmailTemplateFormData>
          name="name"
          id="email-template-name"
          label="Name"
          required
          placeholder="Enter template name"
          testId="email-template-name-input"
        />
        <CrudSelectField<EmailTemplateFormData>
          name="relatedCollectionId"
          id="email-template-related-collection"
          label="Related collection"
          placeholder="— None —"
          options={collectionOptions}
          hint="Sets which collection's fields appear in the field picker."
          testId="email-template-related-collection-input"
        />
      </div>
      <CrudTextField<EmailTemplateFormData>
        name="description"
        id="email-template-description"
        label="Description"
        multiline
        rows={2}
        placeholder="Enter template description"
        testId="email-template-description-input"
      />
      <CrudTextField<EmailTemplateFormData>
        name="subject"
        id={SUBJECT_INPUT_ID}
        label="Subject"
        required
        placeholder="Enter email subject"
        testId="email-template-subject-input"
        trailing={
          <InsertFieldButton
            onClick={() => setPickerTarget('subject')}
            testId="email-template-subject-insert-field"
          />
        }
      />
      <BodyHtmlField editorRef={editorRef} onInsertFieldClick={() => setPickerTarget('body')} />
      <CrudTextField<EmailTemplateFormData>
        name="bodyText"
        id={BODY_TEXT_INPUT_ID}
        label="Body text (plain-text fallback)"
        multiline
        placeholder="Enter plain text fallback (optional)"
        testId="email-template-body-text-input"
        trailing={
          <InsertFieldButton
            onClick={() => setPickerTarget('bodyText')}
            testId="email-template-body-text-insert-field"
          />
        }
      />
      <div className="grid grid-cols-2 items-end gap-4">
        <CrudTextField<EmailTemplateFormData>
          name="folder"
          id="email-template-folder"
          label="Folder"
          placeholder="Enter folder (optional)"
          testId="email-template-folder-input"
        />
        <div className="pb-2">
          <CrudCheckboxField<EmailTemplateFormData>
            name="active"
            id="email-template-active"
            label="Active"
            testId="email-template-active-input"
          />
        </div>
      </div>

      {/* Rendered inside the editor dialog so Radix treats it as a nested layer:
          its Escape and clicks never dismiss the editor underneath. */}
      <FieldExpressionPicker
        open={pickerTarget !== null}
        onOpenChange={(o) => {
          if (!o) setPickerTarget(null)
        }}
        rootCollectionId={relatedCollectionId || null}
        staticNamespaces={ENVELOPE_NAMESPACES}
        onInsert={handlePickerInsert}
        title={
          pickerTarget === 'subject'
            ? 'Insert into subject'
            : pickerTarget === 'bodyText'
              ? 'Insert into plain-text body'
              : 'Insert into body'
        }
      />
    </>
  )
}

/** Live preview of the subject and body; merge tags render as styled chips. */
function EmailTemplatePreview(): React.ReactElement {
  const { control } = useFormContext<EmailTemplateFormData>()
  const subject = useWatch({ control, name: 'subject' })
  const bodyHtml = useWatch({ control, name: 'bodyHtml' })
  const previewHtml = useMemo(
    () => tokenizeMergeTags(bodyHtml || '<p><em>Email body preview…</em></p>'),
    [bodyHtml]
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-muted/10">
      <div className="border-b border-border bg-background px-4 py-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        Preview
      </div>
      <div className="flex flex-1 flex-col gap-3 overflow-y-auto p-4">
        <div className="rounded-md border border-border bg-background px-4 py-3">
          <div className="text-[11px] uppercase tracking-wide text-muted-foreground">Subject</div>
          <div
            className="mt-1 break-words text-sm font-medium text-foreground"
            data-testid="email-template-preview-subject"
            dangerouslySetInnerHTML={{ __html: tokenizeMergeTags(subject || '') }}
          />
        </div>
        <div className="min-h-[320px] flex-1 overflow-hidden rounded-md border border-border bg-white">
          <iframe
            title="Email body preview"
            sandbox=""
            srcDoc={`<!doctype html><html><head><meta charset="utf-8"><style>
              :root { color-scheme: light; }
              html, body { background: #ffffff; }
              body { font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, sans-serif; padding: 16px; color: #111; margin: 0; line-height: 1.5; }
              body > *:first-child { margin-top: 0; }
              p { margin: 0 0 0.75em; }
              h1, h2, h3 { color: #111; margin: 1em 0 0.5em; }
              a { color: #2563eb; }
              [data-merge-tag] { display: inline-block; border-radius: 4px; background: rgba(99,102,241,0.15); color: #4338ca; padding: 0 4px; font-family: ui-monospace, monospace; font-size: 0.9em; }
            </style></head><body>${previewHtml}</body></html>`}
            className="h-full w-full"
            data-testid="email-template-preview-iframe"
          />
        </div>
        <div className="rounded-md border border-dashed border-border bg-background/50 px-3 py-2 text-[11px] text-muted-foreground">
          The preview shows merge tags as styled chips. Real values will be substituted when the
          email is sent.
        </div>
      </div>
    </div>
  )
}

export function EmailTemplatesPage({
  testId = 'email-templates-page',
}: EmailTemplatesPageProps): React.ReactElement {
  const { formatDate } = useI18n()
  const { keltaClient } = useApi()

  const [logsItemId, setLogsItemId] = useState<string | null>(null)
  const [logsItemName, setLogsItemName] = useState('')

  const {
    data: allEmailLogs,
    isLoading: logsLoading,
    error: logsError,
  } = useQuery({
    queryKey: ['email-template-logs', logsItemId],
    queryFn: () => keltaClient.admin.emailTemplates.listLogs(),
    enabled: !!logsItemId,
  })

  const filteredLogs = ((allEmailLogs ?? []) as EmailLog[]).filter(
    (log: EmailLog) => log.templateId === logsItemId
  )

  const logColumns: LogColumn<EmailLog>[] = [
    { key: 'status', header: 'Status' },
    { key: 'recipientEmail', header: 'Recipient' },
    { key: 'subject', header: 'Subject' },
    { key: 'source', header: 'Source' },
    { key: 'errorMessage', header: 'Error' },
    {
      key: 'sentAt',
      header: 'Sent At',
      render: (v) =>
        v
          ? formatDate(new Date(v as string), {
              year: 'numeric',
              month: 'short',
              day: 'numeric',
              hour: '2-digit',
              minute: '2-digit',
            })
          : '-',
    },
  ]

  const {
    data: templates,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['email-templates'],
    queryFn: () => keltaClient.admin.emailTemplates.list(),
  })

  const { data: collectionsData } = useQuery({
    queryKey: ['email-template-collections'],
    queryFn: () => keltaClient.admin.collections.list(),
    staleTime: 5 * 60 * 1000,
  })

  const collections: CollectionOption[] = useMemo(() => {
    return (
      (collectionsData ?? []) as Array<{
        id?: string
        name: string
        displayName?: string
      }>
    ).map((c) => ({
      id: c.id ?? '',
      name: c.name,
      displayName: c.displayName ?? c.name,
    }))
  }, [collectionsData])

  const templateList: EmailTemplate[] = templates ?? []

  const crud = useAdminCrud<EmailTemplate, EmailTemplateFormData>({
    queryKey: ['email-templates'],
    create: (data) =>
      keltaClient.admin.emailTemplates.create(
        '',
        '',
        data as unknown as CreateEmailTemplateRequest
      ),
    update: (template, data) =>
      keltaClient.admin.emailTemplates.update(
        template.id,
        data as unknown as Partial<CreateEmailTemplateRequest>
      ),
    remove: (template) => keltaClient.admin.emailTemplates.delete(template.id),
    messages: {
      created: 'Email template created successfully',
      updated: 'Email template updated successfully',
      deleted: 'Email template deleted successfully',
      error: 'An error occurred',
    },
  })

  const columns = useMemo<AdminColumn<EmailTemplate>[]>(
    () => [
      { id: 'name', header: 'Name', accessor: (r) => r.name },
      { id: 'subject', header: 'Subject', accessor: (r) => r.subject },
      {
        id: 'folder',
        header: 'Folder',
        accessor: (r) => r.folder ?? '',
        cell: (r) => r.folder || '-',
      },
      {
        id: 'active',
        header: 'Active',
        accessor: (r) => r.active,
        cell: (r) => (
          <span
            className={cn(
              'inline-block rounded-full px-3 py-1 text-xs font-semibold',
              r.active
                ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                : 'bg-muted text-muted-foreground'
            )}
          >
            {r.active ? 'Yes' : 'No'}
          </span>
        ),
      },
      {
        id: 'createdAt',
        header: 'Created',
        accessor: (r) => r.createdAt,
        cell: (r) =>
          formatDate(new Date(r.createdAt), {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          }),
      },
    ],
    [formatDate]
  )

  return (
    <AdminCrudPage
      testId={testId}
      title="Email templates"
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label="Create Email Template"
          data-testid="add-email-template-button"
        >
          Create Email Template
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={templateList.length === 0}
      emptyMessage="No email templates found."
      list={
        <AdminDataTable
          tableId="email-templates"
          testId="email-templates-table"
          ariaLabel="Email Templates"
          rows={templateList}
          rowKey={(t) => t.id}
          rowTestId={(_, index) => `email-template-row-${index}`}
          columns={columns}
          renderActions={(template, index) => (
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setLogsItemId(template.id)
                  setLogsItemName(template.name)
                }}
                aria-label={`View logs for ${template.name}`}
                data-testid={`logs-button-${index}`}
              >
                Logs
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(template)}
                aria-label={`Edit ${template.name}`}
                data-testid={`edit-button-${index}`}
              >
                Edit
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(template)}
                aria-label={`Delete ${template.name}`}
                data-testid={`delete-button-${index}`}
              >
                Delete
              </Button>
            </div>
          )}
        />
      }
    >
      {logsItemId && (
        <ExecutionLogModal<EmailLog>
          title="Email Logs"
          subtitle={logsItemName}
          columns={logColumns}
          data={filteredLogs}
          isLoading={logsLoading}
          error={logsError instanceof Error ? logsError : null}
          onClose={() => setLogsItemId(null)}
          emptyMessage="No email logs found."
        />
      )}

      <CrudFormDialog<EmailTemplateFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={crud.editing ? 'Edit Email Template' : 'Create Email Template'}
        schema={emailTemplateSchema}
        defaultValues={templateDefaults(crud.editing)}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="email-template"
        className="h-[90vh] sm:max-w-[1400px]"
        aside={<EmailTemplatePreview />}
      >
        <EmailTemplateFields collections={collections} />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete Email Template"
        message="Are you sure you want to delete this email template? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </AdminCrudPage>
  )
}
