import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ChartWidget } from './ChartWidget'

// recharts ResponsiveContainer needs real layout; stub the module surface we use so the
// test asserts our wiring (series → chart, click → drill-through guard), not recharts.
vi.mock('recharts', () => {
  const Passthrough = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  return {
    ResponsiveContainer: Passthrough,
    BarChart: ({ children, data }: { children?: React.ReactNode; data?: unknown[] }) => (
      <div data-testid="bar-chart" data-rows={(data ?? []).length}>
        {children}
      </div>
    ),
    PieChart: Passthrough,
    Pie: ({ onClick }: { onClick?: (e: unknown) => void }) => (
      <button data-testid="pie" onClick={() => onClick?.({ label: 'Won' })} />
    ),
    Bar: ({ onClick }: { onClick?: (e: unknown) => void }) => (
      <div>
        <button data-testid="bar-won" onClick={() => onClick?.({ label: 'Won' })} />
        <button data-testid="bar-empty" onClick={() => onClick?.({ label: '(empty)' })} />
        <button
          data-testid="bar-with-key"
          onClick={() => onClick?.({ label: 'Apollo', key: 'proj-1' })}
        />
      </div>
    ),
    XAxis: Passthrough,
    YAxis: Passthrough,
    CartesianGrid: Passthrough,
    Tooltip: Passthrough,
    Legend: Passthrough,
    Cell: Passthrough,
  }
})

const DATA = {
  groupByField: 'stage',
  series: [
    { label: 'Won', count: 3, value: 3 },
    { label: '(empty)', count: 1, value: 1 },
  ],
}

describe('ChartWidget', () => {
  it('renders a bar chart with the series rows', () => {
    render(<ChartWidget data={DATA} />)
    expect(screen.getByTestId('bar-chart').getAttribute('data-rows')).toBe('2')
  })

  it('fires drill-through for a labeled segment but not for "(empty)"', () => {
    const onSegmentClick = vi.fn()
    render(<ChartWidget data={DATA} onSegmentClick={onSegmentClick} />)
    screen.getByTestId('bar-won').click()
    expect(onSegmentClick).toHaveBeenCalledWith('Won')
    screen.getByTestId('bar-empty').click()
    expect(onSegmentClick).toHaveBeenCalledTimes(1)
  })

  it('drills through on the raw key when present, ignoring the resolved label', () => {
    const onSegmentClick = vi.fn()
    render(<ChartWidget data={DATA} onSegmentClick={onSegmentClick} />)
    screen.getByTestId('bar-with-key').click()
    expect(onSegmentClick).toHaveBeenCalledWith('proj-1')
  })

  it('renders a placeholder with no series', () => {
    render(<ChartWidget data={{ series: [] }} />)
    expect(screen.getByTestId('chart-empty')).toBeTruthy()
  })
})
