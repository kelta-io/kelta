import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CelExpressionEditor } from './CelExpressionEditor'

describe('CelExpressionEditor', () => {
  it('"Restrict to own records" inserts a rule comparing createdBy with P.attr.userId', () => {
    const onChange = vi.fn()
    render(<CelExpressionEditor value="" onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: 'R.attr.createdBy == P.attr.userId' }))

    expect(onChange).toHaveBeenCalledWith('R.attr.createdBy == P.attr.userId')
  })

  it('documents P.attr.userId as the user id and P.id as the email', () => {
    render(<CelExpressionEditor value="" onChange={vi.fn()} />)

    expect(screen.getByText('P.attr.userId')).toBeInTheDocument()
    expect(screen.getByText(/compare with createdBy/)).toBeInTheDocument()
    expect(screen.getByText(/compare only with email fields/)).toBeInTheDocument()
    expect(screen.queryByText('R.attr.createdBy == P.id')).not.toBeInTheDocument()
  })

  it('does not insert examples when read-only', () => {
    const onChange = vi.fn()
    render(<CelExpressionEditor value="" onChange={onChange} readOnly />)

    fireEvent.click(screen.getByRole('button', { name: 'R.attr.createdBy == P.attr.userId' }))

    expect(onChange).not.toHaveBeenCalled()
  })
})
