/**
 * CampaignsPage Tests — list, create (+ validation), edit gating and delete
 * for email campaigns, plus the suppressions section that sits under the list.
 * API calls flow through the SDK's mocked Axios instance (see testUtils.mockAxios).
 */

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { CampaignsPage } from './CampaignsPage'
import { createTestWrapper, setupAuthMocks, mockAxios, resetMockAxios } from '../../test/testUtils'

const DRAFT = {
  id: 'c1',
  name: 'Spring Sale',
  description: null,
  subject: 'Spring is here',
  bodyHtml: '<p>Hi</p>',
  targetCollection: 'contacts',
  recipientEmailField: 'email',
  fromName: null,
  fromAddress: null,
  status: 'DRAFT',
  scheduledAt: null,
  sentCount: 0,
  openCount: 0,
  clickCount: 0,
}

const SENT = { ...DRAFT, id: 'c2', name: 'Winter Recap', status: 'SENT', sentCount: 12 }

function mockLists(campaigns: unknown[]) {
  mockAxios.get.mockImplementation((url: string) => {
    if (url === '/api/admin/campaigns') return Promise.resolve({ data: campaigns })
    if (url === '/api/admin/campaigns/suppressions') return Promise.resolve({ data: [] })
    if (url === '/api/collections') {
      return Promise.resolve({
        data: [{ id: 'col-1', name: 'contacts', displayName: 'Contacts' }],
      })
    }
    return Promise.resolve({ data: {} })
  })
}

describe('CampaignsPage', () => {
  let cleanupAuthMocks: () => void

  beforeEach(() => {
    cleanupAuthMocks = setupAuthMocks()
    resetMockAxios()
  })

  afterEach(() => {
    cleanupAuthMocks()
    vi.restoreAllMocks()
  })

  it('renders the campaigns table and the suppressions section', async () => {
    mockLists([DRAFT])
    render(<CampaignsPage />, { wrapper: createTestWrapper() })

    const table = await screen.findByTestId('campaigns-table')
    expect(within(table).getByText('Spring Sale')).toBeInTheDocument()
    expect(screen.getByTestId('suppressions-section')).toBeInTheDocument()
  })

  it('renders the empty state with the suppressions section still available', async () => {
    mockLists([])
    render(<CampaignsPage />, { wrapper: createTestWrapper() })

    expect(await screen.findByTestId('empty-state')).toHaveTextContent('No campaigns found.')
    expect(screen.getByTestId('suppressions-section')).toBeInTheDocument()
  })

  it('only offers Edit for draft or scheduled campaigns', async () => {
    mockLists([DRAFT, SENT])
    render(<CampaignsPage />, { wrapper: createTestWrapper() })

    await screen.findByTestId('campaigns-table')
    expect(screen.getByTestId('edit-button-0')).toBeInTheDocument()
    expect(screen.queryByTestId('edit-button-1')).not.toBeInTheDocument()
  })

  it('validates required fields and the from address', async () => {
    const user = userEvent.setup()
    mockLists([])
    render(<CampaignsPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-campaign-button'))
    await user.type(await screen.findByTestId('campaign-from-address-input'), 'not-an-email')
    await user.click(screen.getByTestId('campaign-form-submit'))

    expect(await screen.findByText('Name is required')).toBeInTheDocument()
    expect(screen.getByText('Subject is required')).toBeInTheDocument()
    expect(screen.getByText('Target collection is required')).toBeInTheDocument()
    expect(screen.getByText('Recipient email field is required')).toBeInTheDocument()
    expect(screen.getByText('Enter a valid email address')).toBeInTheDocument()
    expect(mockAxios.post).not.toHaveBeenCalled()
  })

  it('creates a campaign with the same request shape as before', async () => {
    const user = userEvent.setup()
    mockLists([])
    mockAxios.post.mockResolvedValueOnce({
      data: { data: { type: 'campaigns', id: 'c9', attributes: DRAFT } },
    })
    render(<CampaignsPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('add-campaign-button'))
    await user.type(await screen.findByTestId('campaign-name-input'), 'Newsletter')
    await user.type(screen.getByTestId('campaign-subject-input'), 'Monthly news')
    await waitFor(() =>
      expect(
        within(screen.getByTestId('campaign-target-collection-input')).getByText('Contacts')
      ).toBeInTheDocument()
    )
    await user.selectOptions(screen.getByTestId('campaign-target-collection-input'), 'contacts')
    await user.type(screen.getByTestId('campaign-recipient-field-input'), 'email')
    await user.click(screen.getByTestId('campaign-form-submit'))

    await waitFor(() => expect(mockAxios.post).toHaveBeenCalledTimes(1))
    const [url, body] = mockAxios.post.mock.calls[0] as [
      string,
      { data: { attributes: Record<string, unknown> } },
    ]
    expect(url).toBe('/api/admin/campaigns')
    expect(body.data.attributes).toMatchObject({
      name: 'Newsletter',
      subject: 'Monthly news',
      targetCollection: 'contacts',
      recipientEmailField: 'email',
    })
    // Optional blanks are omitted rather than sent as empty strings.
    expect(body.data.attributes.fromAddress).toBeUndefined()
    expect(body.data.attributes.scheduledAt).toBeUndefined()
    expect(await screen.findByText('Campaign created successfully')).toBeInTheDocument()
  })

  it('deletes a campaign after confirmation', async () => {
    const user = userEvent.setup()
    mockLists([DRAFT])
    mockAxios.delete.mockResolvedValueOnce({ data: {} })
    render(<CampaignsPage />, { wrapper: createTestWrapper() })

    await user.click(await screen.findByTestId('delete-button-0'))
    const confirm = await screen.findByTestId('confirm-dialog')
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(mockAxios.delete).toHaveBeenCalledWith('/api/admin/campaigns/c1'))
    expect(await screen.findByText('Campaign deleted successfully')).toBeInTheDocument()
  })
})
