import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ListShell } from './ListShell'
import { useRecordStack } from '@/context/RecordStackContext'

function StackProbe() {
  return <span data-testid="stack">{useRecordStack()}</span>
}

describe('ListShell', () => {
  it('renders only the loading state when isLoading', () => {
    render(
      <ListShell
        variant="enduser"
        isLoading
        statusSlot={<div>status</div>}
        table={<div>table</div>}
        breadcrumb={<div>crumb</div>}
      />
    )
    expect(screen.getByTestId('list-shell-loading')).toBeInTheDocument()
    expect(screen.queryByText('status')).toBeNull()
    expect(screen.queryByText('table')).toBeNull()
    expect(screen.queryByText('crumb')).toBeNull()
  })

  it('renders the status slot instead of the frame (error / permission-gate branch)', () => {
    render(
      <ListShell
        variant="admin"
        statusSlot={<div>insufficient privileges</div>}
        table={<div>table</div>}
        toolbar={<div>toolbar</div>}
      />
    )
    const status = screen.getByTestId('list-shell-status')
    expect(status).toHaveAttribute('data-variant', 'admin')
    expect(screen.getByText('insufficient privileges')).toBeInTheDocument()
    expect(screen.queryByText('table')).toBeNull()
    expect(screen.queryByText('toolbar')).toBeNull()
  })

  it('composes every slot in order when rendering the frame', () => {
    render(
      <ListShell
        variant="enduser"
        breadcrumb={<div>crumb</div>}
        header={<div>header</div>}
        toolbar={<div>toolbar</div>}
        filters={<div>filters</div>}
        table={<div>table</div>}
        pagination={<div>pagination</div>}
        belowTable={<div>below</div>}
        dialogs={<div>dialog</div>}
      />
    )
    const shell = screen.getByTestId('list-shell')
    expect(shell).toHaveAttribute('data-variant', 'enduser')
    const expectedOrder = [
      'crumb',
      'header',
      'toolbar',
      'filters',
      'table',
      'pagination',
      'below',
      'dialog',
    ]
    for (const text of expectedOrder) {
      expect(screen.getByText(text)).toBeInTheDocument()
    }
    const renderedOrder = Array.from(shell.children).map((child) => child.textContent)
    expect(renderedOrder).toEqual(expectedOrder)
  })

  it('applies the end-user frame classes for variant="enduser"', () => {
    render(<ListShell variant="enduser" table={<div>table</div>} />)
    const shell = screen.getByTestId('list-shell')
    expect(shell).toHaveClass('space-y-4', 'p-4', 'sm:p-6')
    expect(shell).not.toHaveClass('gap-6')
  })

  it('applies the admin frame classes for variant="admin"', () => {
    render(<ListShell variant="admin" table={<div>table</div>} />)
    const shell = screen.getByTestId('list-shell')
    expect(shell).toHaveAttribute('data-variant', 'admin')
    expect(shell).toHaveClass('flex', 'flex-col', 'gap-6', 'p-6', 'w-full')
    expect(shell).not.toHaveClass('space-y-4')
  })

  it('honors a custom data-testid and merges className into the frame', () => {
    render(
      <ListShell
        variant="admin"
        data-testid="resource-list-page"
        className="extra-class"
        table={<div>table</div>}
      />
    )
    const shell = screen.getByTestId('resource-list-page')
    expect(shell).toHaveClass('extra-class')
  })

  // Record-stack convergence P7: shared components inside the shell link within its stack.
  it.each(['admin', 'enduser'] as const)('provides the %s record stack to its slots', (variant) => {
    render(<ListShell variant={variant} table={<StackProbe />} />)
    expect(screen.getByTestId('stack')).toHaveTextContent(variant)
  })
})
