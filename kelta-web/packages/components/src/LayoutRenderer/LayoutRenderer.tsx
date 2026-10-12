/**
 * LayoutRenderer Component
 *
 * Renders a PageLayout definition in view or edit mode. Evaluates
 * visibility rules, renders sections with column grids, handles
 * tab groups, highlights panels, and related lists.
 */

import { useState, useMemo, useCallback } from 'react';
import type { ReactNode, ReactElement } from 'react';
import type {
  FieldDefinition,
  LayoutSection,
  LayoutFieldPlacement,
  LayoutRelatedList,
} from '@kelta/sdk';
import type { LayoutRendererProps, FieldRendererFn } from './types';
import { isVisible } from './visibilityRule';

// ---------------------------------------------------------------------------
// Default field renderers
// ---------------------------------------------------------------------------

const defaultViewRenderers: Record<string, (value: unknown) => ReactNode> = {
  string: (v) => String(v ?? ''),
  number: (v) => String(v ?? ''),
  boolean: (v) => (v ? 'Yes' : 'No'),
  date: (v) => {
    if (!v) return '';
    const d = new Date(v as string);
    return d.toLocaleDateString();
  },
  datetime: (v) => {
    if (!v) return '';
    const d = new Date(v as string);
    return d.toLocaleString();
  },
  reference: (v) => String(v ?? ''),
  json: (v) => JSON.stringify(v, null, 2),
  picklist: (v) => String(v ?? ''),
  multi_picklist: (v) => (Array.isArray(v) ? v.join(', ') : String(v ?? '')),
  currency: (v) => (v != null ? `$${Number(v).toFixed(2)}` : ''),
  percent: (v) => (v != null ? `${String(v)}%` : ''),
  phone: (v) => String(v ?? ''),
  email: (v) => String(v ?? ''),
  url: (v) => String(v ?? ''),
  auto_number: (v) => String(v ?? ''),
};

function renderFieldValue(
  value: unknown,
  field: FieldDefinition,
  mode: 'view' | 'edit',
  onChange?: (value: unknown) => void,
  customRenderers?: Record<string, FieldRendererFn>
): ReactNode {
  // Check custom renderer by field name
  if (customRenderers?.[field.name]) {
    return customRenderers[field.name](value, field, mode, onChange);
  }

  // Edit mode: render basic inputs
  if (mode === 'edit' && onChange) {
    return renderEditField(value, field, onChange);
  }

  // View mode: use type-specific renderers
  const renderer = defaultViewRenderers[field.type] ?? defaultViewRenderers.string;
  return renderer(value);
}

function renderEditField(
  value: unknown,
  field: FieldDefinition,
  onChange: (value: unknown) => void
): ReactNode {
  const type = field.type;
  const strValue = value != null ? String(value) : '';

  if (type === 'boolean') {
    return (
      <input
        type="checkbox"
        checked={!!value}
        onChange={(e) => onChange(e.target.checked)}
        className="kelta-layout-renderer__checkbox"
        aria-label={field.displayName || field.name}
      />
    );
  }

  if (type === 'date') {
    return (
      <input
        type="date"
        value={strValue}
        onChange={(e) => onChange(e.target.value)}
        className="kelta-layout-renderer__input"
        aria-label={field.displayName || field.name}
      />
    );
  }

  if (type === 'datetime') {
    return (
      <input
        type="datetime-local"
        value={strValue}
        onChange={(e) => onChange(e.target.value)}
        className="kelta-layout-renderer__input"
        aria-label={field.displayName || field.name}
      />
    );
  }

  if (type === 'number' || type === 'currency' || type === 'percent') {
    return (
      <input
        type="number"
        value={strValue}
        onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
        className="kelta-layout-renderer__input"
        aria-label={field.displayName || field.name}
      />
    );
  }

  if (type === 'json') {
    return (
      <textarea
        value={typeof value === 'string' ? value : JSON.stringify(value, null, 2)}
        onChange={(e) => onChange(e.target.value)}
        className="kelta-layout-renderer__textarea"
        rows={4}
        aria-label={field.displayName || field.name}
      />
    );
  }

  // Default: text input
  return (
    <input
      type="text"
      value={strValue}
      onChange={(e) => onChange(e.target.value)}
      className="kelta-layout-renderer__input"
      aria-label={field.displayName || field.name}
    />
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

interface FieldCellProps {
  placement: LayoutFieldPlacement;
  field: FieldDefinition | undefined;
  record: Record<string, unknown>;
  mode: 'view' | 'edit';
  readOnlyOnLayout: boolean;
  requiredOnLayout: boolean;
  onChange?: (fieldName: string, value: unknown) => void;
  customRenderers?: Record<string, FieldRendererFn>;
}

function FieldCell({
  placement,
  field,
  record,
  mode,
  readOnlyOnLayout,
  requiredOnLayout,
  onChange,
  customRenderers,
}: FieldCellProps): ReactElement | null {
  const fieldName = field?.name ?? '';

  const handleChange = useCallback(
    (newValue: unknown) => {
      onChange?.(fieldName, newValue);
    },
    [onChange, fieldName]
  );

  if (!field) return null;

  const label = placement.labelOverride || field.displayName || field.name;
  const value = record[fieldName];
  const isReadOnly = readOnlyOnLayout || mode === 'view';

  return (
    <div
      className="kelta-layout-renderer__field"
      data-field={fieldName}
      data-testid={`layout-field-${fieldName}`}
    >
      <label className="kelta-layout-renderer__field-label">
        {label}
        {requiredOnLayout && mode === 'edit' && (
          <span className="kelta-layout-renderer__required" aria-hidden="true">
            *
          </span>
        )}
      </label>
      <div className="kelta-layout-renderer__field-value">
        {renderFieldValue(
          value,
          field,
          isReadOnly ? 'view' : mode,
          isReadOnly ? undefined : handleChange,
          customRenderers
        )}
      </div>
      {placement.helpTextOverride && (
        <div className="kelta-layout-renderer__field-help">{placement.helpTextOverride}</div>
      )}
    </div>
  );
}

interface SectionRendererProps {
  section: LayoutSection;
  record: Record<string, unknown>;
  fields: FieldDefinition[];
  mode: 'view' | 'edit';
  onChange?: (fieldName: string, value: unknown) => void;
  customRenderers?: Record<string, FieldRendererFn>;
  fieldMap: Map<string, FieldDefinition>;
}

function SectionRenderer({
  section,
  record,
  mode,
  onChange,
  customRenderers,
  fieldMap,
}: SectionRendererProps): ReactElement | null {
  const [collapsed, setCollapsed] = useState(section.collapsed);

  // Sort and filter visible fields
  const visibleFields = useMemo(() => {
    return section.fields
      .slice()
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .filter((fp) => isVisible(fp.visibilityRule, record));
  }, [section.fields, record]);

  // Check if any fields use column spanning
  const columnCount = section.columns || 1;
  const hasSpanning = useMemo(
    () => visibleFields.some((fp) => (fp.columnSpan ?? 1) > 1),
    [visibleFields]
  );

  // Group by column (used when no spanning)
  const fieldsByColumn = useMemo(() => {
    const grouped: LayoutFieldPlacement[][] = Array.from({ length: columnCount }, () => []);
    for (const fp of visibleFields) {
      const col = Math.min(fp.columnNumber, columnCount - 1);
      grouped[col].push(fp);
    }
    return grouped;
  }, [visibleFields, columnCount]);

  const isCollapsible = section.style === 'COLLAPSIBLE';
  const isCard = section.style === 'CARD';
  const isHighlights = section.sectionType === 'HIGHLIGHTS_PANEL';

  const sectionClass = [
    'kelta-layout-renderer__section',
    isCollapsible ? 'kelta-layout-renderer__section--collapsible' : '',
    isCard ? 'kelta-layout-renderer__section--card' : '',
    isHighlights ? 'kelta-layout-renderer__section--highlights' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <section className={sectionClass} data-testid={`layout-section-${section.id}`}>
      {section.heading && (
        <div className="kelta-layout-renderer__section-header">
          {isCollapsible ? (
            <button
              type="button"
              className="kelta-layout-renderer__section-toggle"
              onClick={() => setCollapsed((prev) => !prev)}
              aria-expanded={!collapsed}
              data-testid={`layout-section-toggle-${section.id}`}
            >
              <span className="kelta-layout-renderer__section-heading">{section.heading}</span>
              <span className="kelta-layout-renderer__section-arrow">
                {collapsed ? '\u25B6' : '\u25BC'}
              </span>
            </button>
          ) : (
            <h3 className="kelta-layout-renderer__section-heading">{section.heading}</h3>
          )}
        </div>
      )}

      {(!collapsed || !isCollapsible) && hasSpanning ? (
        <div
          className="kelta-layout-renderer__section-body"
          style={{
            display: 'grid',
            gridTemplateColumns: `repeat(${columnCount}, 1fr)`,
            gap: '1rem',
          }}
        >
          {visibleFields.map((fp) => {
            const span = Math.min(fp.columnSpan ?? 1, columnCount);
            const colStart = Math.min(fp.columnNumber, columnCount - 1) + 1; // CSS grid is 1-based
            return (
              <div
                key={fp.id}
                className="kelta-layout-renderer__field-cell"
                style={{
                  gridColumn: span > 1 ? `${colStart} / span ${span}` : undefined,
                }}
              >
                <FieldCell
                  placement={fp}
                  field={fieldMap.get(fp.fieldId) ?? fieldMap.get(fp.fieldName ?? '')}
                  record={record}
                  mode={mode}
                  readOnlyOnLayout={fp.readOnlyOnLayout}
                  requiredOnLayout={fp.requiredOnLayout}
                  onChange={onChange}
                  customRenderers={customRenderers}
                />
              </div>
            );
          })}
        </div>
      ) : (
        (!collapsed || !isCollapsible) && (
          <div
            className="kelta-layout-renderer__section-body"
            style={{
              display: 'grid',
              gridTemplateColumns: `repeat(${columnCount}, 1fr)`,
              gap: '1rem',
            }}
          >
            {fieldsByColumn.map((colFields, colIndex) => (
              <div key={colIndex} className="kelta-layout-renderer__column">
                {colFields.map((fp) => (
                  <FieldCell
                    key={fp.id}
                    placement={fp}
                    field={fieldMap.get(fp.fieldId) ?? fieldMap.get(fp.fieldName ?? '')}
                    record={record}
                    mode={mode}
                    readOnlyOnLayout={fp.readOnlyOnLayout}
                    requiredOnLayout={fp.requiredOnLayout}
                    onChange={onChange}
                    customRenderers={customRenderers}
                  />
                ))}
              </div>
            ))}
          </div>
        )
      )}
    </section>
  );
}

interface TabGroupRendererProps {
  tabGroup: string;
  sections: LayoutSection[];
  record: Record<string, unknown>;
  fields: FieldDefinition[];
  mode: 'view' | 'edit';
  onChange?: (fieldName: string, value: unknown) => void;
  customRenderers?: Record<string, FieldRendererFn>;
  fieldMap: Map<string, FieldDefinition>;
}

function TabGroupRenderer({
  sections,
  record,
  fields,
  mode,
  onChange,
  customRenderers,
  fieldMap,
}: TabGroupRendererProps): ReactElement {
  const [activeTab, setActiveTab] = useState(0);

  const visibleSections = useMemo(
    () => sections.filter((s) => isVisible(s.visibilityRule, record)),
    [sections, record]
  );

  if (visibleSections.length === 0) return <></>;

  const activeSection = visibleSections[activeTab] ?? visibleSections[0];

  return (
    <div className="kelta-layout-renderer__tab-group" data-testid="layout-tab-group">
      <div className="kelta-layout-renderer__tab-bar" role="tablist">
        {visibleSections.map((s, i) => (
          <button
            key={s.id}
            type="button"
            role="tab"
            className={`kelta-layout-renderer__tab ${i === activeTab ? 'kelta-layout-renderer__tab--active' : ''}`}
            aria-selected={i === activeTab}
            onClick={() => setActiveTab(i)}
            data-testid={`layout-tab-${s.id}`}
          >
            {s.tabLabel || s.heading || `Tab ${i + 1}`}
          </button>
        ))}
      </div>
      <div role="tabpanel" className="kelta-layout-renderer__tab-panel">
        {activeSection && (
          <SectionRenderer
            section={activeSection}
            record={record}
            fields={fields}
            mode={mode}
            onChange={onChange}
            customRenderers={customRenderers}
            fieldMap={fieldMap}
          />
        )}
      </div>
    </div>
  );
}

interface RelatedListRendererProps {
  relatedList: LayoutRelatedList;
}

function RelatedListRenderer({ relatedList }: RelatedListRendererProps): ReactElement {
  const columns = relatedList.displayColumns
    ? relatedList.displayColumns.split(',').map((c) => c.trim())
    : [];

  return (
    <div
      className="kelta-layout-renderer__related-list"
      data-testid={`layout-related-list-${relatedList.id}`}
    >
      <h3 className="kelta-layout-renderer__related-list-heading">
        {relatedList.relatedCollectionId}
      </h3>
      <div className="kelta-layout-renderer__related-list-info">
        <span>Relationship: {relatedList.relationshipField}</span>
        {columns.length > 0 && <span> | Columns: {columns.join(', ')}</span>}
        <span> | Limit: {relatedList.rowLimit}</span>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

/**
 * Renders a PageLayout definition in view or edit mode.
 *
 * Supports:
 * - Multi-column sections with field placement
 * - Highlights panel (rendered at top)
 * - Tab groups (sections sharing a tab group)
 * - Collapsible and card-style sections
 * - Conditional visibility rules on fields and sections
 * - Related lists
 * - Custom field renderers
 *
 * @example
 * ```tsx
 * <LayoutRenderer
 *   layout={pageLayout}
 *   record={recordData}
 *   fields={fieldDefinitions}
 *   mode="view"
 * />
 * ```
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the component into your app if you depend on it.
 */
export function LayoutRenderer({
  layout,
  record,
  fields,
  mode,
  onChange,
  customRenderers,
  className = '',
  testId = 'kelta-layout-renderer',
}: LayoutRendererProps): ReactElement {
  // Build field lookup maps
  const fieldMap = useMemo(() => {
    const map = new Map<string, FieldDefinition>();
    for (const f of fields) {
      if (f.id) map.set(f.id, f);
      map.set(f.name, f);
    }
    return map;
  }, [fields]);

  // Sort and categorize sections
  const sortedSections = useMemo(
    () => [...layout.sections].sort((a, b) => a.sortOrder - b.sortOrder),
    [layout.sections]
  );

  // Highlights panel
  const highlightsSection = useMemo(
    () => sortedSections.find((s) => s.sectionType === 'HIGHLIGHTS_PANEL'),
    [sortedSections]
  );

  // Group tab sections
  const { tabGroups, standaloneSections } = useMemo(() => {
    const groups = new Map<string, LayoutSection[]>();
    const standalone: LayoutSection[] = [];

    for (const s of sortedSections) {
      if (s.sectionType === 'HIGHLIGHTS_PANEL') continue;
      if (s.tabGroup) {
        const group = groups.get(s.tabGroup) ?? [];
        group.push(s);
        groups.set(s.tabGroup, group);
      } else {
        standalone.push(s);
      }
    }

    return { tabGroups: groups, standaloneSections: standalone };
  }, [sortedSections]);

  // Sorted related lists
  const sortedRelatedLists = useMemo(
    () => [...layout.relatedLists].sort((a, b) => a.sortOrder - b.sortOrder),
    [layout.relatedLists]
  );

  return (
    <div
      className={`kelta-layout-renderer kelta-layout-renderer--${mode} ${className}`}
      data-testid={testId}
    >
      {/* Highlights Panel */}
      {highlightsSection && isVisible(highlightsSection.visibilityRule, record) && (
        <SectionRenderer
          section={highlightsSection}
          record={record}
          fields={fields}
          mode={mode}
          onChange={onChange}
          customRenderers={customRenderers}
          fieldMap={fieldMap}
        />
      )}

      {/* Standalone sections (no tab group) */}
      {standaloneSections
        .filter((s) => isVisible(s.visibilityRule, record))
        .map((s) => (
          <SectionRenderer
            key={s.id}
            section={s}
            record={record}
            fields={fields}
            mode={mode}
            onChange={onChange}
            customRenderers={customRenderers}
            fieldMap={fieldMap}
          />
        ))}

      {/* Tab groups */}
      {Array.from(tabGroups.entries()).map(([groupName, sections]) => (
        <TabGroupRenderer
          key={groupName}
          tabGroup={groupName}
          sections={sections}
          record={record}
          fields={fields}
          mode={mode}
          onChange={onChange}
          customRenderers={customRenderers}
          fieldMap={fieldMap}
        />
      ))}

      {/* Related Lists */}
      {sortedRelatedLists.length > 0 && (
        <div className="kelta-layout-renderer__related-lists" data-testid="layout-related-lists">
          {sortedRelatedLists.map((rl) => (
            <RelatedListRenderer key={rl.id} relatedList={rl} />
          ))}
        </div>
      )}
    </div>
  );
}
