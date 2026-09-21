import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi } from 'vitest'
import { FlowToolbar } from './FlowToolbar'

function renderToolbar(props: Partial<React.ComponentProps<typeof FlowToolbar>> = {}) {
  return render(
    <MemoryRouter>
      <FlowToolbar
        flowName="gate"
        flowType="SCHEDULED"
        isActive
        isDirty={false}
        isSaving={false}
        showJson={false}
        onSave={vi.fn()}
        onToggleJson={vi.fn()}
        {...props}
      />
    </MemoryRouter>
  )
}

describe('FlowToolbar', () => {
  it('exposes the actions as a toolbar', () => {
    renderToolbar()
    expect(screen.getByRole('toolbar', { name: /flow actions/i })).toBeInTheDocument()
  })

  it('offers Auto layout only when a handler is wired', () => {
    renderToolbar()
    expect(screen.queryByRole('button', { name: /auto layout/i })).not.toBeInTheDocument()
  })

  it('invokes onAutoLayout when the button is clicked', async () => {
    const onAutoLayout = vi.fn()
    renderToolbar({ onAutoLayout })

    await userEvent.click(screen.getByRole('button', { name: /auto layout/i }))
    expect(onAutoLayout).toHaveBeenCalledTimes(1)
  })
})
