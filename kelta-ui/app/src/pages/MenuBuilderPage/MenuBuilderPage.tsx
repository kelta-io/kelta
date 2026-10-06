/**
 * MenuBuilderPage Component
 *
 * Menu configuration editor for creating and editing navigation menus.
 * Provides a list of all menus, a tree view editor for menu items,
 * drag-and-drop reordering, and nested menu item support.
 *
 * Requirements:
 * - 8.1: Display list of all menus
 * - 8.2: Create new menu action
 * - 8.3: Menu editor with tree view for items
 * - 8.4: Support drag-and-drop reordering of menu items
 * - 8.5: Support nested menu items (submenus)
 * - 8.6: Configure label, path, icon
 * - 8.7: Save menu configuration via API
 * - 8.8: Display menu preview
 */

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Home,
  BarChart3,
  Settings,
  Users,
  FolderOpen,
  FileText,
  BarChart2,
  Lock,
  Key,
  Database,
  Code,
  MapPin,
  Pencil,
  Trash2,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useToast, ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import type { ApiClient } from '../../services/apiClient'
import { cn } from '@/lib/utils'

/**
 * UI Menu interface matching the API response
 */
export interface UIMenu {
  id: string
  name: string
  items: UIMenuItem[]
  /** Lucide icon name shown in the end-user app switcher (apps/nav v2, V164). */
  icon?: string
  /** The app selected when the user has no stored preference (apps/nav v2, V164). */
  isDefault?: boolean
  /** Inactive apps are hidden from the end-user shell (apps/nav v2, V164). */
  active?: boolean
  displayOrder?: number
  createdAt: string
  updatedAt: string
}

/**
 * UI Menu Item interface
 */
export interface UIMenuItem {
  id: string
  label: string
  path?: string
  icon?: string
  order: number
  children?: UIMenuItem[]
}

/**
 * Form data for creating/editing a menu
 */
interface MenuFormData {
  name: string
  /** Lucide icon name shown in the end-user app switcher (apps/nav v2). */
  icon: string
  /** The app selected when the user has no stored preference (apps/nav v2). */
  isDefault: boolean
  /** Inactive apps are hidden from the end-user shell (apps/nav v2). */
  active: boolean
}

/**
 * Form data for creating/editing a menu item
 */
interface MenuItemFormData {
  label: string
  path: string
  icon: string
}

/**
 * Form validation errors
 */
interface FormErrors {
  name?: string
  label?: string
  path?: string
}

/**
 * Props for MenuBuilderPage component
 */
export interface MenuBuilderPageProps {
  /** Optional menu ID for editing an existing menu */
  menuId?: string
  /** Optional test ID for testing */
  testId?: string
}

// API functions using apiClient (only for endpoints not yet in SDK)
async function createMenu(apiClient: ApiClient, data: Partial<UIMenu>): Promise<UIMenu> {
  return apiClient.postResource('/api/ui-menus', data)
}

async function deleteMenu(apiClient: ApiClient, id: string): Promise<void> {
  return apiClient.deleteResource(`/api/ui-menus/${id}`)
}

/**
 * Validate menu form data
 */
function validateMenuForm(data: MenuFormData, t: (key: string) => string): FormErrors {
  const errors: FormErrors = {}

  if (!data.name.trim()) {
    errors.name = t('builder.menus.validation.nameRequired')
  } else if (data.name.length > 50) {
    errors.name = t('builder.menus.validation.nameTooLong')
  } else if (!/^[a-z][a-z0-9_]*$/.test(data.name)) {
    errors.name = t('builder.menus.validation.nameFormat')
  }

  return errors
}

/**
 * Validate menu item form data
 */
function validateMenuItemForm(data: MenuItemFormData, t: (key: string) => string): FormErrors {
  const errors: FormErrors = {}

  if (!data.label.trim()) {
    errors.label = t('builder.menus.validation.labelRequired')
  } else if (data.label.length > 100) {
    errors.label = t('builder.menus.validation.labelTooLong')
  }

  if (data.path && !data.path.startsWith('/')) {
    errors.path = t('builder.menus.validation.pathFormat')
  }

  return errors
}

/**
 * Generate a unique ID for menu items
 */
function generateId(): string {
  return `item_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

/**
 * Available icons for menu items
 */
const AVAILABLE_ICONS = [
  { value: '', label: 'None' },
  { value: 'home', label: 'Home' },
  { value: 'dashboard', label: 'Dashboard' },
  { value: 'settings', label: 'Settings' },
  { value: 'users', label: 'Users' },
  { value: 'folder', label: 'Folder' },
  { value: 'document', label: 'Document' },
  { value: 'chart', label: 'Chart' },
  { value: 'lock', label: 'Lock' },
  { value: 'key', label: 'Key' },
  { value: 'database', label: 'Database' },
  { value: 'code', label: 'Code' },
]

/**
 * Menu Form Component - for creating/editing menu configuration
 */
interface MenuFormProps {
  menu?: UIMenu
  onSubmit: (data: MenuFormData) => void
  onCancel: () => void
  isSubmitting: boolean
}

function MenuForm({ menu, onSubmit, onCancel, isSubmitting }: MenuFormProps): React.ReactElement {
  const { t } = useI18n()
  const isEditing = !!menu
  const [formData, setFormData] = useState<MenuFormData>({
    name: menu?.name ?? '',
    icon: menu?.icon ?? '',
    isDefault: menu?.isDefault ?? false,
    active: menu?.active ?? true,
  })
  const [errors, setErrors] = useState<FormErrors>({})
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const nameInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    nameInputRef.current?.focus()
  }, [])

  const handleChange = useCallback(
    (field: keyof MenuFormData, value: string | boolean) => {
      setFormData((prev) => ({ ...prev, [field]: value }))
      if (errors[field as keyof FormErrors]) {
        setErrors((prev) => ({ ...prev, [field]: undefined }))
      }
    },
    [errors]
  )

  const handleBlur = useCallback(
    (field: keyof MenuFormData) => {
      setTouched((prev) => ({ ...prev, [field]: true }))
      const validationErrors = validateMenuForm(formData, t)
      if (validationErrors[field as keyof FormErrors]) {
        setErrors((prev) => ({ ...prev, [field]: validationErrors[field as keyof FormErrors] }))
      }
    },
    [formData, t]
  )

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      const validationErrors = validateMenuForm(formData, t)
      setErrors(validationErrors)
      setTouched({ name: true })

      if (Object.keys(validationErrors).length === 0) {
        onSubmit(formData)
      }
    },
    [formData, onSubmit, t]
  )

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel()
      }
    },
    [onCancel]
  )

  const title = isEditing ? t('builder.menus.editMenu') : t('builder.menus.createMenu')

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={handleKeyDown}
      data-testid="menu-form-overlay"
      role="presentation"
    >
      <div
        className="w-full max-w-[500px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl animate-in fade-in zoom-in-95"
        role="dialog"
        aria-modal="true"
        aria-labelledby="menu-form-title"
        data-testid="menu-form-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="menu-form-title" className="m-0 text-lg font-semibold text-foreground">
            {title}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded bg-transparent border-none text-xl text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={onCancel}
            aria-label={t('common.close')}
            data-testid="menu-form-close"
          >
            ×
          </button>
        </div>
        <div className="p-6">
          <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
            <div className="flex flex-col gap-1">
              <label htmlFor="menu-name" className="text-sm font-medium text-foreground">
                {t('builder.menus.menuName')}
                <span className="ml-0.5 text-destructive" aria-hidden="true">
                  *
                </span>
              </label>
              <input
                ref={nameInputRef}
                id="menu-name"
                type="text"
                className={cn(
                  'rounded-md border border-border bg-card px-4 py-2 text-sm text-foreground transition-colors placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                  touched.name && errors.name && 'border-destructive focus:ring-destructive/20'
                )}
                value={formData.name}
                onChange={(e) => handleChange('name', e.target.value)}
                onBlur={() => handleBlur('name')}
                placeholder={t('builder.menus.namePlaceholder')}
                aria-required="true"
                aria-invalid={touched.name && !!errors.name}
                aria-describedby={errors.name ? 'menu-name-error' : 'menu-name-hint'}
                disabled={isSubmitting}
                data-testid="menu-name-input"
              />
              <span id="menu-name-hint" className="mt-1 text-xs text-muted-foreground">
                {t('builder.menus.nameHint')}
              </span>
              {touched.name && errors.name && (
                <span id="menu-name-error" className="mt-1 text-xs text-destructive" role="alert">
                  {errors.name}
                </span>
              )}
            </div>

            {/* Apps (nav v2): switcher icon + default/active flags */}
            <div className="flex flex-col gap-1">
              <label htmlFor="menu-icon" className="text-sm font-medium text-foreground">
                {t('builder.menus.icon')}
              </label>
              <input
                id="menu-icon"
                type="text"
                className="rounded-md border border-border bg-card px-4 py-2 text-sm text-foreground transition-colors placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
                value={formData.icon}
                onChange={(e) => handleChange('icon', e.target.value)}
                placeholder={t('builder.menus.iconPlaceholder')}
                disabled={isSubmitting}
                data-testid="menu-icon-input"
              />
              <span className="mt-1 text-xs text-muted-foreground">
                {t('builder.menus.iconHint')}
              </span>
            </div>
            <label className="flex items-center gap-2 text-sm text-foreground">
              <input
                type="checkbox"
                className="size-4 rounded border-border accent-primary"
                checked={formData.isDefault}
                onChange={(e) => handleChange('isDefault', e.target.checked)}
                disabled={isSubmitting}
                data-testid="menu-default-checkbox"
              />
              {t('builder.menus.isDefault')}
            </label>
            <label className="flex items-center gap-2 text-sm text-foreground">
              <input
                type="checkbox"
                className="size-4 rounded border-border accent-primary"
                checked={formData.active}
                onChange={(e) => handleChange('active', e.target.checked)}
                disabled={isSubmitting}
                data-testid="menu-active-checkbox"
              />
              {t('builder.menus.activeFlag')}
            </label>

            <div className="flex justify-end gap-2 mt-4 pt-4 border-t border-border max-md:flex-col">
              <button
                type="button"
                className="rounded-md border border-border bg-card px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-md:w-full"
                onClick={onCancel}
                disabled={isSubmitting}
                data-testid="menu-form-cancel"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                className="rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed max-md:w-full"
                disabled={isSubmitting}
                data-testid="menu-form-submit"
              >
                {isSubmitting ? t('common.loading') : t('common.save')}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
}

/**
 * Menu Item Form Component - for creating/editing menu items
 */
interface MenuItemFormProps {
  item?: UIMenuItem
  onSubmit: (data: MenuItemFormData) => void
  onCancel: () => void
  isSubmitting: boolean
}

function MenuItemForm({
  item,
  onSubmit,
  onCancel,
  isSubmitting,
}: MenuItemFormProps): React.ReactElement {
  const { t } = useI18n()
  const isEditing = !!item
  const [formData, setFormData] = useState<MenuItemFormData>({
    label: item?.label ?? '',
    path: item?.path ?? '',
    icon: item?.icon ?? '',
  })
  const [errors, setErrors] = useState<FormErrors>({})
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const labelInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    labelInputRef.current?.focus()
  }, [])

  const handleChange = useCallback(
    (field: keyof MenuItemFormData, value: string | string[]) => {
      setFormData((prev) => ({ ...prev, [field]: value }))
      if (errors[field as keyof FormErrors]) {
        setErrors((prev) => ({ ...prev, [field]: undefined }))
      }
    },
    [errors]
  )

  const handleBlur = useCallback(
    (field: keyof MenuItemFormData) => {
      setTouched((prev) => ({ ...prev, [field]: true }))
      const validationErrors = validateMenuItemForm(formData, t)
      if (validationErrors[field as keyof FormErrors]) {
        setErrors((prev) => ({ ...prev, [field]: validationErrors[field as keyof FormErrors] }))
      }
    },
    [formData, t]
  )

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      const validationErrors = validateMenuItemForm(formData, t)
      setErrors(validationErrors)
      setTouched({ label: true, path: true })

      if (Object.keys(validationErrors).length === 0) {
        onSubmit(formData)
      }
    },
    [formData, onSubmit, t]
  )

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel()
      }
    },
    [onCancel]
  )

  const title = isEditing ? t('builder.menus.editItem') : t('builder.menus.addItem')

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 p-4"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={handleKeyDown}
      data-testid="menu-item-form-overlay"
      role="presentation"
    >
      <div
        className="w-full max-w-[500px] max-h-[90vh] overflow-y-auto rounded-lg bg-card shadow-xl animate-in fade-in zoom-in-95"
        role="dialog"
        aria-modal="true"
        aria-labelledby="menu-item-form-title"
        data-testid="menu-item-form-modal"
      >
        <div className="flex items-center justify-between border-b border-border p-6">
          <h2 id="menu-item-form-title" className="m-0 text-lg font-semibold text-foreground">
            {title}
          </h2>
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded bg-transparent border-none text-xl text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={onCancel}
            aria-label={t('common.close')}
            data-testid="menu-item-form-close"
          >
            ×
          </button>
        </div>
        <div className="p-6">
          <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
            <div className="flex flex-col gap-1">
              <label htmlFor="item-label" className="text-sm font-medium text-foreground">
                {t('builder.menus.itemLabel')}
                <span className="ml-0.5 text-destructive" aria-hidden="true">
                  *
                </span>
              </label>
              <input
                ref={labelInputRef}
                id="item-label"
                type="text"
                className={cn(
                  'rounded-md border border-border bg-card px-4 py-2 text-sm text-foreground transition-colors placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                  touched.label && errors.label && 'border-destructive focus:ring-destructive/20'
                )}
                value={formData.label}
                onChange={(e) => handleChange('label', e.target.value)}
                onBlur={() => handleBlur('label')}
                placeholder={t('builder.menus.labelPlaceholder')}
                aria-required="true"
                aria-invalid={touched.label && !!errors.label}
                aria-describedby={errors.label ? 'item-label-error' : undefined}
                disabled={isSubmitting}
                data-testid="item-label-input"
              />
              {touched.label && errors.label && (
                <span id="item-label-error" className="mt-1 text-xs text-destructive" role="alert">
                  {errors.label}
                </span>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="item-path" className="text-sm font-medium text-foreground">
                {t('builder.menus.itemPath')}
              </label>
              <input
                id="item-path"
                type="text"
                className={cn(
                  'rounded-md border border-border bg-card px-4 py-2 text-sm text-foreground transition-colors placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                  touched.path && errors.path && 'border-destructive focus:ring-destructive/20'
                )}
                value={formData.path}
                onChange={(e) => handleChange('path', e.target.value)}
                onBlur={() => handleBlur('path')}
                placeholder={t('builder.menus.pathPlaceholder')}
                aria-invalid={touched.path && !!errors.path}
                aria-describedby={errors.path ? 'item-path-error' : 'item-path-hint'}
                disabled={isSubmitting}
                data-testid="item-path-input"
              />
              <span id="item-path-hint" className="mt-1 text-xs text-muted-foreground">
                {t('builder.menus.pathHint')}
              </span>
              {touched.path && errors.path && (
                <span id="item-path-error" className="mt-1 text-xs text-destructive" role="alert">
                  {errors.path}
                </span>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="item-icon" className="text-sm font-medium text-foreground">
                {t('builder.menus.itemIcon')}
              </label>
              <select
                id="item-icon"
                className="rounded-md border border-border bg-card px-4 py-2 text-sm text-foreground transition-colors focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
                value={formData.icon}
                onChange={(e) => handleChange('icon', e.target.value)}
                disabled={isSubmitting}
                data-testid="item-icon-select"
              >
                {AVAILABLE_ICONS.map((icon) => (
                  <option key={icon.value} value={icon.value}>
                    {icon.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex justify-end gap-2 mt-4 pt-4 border-t border-border max-md:flex-col">
              <button
                type="button"
                className="rounded-md border border-border bg-card px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-md:w-full"
                onClick={onCancel}
                disabled={isSubmitting}
                data-testid="menu-item-form-cancel"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                className="rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed max-md:w-full"
                disabled={isSubmitting}
                data-testid="menu-item-form-submit"
              >
                {isSubmitting ? t('common.loading') : t('common.save')}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
}

/**
 * Menu Tree Item Component - renders a single menu item with children
 * Supports drag-and-drop reordering (Requirement 8.4)
 * Supports nested items (Requirement 8.5)
 */
interface MenuTreeItemProps {
  item: UIMenuItem
  depth: number
  onEdit: (item: UIMenuItem) => void
  onDelete: (itemId: string) => void
  onAddChild: (parentId: string) => void
  onDragStart: (e: React.DragEvent, itemId: string) => void
  onDragOver: (e: React.DragEvent, itemId: string) => void
  onDrop: (e: React.DragEvent, targetId: string) => void
  onDragEnd: () => void
  dragOverId: string | null
  isDragging: boolean
}

function MenuTreeItem({
  item,
  depth,
  onEdit,
  onDelete,
  onAddChild,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  dragOverId,
  isDragging,
}: MenuTreeItemProps): React.ReactElement {
  const { t } = useI18n()
  const [isExpanded, setIsExpanded] = useState(true)
  const hasChildren = item.children && item.children.length > 0
  const isDragOver = dragOverId === item.id

  const iconComponents: Record<string, React.ComponentType<{ size?: number }>> = {
    home: Home,
    dashboard: BarChart3,
    settings: Settings,
    users: Users,
    folder: FolderOpen,
    document: FileText,
    chart: BarChart2,
    lock: Lock,
    key: Key,
    database: Database,
    code: Code,
  }

  const getMenuIcon = (iconName?: string): React.ReactNode => {
    const IconComp = iconComponents[iconName || '']
    if (IconComp) return <IconComp size={14} />
    return <MapPin size={14} />
  }

  return (
    <div className="mb-1">
      <div
        className={cn(
          'flex items-center justify-between rounded border border-border bg-muted p-2 cursor-grab transition-all hover:bg-muted-foreground/10 hover:border-muted-foreground/40',
          isDragOver && 'border-primary ring-2 ring-primary/20',
          isDragging && 'opacity-50'
        )}
        style={{ paddingLeft: `${depth * 24 + 8}px` }}
        draggable
        onDragStart={(e) => onDragStart(e, item.id)}
        onDragOver={(e) => onDragOver(e, item.id)}
        onDrop={(e) => onDrop(e, item.id)}
        onDragEnd={onDragEnd}
        data-testid={`tree-item-${item.id}`}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {hasChildren && (
            <button
              type="button"
              className="flex h-5 w-5 items-center justify-center rounded bg-transparent border-none text-xs text-muted-foreground cursor-pointer transition-colors hover:text-foreground hover:bg-muted-foreground/10 focus:outline-2 focus:outline-primary focus:outline-offset-2"
              onClick={() => setIsExpanded(!isExpanded)}
              aria-expanded={isExpanded}
              aria-label={isExpanded ? 'Collapse' : 'Expand'}
              data-testid={`expand-button-${item.id}`}
            >
              {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
          )}
          {!hasChildren && <span className="h-5 w-5" />}
          <span className="text-base">{getMenuIcon(item.icon)}</span>
          <span className="font-medium text-foreground truncate">{item.label}</span>
          {item.path && (
            <span className="shrink-0">
              <code className="font-mono text-xs rounded bg-card px-2 py-1 text-muted-foreground">
                {item.path}
              </code>
            </span>
          )}
        </div>
        <div className="flex gap-1 opacity-0 transition-opacity group-hover:opacity-100 [.flex:hover>&]:opacity-100">
          <button
            type="button"
            className="flex h-7 w-7 items-center justify-center rounded border border-transparent bg-transparent text-sm text-muted-foreground cursor-pointer transition-colors hover:text-foreground hover:bg-card hover:border-border focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={() => onAddChild(item.id)}
            aria-label={`Add child to ${item.label}`}
            title={t('builder.menus.addChild')}
            data-testid={`add-child-button-${item.id}`}
          >
            +
          </button>
          <button
            type="button"
            className="flex h-7 w-7 items-center justify-center rounded border border-transparent bg-transparent text-sm text-muted-foreground cursor-pointer transition-colors hover:text-foreground hover:bg-card hover:border-border focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={() => onEdit(item)}
            aria-label={`Edit ${item.label}`}
            title={t('common.edit')}
            data-testid={`edit-item-button-${item.id}`}
          >
            <Pencil size={14} />
          </button>
          <button
            type="button"
            className="flex h-7 w-7 items-center justify-center rounded border border-transparent bg-transparent text-sm text-muted-foreground cursor-pointer transition-colors hover:text-destructive hover:bg-destructive/10 hover:border-destructive/30 focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={() => onDelete(item.id)}
            aria-label={`Delete ${item.label}`}
            title={t('common.delete')}
            data-testid={`delete-item-button-${item.id}`}
          >
            <Trash2 size={14} />
          </button>
        </div>
      </div>
      {hasChildren && isExpanded && (
        <div className="ml-4 mt-1" data-testid={`children-${item.id}`}>
          {item.children!.map((child) => (
            <MenuTreeItem
              key={child.id}
              item={child}
              depth={depth + 1}
              onEdit={onEdit}
              onDelete={onDelete}
              onAddChild={onAddChild}
              onDragStart={onDragStart}
              onDragOver={onDragOver}
              onDrop={onDrop}
              onDragEnd={onDragEnd}
              dragOverId={dragOverId}
              isDragging={isDragging}
            />
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * Menu Tree View Component - displays menu items in a tree structure
 * Requirement 8.3: Menu editor with tree view for items
 */
interface MenuTreeViewProps {
  items: UIMenuItem[]
  onEdit: (item: UIMenuItem) => void
  onDelete: (itemId: string) => void
  onAddChild: (parentId: string | null) => void
  onReorder: (draggedId: string, targetId: string) => void
}

function MenuTreeView({
  items,
  onEdit,
  onDelete,
  onAddChild,
  onReorder,
}: MenuTreeViewProps): React.ReactElement {
  const { t } = useI18n()
  const [draggedItemId, setDraggedItemId] = useState<string | null>(null)
  const [dragOverId, setDragOverId] = useState<string | null>(null)

  const handleDragStart = useCallback((e: React.DragEvent, itemId: string) => {
    e.dataTransfer.setData('text/plain', itemId)
    e.dataTransfer.effectAllowed = 'move'
    setDraggedItemId(itemId)
  }, [])

  const handleDragOver = useCallback(
    (e: React.DragEvent, itemId: string) => {
      e.preventDefault()
      e.dataTransfer.dropEffect = 'move'
      if (draggedItemId !== itemId) {
        setDragOverId(itemId)
      }
    },
    [draggedItemId]
  )

  const handleDrop = useCallback(
    (e: React.DragEvent, targetId: string) => {
      e.preventDefault()
      const draggedId = e.dataTransfer.getData('text/plain')
      if (draggedId && draggedId !== targetId) {
        onReorder(draggedId, targetId)
      }
      setDraggedItemId(null)
      setDragOverId(null)
    },
    [onReorder]
  )

  const handleDragEnd = useCallback(() => {
    setDraggedItemId(null)
    setDragOverId(null)
  }, [])

  const handleAddChildWrapper = useCallback(
    (parentId: string) => {
      onAddChild(parentId)
    },
    [onAddChild]
  )

  if (items.length === 0) {
    return (
      <div
        className="flex flex-col items-center justify-center p-12 text-center text-muted-foreground"
        data-testid="tree-empty"
      >
        <p className="m-0">{t('builder.menus.noItems')}</p>
        <p className="m-0 mt-2 text-sm">{t('builder.menus.addItemHint')}</p>
      </div>
    )
  }

  return (
    <div
      className="flex-1 overflow-y-auto p-4"
      role="tree"
      aria-label={t('builder.menus.menuItems')}
      data-testid="menu-tree-view"
    >
      {items.map((item) => (
        <MenuTreeItem
          key={item.id}
          item={item}
          depth={0}
          onEdit={onEdit}
          onDelete={onDelete}
          onAddChild={handleAddChildWrapper}
          onDragStart={handleDragStart}
          onDragOver={handleDragOver}
          onDrop={handleDrop}
          onDragEnd={handleDragEnd}
          dragOverId={dragOverId}
          isDragging={draggedItemId === item.id}
        />
      ))}
    </div>
  )
}

/**
 * Menu Preview Component - displays the menu as it will appear
 * Requirement 8.8: Display menu preview
 */
interface MenuPreviewProps {
  menu: UIMenu | null
  items: UIMenuItem[]
}

function MenuPreview({ menu, items }: MenuPreviewProps): React.ReactElement {
  const { t } = useI18n()

  const previewIconComponents: Record<string, React.ComponentType<{ size?: number }>> = {
    home: Home,
    dashboard: BarChart3,
    settings: Settings,
    users: Users,
    folder: FolderOpen,
    document: FileText,
    chart: BarChart2,
    lock: Lock,
    key: Key,
    database: Database,
    code: Code,
  }

  const getPreviewIcon = (iconName?: string): React.ReactNode => {
    const IconComp = previewIconComponents[iconName || '']
    if (IconComp) return <IconComp size={14} />
    return <MapPin size={14} />
  }

  const renderPreviewItem = (item: UIMenuItem, depth: number = 0): React.ReactNode => {
    return (
      <div
        key={item.id}
        className="flex flex-wrap items-center gap-2 rounded p-2 transition-colors hover:bg-muted"
        style={{ paddingLeft: `${depth * 16}px` }}
        data-testid={`preview-item-${item.id}`}
      >
        <div className="flex items-center gap-2 flex-1">
          <span className="text-base">{getPreviewIcon(item.icon)}</span>
          <span className="text-sm text-foreground">{item.label}</span>
          {item.path && (
            <span className="shrink-0 ml-auto">
              <code className="font-mono text-xs rounded bg-muted px-1 py-0.5 text-muted-foreground">
                {item.path}
              </code>
            </span>
          )}
        </div>
        {item.children && item.children.length > 0 && (
          <div className="w-full mt-1 pl-4 border-l-2 border-border">
            {item.children.map((child) => renderPreviewItem(child, depth + 1))}
          </div>
        )}
      </div>
    )
  }

  return (
    <div
      className="flex flex-col overflow-hidden rounded-md border border-border bg-card"
      data-testid="menu-preview"
    >
      <h3 className="m-0 p-4 text-sm font-semibold text-foreground bg-muted border-b border-border">
        {t('builder.menus.preview')}
      </h3>
      <div className="flex-1 overflow-y-auto p-4">
        {items.length === 0 ? (
          <p className="m-0 p-6 text-center text-muted-foreground">{t('builder.menus.noItems')}</p>
        ) : (
          <nav className="flex flex-col gap-1" aria-label={menu?.name || 'Menu preview'}>
            {items.map((item) => renderPreviewItem(item))}
          </nav>
        )}
      </div>
    </div>
  )
}

/**
 * Helper function to find and update an item in the tree
 */
function updateItemInTree(
  items: UIMenuItem[],
  itemId: string,
  updater: (item: UIMenuItem) => UIMenuItem
): UIMenuItem[] {
  return items.map((item) => {
    if (item.id === itemId) {
      return updater(item)
    }
    if (item.children && item.children.length > 0) {
      return {
        ...item,
        children: updateItemInTree(item.children, itemId, updater),
      }
    }
    return item
  })
}

/**
 * Helper function to remove an item from the tree
 */
function removeItemFromTree(items: UIMenuItem[], itemId: string): UIMenuItem[] {
  return items
    .filter((item) => item.id !== itemId)
    .map((item) => {
      if (item.children && item.children.length > 0) {
        return {
          ...item,
          children: removeItemFromTree(item.children, itemId),
        }
      }
      return item
    })
}

/**
 * Helper function to add a child item to a parent
 */
function addChildToItem(items: UIMenuItem[], parentId: string, newItem: UIMenuItem): UIMenuItem[] {
  return items.map((item) => {
    if (item.id === parentId) {
      return {
        ...item,
        children: [...(item.children || []), newItem],
      }
    }
    if (item.children && item.children.length > 0) {
      return {
        ...item,
        children: addChildToItem(item.children, parentId, newItem),
      }
    }
    return item
  })
}

/**
 * Helper function to find an item in the tree
 */
function findItemInTree(items: UIMenuItem[], itemId: string): UIMenuItem | null {
  for (const item of items) {
    if (item.id === itemId) {
      return item
    }
    if (item.children && item.children.length > 0) {
      const found = findItemInTree(item.children, itemId)
      if (found) return found
    }
  }
  return null
}

/**
 * Helper function to move an item in the tree (for drag-and-drop)
 */
function moveItemInTree(items: UIMenuItem[], draggedId: string, targetId: string): UIMenuItem[] {
  // Find the dragged item
  const draggedItem = findItemInTree(items, draggedId)
  if (!draggedItem) return items

  // Remove the dragged item from its current position
  let newItems = removeItemFromTree(items, draggedId)

  // Find the target item and insert the dragged item after it
  const insertAfter = (arr: UIMenuItem[], targetId: string, item: UIMenuItem): UIMenuItem[] => {
    const result: UIMenuItem[] = []
    for (const current of arr) {
      result.push(current)
      if (current.id === targetId) {
        result.push(item)
      } else if (current.children && current.children.length > 0) {
        const updatedChildren = insertAfter(current.children, targetId, item)
        if (updatedChildren !== current.children) {
          result[result.length - 1] = { ...current, children: updatedChildren }
        }
      }
    }
    return result
  }

  newItems = insertAfter(newItems, targetId, draggedItem)

  // Update order values
  const updateOrders = (arr: UIMenuItem[]): UIMenuItem[] => {
    return arr.map((item, index) => ({
      ...item,
      order: index,
      children: item.children ? updateOrders(item.children) : undefined,
    }))
  }

  return updateOrders(newItems)
}

/**
 * MenuBuilderPage Component
 *
 * Main page for building and editing navigation menus in the Kelta Admin UI.
 * Provides a tree view editor with drag-and-drop reordering and nested item support.
 */
export function MenuBuilderPage({
  menuId,
  testId = 'menu-builder-page',
}: MenuBuilderPageProps): React.ReactElement {
  const { apiClient, keltaClient } = useApi()
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { showToast } = useToast()

  // View mode: 'list' shows all menus, 'editor' shows the menu editor
  const [viewMode, setViewMode] = useState<'list' | 'editor'>(menuId ? 'editor' : 'list')
  const [editingMenuId, setEditingMenuId] = useState<string | null>(menuId ?? null)

  // Form modal state
  const [isMenuFormOpen, setIsMenuFormOpen] = useState(false)
  const [editingMenu, setEditingMenu] = useState<UIMenu | undefined>(undefined)

  // Menu item form state
  const [isItemFormOpen, setIsItemFormOpen] = useState(false)
  const [editingItem, setEditingItem] = useState<UIMenuItem | undefined>(undefined)
  const [parentItemId, setParentItemId] = useState<string | null>(null)

  // Delete confirmation dialog state
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [menuToDelete, setMenuToDelete] = useState<UIMenu | null>(null)
  const [itemToDelete, setItemToDelete] = useState<string | null>(null)

  // Editor state
  const [menuItems, setMenuItems] = useState<UIMenuItem[]>([])
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false)

  // Fetch all menus query - always enabled so we have menu data in editor mode
  const {
    data: menus = [],
    isLoading: isLoadingMenus,
    error: menusError,
    refetch: refetchMenus,
  } = useQuery({
    queryKey: ['ui-menus'],
    queryFn: () => keltaClient.admin.ui.listMenus() as Promise<UIMenu[]>,
  })

  // Fetch single menu query for editing - get from the menus list
  const currentMenu = useMemo(() => {
    if (!editingMenuId || !menus) return undefined
    return menus.find((m) => m.id === editingMenuId)
  }, [editingMenuId, menus])

  useEffect(() => {
    if (currentMenu) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setMenuItems(currentMenu.items || [])
      setHasUnsavedChanges(false)
    }
  }, [currentMenu])

  // Create menu mutation
  const createMenuMutation = useMutation({
    mutationFn: (data: Partial<UIMenu>) => createMenu(apiClient, data),
    onSuccess: (newMenu) => {
      queryClient.invalidateQueries({ queryKey: ['ui-menus'] })
      showToast(t('success.created', { item: t('builder.menus.menu') }), 'success')
      handleCloseMenuForm()
      // Open the editor for the new menu
      setEditingMenuId(newMenu.id)
      setMenuItems([])
      setViewMode('editor')
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Update menu mutation
  const updateMenuMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<UIMenu> }) =>
      keltaClient.admin.ui.updateMenu(id, data as unknown as import('@kelta/sdk').UIMenu),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ui-menus'] })
      queryClient.invalidateQueries({ queryKey: ['ui-menu', editingMenuId] })
      showToast(t('success.updated', { item: t('builder.menus.menu') }), 'success')
      setHasUnsavedChanges(false)
      handleCloseMenuForm()
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Delete menu mutation
  const deleteMenuMutation = useMutation({
    mutationFn: (id: string) => deleteMenu(apiClient, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ui-menus'] })
      showToast(t('success.deleted', { item: t('builder.menus.menu') }), 'success')
      setDeleteDialogOpen(false)
      setMenuToDelete(null)
      if (editingMenuId === menuToDelete?.id) {
        setViewMode('list')
        setEditingMenuId(null)
      }
    },
    onError: (error: Error) => {
      showToast(error.message || t('errors.generic'), 'error')
    },
  })

  // Handle create menu action
  const handleCreateMenu = useCallback(() => {
    setEditingMenu(undefined)
    setIsMenuFormOpen(true)
  }, [])

  // Handle edit menu config
  const handleEditMenuConfig = useCallback((menu: UIMenu) => {
    setEditingMenu(menu)
    setIsMenuFormOpen(true)
  }, [])

  // Handle close menu form
  const handleCloseMenuForm = useCallback(() => {
    setIsMenuFormOpen(false)
    setEditingMenu(undefined)
  }, [])

  // Handle menu form submit
  const handleMenuFormSubmit = useCallback(
    (data: MenuFormData) => {
      const menuData: Partial<UIMenu> = {
        name: data.name,
        icon: data.icon.trim() === '' ? undefined : data.icon.trim(),
        isDefault: data.isDefault,
        active: data.active,
      }

      if (editingMenu) {
        updateMenuMutation.mutate({ id: editingMenu.id, data: menuData })
      } else {
        createMenuMutation.mutate({
          ...menuData,
          items: [],
        })
      }
    },
    [editingMenu, createMenuMutation, updateMenuMutation]
  )

  // Handle delete menu action
  const handleDeleteMenuClick = useCallback((menu: UIMenu) => {
    setMenuToDelete(menu)
    setItemToDelete(null)
    setDeleteDialogOpen(true)
  }, [])

  // Handle delete menu confirmation
  const handleDeleteMenuConfirm = useCallback(() => {
    if (menuToDelete) {
      deleteMenuMutation.mutate(menuToDelete.id)
    } else if (itemToDelete) {
      setMenuItems((prev) => removeItemFromTree(prev, itemToDelete))
      setHasUnsavedChanges(true)
      setDeleteDialogOpen(false)
      setItemToDelete(null)
      showToast(t('success.deleted', { item: t('builder.menus.item') }), 'success')
    }
  }, [menuToDelete, itemToDelete, deleteMenuMutation, showToast, t])

  // Handle delete cancel
  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
    setMenuToDelete(null)
    setItemToDelete(null)
  }, [])

  // Handle opening menu editor
  const handleOpenEditor = useCallback((menu: UIMenu) => {
    setEditingMenuId(menu.id)
    setViewMode('editor')
  }, [])

  // Handle back to list
  const handleBackToList = useCallback(() => {
    if (hasUnsavedChanges) {
      // Could show a confirmation dialog here
    }
    setViewMode('list')
    setEditingMenuId(null)
    setMenuItems([])
    setHasUnsavedChanges(false)
  }, [hasUnsavedChanges])

  // Handle add menu item
  const handleAddItem = useCallback((parentId: string | null) => {
    setParentItemId(parentId)
    setEditingItem(undefined)
    setIsItemFormOpen(true)
  }, [])

  // Handle edit menu item
  const handleEditItem = useCallback((item: UIMenuItem) => {
    setEditingItem(item)
    setParentItemId(null)
    setIsItemFormOpen(true)
  }, [])

  // Handle delete menu item
  const handleDeleteItem = useCallback((itemId: string) => {
    setItemToDelete(itemId)
    setMenuToDelete(null)
    setDeleteDialogOpen(true)
  }, [])

  // Handle close item form
  const handleCloseItemForm = useCallback(() => {
    setIsItemFormOpen(false)
    setEditingItem(undefined)
    setParentItemId(null)
  }, [])

  // Handle item form submit
  const handleItemFormSubmit = useCallback(
    (data: MenuItemFormData) => {
      if (editingItem) {
        // Update existing item (Requirement 8.6: Configure label, path, icon)
        setMenuItems((prev) =>
          updateItemInTree(prev, editingItem.id, (item) => ({
            ...item,
            label: data.label,
            path: data.path || undefined,
            icon: data.icon || undefined,
          }))
        )
      } else {
        // Create new item (Requirement 8.6: Configure label, path, icon)
        const newItem: UIMenuItem = {
          id: generateId(),
          label: data.label,
          path: data.path || undefined,
          icon: data.icon || undefined,
          order: menuItems.length,
        }

        if (parentItemId) {
          // Add as child of parent
          setMenuItems((prev) => addChildToItem(prev, parentItemId, newItem))
        } else {
          // Add to root level
          setMenuItems((prev) => [...prev, newItem])
        }
      }

      setHasUnsavedChanges(true)
      handleCloseItemForm()
      showToast(
        editingItem
          ? t('success.updated', { item: t('builder.menus.item') })
          : t('success.created', { item: t('builder.menus.item') }),
        'success'
      )
    },
    [editingItem, parentItemId, menuItems.length, handleCloseItemForm, showToast, t]
  )

  // Handle reorder (drag-and-drop)
  const handleReorder = useCallback((draggedId: string, targetId: string) => {
    setMenuItems((prev) => moveItemInTree(prev, draggedId, targetId))
    setHasUnsavedChanges(true)
  }, [])

  // Handle save menu
  const handleSaveMenu = useCallback(() => {
    if (!editingMenuId) return
    updateMenuMutation.mutate({
      id: editingMenuId,
      data: { items: menuItems },
    })
  }, [editingMenuId, menuItems, updateMenuMutation])

  const isSubmitting = createMenuMutation.isPending || updateMenuMutation.isPending

  // Render loading state
  if (viewMode === 'list' && isLoadingMenus) {
    return (
      <div className="flex h-full min-h-0 w-full flex-col p-6" data-testid={testId}>
        <div className="flex min-h-[400px] items-center justify-center">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Render error state
  if (viewMode === 'list' && menusError) {
    return (
      <div className="flex h-full min-h-0 w-full flex-col p-6" data-testid={testId}>
        <ErrorMessage
          error={menusError instanceof Error ? menusError : new Error(t('errors.generic'))}
          onRetry={() => refetchMenus()}
        />
      </div>
    )
  }

  // Render editor view
  if (viewMode === 'editor') {
    return (
      <div className="flex h-full min-h-0 w-full flex-col p-6 max-md:p-2" data-testid={testId}>
        <header className="flex items-center justify-between border-b border-border pb-4 mb-4 max-md:flex-col max-md:gap-2">
          <div className="flex items-center gap-4 max-md:w-full max-md:justify-between">
            <button
              type="button"
              className="bg-transparent border-none px-2 py-1 text-sm text-muted-foreground cursor-pointer transition-colors hover:text-foreground focus:outline-2 focus:outline-primary focus:outline-offset-2"
              onClick={handleBackToList}
              aria-label={t('common.back')}
              data-testid="back-to-list-button"
            >
              ← {t('common.back')}
            </button>
            <h1 className="m-0 text-xl font-semibold text-foreground">
              {currentMenu?.name || t('builder.menus.newMenu')}
              {hasUnsavedChanges && (
                <span className="ml-1 text-amber-600 dark:text-amber-400">*</span>
              )}
            </h1>
          </div>
          <div className="flex items-center gap-2 max-md:w-full max-md:justify-between">
            {currentMenu && (
              <button
                type="button"
                className="rounded-md border border-border bg-card px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted focus:outline-2 focus:outline-primary focus:outline-offset-2"
                onClick={() => handleEditMenuConfig(currentMenu)}
                data-testid="edit-config-button"
              >
                {t('builder.menus.editMenu')}
              </button>
            )}
            <button
              type="button"
              className="rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed"
              onClick={handleSaveMenu}
              disabled={!hasUnsavedChanges || updateMenuMutation.isPending}
              data-testid="save-menu-button"
            >
              {updateMenuMutation.isPending ? t('common.saving') : t('common.save')}
            </button>
          </div>
        </header>

        <div className="grid grid-cols-[1fr_300px] gap-4 flex-1 min-h-0 overflow-hidden max-lg:grid-cols-[1fr_250px] max-md:grid-cols-1 max-md:grid-rows-[1fr_auto]">
          <div className="flex flex-col overflow-hidden rounded-md border border-border bg-card">
            <div className="flex items-center border-b border-border bg-muted px-4 py-2">
              <button
                type="button"
                className="inline-flex items-center rounded border-none bg-primary px-2 py-1 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                onClick={() => handleAddItem(null)}
                data-testid="add-item-button"
              >
                {t('builder.menus.addItem')}
              </button>
            </div>
            <MenuTreeView
              items={menuItems}
              onEdit={handleEditItem}
              onDelete={handleDeleteItem}
              onAddChild={handleAddItem}
              onReorder={handleReorder}
            />
          </div>
          <MenuPreview menu={currentMenu || null} items={menuItems} />
        </div>

        {isMenuFormOpen && (
          <MenuForm
            menu={editingMenu}
            onSubmit={handleMenuFormSubmit}
            onCancel={handleCloseMenuForm}
            isSubmitting={isSubmitting}
          />
        )}

        {isItemFormOpen && (
          <MenuItemForm
            item={editingItem}
            onSubmit={handleItemFormSubmit}
            onCancel={handleCloseItemForm}
            isSubmitting={false}
          />
        )}

        <ConfirmDialog
          open={deleteDialogOpen}
          title={menuToDelete ? t('builder.menus.deleteMenu') : t('builder.menus.deleteItem')}
          message={
            menuToDelete
              ? t('builder.menus.confirmDeleteMenu')
              : t('builder.menus.confirmDeleteItem')
          }
          confirmLabel={t('common.delete')}
          cancelLabel={t('common.cancel')}
          onConfirm={handleDeleteMenuConfirm}
          onCancel={handleDeleteCancel}
          variant="danger"
        />
      </div>
    )
  }

  // Render list view
  return (
    <div className="flex h-full min-h-0 w-full flex-col p-6 max-md:p-2" data-testid={testId}>
      <header className="flex flex-wrap items-center justify-between gap-4 mb-6 max-md:flex-col max-md:items-stretch">
        <h1 className="m-0 text-2xl font-semibold text-foreground max-md:text-xl">
          {t('builder.menus.title')}
        </h1>
        <button
          type="button"
          className="inline-flex items-center justify-center rounded-md border-none bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-md:w-full"
          onClick={handleCreateMenu}
          aria-label={t('builder.menus.createMenu')}
          data-testid="create-menu-button"
        >
          {t('builder.menus.createMenu')}
        </button>
      </header>

      {menus.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center rounded-md bg-muted p-12 text-center text-muted-foreground"
          data-testid="empty-state"
        >
          <p className="m-0">{t('common.noResults')}</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border bg-card">
          <table
            className="w-full border-collapse text-sm"
            role="grid"
            aria-label={t('builder.menus.title')}
            data-testid="menus-table"
          >
            <thead className="bg-muted">
              <tr role="row">
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b-2 border-border p-4 text-left font-semibold text-foreground whitespace-nowrap"
                >
                  {t('builder.menus.menuName')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b-2 border-border p-4 text-left font-semibold text-foreground whitespace-nowrap"
                >
                  {t('builder.menus.itemCount')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b-2 border-border p-4 text-left font-semibold text-foreground whitespace-nowrap"
                >
                  {t('collections.updated')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="border-b-2 border-border p-4 text-left font-semibold text-foreground whitespace-nowrap"
                >
                  {t('common.actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {menus.map((menu, index) => (
                <tr
                  key={menu.id}
                  role="row"
                  className="transition-colors hover:bg-muted/50"
                  data-testid={`menu-row-${index}`}
                >
                  <td
                    role="gridcell"
                    className="border-b border-border p-4 font-medium text-foreground"
                  >
                    <button
                      type="button"
                      className="bg-transparent border-none p-0 font-inherit text-primary cursor-pointer hover:underline focus:outline-2 focus:outline-primary focus:outline-offset-2"
                      onClick={() => handleOpenEditor(menu)}
                      data-testid={`menu-name-${index}`}
                    >
                      {menu.name}
                    </button>
                  </td>
                  <td role="gridcell" className="border-b border-border p-4 text-muted-foreground">
                    {menu.items?.length || 0} {t('builder.menus.items')}
                  </td>
                  <td
                    role="gridcell"
                    className="border-b border-border p-4 text-muted-foreground whitespace-nowrap"
                  >
                    {formatDate(new Date(menu.updatedAt), {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    })}
                  </td>
                  <td
                    role="gridcell"
                    className="border-b border-border p-4 w-[1%] whitespace-nowrap"
                  >
                    <div className="flex gap-2">
                      <button
                        type="button"
                        className="rounded border border-border bg-card px-2 py-1 text-xs font-medium text-foreground cursor-pointer transition-colors hover:bg-muted hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={() => handleOpenEditor(menu)}
                        aria-label={`${t('common.edit')} ${menu.name}`}
                        data-testid={`edit-button-${index}`}
                      >
                        {t('common.edit')}
                      </button>
                      <button
                        type="button"
                        className="rounded border border-destructive/30 bg-card px-2 py-1 text-xs font-medium text-destructive cursor-pointer transition-colors hover:bg-destructive/10 hover:border-destructive focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={() => handleDeleteMenuClick(menu)}
                        aria-label={`${t('common.delete')} ${menu.name}`}
                        data-testid={`delete-button-${index}`}
                      >
                        {t('common.delete')}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {isMenuFormOpen && (
        <MenuForm
          menu={editingMenu}
          onSubmit={handleMenuFormSubmit}
          onCancel={handleCloseMenuForm}
          isSubmitting={isSubmitting}
        />
      )}

      <ConfirmDialog
        open={deleteDialogOpen}
        title={t('builder.menus.deleteMenu')}
        message={t('builder.menus.confirmDeleteMenu')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleDeleteMenuConfirm}
        onCancel={handleDeleteCancel}
        variant="danger"
      />
    </div>
  )
}

export default MenuBuilderPage
