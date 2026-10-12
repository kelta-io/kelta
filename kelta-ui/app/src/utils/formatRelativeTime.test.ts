import { describe, it, expect } from 'vitest'
import {
  formatRelativeTime,
  getRelativeTimeBucket,
  type RelativeTimeOptions,
} from './formatRelativeTime'

const NOW = Date.UTC(2026, 5, 15, 12, 0, 0)
const SEC = 1000
const MIN = 60 * SEC
const HOUR = 60 * MIN
const DAY = 24 * HOUR

const ago = (ms: number) => new Date(NOW - ms).toISOString()
const localeDate = (ms: number) => new Date(NOW - ms).toLocaleDateString()

type Row = [label: string, elapsedMs: number, expected: string]

function runTable(options: RelativeTimeOptions, rows: Row[]) {
  it.each(rows)('%s', (_label, elapsedMs, expected) => {
    expect(formatRelativeTime(ago(elapsedMs), { ...options, now: NOW })).toBe(expected)
  })
}

describe('formatRelativeTime', () => {
  describe('default (short, capitalized, locale date after a week) — SearchModal, RecentItems', () => {
    runTable({}, [
      ['future (clock skew)', -5 * SEC, 'Just now'],
      ['zero', 0, 'Just now'],
      ['59.999s', MIN - 1, 'Just now'],
      ['1 minute', MIN, '1m ago'],
      ['59 minutes', 59 * MIN, '59m ago'],
      ['1 hour', HOUR, '1h ago'],
      ['23h59m', DAY - MIN, '23h ago'],
      ['1 day', DAY, 'Yesterday'],
      ['47 hours', 47 * HOUR, 'Yesterday'],
      ['2 days', 2 * DAY, '2d ago'],
      ['6 days', 6 * DAY, '6d ago'],
      ['7 days', 7 * DAY, localeDate(7 * DAY)],
      ['1 year', 365 * DAY, localeDate(365 * DAY)],
    ])
  })

  describe("lowercase + beyondWeek 'days' — GlobalSearch recent items", () => {
    runTable({ lowercase: true, beyondWeek: 'days' }, [
      ['future', -MIN, 'just now'],
      ['30 seconds', 30 * SEC, 'just now'],
      ['5 minutes', 5 * MIN, '5m ago'],
      ['3 hours', 3 * HOUR, '3h ago'],
      ['1 day', DAY, 'yesterday'],
      ['6 days', 6 * DAY, '6d ago'],
      ['7 days', 7 * DAY, '7d ago'],
      ['400 days', 400 * DAY, '400d ago'],
    ])
  })

  describe("lowercase + beyondWeek 'weeks' — AppHomePage recent items", () => {
    runTable({ lowercase: true, beyondWeek: 'weeks' }, [
      ['30 seconds', 30 * SEC, 'just now'],
      ['1 day', DAY, 'yesterday'],
      ['6 days', 6 * DAY, '6d ago'],
      ['7 days', 7 * DAY, '1w ago'],
      ['13 days', 13 * DAY, '1w ago'],
      ['29 days', 29 * DAY, '4w ago'],
      ['30 days', 30 * DAY, localeDate(30 * DAY)],
    ])
  })

  describe('long style — NotesSection, AttachmentsSection', () => {
    runTable({ style: 'long' }, [
      ['future', -HOUR, 'just now'],
      ['59 seconds', 59 * SEC, 'just now'],
      ['1 minute', MIN, '1 minute ago'],
      ['2 minutes', 2 * MIN, '2 minutes ago'],
      ['1 hour', HOUR, '1 hour ago'],
      ['5 hours', 5 * HOUR, '5 hours ago'],
      ['1 day (no "yesterday" in long style)', DAY, '1 day ago'],
      ['6 days', 6 * DAY, '6 days ago'],
      ['7 days', 7 * DAY, localeDate(7 * DAY)],
    ])
  })

  describe("long style + beyondWeek 'units' — ActivityTimeline", () => {
    runTable({ style: 'long', beyondWeek: 'units' }, [
      ['30 seconds', 30 * SEC, 'just now'],
      ['3 days', 3 * DAY, '3 days ago'],
      ['7 days', 7 * DAY, '1 week ago'],
      ['14 days', 14 * DAY, '2 weeks ago'],
      ['34 days', 34 * DAY, '4 weeks ago'],
      ['35 days', 35 * DAY, '1 month ago'],
      ['60 days', 60 * DAY, '2 months ago'],
      ['359 days', 359 * DAY, '11 months ago'],
      // 30-day months reach 12 before 365-day years reach 1 — long-standing output, kept as-is.
      ['360 days', 360 * DAY, '0 years ago'],
      ['365 days', 365 * DAY, '1 year ago'],
      ['800 days', 800 * DAY, '2 years ago'],
    ])
  })

  it('accepts epoch milliseconds and Date objects as well as ISO strings', () => {
    expect(formatRelativeTime(NOW - 5 * MIN, { now: NOW })).toBe('5m ago')
    expect(formatRelativeTime(new Date(NOW - 2 * HOUR), { now: NOW })).toBe('2h ago')
  })

  it('returns an unparseable input unchanged', () => {
    expect(formatRelativeTime('not-a-date', { now: NOW })).toBe('not-a-date')
    expect(formatRelativeTime('not-a-date', { style: 'long', now: NOW })).toBe('not-a-date')
  })
})

describe('getRelativeTimeBucket', () => {
  // RecordHeader's options: days keep counting, no "yesterday", no date fallback.
  const recordHeader = { yesterday: false, beyondWeek: 'days' as const, now: NOW }

  it.each([
    ['future', -MIN, { unit: 'now' }],
    ['59 seconds', 59 * SEC, { unit: 'now' }],
    ['10 minutes', 10 * MIN, { unit: 'minute', count: 10 }],
    ['23 hours', 23 * HOUR, { unit: 'hour', count: 23 }],
    ['1 day', DAY, { unit: 'day', count: 1 }],
    ['400 days', 400 * DAY, { unit: 'day', count: 400 }],
  ])('RecordHeader options: %s', (_label, elapsedMs, expected) => {
    expect(getRelativeTimeBucket(ago(elapsedMs), recordHeader)).toEqual(expected)
  })

  it('reports yesterday by default and the date after a week', () => {
    expect(getRelativeTimeBucket(ago(DAY), { now: NOW })).toEqual({ unit: 'yesterday' })
    expect(getRelativeTimeBucket(ago(8 * DAY), { now: NOW })).toEqual({
      unit: 'date',
      date: new Date(NOW - 8 * DAY),
    })
  })

  it('returns null for an unparseable input', () => {
    expect(getRelativeTimeBucket('garbage', recordHeader)).toBeNull()
  })
})
