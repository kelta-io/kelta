import type { ReactNode } from 'react';
import type { FieldDefinition, PageLayout, LayoutSection, LayoutFieldPlacement } from '@kelta/sdk';

/**
 * Props for the LayoutRenderer component.
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export interface LayoutRendererProps {
  /** The page layout definition to render */
  layout: PageLayout;

  /** The record data to display / edit */
  record: Record<string, unknown>;

  /** Field definitions for the collection */
  fields: FieldDefinition[];

  /** Render mode: view (read-only) or edit (form inputs) */
  mode: 'view' | 'edit';

  /** Called when a field value changes in edit mode */
  onChange?: (fieldName: string, value: unknown) => void;

  /** Called when the user triggers a save action */
  onSave?: () => void;

  /** Custom field renderers by field name */
  customRenderers?: Record<string, FieldRendererFn>;

  /** Additional CSS class name */
  className?: string;

  /** Test ID for testing */
  testId?: string;
}

/**
 * Function that renders a field value.
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export type FieldRendererFn = (
  value: unknown,
  field: FieldDefinition,
  mode: 'view' | 'edit',
  onChange?: (value: unknown) => void
) => ReactNode;

export type { PageLayout, LayoutSection, LayoutFieldPlacement };
