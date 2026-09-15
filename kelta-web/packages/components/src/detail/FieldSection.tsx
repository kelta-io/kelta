/**
 * FieldSection
 *
 * Collapsible card holding a CSS-grid of field name/value pairs.
 *
 * Migrated from kelta-ui/app. The consumer's FieldRenderer is no longer
 * a direct dependency — callers pass a `renderField` callback that this
 * component invokes per-field. That keeps the package free of the 21+
 * field-type rendering logic + plugin registry while still letting the
 * shared layout do the heavy lifting on labels, grid, and collapse state.
 */

import React, { useCallback, useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { Card } from '../ui/card';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '../ui/collapsible';
import { cn } from './_utils';

/**
 * Minimal field shape consumed by FieldSection's grid + label rendering.
 * Mirrors the structural subset of the consumer's full FieldDefinition;
 * consumers usually pass their full definition type via the generic.
 */
export interface DetailField {
  name: string;
  displayName?: string;
  type: string;
  referenceTarget?: string;
}

export interface FieldSectionRenderContext<F extends DetailField = DetailField> {
  field: F;
  value: unknown;
  /** Resolved display label for lookup/reference fields, when available */
  displayLabel?: string;
}

export interface FieldSectionProps<F extends DetailField = DetailField> {
  title: string;
  fields: F[];
  record: Record<string, unknown>;
  /** Lookup display map: { fieldName: { recordId: displayLabel } } */
  lookupDisplayMap?: Record<string, Record<string, string>>;
  defaultCollapsed?: boolean;
  columns?: 1 | 2 | 3 | 4;
  /**
   * Callback that produces the rendered value for a single field. Consumers
   * usually adapt their FieldRenderer here.
   */
  renderField: (ctx: FieldSectionRenderContext<F>) => React.ReactNode;
  /**
   * When set, the open/collapsed state is persisted to localStorage under
   * `kelta_detail_section_${persistKey}`. Survives navigation and reloads.
   */
  persistKey?: string;
  /**
   * Optional override for the field label content. Defaults to
   * `field.displayName || field.name`. Consumers use this to append
   * adornments (e.g. a help-icon tooltip trigger) beside the label.
   */
  renderLabel?: (field: F) => React.ReactNode;
}

const STORAGE_PREFIX = 'kelta_detail_section_';
const REFERENCE_TYPES = new Set(['master_detail', 'lookup', 'reference']);

function readPersistedOpen(persistKey: string | undefined, fallback: boolean): boolean {
  if (!persistKey || typeof window === 'undefined') return fallback;
  try {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + persistKey);
    if (raw === '1') return true;
    if (raw === '0') return false;
  } catch {
    // ignore
  }
  return fallback;
}

function writePersistedOpen(persistKey: string | undefined, open: boolean): void {
  if (!persistKey || typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(STORAGE_PREFIX + persistKey, open ? '1' : '0');
  } catch {
    // ignore
  }
}

export function FieldSection<F extends DetailField = DetailField>({
  title,
  fields,
  record,
  lookupDisplayMap,
  defaultCollapsed = false,
  columns = 2,
  renderField,
  persistKey,
  renderLabel,
}: FieldSectionProps<F>): React.ReactElement | null {
  const [isOpen, setIsOpenState] = useState(() => readPersistedOpen(persistKey, !defaultCollapsed));
  const setIsOpen = useCallback(
    (open: boolean) => {
      setIsOpenState(open);
      writePersistedOpen(persistKey, open);
    },
    [persistKey]
  );

  if (fields.length === 0) return null;

  return (
    <Collapsible open={isOpen} onOpenChange={setIsOpen}>
      <Card
        data-component="FieldSection"
        className="overflow-hidden rounded-xl border border-border bg-card"
      >
        <CollapsibleTrigger asChild>
          <button
            type="button"
            className="flex w-full items-center gap-2 px-5 py-3.5 text-left transition-colors hover:bg-accent/30"
            aria-expanded={isOpen}
          >
            <ChevronRight
              className={cn('kelta-chev h-4 w-4 text-muted-foreground', isOpen && 'rotate-90')}
              aria-hidden="true"
            />
            <span className="text-sm font-semibold text-foreground">{title}</span>
            <span className="ml-2 inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground tabular-nums">
              {fields.length} field{fields.length === 1 ? '' : 's'}
            </span>
          </button>
        </CollapsibleTrigger>

        <CollapsibleContent>
          <div className="border-t border-border px-5 py-5">
            <div
              className="kelta-field-grid"
              style={{ ['--kelta-grid-cols' as string]: String(columns) } as React.CSSProperties}
            >
              {fields.map((field) => {
                const value = record[field.name];
                const isLookup = REFERENCE_TYPES.has(field.type);
                const displayLabel =
                  isLookup && lookupDisplayMap?.[field.name]
                    ? lookupDisplayMap[field.name][String(value)] || undefined
                    : undefined;

                return (
                  <div key={field.name} className="min-w-0 space-y-1">
                    <dt className="kelta-field-label">
                      {renderLabel ? renderLabel(field) : field.displayName || field.name}
                    </dt>
                    <dd className="text-sm text-foreground">
                      {renderField({ field, value, displayLabel })}
                    </dd>
                  </div>
                );
              })}
            </div>
          </div>
        </CollapsibleContent>
      </Card>
    </Collapsible>
  );
}
