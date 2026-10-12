/**
 * The `form` typed-input widget.
 *
 * In `mode:'runtime'` the `form` widget renders the app's one record form, `RecordForm`
 * (`components/record/form/`), embedded (`presentation="embedded"`): the same schema, permissions,
 * record types, page layout, field controls (the `components/fieldControl` registry), client
 * validation, `If-Match` concurrency and save path as the record create/edit pages, minus the page
 * chrome — no breadcrumb, an `h2` title, no Cancel, and it never navigates. After a save it stays on
 * the page: the table widgets bound to the collection refetch, the node's `events.onSubmit` actions
 * run with the saved record as `record`, and a create resets to a blank form.
 *
 * Config (unchanged shape, so saved pages keep working):
 * - `dataView.collection` — the collection; unset → the "no data source" box.
 * - `recordId` (bindable, already resolved) — edit that record; absent → create. `mode:'edit'` with
 *   no record id shows a "no record selected" box instead of silently creating.
 * - `readOnly` — show the record without Save.
 * - `dataView.fields` — optional subset (and order) of fields; empty = every field.
 *
 * In `mode:'editor'` it renders the static placeholder (`page-node-form`), so the canvas needs no
 * live data and the 2a golden snapshot stays stable.
 */
/* eslint-disable react-refresh/only-export-components -- widget-descriptor module, not an HMR component file */
import React, { useCallback, useMemo } from 'react'
import { FileEdit } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useI18n } from '@/context/I18nContext'
import { RecordForm } from '@/components/record/form/RecordForm'
import type { CollectionRecord } from '@/hooks/useCollectionRecords'
import type { BindingScope } from '../../model/bindingScope'
import { useActionRunner } from '../../runtime/useActionRunner'
import type { WidgetDescriptor, WidgetRenderProps } from '../types'
import { asString, asStringList } from '../util'

/** Read `props.dataView.collection` (shared shape with the `table` widget). */
function readFormCollection(props: Record<string, unknown> | undefined): string | undefined {
  const dv = props?.dataView
  if (dv && typeof dv === 'object') {
    return asString((dv as Record<string, unknown>).collection) || undefined
  }
  return undefined
}

/** Read the optional `props.dataView.fields` subset (string entries only). */
function readFormFields(props: Record<string, unknown> | undefined): string[] {
  const dv = props?.dataView
  return dv && typeof dv === 'object' ? asStringList((dv as Record<string, unknown>).fields) : []
}

/** Static editor-mode placeholder — identical to the 2a `form` placeholder (keeps the golden snapshot). */
function FormPlaceholder(): React.ReactElement {
  const { t } = useI18n()
  return (
    <div
      className="flex flex-col items-center justify-center gap-2 rounded-md border border-dashed border-border bg-muted p-6 text-muted-foreground"
      data-testid="page-node-form"
    >
      <span>
        <FileEdit size={24} />
      </span>
      <span>{t('builder.form.label')}</span>
    </div>
  )
}

/** Dashed info box for a runtime form that cannot render a record form yet. */
function FormNotice({ message }: { message: string }): React.ReactElement {
  return (
    <div
      className="rounded-md border border-dashed border-border p-4 text-sm text-muted-foreground"
      data-testid="page-node-form"
    >
      {message}
    </div>
  )
}

/** Runtime form: the shared `RecordForm`, embedded and bound to the configured collection. */
function RuntimeForm({
  node,
  scope,
  collection,
  recordId,
}: {
  node: WidgetRenderProps['node']
  scope: BindingScope
  collection: string
  recordId: string | undefined
}): React.ReactElement {
  const queryClient = useQueryClient()
  const { run } = useActionRunner()
  const readOnly = node.props?.readOnly === true
  // Bindings resolve to fresh objects each render; keep the subset stable while its names are.
  const fieldKey = readFormFields(node.props).join(',')
  const fieldNames = useMemo(() => (fieldKey ? fieldKey.split(',') : []), [fieldKey])
  const onSubmit = node.events?.onSubmit

  const handleSaved = useCallback(
    (saved: CollectionRecord | undefined) => {
      void queryClient.invalidateQueries({ queryKey: ['page-table', collection] })
      // The saved record is the `record` the onSubmit actions see (e.g. navigate to {{record.id}}).
      void run(onSubmit, { ...scope, record: { ...scope.record, ...saved } })
    },
    [queryClient, collection, run, onSubmit, scope]
  )

  return (
    <div data-testid="page-node-form" data-collection={collection}>
      <RecordForm
        presentation="embedded"
        variant="enduser"
        collectionName={collection}
        recordId={recordId}
        readOnly={readOnly}
        fieldNames={fieldNames}
        onSaved={handleSaved}
        testId="page-form"
      />
    </div>
  )
}

function FormRender({ node, mode, scope }: WidgetRenderProps): React.ReactElement {
  const { t } = useI18n()
  if (mode === 'editor') return <FormPlaceholder />
  const collection = readFormCollection(node.props)
  if (!collection) return <FormNotice message={t('builder.form.noDataSource')} />
  // recordId is already binding-resolved by renderNode (resolved-node invariant).
  const recordId = asString(node.props?.recordId) || undefined
  // An edit form whose record binding is still empty must not fall back to creating records.
  if (node.props?.mode === 'edit' && !recordId) {
    return <FormNotice message={t('builder.form.noRecord')} />
  }
  return <RuntimeForm node={node} scope={scope} collection={collection} recordId={recordId} />
}

const form: WidgetDescriptor = {
  type: 'form',
  label: 'Form',
  icon: FileEdit,
  category: 'input',
  acceptsChildren: false,
  defaultProps: { dataView: { collection: '' }, mode: 'create', readOnly: false },
  propSchema: [
    { key: 'dataView.collection', label: 'Collection', kind: 'collection-picker', group: 'Data' },
    {
      key: 'dataView.fields',
      label: 'Fields',
      kind: 'field-picker',
      group: 'Data',
      dependsOnCollection: true,
    },
    {
      key: 'mode',
      label: 'Mode',
      kind: 'select',
      group: 'Data',
      options: [
        { value: 'create', label: 'Create' },
        { value: 'edit', label: 'Edit' },
      ],
    },
    { key: 'recordId', label: 'Record id', kind: 'expression', bindable: true, group: 'Data' },
    { key: 'readOnly', label: 'Read-only', kind: 'boolean', group: 'Data' },
    { key: 'events', label: 'Events', kind: 'event-list', group: 'Behaviour' },
  ],
  supportedEvents: ['onSubmit'],
  Render: FormRender,
}

export const formWidgets: WidgetDescriptor[] = [form]
