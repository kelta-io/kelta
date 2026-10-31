import { toText } from './text.js';

export interface ColumnSpec {
  key: string;
  header: string;
}

const MAX_CELL_WIDTH = 48;

function cellText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  const text = toText(value);
  return text.length > MAX_CELL_WIDTH ? text.slice(0, MAX_CELL_WIDTH - 1) + '…' : text;
}

/** Render rows as an aligned plain-text table (human output only — no format guarantee). */
export function renderTable(rows: Record<string, unknown>[], columns?: ColumnSpec[]): string {
  const cols: ColumnSpec[] =
    columns ??
    [...new Set(rows.flatMap((row) => Object.keys(row)))].map((key) => ({ key, header: key }));
  if (cols.length === 0) return '';

  const cells = rows.map((row) => cols.map((col) => cellText(row[col.key])));
  const widths = cols.map((col, i) =>
    Math.max(col.header.length, ...cells.map((row) => row[i].length))
  );

  const line = (parts: string[]): string =>
    parts
      .map((part, i) => part.padEnd(widths[i]))
      .join('  ')
      .trimEnd();

  const out = [line(cols.map((c) => c.header)), line(widths.map((w) => '-'.repeat(w)))];
  for (const row of cells) out.push(line(row));
  return out.join('\n') + '\n';
}

function escapeCsv(value: string): string {
  return /[",\n\r]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

/** Render rows as CSV with a header line. */
export function renderCsv(rows: Record<string, unknown>[], columns?: ColumnSpec[]): string {
  const cols: ColumnSpec[] =
    columns ??
    [...new Set(rows.flatMap((row) => Object.keys(row)))].map((key) => ({ key, header: key }));
  const lines = [cols.map((c) => escapeCsv(c.header)).join(',')];
  for (const row of rows) {
    lines.push(
      cols
        .map((col) => {
          const value = row[col.key];
          if (value === null || value === undefined) return '';
          return escapeCsv(typeof value === 'object' ? JSON.stringify(value) : toText(value));
        })
        .join(',')
    );
  }
  return lines.join('\n') + '\n';
}
