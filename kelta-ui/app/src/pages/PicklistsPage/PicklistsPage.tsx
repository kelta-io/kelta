import React, { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { ConfirmDialog } from '../../components'
import { PicklistValuesEditor } from '../../components/PicklistValuesEditor'
import { AdminDataTable, type AdminColumn } from '../../components/AdminDataTable'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudTextField,
  useAdminCrud,
} from '../../components/AdminCrud'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import type { CreateGlobalPicklistRequest } from '@kelta/sdk'

interface GlobalPicklist {
  id: string
  tenantId: string
  name: string
  description: string | null
  sorted: boolean
  restricted: boolean
  createdAt: string
  updatedAt: string
}

function buildPicklistSchema(t: (key: string) => string) {
  return z.object({
    name: z
      .string()
      .refine((v) => v.trim().length > 0, t('picklists.validation.nameRequired'))
      .refine((v) => v.length <= 100, t('picklists.validation.nameTooLong')),
    description: z.string().max(500, t('picklists.validation.descriptionTooLong')),
    sorted: z.boolean(),
    restricted: z.boolean(),
  })
}

type PicklistFormData = z.infer<ReturnType<typeof buildPicklistSchema>>

export interface PicklistsPageProps {
  testId?: string
}

function YesNoBadge({ value }: { value: boolean }): React.ReactElement {
  const { t } = useI18n()
  return (
    <span
      className={cn(
        'inline-block rounded-full px-3 py-1 text-xs font-semibold',
        value
          ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
          : 'bg-muted text-muted-foreground'
      )}
    >
      {value ? t('common.yes') : t('common.no')}
    </span>
  )
}

export function PicklistsPage({
  testId = 'picklists-page',
}: PicklistsPageProps): React.ReactElement {
  const { t, formatDate } = useI18n()
  const { keltaClient } = useApi()
  const [valuesPicklist, setValuesPicklist] = useState<GlobalPicklist | null>(null)

  const {
    data: picklists,
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['picklists'],
    queryFn: () => keltaClient.admin.picklists.listGlobal() as Promise<GlobalPicklist[]>,
  })
  const picklistList = picklists ?? []

  const item = t('picklists.title')
  const crud = useAdminCrud<GlobalPicklist, PicklistFormData>({
    queryKey: ['picklists'],
    create: (data) => keltaClient.admin.picklists.createGlobal(data as CreateGlobalPicklistRequest),
    update: (pl, data) =>
      keltaClient.admin.picklists.updateGlobal(pl.id, data as Partial<CreateGlobalPicklistRequest>),
    remove: (pl) => keltaClient.admin.picklists.deleteGlobal(pl.id),
    messages: {
      created: t('success.created', { item }),
      updated: t('success.updated', { item }),
      deleted: t('success.deleted', { item }),
      error: t('errors.generic'),
    },
  })

  const schema = useMemo(() => buildPicklistSchema(t), [t])

  const columns = useMemo<AdminColumn<GlobalPicklist>[]>(
    () => [
      { id: 'name', header: t('picklists.picklistName'), cellClassName: 'text-foreground' },
      {
        id: 'description',
        header: t('picklists.description'),
        cell: (pl) => pl.description || '-',
        cellClassName: 'max-w-[300px] truncate text-muted-foreground',
      },
      {
        id: 'sorted',
        header: t('picklists.sorted'),
        cell: (pl) => <YesNoBadge value={pl.sorted} />,
      },
      {
        id: 'restricted',
        header: t('picklists.restricted'),
        cell: (pl) => <YesNoBadge value={pl.restricted} />,
      },
      {
        id: 'createdAt',
        header: t('collections.created'),
        cell: (pl) =>
          formatDate(new Date(pl.createdAt), { year: 'numeric', month: 'short', day: 'numeric' }),
      },
    ],
    [t, formatDate]
  )

  const editing = crud.editing

  return (
    <AdminCrudPage
      testId={testId}
      title={t('picklists.title')}
      actions={
        <Button
          type="button"
          onClick={crud.openCreate}
          aria-label={t('picklists.addPicklist')}
          data-testid="add-picklist-button"
        >
          {t('picklists.addPicklist')}
        </Button>
      }
      isLoading={isLoading}
      error={error}
      onRetry={() => refetch()}
      isEmpty={picklistList.length === 0}
      emptyMessage={t('common.noResults')}
      list={
        <AdminDataTable
          tableId="picklists"
          testId="picklists-table"
          ariaLabel={t('picklists.title')}
          actionsHeader={t('common.actions')}
          columns={columns}
          rows={picklistList}
          rowKey={(pl) => pl.id}
          rowTestId={(_, index) => `picklist-row-${index}`}
          renderActions={(pl, index) => (
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setValuesPicklist(pl)}
                aria-label={`${t('picklists.manageValues')} ${pl.name}`}
                data-testid={`values-button-${index}`}
              >
                {t('picklists.manageValues')}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => crud.openEdit(pl)}
                aria-label={`${t('common.edit')} ${pl.name}`}
                data-testid={`edit-button-${index}`}
              >
                {t('common.edit')}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="border-destructive/30 text-destructive hover:bg-destructive/10"
                onClick={() => crud.requestDelete(pl)}
                aria-label={`${t('common.delete')} ${pl.name}`}
                data-testid={`delete-button-${index}`}
              >
                {t('common.delete')}
              </Button>
            </div>
          )}
        />
      }
    >
      <CrudFormDialog<PicklistFormData>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={editing ? t('picklists.editPicklist') : t('picklists.addPicklist')}
        schema={schema}
        defaultValues={{
          name: editing?.name ?? '',
          description: editing?.description ?? '',
          sorted: editing?.sorted ?? false,
          restricted: editing?.restricted ?? true,
        }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="picklist"
        className="sm:max-w-[600px]"
      >
        <CrudTextField<PicklistFormData>
          name="name"
          id="picklist-name"
          label={t('picklists.picklistName')}
          required
          placeholder={t('picklists.namePlaceholder')}
          disabled={!!editing}
          testId="picklist-name-input"
        />
        <CrudTextField<PicklistFormData>
          name="description"
          id="picklist-description"
          label={t('picklists.description')}
          multiline
          placeholder={t('picklists.descriptionPlaceholder')}
          testId="picklist-description-input"
        />
        <CrudCheckboxField<PicklistFormData>
          name="sorted"
          id="picklist-sorted"
          label={t('picklists.sorted')}
          testId="picklist-sorted-input"
        />
        <CrudCheckboxField<PicklistFormData>
          name="restricted"
          id="picklist-restricted"
          label={t('picklists.restricted')}
          testId="picklist-restricted-input"
        />
      </CrudFormDialog>

      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title={t('picklists.deletePicklist')}
        message={t('picklists.confirmDelete')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />

      {valuesPicklist && (
        <PicklistValuesEditor
          picklistId={valuesPicklist.id}
          picklistName={valuesPicklist.name}
          onClose={() => setValuesPicklist(null)}
        />
      )}
    </AdminCrudPage>
  )
}
