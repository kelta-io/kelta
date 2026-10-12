/**
 * useGlobalShortcuts — the record shortcuts (`n` new, `e` edit) follow the record stack of
 * the page they are pressed on (record-stack convergence P7).
 */
import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { useGlobalShortcuts } from './useGlobalShortcuts'

function Harness() {
  useGlobalShortcuts()
  const location = useLocation()
  return <div data-testid="location">{location.pathname}</div>
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Harness />
    </MemoryRouter>
  )
}

function press(key: string) {
  act(() => {
    fireEvent.keyDown(document, { key })
  })
}

const location = () => screen.getByTestId('location').textContent

describe('useGlobalShortcuts record shortcuts', () => {
  it.each([
    ['/acme/resources/accounts', '/acme/resources/accounts/new'],
    ['/acme/app/o/accounts', '/acme/app/o/accounts/new'],
  ])('n on the list %s opens %s', (from, to) => {
    renderAt(from)
    press('n')
    expect(location()).toBe(to)
  })

  it.each([
    ['/acme/resources/accounts/r1', '/acme/resources/accounts/r1/edit'],
    ['/acme/app/o/accounts/r1', '/acme/app/o/accounts/r1/edit'],
  ])('e on the record %s opens %s', (from, to) => {
    renderAt(from)
    press('e')
    expect(location()).toBe(to)
  })

  it.each([
    ['e', '/acme/resources/accounts/new'],
    ['e', '/acme/app/o/accounts/r1/edit'],
    ['n', '/acme/resources/accounts/r1'],
    ['n', '/acme/app/home'],
    ['e', '/acme/collections/accounts'],
  ])('%s does nothing on %s', (key, path) => {
    renderAt(path)
    press(key)
    expect(location()).toBe(path)
  })
})
