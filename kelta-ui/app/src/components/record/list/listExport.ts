/**
 * CSV / JSON export of the rows a record list has loaded (the current page, or the
 * selection on it). Pure builders plus the browser download, moved out of `ObjectListPage`
 * (record-stack convergence P9).
 */

/** Escape one value for a CSV cell: objects as JSON; quoted when it holds `,` `"` or a newline. */
export function escapeCSVValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  const str = typeof value === 'object' ? JSON.stringify(value) : String(value)
  if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
    return `"${str.replace(/"/g, '""')}"`
  }
  return str
}

/** A CSV document: a header row of field labels, then one row per record in `fields` order. */
export function recordsToCsv(
  records: Array<Record<string, unknown>>,
  fields: Array<{ name: string; displayName?: string }>
): string {
  const rows = [fields.map((f) => escapeCSVValue(f.displayName || f.name)).join(',')]
  for (const record of records) {
    rows.push(fields.map((f) => escapeCSVValue(record[f.name])).join(','))
  }
  return rows.join('\n')
}

/** `<collection>-<YYYY-MM-DD>.<ext>` for the given day. */
export function exportFileName(
  collectionName: string,
  extension: string,
  now = new Date()
): string {
  return `${collectionName}-${now.toISOString().slice(0, 10)}.${extension}`
}

/** Download a string as a file through a temporary object URL. */
export function downloadFile(content: string, filename: string, mimeType: string): void {
  const blob = new Blob([content], { type: mimeType })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}
