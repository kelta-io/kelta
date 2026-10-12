/**
 * CsvImportDialog tests — the import result against a mocked import endpoint: the row summary
 * renders, and columns the server left out (fields the caller cannot write) are listed.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const { mockPostFormData } = vi.hoisted(() => ({ mockPostFormData: vi.fn() }))

vi.mock('@/context/ApiContext', () => ({
  useApi: () => ({ apiClient: { postFormData: mockPostFormData } }),
}))

import { CsvImportDialog } from './CsvImportDialog'

async function importFile() {
  const user = userEvent.setup()
  render(
    <CsvImportDialog open onOpenChange={vi.fn()} collectionName="contacts" onImported={vi.fn()} />
  )
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  await user.upload(input, new File(['name,salary\nAda,1\n'], 'contacts.csv', { type: 'text/csv' }))
  await user.click(screen.getByRole('button', { name: 'Import' }))
}

describe('CsvImportDialog', () => {
  beforeEach(() => mockPostFormData.mockReset())

  it('lists the columns the server did not import', async () => {
    mockPostFormData.mockResolvedValue({
      rowsProcessed: 1,
      rowsImported: 1,
      errorCount: 0,
      errors: [],
      skippedColumns: ['salary'],
    })

    await importFile()

    expect(await screen.findByText('1 of 1 row imported')).toBeInTheDocument()
    expect(screen.getByTestId('csv-import-skipped-columns')).toHaveTextContent('salary')
    expect(mockPostFormData).toHaveBeenCalledWith(
      '/api/collections/contacts/import/csv',
      expect.any(FormData)
    )
  })

  it('shows no skipped-columns line when every column was imported', async () => {
    mockPostFormData.mockResolvedValue({
      rowsProcessed: 1,
      rowsImported: 1,
      errorCount: 0,
      errors: [],
    })

    await importFile()

    expect(await screen.findByText('1 of 1 row imported')).toBeInTheDocument()
    expect(screen.queryByTestId('csv-import-skipped-columns')).not.toBeInTheDocument()
  })
})
