import { useEffect, useMemo, useCallback } from 'react';
import type { ReactElement } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import type { FieldDefinition, ResourceMetadata, ValidationRule } from '@kelta/sdk';
import { useKeltaClient, useCurrentUser } from '../context/KeltaContext';
import type { ResourceFormProps, FieldRendererComponent } from './types';

/**
 * Optional ComponentRegistry interface for custom field renderers
 * This allows the component to work with or without the plugin-sdk
 */
interface ComponentRegistryInterface {
  getFieldRenderer: (fieldType: string) => FieldRendererComponent | undefined;
  hasFieldRenderer: (fieldType: string) => boolean;
}

// Global reference to ComponentRegistry if available
let componentRegistry: ComponentRegistryInterface | undefined;

/**
 * Set the ComponentRegistry for custom field renderers
 * This should be called by the application if plugin-sdk is available
 */
export function setComponentRegistry(registry: ComponentRegistryInterface): void {
  componentRegistry = registry;
}

/**
 * Get the current ComponentRegistry
 */
export function getComponentRegistry(): ComponentRegistryInterface | undefined {
  return componentRegistry;
}

/**
 * Build a Zod schema from field definitions
 */
function buildZodSchema(fields: FieldDefinition[]): z.ZodObject<Record<string, z.ZodTypeAny>> {
  const shape: Record<string, z.ZodTypeAny> = {};

  for (const field of fields) {
    let fieldSchema: z.ZodTypeAny;

    // Create base schema based on field type
    switch (field.type) {
      case 'boolean':
        fieldSchema = z.boolean().optional().default(false);
        break;
      case 'number':
      case 'currency':
      case 'percent':
        // Handle number fields - allow empty strings and convert to undefined
        fieldSchema = z.preprocess(
          (val) => {
            if (val === '' || val === undefined || val === null) return undefined;
            if (typeof val === 'number' && isNaN(val)) return undefined;
            return Number(val);
          },
          field.required ? z.number() : z.number().optional()
        );
        break;
      case 'date':
      case 'datetime':
        fieldSchema = z
          .string()
          .refine((val) => !val || !isNaN(Date.parse(val)), { message: 'Invalid date format' });
        break;
      case 'json':
      case 'geolocation':
        fieldSchema = z.string().refine(
          (val) => {
            if (!val) return true;
            try {
              JSON.parse(val);
              return true;
            } catch {
              return false;
            }
          },
          { message: 'Invalid JSON format' }
        );
        break;
      case 'email':
        fieldSchema = z.string().refine((val) => !val || /^[^@]+@[^@]+\.[^@]+$/.test(val), {
          message: 'Invalid email format',
        });
        break;
      case 'url':
        fieldSchema = z.string().refine(
          (val) => {
            if (!val) return true;
            try {
              new URL(val);
              return true;
            } catch {
              return false;
            }
          },
          { message: 'Invalid URL format' }
        );
        break;
      case 'reference':
      case 'lookup':
      case 'master_detail':
        fieldSchema = z.string();
        break;
      case 'string':
      case 'phone':
      case 'rich_text':
      case 'encrypted':
      case 'external_id':
      case 'picklist':
      case 'auto_number':
      default:
        fieldSchema = z.string();
        break;
    }

    const isNumericType =
      field.type === 'number' || field.type === 'currency' || field.type === 'percent';

    // Apply validation rules (only for non-number types, as number is handled above)
    if (field.validation && !isNumericType) {
      fieldSchema = applyValidationRules(fieldSchema, field.validation, field.type);
    } else if (field.validation && isNumericType) {
      // For numbers, we need to apply validation after the preprocess
      // This is handled in the preprocess above
    }

    // Handle required/optional for non-number types (number is handled in preprocess)
    if (!field.required && !isNumericType && field.type !== 'boolean') {
      fieldSchema = fieldSchema.optional().or(z.literal(''));
    }

    shape[field.name] = fieldSchema;
  }

  return z.object(shape);
}

/**
 * Apply validation rules to a Zod schema
 */
function applyValidationRules(
  schema: z.ZodTypeAny,
  rules: ValidationRule[],
  fieldType: string
): z.ZodTypeAny {
  let result = schema;

  for (const rule of rules) {
    const message = rule.message ?? `Validation failed: ${rule.type}`;

    switch (rule.type) {
      case 'min':
        if (fieldType === 'number' && result instanceof z.ZodNumber) {
          result = result.min(rule.value as number, message);
        } else if (fieldType === 'string' && result instanceof z.ZodString) {
          result = result.min(rule.value as number, message);
        }
        break;
      case 'max':
        if (fieldType === 'number' && result instanceof z.ZodNumber) {
          result = result.max(rule.value as number, message);
        } else if (fieldType === 'string' && result instanceof z.ZodString) {
          result = result.max(rule.value as number, message);
        }
        break;
      case 'pattern':
        if (result instanceof z.ZodString) {
          result = result.regex(new RegExp(rule.value as string), message);
        }
        break;
      case 'email':
        if (result instanceof z.ZodString) {
          result = result.email(message);
        }
        break;
      case 'url':
        if (result instanceof z.ZodString) {
          result = result.url(message);
        }
        break;
      // 'custom' rules would need custom handling
    }
  }

  return result;
}

/**
 * ResourceForm component for creating and editing resources
 *
 * Features:
 * - Fetches collection schema on mount
 * - Generates form fields from schema
 * - Fetches existing data for edit mode (when recordId provided)
 * - Validates data against schema using Zod
 * - Supports field-level authorization (hides/disables restricted fields)
 * - Provides custom field renderers via plugin registry
 * - Handles optimistic updates with TanStack Query
 */
export function ResourceForm({
  resourceName,
  recordId,
  onSave,
  onCancel,
  initialValues = {},
  readOnly = false,
  className = '',
}: ResourceFormProps): ReactElement {
  const client = useKeltaClient();
  const user = useCurrentUser();
  const queryClient = useQueryClient();
  const isEditMode = !!recordId;

  // Fetch schema
  const {
    data: schema,
    isLoading: schemaLoading,
    error: schemaError,
  } = useQuery({
    queryKey: ['schema', resourceName],
    queryFn: async () => {
      const resources = await client.discover();
      return resources.find((r) => r.name === resourceName);
    },
  });

  // Check if user has access to a field
  const hasFieldAccess = useCallback(
    (field: FieldDefinition, schemaData: ResourceMetadata | undefined): boolean => {
      if (!schemaData?.authz?.fieldLevel) return true;
      const requiredRoles = schemaData.authz.fieldLevel[field.name];
      if (!requiredRoles || requiredRoles.length === 0) return true;
      if (!user) return false;
      return requiredRoles.some((role) => user.roles.includes(role));
    },
    [user]
  );

  // Get accessible fields
  const accessibleFields = useMemo((): FieldDefinition[] => {
    if (!schema) return [];
    return schema.fields.filter((field) => hasFieldAccess(field, schema));
  }, [schema, hasFieldAccess]);

  // Build Zod validation schema from field definitions
  const validationSchema = useMemo((): z.ZodObject<Record<string, z.ZodTypeAny>> => {
    if (accessibleFields.length === 0) return z.object({});
    return buildZodSchema(accessibleFields);
  }, [accessibleFields]);

  // Initialize form with React Hook Form and Zod resolver
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors, isSubmitting },
  } = useForm({
    defaultValues: initialValues,
    resolver: zodResolver(validationSchema),
  });

  // Fetch existing data for edit mode
  const {
    data: existingData,
    isLoading: dataLoading,
    error: dataError,
  } = useQuery({
    queryKey: ['resource', resourceName, recordId],
    queryFn: () => {
      if (!recordId) return null;
      return client.resource(resourceName).get(recordId);
    },
    enabled: isEditMode,
  });

  // Reset form when existing data is loaded
  useEffect(() => {
    if (existingData) {
      reset(existingData as Record<string, unknown>);
    }
  }, [existingData, reset]);

  // Create mutation with optimistic updates
  const createMutation = useMutation({
    mutationFn: (data: Record<string, unknown>) => {
      return client.resource(resourceName).create(data);
    },
    onMutate: async () => {
      // Cancel any outgoing refetches
      await queryClient.cancelQueries({ queryKey: ['resource', resourceName] });

      // Snapshot the previous value
      const previousData = queryClient.getQueryData(['resource', resourceName, 'list']);

      return { previousData };
    },
    onSuccess: (data) => {
      // Invalidate and refetch
      void queryClient.invalidateQueries({ queryKey: ['resource', resourceName] });
      onSave(data);
    },
    onError: (_err, _newData, context) => {
      // Rollback on error
      if (context?.previousData) {
        queryClient.setQueryData(['resource', resourceName, 'list'], context.previousData);
      }
    },
  });

  // Update mutation with optimistic updates
  const updateMutation = useMutation({
    mutationFn: (data: Record<string, unknown>) => {
      if (!recordId) throw new Error('Record ID required for update');
      return client.resource(resourceName).update(recordId, data as never);
    },
    onMutate: async (newData) => {
      // Cancel any outgoing refetches
      await queryClient.cancelQueries({ queryKey: ['resource', resourceName, recordId] });

      // Snapshot the previous value
      const previousData = queryClient.getQueryData(['resource', resourceName, recordId]);

      // Optimistically update to the new value
      queryClient.setQueryData(['resource', resourceName, recordId], newData);

      return { previousData };
    },
    onSuccess: (data) => {
      // Invalidate and refetch
      void queryClient.invalidateQueries({ queryKey: ['resource', resourceName] });
      onSave(data);
    },
    onError: (_err, _newData, context) => {
      // Rollback on error
      if (context?.previousData) {
        queryClient.setQueryData(['resource', resourceName, recordId], context.previousData);
      }
    },
  });

  // Handle form submission
  const onSubmit = async (data: Record<string, unknown>): Promise<void> => {
    // Transform data before submission (e.g., parse JSON fields)
    const transformedData = transformFormData(data, accessibleFields);

    try {
      if (isEditMode) {
        await updateMutation.mutateAsync(transformedData);
      } else {
        await createMutation.mutateAsync(transformedData);
      }
    } catch (error) {
      // Error is handled by mutation's onError callback
      // This catch prevents unhandled promise rejection
    }
  };

  // Get mutation error
  const mutationError = createMutation.error || updateMutation.error;

  // Render loading state
  if (schemaLoading || (isEditMode && dataLoading)) {
    return (
      <div
        className={`kelta-resource-form kelta-resource-form--loading ${className}`}
        role="status"
        aria-busy="true"
      >
        <div className="kelta-resource-form__loading">Loading...</div>
      </div>
    );
  }

  // Render error state if schema not found
  if (!schema || schemaError) {
    return (
      <div className={`kelta-resource-form kelta-resource-form--error ${className}`} role="alert">
        <div className="kelta-resource-form__error">
          {schemaError
            ? `Error loading schema: ${schemaError.message}`
            : 'Resource schema not found'}
        </div>
      </div>
    );
  }

  // Render error state if data fetch failed
  if (isEditMode && dataError) {
    return (
      <div className={`kelta-resource-form kelta-resource-form--error ${className}`} role="alert">
        <div className="kelta-resource-form__error">Error loading data: {dataError.message}</div>
      </div>
    );
  }

  return (
    <form
      className={`kelta-resource-form ${className}`}
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
      noValidate
    >
      {/* Display mutation error if any */}
      {mutationError && (
        <div className="kelta-resource-form__error-banner" role="alert">
          {mutationError.message}
        </div>
      )}

      {accessibleFields.map((field) => (
        <div
          key={field.name}
          className={`kelta-resource-form__field ${errors[field.name] ? 'kelta-resource-form__field--error' : ''}`}
          data-field={field.name}
        >
          <label className="kelta-resource-form__label" htmlFor={`field-${field.name}`}>
            {field.displayName ?? field.name}
            {field.required && (
              <span className="kelta-resource-form__required" aria-label="required">
                *
              </span>
            )}
          </label>

          {renderField(field, {
            register,
            control,
            readOnly,
          })}

          {errors[field.name] && (
            <span className="kelta-resource-form__error-message" role="alert">
              {(errors[field.name] as { message?: string })?.message ?? 'Invalid value'}
            </span>
          )}
        </div>
      ))}

      <div className="kelta-resource-form__actions">
        <button
          type="button"
          className="kelta-resource-form__button kelta-resource-form__button--cancel"
          onClick={onCancel}
        >
          Cancel
        </button>
        {!readOnly && (
          <button
            type="submit"
            className="kelta-resource-form__button kelta-resource-form__button--submit"
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Saving...' : isEditMode ? 'Update' : 'Create'}
          </button>
        )}
      </div>
    </form>
  );
}

/**
 * Transform form data before submission
 */
function transformFormData(
  data: Record<string, unknown>,
  fields: FieldDefinition[]
): Record<string, unknown> {
  const result: Record<string, unknown> = {};

  for (const field of fields) {
    const value = data[field.name];

    switch (field.type) {
      case 'number':
      case 'currency':
      case 'percent':
        result[field.name] = value !== '' && value !== undefined ? Number(value) : undefined;
        break;
      case 'boolean':
        result[field.name] = Boolean(value);
        break;
      case 'json':
      case 'geolocation':
        if (typeof value === 'string' && value) {
          try {
            result[field.name] = JSON.parse(value);
          } catch {
            result[field.name] = value;
          }
        } else {
          result[field.name] = value;
        }
        break;
      default:
        result[field.name] = value;
    }
  }

  return result;
}

/**
 * Render options for field rendering
 */
interface RenderFieldOptions {
  register: ReturnType<typeof useForm>['register'];
  control: ReturnType<typeof useForm>['control'];
  readOnly: boolean;
}

/**
 * Render a field using custom renderer or default input
 */
function renderField(field: FieldDefinition, options: RenderFieldOptions): ReactElement {
  const { register, control, readOnly } = options;

  // Check for custom field renderer from plugin registry
  const registry = getComponentRegistry();
  if (registry?.hasFieldRenderer(field.type)) {
    const CustomRenderer = registry.getFieldRenderer(field.type);
    if (CustomRenderer) {
      return (
        <Controller
          name={field.name}
          control={control}
          render={({ field: formField }) => (
            <CustomRenderer
              value={formField.value as unknown}
              field={field}
              onChange={formField.onChange}
              readOnly={readOnly}
            />
          )}
        />
      );
    }
  }

  // Default field rendering
  return renderDefaultFieldInput(field, register, readOnly);
}

/**
 * Render the default input for a field type
 */
function renderDefaultFieldInput(
  field: FieldDefinition,
  register: ReturnType<typeof useForm>['register'],
  readOnly: boolean
): ReactElement {
  const baseClassName = `kelta-resource-form__input kelta-resource-form__input--${field.type}`;
  const id = `field-${field.name}`;

  const commonProps = {
    id,
    disabled: readOnly,
    className: baseClassName,
    'aria-required': field.required,
  };

  switch (field.type) {
    case 'boolean':
      return <input type="checkbox" {...register(field.name)} {...commonProps} />;
    case 'number':
    case 'currency':
    case 'percent':
      return (
        <input type="number" {...register(field.name, { valueAsNumber: true })} {...commonProps} />
      );
    case 'date':
      return <input type="date" {...register(field.name)} {...commonProps} />;
    case 'datetime':
      return <input type="datetime-local" {...register(field.name)} {...commonProps} />;
    case 'json':
    case 'geolocation':
      return (
        <textarea
          {...register(field.name)}
          {...commonProps}
          rows={4}
          placeholder="Enter valid JSON"
        />
      );
    case 'email':
      return <input type="email" {...register(field.name)} {...commonProps} />;
    case 'url':
      return <input type="url" {...register(field.name)} {...commonProps} />;
    case 'phone':
      return <input type="tel" {...register(field.name)} {...commonProps} />;
    case 'reference':
    case 'lookup':
    case 'master_detail':
      return (
        <input
          type="text"
          {...register(field.name)}
          {...commonProps}
          placeholder={`Reference to ${field.referenceTarget ?? 'resource'}`}
        />
      );
    case 'rich_text':
      return (
        <textarea
          {...register(field.name)}
          {...commonProps}
          rows={6}
          placeholder="Enter rich text content"
        />
      );
    case 'encrypted':
      return <input type="password" {...register(field.name)} {...commonProps} />;
    case 'string':
    default:
      return <input type="text" {...register(field.name)} {...commonProps} />;
  }
}
