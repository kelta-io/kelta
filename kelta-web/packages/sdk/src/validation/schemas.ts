import { z } from 'zod';

/**
 * Field definition schema
 */
export const FieldDefinitionSchema = z.object({
  name: z.string(),
  type: z.enum([
    'string',
    'number',
    'boolean',
    'date',
    'datetime',
    'json',
    'reference',
    'picklist',
    'multi_picklist',
    'currency',
    'percent',
    'auto_number',
    'phone',
    'email',
    'url',
    'rich_text',
    'encrypted',
    'external_id',
    'geolocation',
    'lookup',
    'master_detail',
    'formula',
    'rollup_summary',
  ]),
  displayName: z.string().optional(),
  required: z.boolean().optional(),
  unique: z.boolean().optional(),
  validation: z
    .array(
      z.object({
        type: z.enum(['min', 'max', 'pattern', 'email', 'url', 'custom']),
        value: z.unknown().optional(),
        message: z.string().optional(),
      })
    )
    .optional(),
  defaultValue: z.unknown().optional(),
  referenceTarget: z.string().optional(),
});

/**
 * Authorization config schema
 */
export const AuthzConfigSchema = z.object({
  read: z.array(z.string()).optional(),
  create: z.array(z.string()).optional(),
  update: z.array(z.string()).optional(),
  delete: z.array(z.string()).optional(),
  fieldLevel: z.record(z.string(), z.array(z.string())).optional(),
});

/**
 * Resource metadata schema
 */
export const ResourceMetadataSchema = z.object({
  name: z.string(),
  displayName: z.string(),
  fields: z.array(FieldDefinitionSchema),
  operations: z.array(z.string()),
  authz: AuthzConfigSchema.optional(),
});

/**
 * Pagination metadata schema
 */
export const PaginationSchema = z.object({
  page: z.number(),
  size: z.number(),
  total: z.number(),
  totalPages: z.number(),
});

/**
 * Generic list response schema factory
 */
export const ListResponseSchema = <T extends z.ZodTypeAny>(itemSchema: T) =>
  z.object({
    data: z.array(itemSchema),
    pagination: PaginationSchema,
  });

/**
 * Error response schema
 */
export const ErrorResponseSchema = z.object({
  error: z.string().optional(),
  message: z.string(),
  details: z.unknown().optional(),
  fieldErrors: z.record(z.string(), z.array(z.string())).optional(),
});

/**
 * Discovery response schema
 */
export const DiscoveryResponseSchema = z.object({
  resources: z.array(ResourceMetadataSchema),
});

// Type exports
export type FieldDefinitionSchemaType = z.infer<typeof FieldDefinitionSchema>;
export type AuthzConfigSchemaType = z.infer<typeof AuthzConfigSchema>;
export type ResourceMetadataSchemaType = z.infer<typeof ResourceMetadataSchema>;
export type PaginationSchemaType = z.infer<typeof PaginationSchema>;
export type ErrorResponseSchemaType = z.infer<typeof ErrorResponseSchema>;
export type DiscoveryResponseSchemaType = z.infer<typeof DiscoveryResponseSchema>;
