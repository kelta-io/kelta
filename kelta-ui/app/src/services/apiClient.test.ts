/**
 * ApiClient Tests
 *
 * Tests the ApiError class and Axios-based error handling of the ApiClient.
 * The ApiClient wraps an Axios instance (from the SDK's KeltaClient), so all
 * tests mock Axios methods rather than fetch.
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { ApiClient, ApiError } from './apiClient'
import { mockAxios, createAxiosError, resetMockAxios } from '../test/testUtils'

describe('ApiError', () => {
  it('should use field error messages when available', () => {
    const error = new ApiError(400, 'Validation failed', [
      { field: 'quantity_on_hand', message: 'Value must be >= 100', code: 'validationRule' },
    ])
    expect(error.message).toBe('Value must be >= 100')
    expect(error.status).toBe(400)
    expect(error.serverMessage).toBe('Validation failed')
    expect(error.fieldErrors).toHaveLength(1)
    expect(error.fieldErrors[0].field).toBe('quantity_on_hand')
  })

  it('should join multiple field error messages', () => {
    const error = new ApiError(400, 'Validation failed', [
      { field: 'name', message: 'Name is required' },
      { field: 'email', message: 'Invalid email' },
    ])
    expect(error.message).toBe('Name is required; Invalid email')
  })

  it('should fall back to server message when no field errors', () => {
    const error = new ApiError(400, 'Bad Request', [])
    expect(error.message).toBe('Bad Request')
  })

  it('should fall back to "Request failed" when no messages at all', () => {
    const error = new ApiError(500, '', [])
    expect(error.message).toBe('Request failed')
  })

  it('should be instanceof Error', () => {
    const error = new ApiError(400, 'test')
    expect(error).toBeInstanceOf(Error)
    expect(error.name).toBe('ApiError')
  })
})

describe('ApiClient error handling', () => {
  let client: ApiClient

  beforeEach(() => {
    resetMockAxios()
    // Create an ApiClient wrapping the shared mock Axios instance
    client = new ApiClient(mockAxios as never)
  })

  describe('GET', () => {
    it('should throw ApiError with parsed body on error response', async () => {
      mockAxios.get.mockRejectedValueOnce(
        createAxiosError(400, {
          message: 'Validation failed',
          errors: [{ field: 'name', message: 'Name is required', code: 'nullable' }],
        })
      )

      try {
        await client.get('/test')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(400)
        expect(apiError.serverMessage).toBe('Validation failed')
        expect(apiError.fieldErrors).toHaveLength(1)
        expect(apiError.fieldErrors[0].field).toBe('name')
        expect(apiError.message).toBe('Name is required')
      }
    })

    it('should fall back to statusText when body has no message', async () => {
      mockAxios.get.mockRejectedValueOnce(createAxiosError(500, null, 'Internal Server Error'))

      try {
        await client.get('/test')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(500)
        expect(apiError.serverMessage).toBe('Internal Server Error')
        expect(apiError.fieldErrors).toHaveLength(0)
      }
    })
  })

  describe('PATCH', () => {
    it('should throw ApiError with validation details on 400', async () => {
      mockAxios.patch.mockRejectedValueOnce(
        createAxiosError(400, {
          requestId: 'abc123',
          status: 400,
          error: 'Bad Request',
          message: 'Record failed 1 validation rule(s)',
          path: '/api/products/123',
          errors: [
            {
              field: 'quantity_on_hand',
              message: 'Quantity on hand must be at least 100',
              code: 'validationRule',
            },
          ],
        })
      )

      try {
        await client.patch('/api/products/123', { data: {} })
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(400)
        expect(apiError.message).toBe('Quantity on hand must be at least 100')
        expect(apiError.fieldErrors[0].field).toBe('quantity_on_hand')
        expect(apiError.fieldErrors[0].code).toBe('validationRule')
      }
    })
  })

  describe('POST', () => {
    it('should throw ApiError with field errors on 400', async () => {
      mockAxios.post.mockRejectedValueOnce(
        createAxiosError(400, {
          message: 'Validation failed',
          errors: [{ field: 'email', message: 'Invalid email format', code: 'email' }],
        })
      )

      try {
        await client.post('/test', { email: 'bad' })
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.fieldErrors).toHaveLength(1)
        expect(apiError.message).toBe('Invalid email format')
      }
    })
  })

  describe('PUT', () => {
    it('should throw ApiError on error response', async () => {
      mockAxios.put.mockRejectedValueOnce(
        createAxiosError(409, {
          message: 'Unique constraint violation',
          errors: [{ field: 'name', message: 'Name already exists', code: 'unique' }],
        })
      )

      try {
        await client.put('/test', {})
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(409)
        expect(apiError.fieldErrors[0].field).toBe('name')
      }
    })
  })

  describe('DELETE', () => {
    it('should throw ApiError on error response', async () => {
      mockAxios.delete.mockRejectedValueOnce(
        createAxiosError(404, {
          message: 'Not found',
          errors: [],
        })
      )

      try {
        await client.delete('/test/123')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(404)
        expect(apiError.serverMessage).toBe('Not found')
      }
    })
  })

  describe('successful requests', () => {
    it('GET should return parsed data on success', async () => {
      mockAxios.get.mockResolvedValueOnce({ data: { id: '1', name: 'Test' } })
      const result = await client.get('/test')
      expect(result).toEqual({ id: '1', name: 'Test' })
    })

    it('POST should return parsed data on success', async () => {
      mockAxios.post.mockResolvedValueOnce({ data: { id: '2' } })
      const result = await client.post('/test', { name: 'New' })
      expect(result).toEqual({ id: '2' })
    })

    it('DELETE should return undefined when response data is undefined', async () => {
      mockAxios.delete.mockResolvedValueOnce({ data: undefined })
      const result = await client.delete('/test/1')
      expect(result).toBeUndefined()
    })
  })

  describe('getBlob', () => {
    it('should request with responseType blob and return the Blob', async () => {
      const pdf = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
      mockAxios.get.mockResolvedValueOnce({ data: pdf })

      const result = await client.getBlob('/api/reports/rep-1/export?format=pdf')

      expect(result).toBe(pdf)
      expect(mockAxios.get).toHaveBeenCalledWith('/api/reports/rep-1/export?format=pdf', {
        responseType: 'blob',
      })
    })

    it('should decode a JSON:API error Blob into a structured ApiError', async () => {
      const errorBody = {
        errors: [
          {
            status: '400',
            title: 'Bad Request',
            detail: 'Unsupported export format: xml. Supported: csv, pdf',
          },
        ],
      }
      mockAxios.get.mockRejectedValueOnce(
        createAxiosError(400, new Blob([JSON.stringify(errorBody)], { type: 'application/json' }))
      )

      try {
        await client.getBlob('/api/reports/rep-1/export?format=xml')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(400)
        expect(apiError.message).toBe('Unsupported export format: xml. Supported: csv, pdf')
      }
    })

    it('should fall back to statusText when the error Blob is not JSON', async () => {
      mockAxios.get.mockRejectedValueOnce(
        createAxiosError(500, new Blob(['boom'], { type: 'text/plain' }), 'Internal Server Error')
      )

      try {
        await client.getBlob('/api/reports/rep-1/export?format=pdf')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(500)
        expect(apiError.message).toBe('Internal Server Error')
      }
    })
  })

  describe('optimistic locking (slice 5)', () => {
    it('getWithMeta returns the raw body and the ETag header', async () => {
      mockAxios.get.mockResolvedValueOnce({
        data: { data: { id: '1' } },
        headers: { etag: 'W/"v1"' },
      })
      const result = await client.getWithMeta('/api/things/1')
      expect(result.data).toEqual({ data: { id: '1' } })
      expect(result.etag).toBe('W/"v1"')
    })

    it('patchResource sends If-Match when ifMatch is provided', async () => {
      mockAxios.patch.mockResolvedValueOnce({ data: { data: { id: '1', attributes: {} } } })
      await client.patchResource('/api/things/1', { name: 'x' }, 'W/"v1"')
      expect(mockAxios.patch).toHaveBeenCalledWith('/api/things/1', expect.anything(), {
        headers: { 'If-Match': 'W/"v1"' },
      })
    })

    it('base patch omits the config when no ifMatch is given', async () => {
      mockAxios.patch.mockResolvedValueOnce({ data: {} })
      await client.patch('/api/things/1', { data: {} })
      expect(mockAxios.patch).toHaveBeenCalledWith('/api/things/1', { data: {} }, undefined)
    })

    it('deleteResource sends If-Match when provided', async () => {
      mockAxios.delete.mockResolvedValueOnce({ data: undefined })
      await client.deleteResource('/api/things/1', 'W/"v1"')
      expect(mockAxios.delete).toHaveBeenCalledWith('/api/things/1', {
        headers: { 'If-Match': 'W/"v1"' },
      })
    })
  })

  describe('JSON:API error format', () => {
    it('should parse JSON:API validation errors with source.pointer', async () => {
      mockAxios.get.mockRejectedValueOnce(
        createAxiosError(400, {
          errors: [
            {
              status: '400',
              code: 'nullable',
              title: 'Validation Error',
              detail: 'Name is required',
              source: { pointer: '/data/attributes/name' },
              meta: { requestId: 'abc12345' },
            },
          ],
        })
      )

      try {
        await client.get('/test')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(400)
        expect(apiError.serverMessage).toBe('Name is required')
        expect(apiError.fieldErrors).toHaveLength(1)
        expect(apiError.fieldErrors[0].field).toBe('name')
        expect(apiError.fieldErrors[0].message).toBe('Name is required')
        expect(apiError.fieldErrors[0].code).toBe('nullable')
      }
    })

    it('should parse multiple JSON:API field errors', async () => {
      mockAxios.post.mockRejectedValueOnce(
        createAxiosError(400, {
          errors: [
            {
              status: '400',
              code: 'nullable',
              title: 'Validation Error',
              detail: 'Name is required',
              source: { pointer: '/data/attributes/name' },
              meta: { requestId: 'abc12345' },
            },
            {
              status: '400',
              code: 'validationRule',
              title: 'Validation Error',
              detail: 'Quantity must be >= 100',
              source: { pointer: '/data/attributes/quantity_on_hand' },
              meta: { requestId: 'abc12345' },
            },
          ],
        })
      )

      try {
        await client.post('/test', {})
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(400)
        expect(apiError.fieldErrors).toHaveLength(2)
        expect(apiError.fieldErrors[0].field).toBe('name')
        expect(apiError.fieldErrors[0].code).toBe('nullable')
        expect(apiError.fieldErrors[1].field).toBe('quantity_on_hand')
        expect(apiError.fieldErrors[1].code).toBe('validationRule')
        expect(apiError.message).toBe('Name is required; Quantity must be >= 100')
      }
    })

    it('should parse JSON:API unique constraint error', async () => {
      mockAxios.put.mockRejectedValueOnce(
        createAxiosError(409, {
          errors: [
            {
              status: '409',
              code: 'unique',
              title: 'Conflict',
              detail: 'Duplicate value for field name',
              source: { pointer: '/data/attributes/name' },
              meta: { requestId: 'def67890' },
            },
          ],
        })
      )

      try {
        await client.put('/test', {})
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(409)
        expect(apiError.fieldErrors[0].field).toBe('name')
        expect(apiError.fieldErrors[0].code).toBe('unique')
      }
    })

    it('should parse JSON:API non-field error (no source)', async () => {
      mockAxios.get.mockRejectedValueOnce(
        createAxiosError(500, {
          errors: [
            {
              status: '500',
              code: 'internalError',
              title: 'Internal Server Error',
              detail: 'An unexpected error occurred',
              meta: { requestId: 'ghi11111', path: '/api/test' },
            },
          ],
        })
      )

      try {
        await client.get('/test')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(500)
        expect(apiError.serverMessage).toBe('An unexpected error occurred')
        expect(apiError.fieldErrors).toHaveLength(1)
        expect(apiError.fieldErrors[0].field).toBe('')
        expect(apiError.fieldErrors[0].message).toBe('An unexpected error occurred')
      }
    })
  })

  describe('network errors', () => {
    it('should wrap non-Axios errors into ApiError with status 0', async () => {
      mockAxios.get.mockRejectedValueOnce(new Error('Network error'))

      try {
        await client.get('/test')
        expect.fail('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        const apiError = error as ApiError
        expect(apiError.status).toBe(0)
        expect(apiError.message).toBe('Network error')
      }
    })
  })
})

describe('parseAxiosError', () => {
  it('returns an ApiError unchanged, keeping its field errors', async () => {
    const { parseAxiosError } = await import('./apiClient')
    const original = new ApiError(400, 'Validation Error', [
      { field: 'title', message: 'Field is required', code: 'nullable' },
    ])

    const parsed = parseAxiosError(original)

    expect(parsed).toBe(original)
    expect(parsed.fieldErrors).toEqual([
      { field: 'title', message: 'Field is required', code: 'nullable' },
    ])
  })

  it('keeps JSON:API field errors through a client call and a second parse', async () => {
    resetMockAxios()
    mockAxios.post.mockRejectedValueOnce(
      createAxiosError(400, {
        errors: [
          {
            status: '400',
            code: 'nullable',
            title: 'Validation Error',
            detail: 'Field is required',
            source: { pointer: '/data/attributes/title' },
          },
        ],
      })
    )
    const { parseAxiosError } = await import('./apiClient')
    const client = new ApiClient(mockAxios as never)

    const thrown = await client.post('/api/things', {}).catch((e: unknown) => e)
    const reparsed = parseAxiosError(thrown)

    expect(reparsed.fieldErrors.map((e) => e.field)).toEqual(['title'])
  })
})
