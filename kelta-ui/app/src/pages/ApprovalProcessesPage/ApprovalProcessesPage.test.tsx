import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalProcessesPage } from './ApprovalProcessesPage'
import {
  createTestWrapper,
  setupAuthMocks,
  getMockAxiosInstance,
  resetMockAxios,
} from '../../test/testUtils'

vi.mock('../../components/FieldExpressionPicker', () => ({
  FieldExpressionPicker: vi.fn(
    ({
      open,
      mode,
      rootCollectionId,
      allowedTypes,
      onInsert,
      testId,
    }: {
      open: boolean
      mode?: string
      rootCollectionId: string | null
      allowedTypes?: string[]
      onInsert: (token: string) => void
      testId?: string
    }) => {
      if (!open) return null
      return (
        <div
          data-testid={testId ?? 'field-expression-picker'}
          data-mode={mode}
          data-collection-id={rootCollectionId ?? ''}
          data-allowed-types={allowedTypes?.join(',')}
        >
          <button onClick={() => onInsert('status')}>Insert status</button>
        </div>
      )
    }
  ),
}))

// Waiting is done with findBy*, never waitFor(queryByLabelText(/loading/i)). That old guard
// never waited: LoadingSpinner renders its `label` as visible TEXT, not an aria-label, so
// getByLabelText matched nothing and the assertion passed on the first tick while the page
// was still loading. Every test then raced the mocked fetch and lost under CPU contention.
// Post-click elements are queried with findBy*, not getBy*. The picker and the
// create-form fields mount as a result of the preceding userEvent.click, so a
// synchronous getBy* asserts before React has committed and fails under CPU
// contention — this file failed 2 of 3 consecutive local runs that way, the same
// shape as the Chat.test.tsx starvation flake in concerns.md.
describe('ApprovalProcessesPage – FieldExpressionPicker adoption', () => {
  beforeEach(() => {
    setupAuthMocks()
    resetMockAxios()
    const mockAxios = getMockAxiosInstance()
    // listProcesses → empty list
    mockAxios.get.mockResolvedValue({ data: { data: [], metadata: { totalCount: 0 } } })
  })

  it('shows the Insert field button in the Create Approval Process form', async () => {
    const Wrapper = createTestWrapper()
    render(
      <Wrapper>
        <ApprovalProcessesPage />
      </Wrapper>
    )

    // Open the create form
    await userEvent.click(await screen.findByTestId('add-approval-process-button'))

    expect(await screen.findByTestId('entry-criteria-insert-field')).toBeInTheDocument()
  })

  it('opens the picker with mode=expression when Insert field is clicked', async () => {
    const Wrapper = createTestWrapper()
    render(
      <Wrapper>
        <ApprovalProcessesPage />
      </Wrapper>
    )

    await userEvent.click(await screen.findByTestId('add-approval-process-button'))
    await userEvent.click(await screen.findByTestId('entry-criteria-insert-field'))

    const picker = await screen.findByTestId('approval-entry-criteria-picker')
    expect(picker).toBeInTheDocument()
    expect(picker).toHaveAttribute('data-mode', 'expression')
  })

  it('uses the form collectionId as rootCollectionId', async () => {
    const Wrapper = createTestWrapper()
    render(
      <Wrapper>
        <ApprovalProcessesPage />
      </Wrapper>
    )

    await userEvent.click(await screen.findByTestId('add-approval-process-button'))

    // Type a collection id into the collection field
    const collectionInput = await screen.findByTestId('approval-process-collection-id-input')
    await userEvent.clear(collectionInput)
    await userEvent.type(collectionInput, 'col-invoices')

    await userEvent.click(await screen.findByTestId('entry-criteria-insert-field'))

    expect(await screen.findByTestId('approval-entry-criteria-picker')).toHaveAttribute(
      'data-collection-id',
      'col-invoices'
    )
  })

  it('inserts the token into the entry criteria textarea', async () => {
    const Wrapper = createTestWrapper()
    render(
      <Wrapper>
        <ApprovalProcessesPage />
      </Wrapper>
    )

    await userEvent.click(await screen.findByTestId('add-approval-process-button'))
    await userEvent.click(await screen.findByTestId('entry-criteria-insert-field'))
    await userEvent.click(await screen.findByText('Insert status'))

    const textarea = (await screen.findByTestId(
      'approval-process-entry-criteria-input'
    )) as HTMLTextAreaElement
    expect(textarea.value).toBe('status')
  })
})
