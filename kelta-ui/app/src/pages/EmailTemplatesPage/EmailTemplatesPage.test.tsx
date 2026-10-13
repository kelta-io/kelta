/**
 * EmailTemplatesPage Tests — list, empty state, the template editor (a
 * CrudFormDialog with a live-preview aside: create, edit, validation, preview,
 * update payload, Escape) and delete. The editor internals are covered by the
 * RichTextEditor / FieldExpressionPicker tests and the Playwright spec.
 */

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { EmailTemplatesPage } from './EmailTemplatesPage'
import { createTestWrapper, setupAuthMocks, mockAxios, resetMockAxios } from '../../test/testUtils'

const TEMPLATE = {
  id: 'et-1',
  name: 'Welcome',
  description: null,
  subject: 'Welcome aboard',
  bodyHtml: '<p>Hello</p>',
  bodyText: null,
  relatedCollectionId: null,
  folder: null,
  active: true,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
}

function mockLists(templates: unknown[]) {
  mockAxios.get.mockImplementation((url: string) => {
    if (url === '/api/email-templates') return Promise.resolve({ data: templates })
    if (url === '/api/collections') return Promise.resolve({ data: [] })
    return Promise.resolve({ data: {} })
  })
}

describe('EmailTemplatesPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  it('renders the templates table with row test ids', async () => {
    mockLists([TEMPLATE])
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })

    const table = await screen.findByTestId('email-templates-table')
    expect(within(table).getByText('Welcome')).toBeInTheDocument()
    expect(screen.getByTestId('email-template-row-0')).toHaveTextContent('Welcome aboard')
  })

  it('renders the empty state when there are no templates', async () => {
    mockLists([])
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })
    expect(await screen.findByTestId('empty-state')).toHaveTextContent('No email templates found.')
  })

  it('opens the builder blank for create and pre-filled for edit', async () => {
    const user = userEvent.setup()
    mockLists([TEMPLATE])
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-email-template-button'))
    expect(await screen.findByTestId('email-template-form-modal')).toHaveTextContent(
      'Create Email Template'
    )
    expect(screen.getByTestId('email-template-name-input')).toHaveValue('')
    await user.click(screen.getByTestId('email-template-form-cancel'))
    await waitFor(() =>
      expect(screen.queryByTestId('email-template-form-modal')).not.toBeInTheDocument()
    )

    await user.click(screen.getByTestId('edit-button-0'))
    expect(await screen.findByTestId('email-template-form-modal')).toHaveTextContent(
      'Edit Email Template'
    )
    expect(screen.getByTestId('email-template-name-input')).toHaveValue('Welcome')
    expect(screen.getByTestId('email-template-subject-input')).toHaveValue('Welcome aboard')
  })

  it('blocks save until the required fields are filled', async () => {
    const user = userEvent.setup()
    mockLists([])
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-email-template-button'))
    await user.click(await screen.findByTestId('email-template-form-submit'))

    expect(await screen.findByText('Name is required')).toBeInTheDocument()
    expect(screen.getByText('Subject is required')).toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('previews the subject as it is typed', async () => {
    const user = userEvent.setup()
    mockLists([])
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-email-template-button'))
    await user.type(await screen.findByTestId('email-template-subject-input'), 'Hi there')
    expect(screen.getByTestId('email-template-preview-subject')).toHaveTextContent('Hi there')
  })

  it('saves edits with the full template payload', async () => {
    const user = userEvent.setup()
    mockLists([TEMPLATE])
    mockAxios.patch.mockResolvedValueOnce({ data: TEMPLATE })
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('edit-button-0'))
    const name = await screen.findByTestId('email-template-name-input')
    await user.clear(name)
    await user.type(name, 'Welcome v2')
    await user.click(screen.getByTestId('email-template-form-submit'))

    await waitFor(() => expect(mockAxios.patch).toHaveBeenCalled())
    const [url, body] = mockAxios.patch.mock.calls[0]
    expect(url).toBe('/api/email-templates/et-1')
    expect(body.data.attributes).toEqual(
      expect.objectContaining({
        name: 'Welcome v2',
        subject: 'Welcome aboard',
        bodyHtml: '<p>Hello</p>',
        active: true,
      })
    )
    await waitFor(() =>
      expect(screen.queryByTestId('email-template-form-modal')).not.toBeInTheDocument()
    )
  })

  it('closes the editor on Escape without saving', async () => {
    const user = userEvent.setup()
    mockLists([])
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-email-template-button'))
    await screen.findByTestId('email-template-form-modal')
    await user.keyboard('{Escape}')
    await waitFor(() =>
      expect(screen.queryByTestId('email-template-form-modal')).not.toBeInTheDocument()
    )
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('deletes a template after confirmation', async () => {
    const user = userEvent.setup()
    mockLists([TEMPLATE])
    mockAxios.delete.mockResolvedValueOnce({ data: {} })
    render(<EmailTemplatesPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('delete-button-0'))
    const confirm = await screen.findByTestId('confirm-dialog')
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(mockAxios.delete).toHaveBeenCalledWith('/api/email-templates/et-1'))
    expect(await screen.findByText('Email template deleted successfully')).toBeInTheDocument()
  })
})
