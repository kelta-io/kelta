import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RecordShell } from './RecordShell'
import { useRecordStack } from '@/context/RecordStackContext'
import { useRealtimeCollection } from '@/realtime'

vi.mock('@/realtime', () => ({ useRealtimeCollection: vi.fn() }))

function StackProbe() {
  return <span data-testid="stack">{useRecordStack()}</span>
}

describe('RecordShell', () => {
  it('subscribes the realtime socket to the record collection, even while loading', () => {
    render(<RecordShell variant="enduser" collectionName="orders" isLoading body={<div />} />)
    expect(useRealtimeCollection).toHaveBeenCalledWith('orders')
  })

  it('renders only the loading state when isLoading', () => {
    render(
      <RecordShell
        variant="enduser"
        isLoading
        body={<div>body</div>}
        breadcrumb={<div>crumb</div>}
      />
    )
    expect(screen.getByTestId('record-shell-loading')).toBeInTheDocument()
    expect(screen.queryByText('body')).toBeNull()
    expect(screen.queryByText('crumb')).toBeNull()
  })

  it('renders the status slot instead of the frame (error / not-found branch)', () => {
    render(
      <RecordShell
        variant="admin"
        statusSlot={<div>not found</div>}
        body={<div>body</div>}
        tabBar={<div>tabs</div>}
      />
    )
    const status = screen.getByTestId('record-shell-status')
    expect(status).toHaveAttribute('data-variant', 'admin')
    expect(screen.getByText('not found')).toBeInTheDocument()
    expect(screen.queryByText('body')).toBeNull()
    expect(screen.queryByText('tabs')).toBeNull()
  })

  it('composes every slot in order when rendering the frame', () => {
    render(
      <RecordShell
        variant="enduser"
        breadcrumb={<div>crumb</div>}
        header={<div>header</div>}
        body={<div>body</div>}
        rail={<div>rail</div>}
        tabBar={<div>tabs</div>}
        belowTabs={<div>below</div>}
        dialogs={<div>dialog</div>}
      />
    )
    const shell = screen.getByTestId('record-shell')
    expect(shell).toHaveAttribute('data-variant', 'enduser')
    for (const text of ['crumb', 'header', 'body', 'rail', 'tabs', 'below', 'dialog']) {
      expect(screen.getByText(text)).toBeInTheDocument()
    }
  })

  it('omits the rail when no rail slot is supplied', () => {
    render(<RecordShell variant="admin" body={<div>body</div>} />)
    expect(screen.getByText('body')).toBeInTheDocument()
    expect(screen.queryByText('rail')).toBeNull()
  })

  it('renders the section nav as a left column of the body grid when supplied', () => {
    render(
      <RecordShell
        variant="enduser"
        body={<div>body</div>}
        sectionNav={<div>nav</div>}
        rail={<div>rail</div>}
      />
    )
    const navCell = screen.getByTestId('record-shell-section-nav')
    expect(navCell).toHaveTextContent('nav')
    expect(navCell.parentElement).toHaveClass('lg:grid-cols-[280px_minmax(0,1fr)_340px]')
  })

  it('uses the two-column grid when the section nav is supplied without a rail', () => {
    render(<RecordShell variant="admin" body={<div>body</div>} sectionNav={<div>nav</div>} />)
    const navCell = screen.getByTestId('record-shell-section-nav')
    expect(navCell.parentElement).toHaveClass('lg:grid-cols-[280px_minmax(0,1fr)]')
  })

  it('omits the section nav cell when no sectionNav slot is supplied', () => {
    render(<RecordShell variant="enduser" body={<div>body</div>} rail={<div>rail</div>} />)
    expect(screen.queryByTestId('record-shell-section-nav')).toBeNull()
  })

  // Record-stack convergence P7: shared components inside the shell link within its stack.
  it.each(['admin', 'enduser'] as const)('provides the %s record stack to its slots', (variant) => {
    render(<RecordShell variant={variant} body={<StackProbe />} />)
    expect(screen.getByTestId('stack')).toHaveTextContent(variant)
  })

  it('provides the record stack to the status slot', () => {
    render(<RecordShell variant="admin" statusSlot={<StackProbe />} body={<div>body</div>} />)
    expect(screen.getByTestId('stack')).toHaveTextContent('admin')
  })
})
