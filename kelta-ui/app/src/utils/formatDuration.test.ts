import { describe, it, expect } from 'vitest'
import { formatDuration, formatLatency } from './formatDuration'

describe('formatDuration', () => {
  it.each([
    [0, '0ms'],
    [850, '850ms'],
    [999, '999ms'],
    [1000, '1.0s'],
    [1500, '1.5s'],
    [59_999, '60.0s'],
    [60_000, '1.0m'],
    [138_000, '2.3m'],
    [3_600_000, '60.0m'],
  ])('%d ms → %s', (ms, expected) => {
    expect(formatDuration(ms)).toBe(expected)
  })

  it("renders a missing duration as '-' by default", () => {
    expect(formatDuration(null)).toBe('-')
    expect(formatDuration(undefined)).toBe('-')
  })

  it('renders a missing duration with a custom placeholder', () => {
    expect(formatDuration(null, '')).toBe('')
    expect(formatDuration(250, '')).toBe('250ms')
  })
})

describe('formatLatency', () => {
  it.each([
    [0, '0µs'],
    [0.45, '450µs'],
    [0.9996, '1000µs'],
    [1, '1.0ms'],
    [12.34, '12.3ms'],
    [999.9, '999.9ms'],
    [1000, '1.00s'],
    [1250, '1.25s'],
    [65_432, '65.43s'],
  ])('%d ms → %s', (ms, expected) => {
    expect(formatLatency(ms)).toBe(expected)
  })
})
