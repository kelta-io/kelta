/**
 * AdminCrud building-block tests: useAdminCrud (create/update/delete dispatch,
 * toasts, list invalidation), CrudFormDialog (zod validation, Escape, close,
 * labelled dialog, initial focus) and the AdminCrudPage shell states.
 */

import React from 'react'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { z } from 'zod'
import { I18nProvider } from '../../../context/I18nContext'
import { ToastProvider } from '../../Toast'
import { ConfirmDialog } from '../../ConfirmDialog'
import {
  AdminCrudPage,
  CrudCheckboxField,
  CrudFormDialog,
  CrudTextField,
  useAdminCrud,
  type CrudMessages,
} from '..'

interface Thing {
  id: string
  name: string
  active: boolean
}

const thingSchema = z.object({
  name: z.string().min(1, 'Name is required').max(10, 'Name is too long'),
  active: z.boolean(),
})
type ThingValues = z.infer<typeof thingSchema>

const ROWS: Thing[] = [{ id: 't1', name: 'Alpha', active: true }]

const MESSAGES: CrudMessages = {
  created: 'Thing created',
  updated: 'Thing updated',
  deleted: 'Thing deleted',
  error: 'Something failed',
  deleteError: 'Delete failed',
}

interface Api {
  create: Mock<(values: ThingValues) => Promise<unknown>>
  update: Mock<(row: Thing, values: ThingValues) => Promise<unknown>>
  remove: Mock<(row: Thing) => Promise<unknown>>
}

function Harness({ api }: { api: Api }) {
  const crud = useAdminCrud<Thing, ThingValues>({
    queryKey: ['things'],
    create: api.create,
    update: api.update,
    remove: api.remove,
    messages: MESSAGES,
  })
  return (
    <>
      <button onClick={crud.openCreate}>new thing</button>
      {ROWS.map((row) => (
        <div key={row.id}>
          <button onClick={() => crud.openEdit(row)}>edit {row.id}</button>
          <button onClick={() => crud.requestDelete(row)}>delete {row.id}</button>
        </div>
      ))}
      <CrudFormDialog<ThingValues>
        open={crud.isFormOpen}
        onClose={crud.closeForm}
        title={crud.editing ? 'Edit thing' : 'New thing'}
        schema={thingSchema}
        defaultValues={{ name: crud.editing?.name ?? '', active: crud.editing?.active ?? false }}
        onSubmit={crud.submit}
        isSubmitting={crud.isSaving}
        testIdPrefix="thing"
      >
        <CrudTextField<ThingValues>
          name="name"
          id="thing-name"
          label="Name"
          required
          testId="thing-name-input"
          errorTestId="thing-name-error"
        />
        <CrudCheckboxField<ThingValues>
          name="active"
          id="thing-active"
          label="Active"
          testId="thing-active-input"
        />
      </CrudFormDialog>
      <ConfirmDialog
        open={crud.deleteTarget !== null}
        title="Delete thing"
        message={`Delete ${crud.deleteTarget?.name}?`}
        confirmLabel="Delete"
        onConfirm={crud.confirmDelete}
        onCancel={crud.cancelDelete}
        variant="danger"
      />
    </>
  )
}

function renderHarness(api: Api) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
  render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <ToastProvider>
          <Harness api={api} />
        </ToastProvider>
      </I18nProvider>
    </QueryClientProvider>
  )
  return { invalidate }
}

function makeApi(): Api {
  return {
    create: vi.fn<(values: ThingValues) => Promise<unknown>>().mockResolvedValue({ id: 't2' }),
    update: vi
      .fn<(row: Thing, values: ThingValues) => Promise<unknown>>()
      .mockResolvedValue({ id: 't1' }),
    remove: vi.fn<(row: Thing) => Promise<unknown>>().mockResolvedValue(undefined),
  }
}

describe('AdminCrud', () => {
  let api: Api

  beforeEach(() => {
    api = makeApi()
  })

  it('opens a labelled dialog and focuses the first field', async () => {
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('new thing'))

    const dialog = await screen.findByRole('dialog', { name: 'New thing' })
    expect(dialog).toHaveAttribute('data-testid', 'thing-form-modal')
    await waitFor(() => expect(screen.getByTestId('thing-name-input')).toHaveFocus())
  })

  it('shows zod validation errors and does not submit invalid values', async () => {
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('new thing'))
    await user.click(await screen.findByTestId('thing-form-submit'))

    const error = await screen.findByTestId('thing-name-error')
    expect(error).toHaveTextContent('Name is required')
    expect(error).toHaveAttribute('role', 'alert')
    const input = screen.getByTestId('thing-name-input')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'thing-name-error')
    expect(api.create).not.toHaveBeenCalled()

    // Re-validates on change once touched.
    await user.type(input, 'way too long a name')
    expect(await screen.findByTestId('thing-name-error')).toHaveTextContent('Name is too long')
    await user.clear(input)
    await user.type(input, 'Ok')
    await waitFor(() => expect(screen.queryByTestId('thing-name-error')).not.toBeInTheDocument())
  })

  it('creates, toasts, invalidates the list and closes the dialog', async () => {
    const user = userEvent.setup()
    const { invalidate } = renderHarness(api)
    await user.click(screen.getByText('new thing'))
    await user.type(await screen.findByTestId('thing-name-input'), 'Bravo')
    await user.click(screen.getByTestId('thing-active-input'))
    await user.click(screen.getByTestId('thing-form-submit'))

    await waitFor(() => expect(api.create).toHaveBeenCalledWith({ name: 'Bravo', active: true }))
    expect(await screen.findByText('Thing created')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['things'] })
    await waitFor(() => expect(screen.queryByTestId('thing-form-modal')).not.toBeInTheDocument())
    expect(api.update).not.toHaveBeenCalled()
  })

  it('pre-fills the edit form and dispatches update with the edited row', async () => {
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('edit t1'))

    await screen.findByRole('dialog', { name: 'Edit thing' })
    const input = screen.getByTestId('thing-name-input')
    expect(input).toHaveValue('Alpha')
    expect(screen.getByTestId('thing-active-input')).toBeChecked()

    await user.clear(input)
    await user.type(input, 'Alpha 2')
    await user.click(screen.getByTestId('thing-form-submit'))

    await waitFor(() =>
      expect(api.update).toHaveBeenCalledWith(ROWS[0], { name: 'Alpha 2', active: true })
    )
    expect(await screen.findByText('Thing updated')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('thing-form-modal')).not.toBeInTheDocument())
    expect(api.create).not.toHaveBeenCalled()
  })

  it('reopening create after edit starts from blank defaults', async () => {
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('edit t1'))
    expect(await screen.findByTestId('thing-name-input')).toHaveValue('Alpha')
    await user.click(screen.getByTestId('thing-form-cancel'))
    await waitFor(() => expect(screen.queryByTestId('thing-form-modal')).not.toBeInTheDocument())

    await user.click(screen.getByText('new thing'))
    expect(await screen.findByTestId('thing-name-input')).toHaveValue('')
  })

  it('closes on Escape and on the close button without submitting', async () => {
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('new thing'))
    await screen.findByTestId('thing-form-modal')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('thing-form-modal')).not.toBeInTheDocument())

    await user.click(screen.getByText('new thing'))
    await user.click(await screen.findByTestId('thing-form-close'))
    await waitFor(() => expect(screen.queryByTestId('thing-form-modal')).not.toBeInTheDocument())
    expect(api.create).not.toHaveBeenCalled()
  })

  it('keeps the dialog open and toasts the server message when save fails', async () => {
    api.create.mockRejectedValueOnce(new Error('Name already taken'))
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('new thing'))
    await user.type(await screen.findByTestId('thing-name-input'), 'Dup')
    await user.click(screen.getByTestId('thing-form-submit'))

    expect(await screen.findByText('Name already taken')).toBeInTheDocument()
    expect(screen.getByTestId('thing-form-modal')).toBeInTheDocument()
  })

  it('confirms delete, toasts and clears the target', async () => {
    const user = userEvent.setup()
    const { invalidate } = renderHarness(api)
    await user.click(screen.getByText('delete t1'))

    const confirm = await screen.findByTestId('confirm-dialog')
    expect(within(confirm).getByText('Delete Alpha?')).toBeInTheDocument()
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(api.remove).toHaveBeenCalledWith(ROWS[0]))
    expect(await screen.findByText('Thing deleted')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['things'] })
    await waitFor(() => expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument())
  })

  it('uses the per-operation fallback when a delete error has no message', async () => {
    api.remove.mockRejectedValueOnce(new Error(''))
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('delete t1'))
    const confirm = await screen.findByTestId('confirm-dialog')
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Delete failed')).toBeInTheDocument()
  })

  it('cancelling delete does not call remove', async () => {
    const user = userEvent.setup()
    renderHarness(api)
    await user.click(screen.getByText('delete t1'))
    const confirm = await screen.findByTestId('confirm-dialog')
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument())
    expect(api.remove).not.toHaveBeenCalled()
  })
})

describe('AdminCrudPage', () => {
  const base = {
    testId: 'things-page',
    title: 'Things',
    isLoading: false,
    isEmpty: false,
    emptyMessage: 'No things yet.',
    list: <table data-testid="things-table" />,
  }

  function renderPage(props: Partial<React.ComponentProps<typeof AdminCrudPage>>) {
    return render(
      <I18nProvider>
        <AdminCrudPage {...base} {...props}>
          <div data-testid="dialogs" />
        </AdminCrudPage>
      </I18nProvider>
    )
  }

  it('renders the spinner while loading', () => {
    renderPage({ isLoading: true })
    expect(screen.getByTestId('loading-spinner-label')).toHaveTextContent('Loading...')
    expect(screen.queryByTestId('things-table')).not.toBeInTheDocument()
  })

  it('renders the load error with retry', async () => {
    const onRetry = vi.fn()
    renderPage({ error: new Error('boom'), loadErrorMessage: 'Failed to load things.', onRetry })
    expect(screen.getByText('Failed to load things.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('renders the empty state and still mounts dialogs', () => {
    renderPage({ isEmpty: true, actions: <button>New thing</button> })
    expect(screen.getByTestId('empty-state')).toHaveTextContent('No things yet.')
    expect(screen.queryByTestId('things-table')).not.toBeInTheDocument()
    expect(screen.getByTestId('dialogs')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Things' })).toBeInTheDocument()
    expect(screen.getByText('New thing')).toBeInTheDocument()
  })

  it('renders the list when loaded', () => {
    renderPage({ description: 'All the things.' })
    expect(screen.getByTestId('things-page')).toBeInTheDocument()
    expect(screen.getByTestId('things-table')).toBeInTheDocument()
    expect(screen.getByText('All the things.')).toBeInTheDocument()
    expect(screen.queryByTestId('empty-state')).not.toBeInTheDocument()
  })
})
