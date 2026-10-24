/**
 * Shared UI Components
 *
 * This module exports all shared UI components used throughout the application.
 * Components include: Header, Sidebar, ConfirmDialog, LoadingSpinner, ErrorMessage, etc.
 */

// ConfirmDialog component
export { ConfirmDialog } from './ConfirmDialog'

// LoadingSpinner component
export { LoadingSpinner } from './LoadingSpinner'

// ErrorMessage component
export { ErrorMessage } from './ErrorMessage'

// CollectionForm component
export { CollectionForm } from './CollectionForm'

// ExecutionLogModal component
export { ExecutionLogModal } from './ExecutionLogModal'
export type { LogColumn } from './ExecutionLogModal'

// DataSourceSettings — configure an external connector on a collection (Rec 4)
export { DataSourceSettings } from './DataSourceSettings/DataSourceSettings'
