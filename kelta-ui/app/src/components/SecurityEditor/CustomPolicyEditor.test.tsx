/**
 * CustomPolicyEditor tests
 *
 * The server validates a custom rule on save and owns the tenant, so the editor sends no tenant,
 * keeps the form open when a rule is refused, and reads rules in the shape the list endpoint serves.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CustomPolicyEditor } from './CustomPolicyEditor'

const { mockGet, mockPost, mockPut, mockDelete, mockToastError } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  mockPost: vi.fn(),
  mockPut: vi.fn(),
  mockDelete: vi.fn(),
  mockToastError: vi.fn(),
}))

vi.mock('@/context/ApiContext', () => ({
  useApi: vi.fn(() => ({
    apiClient: { get: mockGet, post: mockPost, put: mockPut, delete: mockDelete },
  })),
}))

vi.mock('@/hooks/useCollectionSummaries', () => ({
  useCollectionSummaries: () => ({
    summaries: [{ id: 'col-1', name: 'accounts', displayName: 'Accounts' }],
    isLoading: false,
    error: null,
  }),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: mockToastError },
}))

const inRule = {
  id: 'r-1',
  profileId: 'p-1',
  tenantId: 't-1',
  collectionId: 'col-1',
  action: 'edit',
  effect: 'deny',
  conditionType: 'visual',
  condition: { field: 'region', operator: 'in', value: ['US', 'EU'] },
  enabled: true,
}

function renderEditor() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <CustomPolicyEditor profileId="p-1" testId="cpe" />
    </QueryClientProvider>
  )
}

describe('CustomPolicyEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGet.mockResolvedValue([])
  })

  it('lists a rule served by the API, including an in list', async () => {
    mockGet.mockResolvedValue([inRule])

    renderEditor()

    const row = await screen.findByTestId('cpe-rule-r-1')
    expect(row.textContent).toContain('deny')
    expect(row.textContent).toContain('Accounts')
    expect(row.textContent).toContain('[when region in US, EU]')
  })

  it('does not crash on a stored rule without a readable condition', async () => {
    mockGet.mockResolvedValue([{ ...inRule, condition: null }])

    renderEditor()

    expect(await screen.findByTestId('cpe-rule-r-1')).toBeInTheDocument()
  })

  it('sends no tenant and keeps the form open when the server refuses the rule', async () => {
    const user = userEvent.setup()
    mockPost.mockRejectedValue(new Error('operator must be one of contains, equals, greater_than'))

    renderEditor()
    await user.click(await screen.findByTestId('cpe-add-button'))
    await user.type(screen.getByTestId('cpe-field-input'), 'status')
    await user.type(screen.getByTestId('cpe-value-input'), 'He said "hi"')
    await user.click(screen.getByTestId('cpe-save-button'))

    await waitFor(() =>
      expect(mockToastError).toHaveBeenCalledWith(
        'operator must be one of contains, equals, greater_than'
      )
    )
    const payload = mockPost.mock.calls[0][1] as Record<string, unknown>
    expect(payload).not.toHaveProperty('tenantId')
    expect(payload).toMatchObject({
      effect: 'deny',
      conditionType: 'visual',
      condition: { field: 'status', operator: 'equals', value: 'He said "hi"' },
    })
    expect(screen.getByTestId('cpe-form')).toBeInTheDocument()
    expect(screen.getByTestId('cpe-value-input')).toHaveValue('He said "hi"')
  })

  it('closes the form once the server accepts the rule', async () => {
    const user = userEvent.setup()
    mockPost.mockResolvedValue({ id: 'r-2' })

    renderEditor()
    await user.click(await screen.findByTestId('cpe-add-button'))
    await user.type(screen.getByTestId('cpe-field-input'), 'status')
    await user.click(screen.getByTestId('cpe-save-button'))

    await waitFor(() => expect(screen.queryByTestId('cpe-form')).not.toBeInTheDocument())
  })

  it('edits an in rule as comma-separated text with a matching hint', async () => {
    const user = userEvent.setup()
    mockGet.mockResolvedValue([inRule])

    renderEditor()
    await user.click(await screen.findByTestId('cpe-edit-r-1'))

    const value = screen.getByTestId('cpe-value-input')
    expect(value).toHaveValue('US, EU')
    expect(value).toHaveAttribute('placeholder', 'comma-separated, e.g., US, EU')
  })
})
