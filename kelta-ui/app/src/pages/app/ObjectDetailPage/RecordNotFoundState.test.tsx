/**
 * RecordNotFoundState + classifyRecordLoad tests.
 *
 * A collection or record 404 (e.g. a recents link carried over from another
 * workspace) must end in a terminal "not found in this workspace" state, not
 * a generic error and not a spinner.
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ApiError } from '@/services/apiClient'
import { RecordNotFoundState } from './RecordNotFoundState'
import { classifyRecordLoad, isNotFoundError } from './recordLoadOutcome'

const notFound = new ApiError(404, 'Collection not found: titles')
const serverError = new ApiError(500, 'boom')

describe('isNotFoundError', () => {
  it('is true only for an ApiError with status 404', () => {
    expect(isNotFoundError(notFound)).toBe(true)
    expect(isNotFoundError(serverError)).toBe(false)
    expect(isNotFoundError(new Error('404'))).toBe(false)
    expect(isNotFoundError(null)).toBe(false)
  })
})

describe('classifyRecordLoad', () => {
  it('reports a missing collection when the schema lookup 404s', () => {
    expect(classifyRecordLoad({ schemaError: notFound, recordError: null, hasRecord: false })).toBe(
      'collection-missing'
    )
  })

  it('lets a collection 404 win over a record error', () => {
    expect(
      classifyRecordLoad({ schemaError: notFound, recordError: serverError, hasRecord: false })
    ).toBe('collection-missing')
  })

  it('reports a missing record when only the record 404s', () => {
    expect(classifyRecordLoad({ schemaError: null, recordError: notFound, hasRecord: false })).toBe(
      'record-missing'
    )
  })

  it('reports a missing record when nothing errored but no record came back', () => {
    expect(classifyRecordLoad({ schemaError: null, recordError: null, hasRecord: false })).toBe(
      'record-missing'
    )
  })

  it('reports a generic error for non-404 failures', () => {
    expect(
      classifyRecordLoad({ schemaError: serverError, recordError: null, hasRecord: false })
    ).toBe('error')
    expect(
      classifyRecordLoad({ schemaError: null, recordError: serverError, hasRecord: false })
    ).toBe('error')
  })

  it('reports loaded when a record is present', () => {
    expect(classifyRecordLoad({ schemaError: null, recordError: null, hasRecord: true })).toBe(
      'loaded'
    )
  })
})

describe('RecordNotFoundState', () => {
  it('explains a missing collection in workspace terms and offers Home only', () => {
    const onNavigate = vi.fn()
    render(
      <RecordNotFoundState
        outcome="collection-missing"
        collectionName="titles"
        tenantSlug="spotopened"
        basePath="/spotopened/app"
        onNavigate={onNavigate}
      />
    )

    expect(screen.getByText('Not found in this workspace')).toBeInTheDocument()
    expect(
      screen.getByText(/no "titles" collection in the spotopened workspace/)
    ).toBeInTheDocument()
    expect(screen.queryByText('Back to list')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('Go to Home'))
    expect(onNavigate).toHaveBeenCalledWith('/spotopened/app/home')
  })

  it('offers the list for a missing record in a known collection', () => {
    const onNavigate = vi.fn()
    render(
      <RecordNotFoundState
        outcome="record-missing"
        collectionName="watches"
        tenantSlug="spotopened"
        basePath="/spotopened/app"
        onNavigate={onNavigate}
      />
    )

    expect(screen.getByText(/does not exist in the spotopened workspace/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('Back to list'))
    expect(onNavigate).toHaveBeenCalledWith('/spotopened/app/o/watches')
  })
})
