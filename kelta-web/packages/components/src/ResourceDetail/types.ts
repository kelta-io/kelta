import type { ReactNode } from 'react';
import type { ReactElement } from 'react';
import type { FieldDefinition } from '@kelta/sdk';

/**
 * Field renderer function type
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export type FieldRenderer = (value: unknown, field: FieldDefinition) => ReactNode;

/**
 * Field renderer component props (for plugin registry)
 */
export interface FieldRendererProps {
  value: unknown;
  field: FieldDefinition;
  onChange?: (value: unknown) => void;
  readOnly?: boolean;
}

/**
 * Field renderer component type (for plugin registry)
 */
export type FieldRendererComponent = (props: FieldRendererProps) => ReactElement;

/**
 * Props for ResourceDetail component
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export interface ResourceDetailProps {
  /**
   * Name of the resource
   */
  resourceName: string;

  /**
   * Record ID to display
   */
  recordId: string;

  /**
   * Layout style
   */
  layout?: 'vertical' | 'horizontal' | 'grid';

  /**
   * Custom field renderers by field name
   */
  customRenderers?: Record<string, FieldRenderer>;

  /**
   * Custom class name
   */
  className?: string;

  /**
   * Test ID for testing
   */
  testId?: string;
}
