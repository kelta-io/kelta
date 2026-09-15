import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

export interface ChartSeriesEntry {
  label: string
  /** Raw group-by value (e.g. the referenced record's id) when the label was resolved
   *  from a LOOKUP/MASTER_DETAIL field's display value. Absent for older cached payloads
   *  and for non-reference group-by fields, where label itself is the raw value. */
  key?: string
  count: number
  value: number
}

export interface ChartWidgetProps {
  /** Widget data: {groupByField, aggregateFunction, series, totalRecords}. */
  data: Record<string, unknown>
  chartStyle?: 'bar' | 'pie'
  /** Called with the clicked segment's drill-through value: `key` when present
   *  (the raw id behind a resolved lookup label), otherwise `label`. */
  onSegmentClick?: (value: string) => void
}

const PIE_COLORS = ['#3B82F6', '#06B6D4', '#8B5CF6', '#F59E0B', '#10B981', '#EF4444', '#64748B']

/** Grouped-aggregate chart over the server's `series: [{label, count, value}]`. */
export function ChartWidget({ data, chartStyle = 'bar', onSegmentClick }: ChartWidgetProps) {
  const series = (data.series as ChartSeriesEntry[]) ?? []

  const handleClick = (entry: { label?: string; key?: string } | undefined) => {
    const drillValue = entry?.key ?? entry?.label
    if (onSegmentClick && drillValue && drillValue !== '(empty)') {
      onSegmentClick(drillValue)
    }
  }

  if (series.length === 0) {
    return (
      <div className="text-sm text-muted-foreground" data-testid="chart-empty">
        —
      </div>
    )
  }

  return (
    <div className="h-full min-h-[240px]" data-testid="chart-widget">
      <ResponsiveContainer width="100%" height="100%" minHeight={240}>
        {chartStyle === 'pie' ? (
          <PieChart>
            <Pie
              data={series}
              dataKey="value"
              nameKey="label"
              onClick={(entry) => handleClick(entry as unknown as { label?: string })}
              cursor={onSegmentClick ? 'pointer' : undefined}
            >
              {series.map((entry, i) => (
                <Cell key={entry.label} fill={PIE_COLORS[i % PIE_COLORS.length]} />
              ))}
            </Pie>
            <Tooltip />
            <Legend />
          </PieChart>
        ) : (
          <BarChart data={series}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="label" tick={{ fontSize: 11 }} />
            <YAxis tick={{ fontSize: 11 }} />
            <Tooltip />
            <Bar
              dataKey="value"
              fill="#3B82F6"
              onClick={(entry) => handleClick(entry as unknown as { label?: string })}
              cursor={onSegmentClick ? 'pointer' : undefined}
            />
          </BarChart>
        )}
      </ResponsiveContainer>
    </div>
  )
}
