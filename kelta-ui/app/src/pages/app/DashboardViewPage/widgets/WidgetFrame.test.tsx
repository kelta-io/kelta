import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { I18nProvider } from '../../../../context/I18nContext'
import { WidgetFrame } from './WidgetFrame'

function renderFrame(props: Partial<React.ComponentProps<typeof WidgetFrame>> = {}) {
  return render(
    <I18nProvider>
      <WidgetFrame title="Open deals" {...props}>
        <div>content</div>
      </WidgetFrame>
    </I18nProvider>
  )
}

describe('WidgetFrame', () => {
  it('renders no chip by default', () => {
    renderFrame()
    expect(screen.queryByTestId('widget-time-scope-chip')).toBeNull()
  })

  it('renders an "All time" chip for a widget that ignores the page time range', () => {
    renderFrame({ timeScopeLabel: 'All time' })
    expect(screen.getByTestId('widget-time-scope-chip').textContent).toBe('All time')
  })

  it('renders the fixed range label as a chip', () => {
    renderFrame({ timeScopeLabel: 'Last 7 days' })
    expect(screen.getByTestId('widget-time-scope-chip').textContent).toBe('Last 7 days')
  })

  it('still shows the chip when the widget has no title', () => {
    render(
      <I18nProvider>
        <WidgetFrame title={null} timeScopeLabel="All time">
          <div>content</div>
        </WidgetFrame>
      </I18nProvider>
    )
    expect(screen.getByTestId('widget-time-scope-chip').textContent).toBe('All time')
  })
})
