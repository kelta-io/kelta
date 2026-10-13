import React from 'react'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { TriggerEditSheet } from './TriggerEditSheet'
import type { Flow } from '../types'

const update = vi.fn(async () => ({}))

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({
    keltaClient: {
      admin: {
        flows: { update },
        users: { list: vi.fn(async () => ({ content: [] })) },
      },
    },
  }),
}))

vi.mock('@/components/flows', () => ({
  RecordTriggerForm: () => null,
  ScheduledTriggerForm: () => null,
  AutolaunchedTriggerForm: () => null,
  NatsTriggerForm: () => null,
}))

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

// Inline Select so its options can be read and picked without a portal.
vi.mock('@/components/ui/select', async () => {
  const { createContext, useContext } = await vi.importActual<typeof import('react')>('react')
  const Ctx = createContext<{ value?: string; onValueChange?: (v: string) => void }>({})
  return {
    Select: ({
      value,
      onValueChange,
      children,
    }: React.PropsWithChildren<{ value?: string; onValueChange?: (v: string) => void }>) => (
      <Ctx.Provider value={{ value, onValueChange }}>
        <div>{children}</div>
      </Ctx.Provider>
    ),
    SelectTrigger: ({ children, ...props }: React.PropsWithChildren<Record<string, unknown>>) => {
      const { value } = useContext(Ctx)
      return (
        <div {...props} data-value={value}>
          {children}
        </div>
      )
    },
    SelectValue: () => null,
    SelectContent: ({ children }: React.PropsWithChildren) => <div role="listbox">{children}</div>,
    SelectItem: ({ children, value }: React.PropsWithChildren<{ value: string }>) => {
      const { onValueChange } = useContext(Ctx)
      return (
        <button
          type="button"
          role="option"
          aria-selected={false}
          data-value={value}
          onClick={() => onValueChange?.(value)}
        >
          {children}
        </button>
      )
    },
  }
})

const FLOW: Flow = {
  id: 'flow-1',
  name: 'Ingest',
  description: null,
  flowType: 'SCHEDULED',
  active: true,
  definition: '{"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}',
  triggerConfig: null,
  createdBy: 'u1',
  createdAt: '2026-10-13T00:00:00Z',
  updatedAt: '2026-10-13T00:00:00Z',
  version: 1,
  publishedVersion: null,
}

function renderSheet(flow: Flow) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <TriggerEditSheet open onOpenChange={vi.fn()} flow={flow} />
    </QueryClientProvider>
  )
}

function stepLoggingSelect() {
  const trigger = screen.getByTestId('flow-step-logging-select')
  const menu = within(trigger.parentElement as HTMLElement).getByRole('listbox')
  return { trigger, options: within(menu).getAllByRole('option') }
}

describe('TriggerEditSheet step logging', () => {
  beforeEach(() => update.mockClear())

  it('offers FULL, ERRORS_ONLY and NONE, defaulting to FULL', () => {
    renderSheet(FLOW)

    const { trigger, options } = stepLoggingSelect()
    expect(options.map((o) => o.getAttribute('data-value'))).toEqual([
      'FULL',
      'ERRORS_ONLY',
      'NONE',
    ])
    expect(trigger).toHaveAttribute('data-value', 'FULL')
    expect(screen.getByText(/high-frequency or high-volume/i)).toBeInTheDocument()
  })

  it("starts from the flow's stored level", () => {
    renderSheet({ ...FLOW, stepLogging: 'NONE' })

    expect(stepLoggingSelect().trigger).toHaveAttribute('data-value', 'NONE')
  })

  it('saves the chosen level', async () => {
    renderSheet(FLOW)

    const errorsOnly = stepLoggingSelect().options.find(
      (o) => o.getAttribute('data-value') === 'ERRORS_ONLY'
    )
    await userEvent.click(errorsOnly as HTMLElement)
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update).toHaveBeenCalledWith(
      'flow-1',
      expect.objectContaining({ stepLogging: 'ERRORS_ONLY' })
    )
  })
})
