/**
 * Shared UI Components
 *
 * This module exports all shared UI components used throughout the application.
 * Components include: Header, Sidebar, ConfirmDialog, LoadingSpinner, ErrorMessage, etc.
 */

// Header component
export { Header } from './Header'
export type { HeaderProps } from './Header'

// ErrorBoundary component
export { ErrorBoundary, ErrorFallback } from './ErrorBoundary'
export type { ErrorBoundaryProps, ErrorBoundaryState, ErrorFallbackProps } from './ErrorBoundary'

// PluginErrorBoundary component
export { PluginErrorBoundary } from './PluginErrorBoundary'
export type { PluginErrorBoundaryProps } from './PluginErrorBoundary'

// ConfirmDialog component
export { ConfirmDialog } from './ConfirmDialog'
export type { ConfirmDialogProps, ConfirmDialogVariant } from './ConfirmDialog'

// LoadingSpinner component
export { LoadingSpinner } from './LoadingSpinner'
export type { LoadingSpinnerProps, SpinnerSize } from './LoadingSpinner'

// ErrorMessage component
export { ErrorMessage } from './ErrorMessage'
export type { ErrorMessageProps, ErrorType, ErrorVariant } from './ErrorMessage'

// CollectionForm component
export { CollectionForm, collectionFormSchema } from './CollectionForm'
export type {
  CollectionFormProps,
  CollectionFormData,
  CollectionFormSchema,
  Collection as CollectionFormCollection,
  AvailableField,
} from './CollectionForm'

// FieldEditor component
export {
  FieldEditor,
  fieldEditorSchema,
  FIELD_TYPES,
  VALIDATION_RULES_BY_TYPE,
} from './FieldEditor'
export type {
  FieldEditorProps,
  FieldEditorFormData,
  ValidationRuleType,
  CollectionSummary,
} from './FieldEditor'

// LiveRegion component for screen reader announcements
export { LiveRegion, LiveRegionProvider, useAnnounce } from './LiveRegion'
export type {
  LiveRegionProps,
  LiveRegionPoliteness,
  LiveRegionContextValue,
  LiveRegionProviderProps,
} from './LiveRegion'

// ProtectedRoute component for route guards
export { ProtectedRoute, hasRequiredPolicies } from './ProtectedRoute'
export type { ProtectedRouteProps } from './ProtectedRoute'

// PageTransition component for page animations
export { PageTransition, usePrefersReducedMotion } from './PageTransition'
export type { PageTransitionProps, TransitionType } from './PageTransition'

// ValidationRuleEditor component
export { ValidationRuleEditor } from './ValidationRuleEditor'
export type { ValidationRuleEditorProps } from './ValidationRuleEditor'

// RecordTypeEditor component
export { RecordTypeEditor } from './RecordTypeEditor'
export type { RecordTypeEditorProps } from './RecordTypeEditor'

// PicklistValuesEditor component
export { PicklistValuesEditor } from './PicklistValuesEditor'
export type { PicklistValuesEditorProps } from './PicklistValuesEditor'

// PicklistDependencyEditor component
export { PicklistDependencyEditor } from './PicklistDependencyEditor'
export type { PicklistDependencyEditorProps } from './PicklistDependencyEditor'

// ExecutionLogModal component
export { ExecutionLogModal } from './ExecutionLogModal'
export type { ExecutionLogModalProps, LogColumn } from './ExecutionLogModal'

// SearchModal component for global search (Cmd+K)
export { SearchModal } from './SearchModal'
export type { SearchModalProps } from './SearchModal'

// RecentItemsDropdown component for header
export { RecentItemsDropdown } from './RecentItemsDropdown'
export type { RecentItemsDropdownProps } from './RecentItemsDropdown'

// LookupSelect component for reference field dropdowns
export { LookupSelect } from './LookupSelect'
export type { LookupSelectProps, LookupOption } from './LookupSelect'

// DataSourceSettings — configure an external connector on a collection (Rec 4)
export { DataSourceSettings } from './DataSourceSettings/DataSourceSettings'
export type { DataSourceSettingsProps } from './DataSourceSettings/DataSourceSettings'

// PageLoader component for loading states
export { PageLoader, Skeleton, ContentLoader } from './PageLoader'
export type {
  PageLoaderProps,
  SkeletonProps,
  SkeletonVariant,
  ContentLoaderProps,
} from './PageLoader'
