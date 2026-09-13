import { describe, it, expect } from 'vitest'
import { ApiError } from '../services/apiClient'
import { shouldRetryQuery, MAX_QUERY_RETRIES } from './queryRetry'

describe('shouldRetryQuery', () => {
  it('never retries a 404 (or any other definitive 4xx)', () => {
    expect(shouldRetryQuery(0, new ApiError(404, 'nope'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(403, 'nope'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(400, 'nope'))).toBe(false)
  })

  it('retries transient client statuses and server/network errors up to the cap', () => {
    expect(shouldRetryQuery(0, new ApiError(429, 'slow down'))).toBe(true)
    expect(shouldRetryQuery(0, new ApiError(408, 'timeout'))).toBe(true)
    expect(shouldRetryQuery(0, new ApiError(503, 'down'))).toBe(true)
    expect(shouldRetryQuery(0, new Error('Network Error'))).toBe(true)
    expect(shouldRetryQuery(MAX_QUERY_RETRIES - 1, new ApiError(503, 'down'))).toBe(true)
    expect(shouldRetryQuery(MAX_QUERY_RETRIES, new ApiError(503, 'down'))).toBe(false)
  })
})
