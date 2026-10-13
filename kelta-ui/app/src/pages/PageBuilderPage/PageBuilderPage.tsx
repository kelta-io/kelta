/**
 * PageBuilderPage Component
 *
 * Visual page editor for creating and editing UI pages.
 * Provides a list of all pages, a canvas for page layout,
 * a component palette for adding components, and a property panel
 * for editing component properties.
 *
 * Requirements:
 * - 7.1: Display list of all pages
 * - 7.2: Create new page action
 * - 7.3: Page editor with canvas area
 * - 7.4: Component palette for adding components
 * - 7.5: Property panel for editing component properties
 * - 7.6: Page configuration (path, title, layout)
 * - 7.7: Preview mode shows page as it will appear to end users
 * - 7.8: Save persists page configuration to backend
 * - 7.9: Publish makes page available to end users
 * - 7.10: Duplicate creates copy of existing page
 * - 12.5: Use registered custom page components when rendering pages
 */

import React, { useState, useCallback, useEffect, useRef } from 'react'
import { toast } from 'sonner'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { getTenantSlug } from '../../context/TenantContext'
import { ConfirmDialog, LoadingSpinner, ErrorMessage } from '../../components'
import { cn } from '@/lib/utils'
import { readComponents, readConfig, mergeConfig } from './pageConfig'
import type { PageDataSource, PageVariable } from './pageConfig'
import { Palette } from './palette/Palette'
import { Inspector } from './inspector/Inspector'
import { Canvas } from './canvas/Canvas'
import { PageSettingsDrawer } from './inspector/pageSettings/PageSettingsDrawer'
import { RenderTree } from './widgets/renderTree'
import { widgetRegistry } from './widgets/registry'
import './widgets/builtins'
import type { PageComponent as ModelPageComponent, ResponsiveSpan } from './model/pageModel'
import { insertNode, findNode } from './model/treeOps'
import { migrateTree, needsMigration } from './model/migrate'
import { useEditorHistory } from './hooks/useEditorHistory'

/**
 * UI Page interface matching the API response
 */
export interface UIPage {
  id: string
  name: string
  path: string
  title: string
  layout: PageLayout
  components: PageComponent[]
  policies?: string[]
  published: boolean
  createdAt: string
  updatedAt: string
}

/**
 * Page layout configuration
 */
export interface PageLayout {
  type: 'single' | 'sidebar' | 'grid'
  config?: Record<string, unknown>
}

/**
 * Page component definition. Slice 2c stops writing `position` (the v2 layout is the widget tree +
 * per-child `span`) and adds `span?`; `position` is retained only as deprecated legacy input that
 * `migrate.ts` reads and strips on load.
 */
export interface PageComponent {
  id: string
  type: string
  props: Record<string, unknown>
  children?: PageComponent[]
  span?: ResponsiveSpan
  /** @deprecated Legacy canvas coords — ignored by the renderer, migrated away by `migrate.ts` (2c). */
  position?: ComponentPosition
}

/**
 * Component position on the canvas.
 * @deprecated Legacy layout coords — no longer written; migrated to `grid`/`column` + `span` on load.
 */
export interface ComponentPosition {
  row: number
  column: number
  width: number
  height: number
}

/**
 * Form data for creating/editing a page
 */
interface PageFormData {
  name: string
  path: string
  title: string
  layoutType: 'single' | 'sidebar' | 'grid'
}

/**
 * Form validation errors
 */
interface FormErrors {
  name?: string
  path?: string
  title?: string
}

/**
 * Props for PageBuilderPage component
 */
export interface PageBuilderPageProps {
  /** Optional page ID for editing an existing page */
  pageId?: string
  /** Optional test ID for testing */
  testId?: string
}

/**
 * Validate page form data
 */
function validateForm(data: PageFormData, t: (key: string) => string): FormErrors {
  const errors: FormErrors = {}

  if (!data.name.trim()) {
    errors.name = t('builder.pages.validation.nameRequired')
  } else if (data.name.length > 50) {
    errors.name = t('builder.pages.validation.nameTooLong')
  } else if (!/^[a-z][a-z0-9_]*$/.test(data.name)) {
    errors.name = t('builder.pages.validation.nameFormat')
  }

  if (!data.path.trim()) {
    errors.path = t('builder.pages.validation.pathRequired')
  } else if (!data.path.startsWith('/')) {
    errors.path = t('builder.pages.validation.pathFormat')
  }

  if (!data.title.trim()) {
    errors.title = t('builder.pages.validation.titleRequired')
  } else if (data.title.length > 100) {
    errors.title = t('builder.pages.validation.titleTooLong')
  }

  return errors
}

/**
 * Generate a unique ID for components
 */
function generateId(): string {
  return `comp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

/**
 * Read a page's component tree, migrating a legacy `position`-based tree (no `schemaVersion: 2`) into the
 * v2 `grid`/`column` + `span` model on load (slice 2c). A page already at `schemaVersion: 2`, or one with
 * no `position` anywhere, is returned untouched — the migration is idempotent and runs only in the
 * builder (never server-side), so a page is only rewritten when an author opens + saves it.
 */
function seedComponents(page: Partial<UIPage> | null | undefined): PageComponent[] {
  const raw = readComponents(page)
  const cfg = readConfig(page)
  if (cfg.schemaVersion === 2) return raw
  return needsMigration(raw as ModelPageComponent[])
    ? (migrateTree(raw as ModelPageComponent[]) as PageComponent[])
    : raw
}

/**
 * Status Badge Component
 */
interface StatusBadgeProps {
  published: boolean
}

function StatusBadge({ published }: StatusBadgeProps): React.ReactElement {
  const { t } = useI18n()
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-1 text-xs font-medium rounded-full',
        published
          ? 'text-green-800 bg-green-100 dark:text-green-300 dark:bg-green-900'
          : 'text-yellow-800 bg-yellow-100 dark:text-yellow-300 dark:bg-yellow-900'
      )}
      data-testid="status-badge"
    >
      {published ? t('builder.pages.published') : t('builder.pages.draft')}
    </span>
  )
}

/**
 * Preview Component - displays the page as it will appear to end users
 * Requirement 7.7: Preview mode shows page as it will appear to end users
 * Requirement 12.5: Use registered custom page components when rendering pages
 *
 * Renders the component tree through the shared registry-driven `RenderTree` (editor mode), the same
 * path the canvas preview and the runtime renderer use — so previewing matches what end users see.
 */
interface PreviewProps {
  page: UIPage | null
  components: PageComponent[]
  onClose: () => void
}

function Preview({ page, components, onClose }: PreviewProps): React.ReactElement {
  const { t } = useI18n()

  return (
    <div
      className="fixed inset-0 bg-black/80 flex items-center justify-center z-[1100] p-4"
      data-testid="preview-overlay"
    >
      <div
        className="bg-background rounded-lg shadow-2xl w-full max-w-[1200px] max-h-[90vh] flex flex-col overflow-hidden animate-in zoom-in-90 duration-300"
        data-testid="preview-container"
      >
        <header className="flex justify-between items-center px-6 py-4 border-b border-border bg-muted">
          <h2 className="m-0 text-lg font-semibold text-foreground">
            {t('builder.pages.preview')}: {page?.title || t('builder.pages.newPage')}
          </h2>
          <button
            type="button"
            className="flex items-center justify-center w-8 h-8 p-0 text-xl text-muted-foreground bg-transparent border-none rounded cursor-pointer transition-colors hover:bg-accent hover:text-foreground focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={onClose}
            aria-label={t('common.close')}
            data-testid="preview-close-button"
          >
            ×
          </button>
        </header>
        <div className="flex-1 overflow-y-auto p-6 min-h-[400px]" data-testid="preview-content">
          {components.length === 0 ? (
            <div className="flex items-center justify-center h-full min-h-[300px] text-muted-foreground">
              <p>{t('builder.pages.canvasEmpty')}</p>
            </div>
          ) : (
            <RenderTree mode="editor" components={components} tenantSlug={getTenantSlug()} />
          )}
        </div>
        <footer className="flex justify-between items-center px-6 py-4 border-t border-border bg-muted">
          <span className="text-sm text-muted-foreground">
            {t('builder.pages.pagePath')}:{' '}
            <code className="font-mono px-2 py-1 bg-background rounded">{page?.path || '/'}</code>
          </span>
          <button
            type="button"
            className="px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={onClose}
            data-testid="preview-exit-button"
          >
            {t('builder.pages.exitPreview') || 'Exit Preview'}
          </button>
        </footer>
      </div>
    </div>
  )
}

// The canvas now lives in `./canvas/Canvas.tsx` (the `@dnd-kit` layout engine, slice 2c). The legacy
// native-HTML5-DnD `Canvas` + its `renderComponent`/drag handlers were removed in this slice.

/**
 * Page Form Component - for creating/editing page configuration
 */
interface PageFormProps {
  page?: UIPage
  onSubmit: (data: PageFormData) => void
  onCancel: () => void
  isSubmitting: boolean
}

function PageForm({ page, onSubmit, onCancel, isSubmitting }: PageFormProps): React.ReactElement {
  const { t } = useI18n()
  const isEditing = !!page
  const [formData, setFormData] = useState<PageFormData>({
    name: page?.name ?? '',
    path: page?.path ?? '/',
    title: page?.title ?? '',
    layoutType: page?.layout?.type ?? 'single',
  })
  const [errors, setErrors] = useState<FormErrors>({})
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const nameInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    nameInputRef.current?.focus()
  }, [])

  const handleChange = useCallback(
    (field: keyof PageFormData, value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }))
      if (errors[field as keyof FormErrors]) {
        setErrors((prev) => ({ ...prev, [field]: undefined }))
      }
    },
    [errors]
  )

  const handleBlur = useCallback(
    (field: keyof PageFormData) => {
      setTouched((prev) => ({ ...prev, [field]: true }))
      const validationErrors = validateForm(formData, t)
      if (validationErrors[field as keyof FormErrors]) {
        setErrors((prev) => ({ ...prev, [field]: validationErrors[field as keyof FormErrors] }))
      }
    },
    [formData, t]
  )

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault()
      const validationErrors = validateForm(formData, t)
      setErrors(validationErrors)
      setTouched({ name: true, path: true, title: true })

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

  const title = isEditing ? t('builder.pages.editPage') : t('builder.pages.createPage')

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4"
      onClick={(e) => e.target === e.currentTarget && onCancel()}
      onKeyDown={handleKeyDown}
      data-testid="page-form-overlay"
      role="presentation"
    >
      <div
        className="bg-background rounded-lg shadow-xl w-full max-w-[500px] max-h-[90vh] overflow-y-auto animate-in zoom-in-95 duration-200 max-md:max-w-full max-md:mx-2"
        role="dialog"
        aria-modal="true"
        aria-labelledby="page-form-title"
        data-testid="page-form-modal"
      >
        <div className="flex justify-between items-center p-6 border-b border-border">
          <h2 id="page-form-title" className="m-0 text-lg font-semibold text-foreground">
            {title}
          </h2>
          <button
            type="button"
            className="flex items-center justify-center w-8 h-8 p-0 text-xl text-muted-foreground bg-transparent border-none rounded cursor-pointer transition-colors hover:bg-accent hover:text-foreground focus:outline-2 focus:outline-primary focus:outline-offset-2"
            onClick={onCancel}
            aria-label={t('common.close')}
            data-testid="page-form-close"
          >
            ×
          </button>
        </div>
        <div className="p-6">
          <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
            <div className="flex flex-col gap-1">
              <label htmlFor="page-name" className="text-sm font-medium text-foreground">
                {t('builder.pages.pageName')}
                <span className="text-destructive ml-0.5" aria-hidden="true">
                  *
                </span>
              </label>
              <input
                ref={nameInputRef}
                id="page-name"
                type="text"
                className={cn(
                  'px-4 py-2 text-sm text-foreground bg-background border border-border rounded-md transition-[border-color,box-shadow] focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/20 placeholder:text-muted-foreground',
                  touched.name && errors.name && 'border-destructive focus:ring-destructive/20'
                )}
                value={formData.name}
                onChange={(e) => handleChange('name', e.target.value)}
                onBlur={() => handleBlur('name')}
                placeholder={t('builder.pages.namePlaceholder')}
                aria-required="true"
                aria-invalid={touched.name && !!errors.name}
                aria-describedby={errors.name ? 'page-name-error' : 'page-name-hint'}
                disabled={isSubmitting}
                data-testid="page-name-input"
              />
              <span id="page-name-hint" className="text-xs text-muted-foreground mt-1">
                {t('builder.pages.nameHint')}
              </span>
              {touched.name && errors.name && (
                <span id="page-name-error" className="text-xs text-destructive mt-1" role="alert">
                  {errors.name}
                </span>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="page-path" className="text-sm font-medium text-foreground">
                {t('builder.pages.pagePath')}
                <span className="text-destructive ml-0.5" aria-hidden="true">
                  *
                </span>
              </label>
              <input
                id="page-path"
                type="text"
                className={cn(
                  'px-4 py-2 text-sm text-foreground bg-background border border-border rounded-md transition-[border-color,box-shadow] focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/20 placeholder:text-muted-foreground',
                  touched.path && errors.path && 'border-destructive focus:ring-destructive/20'
                )}
                value={formData.path}
                onChange={(e) => handleChange('path', e.target.value)}
                onBlur={() => handleBlur('path')}
                placeholder={t('builder.pages.pathPlaceholder')}
                aria-required="true"
                aria-invalid={touched.path && !!errors.path}
                aria-describedby={errors.path ? 'page-path-error' : 'page-path-hint'}
                disabled={isSubmitting}
                data-testid="page-path-input"
              />
              <span id="page-path-hint" className="text-xs text-muted-foreground mt-1">
                {t('builder.pages.pathHint')}
              </span>
              {touched.path && errors.path && (
                <span id="page-path-error" className="text-xs text-destructive mt-1" role="alert">
                  {errors.path}
                </span>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="page-title" className="text-sm font-medium text-foreground">
                {t('builder.pages.pageTitle')}
                <span className="text-destructive ml-0.5" aria-hidden="true">
                  *
                </span>
              </label>
              <input
                id="page-title"
                type="text"
                className={cn(
                  'px-4 py-2 text-sm text-foreground bg-background border border-border rounded-md transition-[border-color,box-shadow] focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/20 placeholder:text-muted-foreground',
                  touched.title && errors.title && 'border-destructive focus:ring-destructive/20'
                )}
                value={formData.title}
                onChange={(e) => handleChange('title', e.target.value)}
                onBlur={() => handleBlur('title')}
                placeholder={t('builder.pages.titlePlaceholder')}
                aria-required="true"
                aria-invalid={touched.title && !!errors.title}
                aria-describedby={errors.title ? 'page-title-error' : undefined}
                disabled={isSubmitting}
                data-testid="page-title-input"
              />
              {touched.title && errors.title && (
                <span id="page-title-error" className="text-xs text-destructive mt-1" role="alert">
                  {errors.title}
                </span>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="page-layout" className="text-sm font-medium text-foreground">
                {t('builder.pages.layout')}
              </label>
              <select
                id="page-layout"
                className="px-4 py-2 text-sm text-foreground bg-background border border-border rounded-md transition-[border-color,box-shadow] focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/20"
                value={formData.layoutType}
                onChange={(e) => handleChange('layoutType', e.target.value)}
                disabled={isSubmitting}
                data-testid="page-layout-select"
              >
                <option value="single">{t('builder.pages.layoutSingle')}</option>
                <option value="sidebar">{t('builder.pages.layoutSidebar')}</option>
                <option value="grid">{t('builder.pages.layoutGrid')}</option>
              </select>
            </div>

            <div className="flex justify-end gap-2 mt-4 pt-4 border-t border-border max-md:flex-col">
              <button
                type="button"
                className="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-md:w-full"
                onClick={onCancel}
                disabled={isSubmitting}
                data-testid="page-form-cancel"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                className="px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed max-md:w-full"
                disabled={isSubmitting}
                data-testid="page-form-submit"
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
 * PageBuilderPage Component
 *
 * Main page for building and editing UI pages in the Kelta Admin UI.
 * Provides a visual editor with component palette, canvas, and property panel.
 *
 * Requirement 12.5: Integrates with plugin system to use custom page components
 */
export function PageBuilderPage({
  pageId,
  testId = 'page-builder-page',
}: PageBuilderPageProps): React.ReactElement {
  const queryClient = useQueryClient()
  const { t, formatDate } = useI18n()
  const { apiClient, keltaClient } = useApi()

  // The canvas resolves plugin page components itself (via the widget registry / componentRegistry).
  // The end-user route binds the tenant slug; in the admin builder it is read from TenantContext.
  const tenantSlug = getTenantSlug()

  // View mode: 'list' shows all pages, 'editor' shows the page editor
  const [viewMode, setViewMode] = useState<'list' | 'editor'>(pageId ? 'editor' : 'list')
  const [editingPageId, setEditingPageId] = useState<string | null>(pageId ?? null)

  // Form modal state
  const [isFormOpen, setIsFormOpen] = useState(false)
  const [editingPage, setEditingPage] = useState<UIPage | undefined>(undefined)

  // Delete confirmation dialog state
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [pageToDelete, setPageToDelete] = useState<UIPage | null>(null)

  // Editor state
  const [components, setComponents] = useState<PageComponent[]>([])
  const [selectedComponentId, setSelectedComponentId] = useState<string | null>(null)
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false)

  // Page-level config (slice 2d): variables + on-load data sources, authored in the page-settings drawer.
  const [pageVariables, setPageVariables] = useState<PageVariable[]>([])
  const [pageDataSources, setPageDataSources] = useState<PageDataSource[]>([])
  // Slice 1h: optional per-page access restriction (a system-permission name). `undefined` ⇒ untouched
  // (no key written); `{}` ⇒ explicitly cleared (no restriction).
  const [pageAccess, setPageAccess] = useState<{ requiredPermission?: string } | undefined>(
    undefined
  )
  // Whether this page overrides the app's default landing page (`config.isHomePage`).
  const [pageIsHomePage, setPageIsHomePage] = useState<boolean>(false)
  const [pageSettingsOpen, setPageSettingsOpen] = useState(false)

  // Preview mode state (Requirement 7.7)
  const [isPreviewMode, setIsPreviewMode] = useState(false)

  // Undo/redo history over the authored artifact (app-platform slice 4). Selection,
  // preview, and drawer state are deliberately excluded; access/isHomePage too (rare,
  // drawer-authored). Recording is effect-based below; `applyingHistoryRef` keeps an
  // undo/redo application from re-recording; the saved-snapshot refs keep
  // `hasUnsavedChanges` truthful when undoing back to the last-saved state.
  const history = useEditorHistory<{
    components: PageComponent[]
    variables: PageVariable[]
    dataSources: PageDataSource[]
  }>()
  const applyingHistoryRef = useRef(false)
  const currentSnapshotJsonRef = useRef('')
  const savedSnapshotJsonRef = useRef('')

  // Fetch all pages query
  const {
    data: pages = [],
    isLoading: isLoadingPages,
    error: pagesError,
    refetch: refetchPages,
  } = useQuery({
    queryKey: ['ui-pages'],
    queryFn: () => keltaClient.admin.ui.listPages() as unknown as Promise<UIPage[]>,
    enabled: viewMode === 'list',
  })

  // Fetch single page query for editing
  const { data: currentPage } = useQuery({
    queryKey: ['ui-page', editingPageId],
    queryFn: () => apiClient.getOne<UIPage>(`/api/ui-pages/${editingPageId}`),
    enabled: viewMode === 'editor' && !!editingPageId,
  })

  // Update components when page data loads
  const [loadedPageId, setLoadedPageId] = useState<string | null>(null)
  const currentPageId = currentPage?.id ?? null
  if (currentPageId && loadedPageId !== currentPageId) {
    setLoadedPageId(currentPageId)
    setComponents(seedComponents(currentPage))
    const cfg = readConfig(currentPage)
    setPageVariables(cfg.variables ?? [])
    setPageDataSources(cfg.dataSources ?? [])
    setPageAccess(cfg.access)
    setPageIsHomePage(cfg.isHomePage === true)
    setHasUnsavedChanges(false)
    // The undo baseline for the freshly-seeded page is established by the history
    // effect below (it detects the loadedPageId change) — no ref access in render.
  }

  // Create mutation
  const createMutation = useMutation({
    mutationFn: (data: Partial<UIPage>) =>
      keltaClient.admin.ui.createPage(
        data as unknown as import('@kelta/sdk').UIPage
      ) as unknown as Promise<UIPage>,
    onSuccess: (newPage) => {
      queryClient.invalidateQueries({ queryKey: ['ui-pages'] })
      toast.success(t('success.created', { item: t('builder.pages.page') }))
      handleCloseForm()
      // Open the editor for the new page
      setEditingPageId(newPage.id)
      setComponents(seedComponents(newPage))
      const cfg = readConfig(newPage)
      setPageVariables(cfg.variables ?? [])
      setPageDataSources(cfg.dataSources ?? [])
      setPageAccess(cfg.access)
      setPageIsHomePage(cfg.isHomePage === true)
      setViewMode('editor')
    },
    onError: (error: Error) => {
      toast.error(error.message || t('errors.generic'))
    },
  })

  // Update mutation
  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<UIPage> }) =>
      keltaClient.admin.ui.updatePage(id, data as unknown as import('@kelta/sdk').UIPage),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ui-pages'] })
      queryClient.invalidateQueries({ queryKey: ['ui-page', editingPageId] })
      toast.success(t('success.updated', { item: t('builder.pages.page') }))
      setHasUnsavedChanges(false)
      // Undoing back past this point should re-light the unsaved flag (slice 4).
      savedSnapshotJsonRef.current = currentSnapshotJsonRef.current
      handleCloseForm()
    },
    onError: (error: Error) => {
      toast.error(error.message || t('errors.generic'))
    },
  })

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: (id: string) => apiClient.deleteResource(`/api/ui-pages/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ui-pages'] })
      toast.success(t('success.deleted', { item: t('builder.pages.page') }))
      setDeleteDialogOpen(false)
      setPageToDelete(null)
      if (editingPageId === pageToDelete?.id) {
        setViewMode('list')
        setEditingPageId(null)
      }
    },
    onError: (error: Error) => {
      toast.error(error.message || t('errors.generic'))
    },
  })

  // Publish mutation (Requirement 7.9)
  const publishMutation = useMutation({
    mutationFn: (id: string) => apiClient.postResource<UIPage>(`/api/ui-pages/${id}/publish`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ui-pages'] })
      queryClient.invalidateQueries({ queryKey: ['ui-page', editingPageId] })
      toast.success(t('builder.pages.publishSuccess') || 'Page published successfully')
    },
    onError: (error: Error) => {
      toast.error(error.message || t('errors.generic'))
    },
  })

  // Unpublish mutation
  const unpublishMutation = useMutation({
    mutationFn: (id: string) => apiClient.postResource<UIPage>(`/api/ui-pages/${id}/unpublish`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ui-pages'] })
      queryClient.invalidateQueries({ queryKey: ['ui-page', editingPageId] })
      toast.success(t('builder.pages.unpublishSuccess') || 'Page unpublished successfully')
    },
    onError: (error: Error) => {
      toast.error(error.message || t('errors.generic'))
    },
  })

  // Duplicate mutation (Requirement 7.10)
  const duplicateMutation = useMutation({
    mutationFn: (id: string) => apiClient.postResource<UIPage>(`/api/ui-pages/${id}/duplicate`, {}),
    onSuccess: (newPage) => {
      queryClient.invalidateQueries({ queryKey: ['ui-pages'] })
      toast.success(t('builder.pages.duplicateSuccess') || 'Page duplicated successfully')
      // Open the editor for the duplicated page
      setEditingPageId(newPage.id)
      setComponents(seedComponents(newPage))
      setViewMode('editor')
    },
    onError: (error: Error) => {
      toast.error(error.message || t('errors.generic'))
    },
  })

  // Handle create action
  const handleCreate = useCallback(() => {
    setEditingPage(undefined)
    setIsFormOpen(true)
  }, [])

  // Handle edit page config
  const handleEditConfig = useCallback((page: UIPage) => {
    setEditingPage(page)
    setIsFormOpen(true)
  }, [])

  // Handle close form
  const handleCloseForm = useCallback(() => {
    setIsFormOpen(false)
    setEditingPage(undefined)
  }, [])

  // Handle form submit
  const handleFormSubmit = useCallback(
    (data: PageFormData) => {
      const base = { name: data.name, path: data.path, title: data.title }
      const layout: PageLayout = { type: data.layoutType }

      if (editingPage) {
        // Preserve the existing component tree; only update the layout in config.
        updateMutation.mutate({
          id: editingPage.id,
          data: {
            ...base,
            config: mergeConfig(readConfig(editingPage), { layout }),
          } as unknown as Partial<UIPage>,
        })
      } else {
        createMutation.mutate({
          ...base,
          published: false,
          config: { layout, components: [] },
        } as unknown as Partial<UIPage>)
      }
    },
    [editingPage, createMutation, updateMutation]
  )

  // Handle delete action
  const handleDeleteClick = useCallback((page: UIPage) => {
    setPageToDelete(page)
    setDeleteDialogOpen(true)
  }, [])

  // Handle delete confirmation
  const handleDeleteConfirm = useCallback(() => {
    if (pageToDelete) {
      deleteMutation.mutate(pageToDelete.id)
    }
  }, [pageToDelete, deleteMutation])

  // Handle delete cancel
  const handleDeleteCancel = useCallback(() => {
    setDeleteDialogOpen(false)
    setPageToDelete(null)
  }, [])

  // Handle opening page editor
  const handleOpenEditor = useCallback((page: UIPage) => {
    setEditingPageId(page.id)
    setViewMode('editor')
  }, [])

  // Handle back to list — confirm before discarding unsaved in-builder changes (slice 2c). dnd-kit makes
  // more canvas state losable (reorders, span resizes, drop-into-container moves), so we guard the exit.
  const handleBackToList = useCallback(() => {
    if (hasUnsavedChanges && !window.confirm(t('builder.pages.unsavedConfirm'))) {
      return // stay in the builder; nothing is reset
    }
    setViewMode('list')
    setEditingPageId(null)
    setComponents([])
    setSelectedComponentId(null)
    setHasUnsavedChanges(false)
  }, [hasUnsavedChanges, t])

  // Native tab-close / refresh guard while editing with unsaved changes (slice 2c).
  useEffect(() => {
    if (!hasUnsavedChanges) return
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = '' // browsers show their own native prompt; the string is ignored
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [hasUnsavedChanges])

  // Tree-mutation callback — every drop/move/resize from the canvas routes its new tree through here.
  const handleTreeChange = useCallback((next: PageComponent[]) => {
    setComponents(next)
    setHasUnsavedChanges(true)
  }, [])

  // Handle adding a component via the palette CLICK path — append to root via `insertNode` so click-add
  // and drop-add agree on the descriptor defaults. No legacy `position` is written (slice 2c).
  const handleAddComponent = useCallback((componentType: string) => {
    const node: PageComponent = {
      id: generateId(),
      type: componentType,
      props: { ...widgetRegistry.get(componentType).defaultProps },
    }
    setComponents(
      (prev) =>
        insertNode(
          prev as ModelPageComponent[],
          node as ModelPageComponent,
          null
        ) as PageComponent[]
    )
    setSelectedComponentId(node.id)
    setHasUnsavedChanges(true)
  }, [])

  // Handle component selection
  const handleSelectComponent = useCallback((id: string | null) => {
    setSelectedComponentId(id)
  }, [])

  // Handle component property change from the inspector — patch the selected node anywhere in the tree.
  const handleComponentChange = useCallback(
    (updates: Partial<PageComponent>) => {
      if (!selectedComponentId) return
      const patch = (nodes: PageComponent[]): PageComponent[] =>
        nodes.map((comp) => {
          if (comp.id === selectedComponentId) return { ...comp, ...updates }
          return comp.children ? { ...comp, children: patch(comp.children) } : comp
        })
      setComponents((prev) => patch(prev))
      setHasUnsavedChanges(true)
    },
    [selectedComponentId]
  )

  // Handle component delete — remove the node anywhere in the tree.
  const handleDeleteComponent = useCallback(
    (id: string) => {
      const strip = (nodes: PageComponent[]): PageComponent[] =>
        nodes
          .filter((comp) => comp.id !== id)
          .map((comp) => (comp.children ? { ...comp, children: strip(comp.children) } : comp))
      setComponents((prev) => strip(prev))
      if (selectedComponentId === id) {
        setSelectedComponentId(null)
      }
      setHasUnsavedChanges(true)
    },
    [selectedComponentId]
  )

  // Handle save page — slice 2c OWNS the canonical save path. The tree persists inside the `config` JSON
  // column; `schemaVersion: 2` is stamped on EVERY save (so a migrated legacy page is persisted and never
  // re-migrates on reload). `variables`/`dataSources` round-trip their current value (authored in 2d) so
  // the call shape is final — `mergeConfig` only overlays keys it is passed, so they MUST be passed here.
  const handleSavePage = useCallback(() => {
    if (!editingPageId) return
    const existing = readConfig(currentPage)
    updateMutation.mutate({
      id: editingPageId,
      data: {
        config: mergeConfig(existing, {
          components: components as ModelPageComponent[],
          // Slice 2d: persist the drawer-authored page-level config. `mergeConfig` only overlays keys it
          // is passed, so these MUST be passed here or they are silently dropped on save.
          variables: pageVariables,
          dataSources: pageDataSources,
          // Slice 1h: persist the per-page access restriction. `undefined` is skipped by `mergeConfig`
          // (untouched page keeps no key); `{}` writes an explicit "no restriction".
          access: pageAccess,
          // Whether this page overrides the app's default landing page.
          isHomePage: pageIsHomePage,
          schemaVersion: 2,
        }),
      } as unknown as Partial<UIPage>,
    })
  }, [
    editingPageId,
    components,
    currentPage,
    pageVariables,
    pageDataSources,
    pageAccess,
    pageIsHomePage,
    updateMutation,
  ])

  // Page-settings drawer edits (mark unsaved so Save lights up).
  const handleVariablesChange = useCallback((next: PageVariable[]) => {
    setPageVariables(next)
    setHasUnsavedChanges(true)
  }, [])
  const handleDataSourcesChange = useCallback((next: PageDataSource[]) => {
    setPageDataSources(next)
    setHasUnsavedChanges(true)
  }, [])
  const handleRequiredPermissionChange = useCallback((permission: string | undefined) => {
    // A selected permission stores `{requiredPermission}`; "Anyone (published)" stores `{}` to
    // explicitly clear any previously-set restriction on save.
    setPageAccess(permission ? { requiredPermission: permission } : {})
    setHasUnsavedChanges(true)
  }, [])
  const handleIsHomePageChange = useCallback((next: boolean) => {
    setPageIsHomePage(next)
    setHasUnsavedChanges(true)
  }, [])

  // History bookkeeping (slice 4): one effect watches the authored artifact. When the
  // loaded page changes it establishes the fresh baseline (reset + saved-marker);
  // otherwise it records the change. Both are deferred by a 0ms timer (cleanup
  // cancels) so same-render bursts collapse and no setState runs synchronously inside
  // the effect; the hook itself coalesces rapid edits (400ms) and skips identical
  // snapshots. `applyingHistoryRef` keeps undo/redo applications from re-recording.
  const { record: recordHistory, reset: resetHistory } = history
  const historyPageRef = useRef<string | null>(null)
  useEffect(() => {
    if (viewMode !== 'editor' || !loadedPageId) return
    const snapshot = {
      components,
      variables: pageVariables,
      dataSources: pageDataSources,
    }
    currentSnapshotJsonRef.current = JSON.stringify(snapshot)
    if (historyPageRef.current !== loadedPageId) {
      historyPageRef.current = loadedPageId
      savedSnapshotJsonRef.current = currentSnapshotJsonRef.current
      const timer = setTimeout(() => resetHistory(snapshot), 0)
      return () => clearTimeout(timer)
    }
    if (applyingHistoryRef.current) {
      applyingHistoryRef.current = false
      return
    }
    const timer = setTimeout(() => recordHistory(snapshot), 0)
    return () => clearTimeout(timer)
  }, [
    components,
    pageVariables,
    pageDataSources,
    viewMode,
    loadedPageId,
    recordHistory,
    resetHistory,
  ])

  // Apply an undo/redo snapshot: swap the three states, drop the selection (the node
  // may be gone), and recompute the unsaved flag against the last-saved snapshot.
  const applyHistorySnapshot = useCallback(
    (
      snapshot: {
        components: PageComponent[]
        variables: PageVariable[]
        dataSources: PageDataSource[]
      } | null
    ) => {
      if (!snapshot) return
      applyingHistoryRef.current = true
      setComponents(snapshot.components)
      setPageVariables(snapshot.variables)
      setPageDataSources(snapshot.dataSources)
      setSelectedComponentId(null)
      const json = JSON.stringify(snapshot)
      currentSnapshotJsonRef.current = json
      setHasUnsavedChanges(json !== savedSnapshotJsonRef.current)
    },
    []
  )
  // Destructured for stable identities (the hook's callbacks are dependency-free).
  const { undo: undoHistory, redo: redoHistory } = history
  const handleUndo = useCallback(
    () => applyHistorySnapshot(undoHistory()),
    [applyHistorySnapshot, undoHistory]
  )
  const handleRedo = useCallback(
    () => applyHistorySnapshot(redoHistory()),
    [applyHistorySnapshot, redoHistory]
  )

  // Cmd/Ctrl+Z / Shift+Cmd/Ctrl+Z while editing — inert inside text inputs so the
  // native text-field undo wins.
  useEffect(() => {
    if (viewMode !== 'editor') return
    const onKeyDown = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'z') return
      const target = e.target as HTMLElement | null
      if (
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      ) {
        return
      }
      e.preventDefault()
      if (e.shiftKey) handleRedo()
      else handleUndo()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [viewMode, handleUndo, handleRedo])

  // Handle preview mode toggle (Requirement 7.7)
  const handleTogglePreview = useCallback(() => {
    setIsPreviewMode((prev) => !prev)
  }, [])

  // Handle close preview
  const handleClosePreview = useCallback(() => {
    setIsPreviewMode(false)
  }, [])

  // Handle publish page (Requirement 7.9)
  const handlePublishPage = useCallback(() => {
    if (!editingPageId) return
    publishMutation.mutate(editingPageId)
  }, [editingPageId, publishMutation])

  // Handle unpublish page
  const handleUnpublishPage = useCallback(() => {
    if (!editingPageId) return
    unpublishMutation.mutate(editingPageId)
  }, [editingPageId, unpublishMutation])

  // Handle duplicate page (Requirement 7.10)
  const handleDuplicatePage = useCallback(
    (pageId: string) => {
      duplicateMutation.mutate(pageId)
    },
    [duplicateMutation]
  )

  // Resolve the selected node anywhere in the tree (NOT just top-level) so the inspector shows
  // properties for nested children (e.g. a heading inside a card inside a grid), matching the
  // recursive patch/delete handlers above.
  const selectedComponent: PageComponent | null = selectedComponentId
    ? ((findNode(components as ModelPageComponent[], selectedComponentId)?.node as
        | PageComponent
        | undefined) ?? null)
    : null
  const isSubmitting = createMutation.isPending || updateMutation.isPending
  const isPublishing = publishMutation.isPending || unpublishMutation.isPending
  const isDuplicating = duplicateMutation.isPending

  // Render loading state
  if (viewMode === 'list' && isLoadingPages) {
    return (
      <div
        className="flex flex-col h-full min-h-0 p-6 w-full max-md:p-2 max-lg:p-4"
        data-testid={testId}
      >
        <div className="flex justify-center items-center min-h-[400px]">
          <LoadingSpinner size="large" label={t('common.loading')} />
        </div>
      </div>
    )
  }

  // Render error state
  if (viewMode === 'list' && pagesError) {
    return (
      <div
        className="flex flex-col h-full min-h-0 p-6 w-full max-md:p-2 max-lg:p-4"
        data-testid={testId}
      >
        <ErrorMessage
          error={pagesError instanceof Error ? pagesError : new Error(t('errors.generic'))}
          onRetry={() => refetchPages()}
        />
      </div>
    )
  }

  // Render editor view
  if (viewMode === 'editor') {
    return (
      <div
        className="flex flex-col h-full min-h-0 p-6 w-full max-md:p-2 max-lg:p-4"
        data-testid={testId}
      >
        <header className="flex justify-between items-center pb-4 border-b border-border mb-4 max-md:flex-col max-md:gap-2">
          <div className="flex items-center gap-4 max-md:w-full max-md:justify-between">
            <button
              type="button"
              className="px-2 py-1 text-sm text-muted-foreground bg-transparent border-none cursor-pointer transition-colors hover:text-foreground focus:outline-2 focus:outline-primary focus:outline-offset-2"
              onClick={handleBackToList}
              aria-label={t('common.back')}
              data-testid="back-to-list-button"
            >
              ← {t('common.back')}
            </button>
            <h1 className="m-0 text-xl font-semibold text-foreground">
              {currentPage?.title || t('builder.pages.newPage')}
              {hasUnsavedChanges && (
                <span className="text-yellow-800 dark:text-yellow-300 ml-1">*</span>
              )}
            </h1>
            {currentPage && <StatusBadge published={currentPage.published} />}
          </div>
          <div className="flex items-center gap-2 max-md:w-full max-md:justify-between">
            {/* Undo/redo (app-platform slice 4) */}
            <button
              type="button"
              className="px-3 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
              onClick={handleUndo}
              disabled={!history.canUndo}
              aria-label={t('builder.pages.undo')}
              title={t('builder.pages.undo')}
              data-testid="undo-button"
            >
              ↶
            </button>
            <button
              type="button"
              className="px-3 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
              onClick={handleRedo}
              disabled={!history.canRedo}
              aria-label={t('builder.pages.redo')}
              title={t('builder.pages.redo')}
              data-testid="redo-button"
            >
              ↷
            </button>
            {/* Preview button (Requirement 7.7) */}
            <button
              type="button"
              className="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2"
              onClick={handleTogglePreview}
              aria-label={t('builder.pages.preview')}
              data-testid="preview-page-button"
            >
              {t('builder.pages.preview')}
            </button>
            {/* Page settings drawer (slice 2d): page-level variables + on-load data sources. */}
            <button
              type="button"
              className="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2"
              onClick={() => setPageSettingsOpen(true)}
              aria-label={t('builder.pageSettings.open')}
              data-testid="page-settings-button"
            >
              {t('builder.pageSettings.open')}
            </button>
            {currentPage && (
              <>
                {/* Duplicate button (Requirement 7.10) */}
                <button
                  type="button"
                  className="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed"
                  onClick={() => handleDuplicatePage(currentPage.id)}
                  disabled={isDuplicating}
                  aria-label={t('builder.pages.duplicate')}
                  data-testid="duplicate-page-button"
                >
                  {isDuplicating ? t('common.loading') : t('builder.pages.duplicate')}
                </button>
                {/* Publish/Unpublish button (Requirement 7.9) */}
                {currentPage.published ? (
                  <button
                    type="button"
                    className="px-4 py-2 text-sm font-medium text-yellow-800 bg-yellow-100 border border-yellow-300 rounded-md cursor-pointer transition-colors hover:bg-yellow-200 focus:outline-2 focus:outline-yellow-500 focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed dark:text-yellow-300 dark:bg-yellow-900/30 dark:border-yellow-700"
                    onClick={handleUnpublishPage}
                    disabled={isPublishing || hasUnsavedChanges}
                    aria-label={t('builder.pages.unpublish') || 'Unpublish'}
                    data-testid="unpublish-page-button"
                  >
                    {isPublishing
                      ? t('common.loading')
                      : t('builder.pages.unpublish') || 'Unpublish'}
                  </button>
                ) : (
                  <button
                    type="button"
                    className="px-4 py-2 text-sm font-medium text-primary-foreground bg-green-600 border-none rounded-md cursor-pointer transition-colors hover:bg-green-700 focus:outline-2 focus:outline-green-600 focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed"
                    onClick={handlePublishPage}
                    disabled={isPublishing || hasUnsavedChanges}
                    aria-label={t('builder.pages.publish')}
                    data-testid="publish-page-button"
                  >
                    {isPublishing ? t('common.loading') : t('builder.pages.publish')}
                  </button>
                )}
                <button
                  type="button"
                  className="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-colors hover:bg-accent focus:outline-2 focus:outline-primary focus:outline-offset-2"
                  onClick={() => handleEditConfig(currentPage)}
                  data-testid="edit-config-button"
                >
                  {t('builder.pages.editConfig')}
                </button>
              </>
            )}
            {/* Save button (Requirement 7.8) */}
            <button
              type="button"
              className="px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed"
              onClick={handleSavePage}
              disabled={!hasUnsavedChanges || updateMutation.isPending}
              data-testid="save-page-button"
            >
              {updateMutation.isPending ? t('common.saving') : t('common.save')}
            </button>
          </div>
        </header>

        <div className="grid grid-cols-[200px_1fr_280px] max-lg:grid-cols-[160px_1fr_240px] max-md:grid-cols-1 max-md:grid-rows-[auto_1fr_auto] gap-4 flex-1 min-h-0 overflow-hidden">
          {/* Palette is rendered INSIDE the Canvas's DndContext (its tiles are draggable sources). The
              DndContext adds no DOM wrapper, so the palette + canvas stay as the first two grid columns. */}
          <Canvas
            components={components as ModelPageComponent[]}
            selectedId={selectedComponentId}
            onSelect={handleSelectComponent}
            onChange={handleTreeChange as (next: ModelPageComponent[]) => void}
            onDelete={handleDeleteComponent}
            tenantSlug={tenantSlug}
            palette={<Palette onAddComponent={handleAddComponent} />}
          />
          <Inspector
            node={selectedComponent as ModelPageComponent | null}
            onChange={handleComponentChange as (updates: Partial<ModelPageComponent>) => void}
          />
        </div>

        {/* Page-settings drawer (slice 2d): variables + on-load data sources. */}
        <PageSettingsDrawer
          open={pageSettingsOpen}
          onOpenChange={setPageSettingsOpen}
          variables={pageVariables}
          dataSources={pageDataSources}
          onVariablesChange={handleVariablesChange}
          onDataSourcesChange={handleDataSourcesChange}
          requiredPermission={pageAccess?.requiredPermission}
          onRequiredPermissionChange={handleRequiredPermissionChange}
          isHomePage={pageIsHomePage}
          onIsHomePageChange={handleIsHomePageChange}
        />

        {/* Preview mode overlay (Requirement 7.7, 12.5) */}
        {isPreviewMode && (
          <Preview
            page={currentPage || null}
            components={components}
            onClose={handleClosePreview}
          />
        )}

        {isFormOpen && (
          <PageForm
            page={editingPage}
            onSubmit={handleFormSubmit}
            onCancel={handleCloseForm}
            isSubmitting={isSubmitting}
          />
        )}
      </div>
    )
  }

  // Render list view
  return (
    <div
      className="flex flex-col h-full min-h-0 p-6 w-full max-md:p-2 max-lg:p-4"
      data-testid={testId}
    >
      <header className="flex justify-between items-center flex-wrap gap-4 mb-6 max-md:flex-col max-md:items-stretch">
        <h1 className="m-0 text-2xl max-md:text-xl font-semibold text-foreground">
          {t('builder.pages.title')}
        </h1>
        <button
          type="button"
          className="inline-flex items-center justify-center px-4 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors hover:bg-primary/90 focus:outline-2 focus:outline-primary focus:outline-offset-2 max-md:w-full"
          onClick={handleCreate}
          aria-label={t('builder.pages.createPage')}
          data-testid="create-page-button"
        >
          {t('builder.pages.createPage')}
        </button>
      </header>

      {pages.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center p-12 text-center text-muted-foreground bg-muted rounded-md"
          data-testid="empty-state"
        >
          <p>{t('common.noResults')}</p>
        </div>
      ) : (
        <div className="overflow-x-auto border border-border rounded-md bg-background">
          <table
            className="w-full border-collapse text-sm"
            role="grid"
            aria-label={t('builder.pages.title')}
            data-testid="pages-table"
          >
            <thead className="bg-muted">
              <tr role="row">
                <th
                  role="columnheader"
                  scope="col"
                  className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                >
                  {t('builder.pages.pageName')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                >
                  {t('builder.pages.pagePath')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                >
                  {t('builder.pages.pageTitle')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                >
                  {t('collections.status')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                >
                  {t('collections.updated')}
                </th>
                <th
                  role="columnheader"
                  scope="col"
                  className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap"
                >
                  {t('common.actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {pages.map((page, index) => (
                <tr
                  key={page.id}
                  role="row"
                  className="transition-colors hover:bg-accent/50"
                  data-testid={`page-row-${index}`}
                >
                  <td
                    role="gridcell"
                    className="p-4 text-foreground border-b border-border/50 font-medium"
                  >
                    <button
                      type="button"
                      className="bg-transparent border-none p-0 text-primary cursor-pointer font-inherit no-underline hover:underline focus:outline-2 focus:outline-primary focus:outline-offset-2"
                      onClick={() => handleOpenEditor(page)}
                      data-testid={`page-name-${index}`}
                    >
                      {page.name}
                    </button>
                  </td>
                  <td
                    role="gridcell"
                    className="p-4 text-foreground border-b border-border/50 max-w-[200px]"
                  >
                    <code className="font-mono text-xs px-2 py-1 bg-muted rounded text-foreground">
                      {page.path}
                    </code>
                  </td>
                  <td role="gridcell" className="p-4 text-foreground border-b border-border/50">
                    {page.title}
                  </td>
                  <td role="gridcell" className="p-4 text-foreground border-b border-border/50">
                    <StatusBadge published={page.published} />
                  </td>
                  <td
                    role="gridcell"
                    className="p-4 text-muted-foreground border-b border-border/50 whitespace-nowrap"
                  >
                    {formatDate(new Date(page.updatedAt), {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    })}
                  </td>
                  <td
                    role="gridcell"
                    className="p-4 text-foreground border-b border-border/50 w-[1%] whitespace-nowrap"
                  >
                    <div className="flex gap-2">
                      <button
                        type="button"
                        className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={() => handleOpenEditor(page)}
                        aria-label={`${t('common.edit')} ${page.name}`}
                        data-testid={`edit-button-${index}`}
                      >
                        {t('common.edit')}
                      </button>
                      {/* Duplicate button (Requirement 7.10) */}
                      <button
                        type="button"
                        className="px-2 py-1 text-xs font-medium text-foreground bg-background border border-border rounded cursor-pointer transition-colors hover:bg-accent hover:border-muted-foreground/40 focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={() => handleDuplicatePage(page.id)}
                        disabled={isDuplicating}
                        aria-label={`${t('builder.pages.duplicate')} ${page.name}`}
                        data-testid={`duplicate-button-${index}`}
                      >
                        {t('builder.pages.duplicate')}
                      </button>
                      <button
                        type="button"
                        className="px-2 py-1 text-xs font-medium text-destructive bg-background border border-destructive/30 rounded cursor-pointer transition-colors hover:bg-destructive/5 hover:border-destructive focus:outline-2 focus:outline-primary focus:outline-offset-2"
                        onClick={() => handleDeleteClick(page)}
                        aria-label={`${t('common.delete')} ${page.name}`}
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

      {isFormOpen && (
        <PageForm
          page={editingPage}
          onSubmit={handleFormSubmit}
          onCancel={handleCloseForm}
          isSubmitting={isSubmitting}
        />
      )}

      <ConfirmDialog
        open={deleteDialogOpen}
        title={t('builder.pages.deletePage')}
        message={t('builder.pages.confirmDelete')}
        confirmLabel={t('common.delete')}
        cancelLabel={t('common.cancel')}
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
        variant="danger"
      />
    </div>
  )
}

export default PageBuilderPage
