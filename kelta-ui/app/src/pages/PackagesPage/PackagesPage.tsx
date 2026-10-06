/**
 * PackagesPage Component
 *
 * Manages configuration package export and import.
 * Provides export wizard with item selection, import with file upload and preview,
 * dry-run validation, and package history.
 *
 * Requirements:
 * - 9.1: Display export and import options
 * - 9.10: Display package history showing previous exports and imports
 */

import React, { useState, useCallback, useRef, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Package, FolderOpen } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useI18n } from '../../context/I18nContext'
import { useApi } from '../../context/ApiContext'
import { useCollectionSummaries } from '../../hooks/useCollectionSummaries'
import { useToast, LoadingSpinner, ErrorMessage } from '../../components'
import type { ApiClient } from '../../services/apiClient'

/**
 * Package interface matching the API response
 */
export interface Package {
  id: string
  name: string
  version: string
  items: PackageItem[]
  createdAt: string
  type: 'export' | 'import'
  status: 'success' | 'failed' | 'pending'
}

/**
 * Package item interface
 */
export interface PackageItem {
  type: 'collection' | 'page' | 'menu' | 'flow' | 'layout' | 'validation-rule' | 'picklist'
  id: string
  name: string
  content?: unknown
}

/**
 * Export options interface
 */
export interface ExportOptions {
  name: string
  version: string
  description?: string
  collectionIds: string[]
  uiPageIds: string[]
  uiMenuIds: string[]
  flowIds: string[]
  pageLayoutIds: string[]
  validationRuleIds: string[]
  globalPicklistIds: string[]
}

/**
 * Import preview interface
 */
export interface ImportPreview {
  creates: PackageItem[]
  updates: PackageItem[]
  conflicts: ImportConflict[]
}

/**
 * Import conflict interface
 */
export interface ImportConflict {
  item: PackageItem
  existingItem: PackageItem
  resolution?: 'skip' | 'overwrite'
}

/**
 * Import result interface
 */
export interface ImportResult {
  success: boolean
  created: number
  updated: number
  skipped: number
  errors: ImportError[]
}

/**
 * Import error interface
 */
export interface ImportError {
  item: PackageItem
  message: string
}

/**
 * Selectable item for export
 */
interface SelectableItem {
  id: string
  name: string
  type: string
  /** Owning collection, for per-collection metadata (layouts, validation rules). */
  collectionId?: string
}

/**
 * Props for PackagesPage component
 */
export interface PackagesPageProps {
  /** Optional test ID for testing */
  testId?: string
}

// API functions using apiClient
async function fetchPackageHistory(apiClient: ApiClient): Promise<Package[]> {
  return apiClient.getList('/api/packages/history')
}

// Page size for the export pickers; the API clamps HTTP pages at 200.
const EXPORT_LIST_PAGE_SIZE = 200

interface NamedResource {
  id: string
  name: string
  collectionId?: string
}

/**
 * Every row of a JSON:API list, following `page[number]` until the last page. A single
 * request is capped at 200 rows, so a tenant with more than that would otherwise lose the
 * rest from the export picker without any sign of it.
 */
async function fetchAllPages(apiClient: ApiClient, path: string): Promise<NamedResource[]> {
  const all: NamedResource[] = []
  for (let page = 1; ; page++) {
    const result = await apiClient.getPage<NamedResource>(
      `${path}?page[size]=${EXPORT_LIST_PAGE_SIZE}&page[number]=${page}`
    )
    all.push(...result.content)
    if (page >= result.totalPages || result.content.length < EXPORT_LIST_PAGE_SIZE) {
      return all
    }
  }
}

async function fetchSelectable(
  apiClient: ApiClient,
  path: string,
  type: string
): Promise<SelectableItem[]> {
  const rows = await fetchAllPages(apiClient, path)
  return rows.map((r) => ({ id: r.id, name: r.name, type, collectionId: r.collectionId }))
}

/**
 * The export endpoint answers with the package document itself (a JSON attachment), which the
 * API client parses into an object. Wrap it back into a file for the download — handing the
 * parsed object to `URL.createObjectURL` throws, so no file was ever saved.
 */
async function exportPackage(apiClient: ApiClient, options: ExportOptions): Promise<Blob> {
  const pkg = await apiClient.postResource<unknown>('/api/packages/export', options)
  return new Blob([JSON.stringify(pkg, null, 2)], { type: 'application/json' })
}

async function previewImport(apiClient: ApiClient, file: File): Promise<ImportPreview> {
  const formData = new FormData()
  formData.append('file', file)
  return apiClient.postResource('/api/packages/import/preview', formData)
}

async function executeImport(
  apiClient: ApiClient,
  file: File,
  dryRun: boolean = false
): Promise<ImportResult> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('dryRun', String(dryRun))
  return apiClient.postResource('/api/packages/import', formData)
}

/**
 * Tab type for the packages page
 */
type TabType = 'export' | 'import' | 'history'

/**
 * Status Badge Component
 */
interface StatusBadgeProps {
  status: 'success' | 'failed' | 'pending'
}

function StatusBadge({ status }: StatusBadgeProps): React.ReactElement {
  const { t } = useI18n()
  const statusLabels: Record<string, string> = {
    success: t('packages.statusSuccess'),
    failed: t('packages.statusFailed'),
    pending: t('packages.statusPending'),
  }
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-1 text-xs font-medium rounded-full',
        status === 'success' && 'text-green-800 bg-green-50 dark:text-green-300 dark:bg-green-950',
        status === 'failed' && 'text-red-600 bg-red-50 dark:text-red-300 dark:bg-red-950',
        status === 'pending' &&
          'text-yellow-800 bg-yellow-50 dark:text-yellow-300 dark:bg-yellow-950'
      )}
      data-testid="status-badge"
    >
      {statusLabels[status] || status}
    </span>
  )
}

/**
 * Type Badge Component
 */
interface TypeBadgeProps {
  type: 'export' | 'import'
}

function TypeBadge({ type }: TypeBadgeProps): React.ReactElement {
  const { t } = useI18n()
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-1 text-xs font-medium rounded',
        type === 'export' && 'text-blue-800 bg-blue-50 dark:text-blue-300 dark:bg-blue-950',
        type === 'import' && 'text-purple-800 bg-purple-50 dark:text-purple-300 dark:bg-purple-950'
      )}
      data-testid="type-badge"
    >
      {type === 'export' ? t('packages.export') : t('packages.import')}
    </span>
  )
}

/**
 * Item Selection Component for Export
 */
interface ItemSelectionProps {
  /** Stable id for the section's test hooks; the title is translated. */
  sectionKey: string
  title: string
  items: SelectableItem[]
  selectedIds: string[]
  onSelectionChange: (ids: string[]) => void
  isLoading: boolean
}

function ItemSelection({
  sectionKey,
  title,
  items,
  selectedIds,
  onSelectionChange,
  isLoading,
}: ItemSelectionProps): React.ReactElement {
  const { t } = useI18n()

  const handleToggle = useCallback(
    (id: string) => {
      if (selectedIds.includes(id)) {
        onSelectionChange(selectedIds.filter((i) => i !== id))
      } else {
        onSelectionChange([...selectedIds, id])
      }
    },
    [selectedIds, onSelectionChange]
  )

  const handleSelectAll = useCallback(() => {
    if (selectedIds.length === items.length) {
      onSelectionChange([])
    } else {
      onSelectionChange(items.map((i) => i.id))
    }
  }, [items, selectedIds, onSelectionChange])

  if (isLoading) {
    return (
      <div className="rounded-md border border-border bg-muted p-4">
        <h4 className="m-0 text-sm font-semibold text-foreground">{title}</h4>
        <div className="flex justify-center p-4">
          <LoadingSpinner size="small" />
        </div>
      </div>
    )
  }

  return (
    <div
      className="rounded-md border border-border bg-muted p-4"
      data-testid={`item-section-${sectionKey}`}
    >
      <div className="flex items-center justify-between mb-2">
        <h4 className="m-0 text-sm font-semibold text-foreground">{title}</h4>
        {items.length > 0 && (
          <button
            type="button"
            className="px-2 py-1 text-xs text-primary bg-transparent border-none cursor-pointer transition-colors duration-150 hover:text-primary/80 hover:underline focus:outline-2 focus:outline-ring focus:outline-offset-2"
            onClick={handleSelectAll}
            data-testid={`select-all-${sectionKey}`}
          >
            {selectedIds.length === items.length ? t('common.deselectAll') : t('common.selectAll')}
          </button>
        )}
      </div>
      {items.length === 0 ? (
        <p className="m-0 p-2 text-sm text-muted-foreground text-center">
          {t('packages.noItemsAvailable')}
        </p>
      ) : (
        <div
          className="flex flex-col gap-1 max-h-[200px] overflow-y-auto"
          role="group"
          aria-label={title}
        >
          {items.map((item) => (
            <label
              key={item.id}
              className="flex items-center gap-2 px-2 py-1 rounded cursor-pointer transition-colors duration-150 hover:bg-accent"
              data-testid={`item-${item.id}`}
            >
              <input
                type="checkbox"
                className="shrink-0 w-4 h-4 cursor-pointer"
                checked={selectedIds.includes(item.id)}
                onChange={() => handleToggle(item.id)}
                data-testid={`checkbox-${item.id}`}
              />
              <span className="text-sm text-foreground whitespace-nowrap overflow-hidden text-ellipsis">
                {item.name}
              </span>
            </label>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * Export Panel Component
 * Requirement 9.1: Display export options
 */
interface ExportPanelProps {
  onExportComplete: () => void
}

function ExportPanel({ onExportComplete }: ExportPanelProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const { showToast } = useToast()
  const [packageName, setPackageName] = useState<string>('')
  const [packageVersion, setPackageVersion] = useState<string>('1.0.0')
  const [packageDescription, setPackageDescription] = useState<string>('')
  const [selectedCollections, setSelectedCollections] = useState<string[]>([])
  const [selectedPages, setSelectedPages] = useState<string[]>([])
  const [selectedMenus, setSelectedMenus] = useState<string[]>([])
  const [selectedFlows, setSelectedFlows] = useState<string[]>([])
  const [selectedLayouts, setSelectedLayouts] = useState<string[]>([])
  const [selectedValidationRules, setSelectedValidationRules] = useState<string[]>([])
  const [selectedPicklists, setSelectedPicklists] = useState<string[]>([])

  const { summaries, isLoading: collectionsLoading } = useCollectionSummaries()
  const collections: SelectableItem[] = useMemo(
    () => summaries.map((c) => ({ id: c.id, name: c.name, type: 'collection' })),
    [summaries]
  )

  const { data: pages = [], isLoading: pagesLoading } = useQuery({
    queryKey: ['export-pages'],
    queryFn: () => fetchSelectable(apiClient, '/api/ui-pages', 'page'),
  })

  const { data: menus = [], isLoading: menusLoading } = useQuery({
    queryKey: ['export-menus'],
    queryFn: () => fetchSelectable(apiClient, '/api/ui-menus', 'menu'),
  })

  const { data: flows = [], isLoading: flowsLoading } = useQuery({
    queryKey: ['export-flows'],
    queryFn: () => fetchSelectable(apiClient, '/api/flows', 'flow'),
  })

  const { data: rawLayouts = [], isLoading: layoutsLoading } = useQuery({
    queryKey: ['export-layouts'],
    queryFn: () => fetchSelectable(apiClient, '/api/page-layouts', 'layout'),
  })

  const { data: rawValidationRules = [], isLoading: validationRulesLoading } = useQuery({
    queryKey: ['export-validation-rules'],
    queryFn: () => fetchSelectable(apiClient, '/api/validation-rules', 'validation-rule'),
  })

  const { data: picklists = [], isLoading: picklistsLoading } = useQuery({
    queryKey: ['export-picklists'],
    queryFn: () => fetchSelectable(apiClient, '/api/global-picklists', 'picklist'),
  })

  // Layouts and validation rules belong to a collection, and their names repeat across
  // collections ("Default", "Required email"), so label them with their collection.
  const collectionNames = useMemo(() => new Map(summaries.map((c) => [c.id, c.name])), [summaries])
  const withCollection = useCallback(
    (items: SelectableItem[]): SelectableItem[] =>
      items.map((item) => {
        const collection = item.collectionId ? collectionNames.get(item.collectionId) : undefined
        return collection ? { ...item, name: `${collection} · ${item.name}` } : item
      }),
    [collectionNames]
  )
  const layouts = useMemo(() => withCollection(rawLayouts), [withCollection, rawLayouts])
  const validationRules = useMemo(
    () => withCollection(rawValidationRules),
    [withCollection, rawValidationRules]
  )

  const exportMutation = useMutation({
    mutationFn: (options: ExportOptions) => exportPackage(apiClient, options),
    onSuccess: (blob) => {
      // Download the file
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${packageName}-${packageVersion}.json`
      document.body.appendChild(a)
      a.click()
      window.URL.revokeObjectURL(url)
      document.body.removeChild(a)
      showToast(t('packages.exportSuccess'), 'success')
      onExportComplete()
    },
    onError: (error: Error) => {
      showToast(error.message, 'error')
    },
  })

  const hasSelection = [
    selectedCollections,
    selectedPages,
    selectedMenus,
    selectedFlows,
    selectedLayouts,
    selectedValidationRules,
    selectedPicklists,
  ].some((selected) => selected.length > 0)

  const canExport = packageName.trim() !== '' && packageVersion.trim() !== '' && hasSelection

  const handleExport = useCallback(() => {
    exportMutation.mutate({
      name: packageName,
      version: packageVersion,
      description: packageDescription || undefined,
      collectionIds: selectedCollections,
      uiPageIds: selectedPages,
      uiMenuIds: selectedMenus,
      flowIds: selectedFlows,
      pageLayoutIds: selectedLayouts,
      validationRuleIds: selectedValidationRules,
      globalPicklistIds: selectedPicklists,
    })
  }, [
    packageName,
    packageVersion,
    packageDescription,
    selectedCollections,
    selectedPages,
    selectedMenus,
    selectedFlows,
    selectedLayouts,
    selectedValidationRules,
    selectedPicklists,
    exportMutation,
  ])

  return (
    <div className="rounded-md border border-border bg-card p-6" data-testid="export-panel">
      <h3 className="m-0 mb-1 text-lg font-semibold text-foreground">
        {t('packages.packageDetails')}
      </h3>
      <div className="mb-4">
        <label htmlFor="package-name" className="block mb-1 text-sm font-medium text-foreground">
          {t('packages.packageName')} <span className="text-destructive">*</span>
        </label>
        <input
          id="package-name"
          type="text"
          className="w-full p-2 text-sm text-foreground bg-background border border-border rounded-md transition-colors duration-150 focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/10 placeholder:text-muted-foreground"
          value={packageName}
          onChange={(e) => setPackageName(e.target.value)}
          placeholder={t('packages.packageNamePlaceholder')}
          required
        />
      </div>
      <div className="mb-4">
        <label htmlFor="package-version" className="block mb-1 text-sm font-medium text-foreground">
          {t('packages.packageVersion')} <span className="text-destructive">*</span>
        </label>
        <input
          id="package-version"
          type="text"
          className="w-full p-2 text-sm text-foreground bg-background border border-border rounded-md transition-colors duration-150 focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/10 placeholder:text-muted-foreground"
          value={packageVersion}
          onChange={(e) => setPackageVersion(e.target.value)}
          placeholder="1.0.0"
          required
        />
      </div>
      <div className="mb-4">
        <label
          htmlFor="package-description"
          className="block mb-1 text-sm font-medium text-foreground"
        >
          {t('packages.packageDescription')}
        </label>
        <textarea
          id="package-description"
          className="w-full p-2 text-sm text-foreground bg-background border border-border rounded-md transition-colors duration-150 resize-y min-h-[80px] focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/10 placeholder:text-muted-foreground"
          value={packageDescription}
          onChange={(e) => setPackageDescription(e.target.value)}
          placeholder={t('packages.packageDescriptionPlaceholder')}
          rows={3}
        />
      </div>

      <h3 className="m-0 mb-1 text-lg font-semibold text-foreground">
        {t('packages.selectItems')}
      </h3>
      <p className="m-0 mb-6 text-sm text-muted-foreground">
        {t('packages.selectItemsDescription')}
      </p>

      <div className="grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-4 max-lg:grid-cols-2 max-md:grid-cols-1">
        <ItemSelection
          sectionKey="collections"
          title={t('navigation.collections')}
          items={collections}
          selectedIds={selectedCollections}
          onSelectionChange={setSelectedCollections}
          isLoading={collectionsLoading}
        />
        <ItemSelection
          sectionKey="pages"
          title={t('navigation.pages')}
          items={pages}
          selectedIds={selectedPages}
          onSelectionChange={setSelectedPages}
          isLoading={pagesLoading}
        />
        <ItemSelection
          sectionKey="menus"
          title={t('navigation.menus')}
          items={menus}
          selectedIds={selectedMenus}
          onSelectionChange={setSelectedMenus}
          isLoading={menusLoading}
        />
        <ItemSelection
          sectionKey="flows"
          title={t('packages.flows')}
          items={flows}
          selectedIds={selectedFlows}
          onSelectionChange={setSelectedFlows}
          isLoading={flowsLoading}
        />
        <ItemSelection
          sectionKey="layouts"
          title={t('packages.pageLayouts')}
          items={layouts}
          selectedIds={selectedLayouts}
          onSelectionChange={setSelectedLayouts}
          isLoading={layoutsLoading || collectionsLoading}
        />
        <ItemSelection
          sectionKey="validation-rules"
          title={t('packages.validationRules')}
          items={validationRules}
          selectedIds={selectedValidationRules}
          onSelectionChange={setSelectedValidationRules}
          isLoading={validationRulesLoading || collectionsLoading}
        />
        <ItemSelection
          sectionKey="picklists"
          title={t('navigation.picklists')}
          items={picklists}
          selectedIds={selectedPicklists}
          onSelectionChange={setSelectedPicklists}
          isLoading={picklistsLoading}
        />
      </div>

      <div className="flex justify-end gap-2 mt-6 pt-6 border-t border-border max-md:flex-col">
        <button
          type="button"
          className="px-6 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 hover:bg-primary/90 focus:outline-2 focus:outline-ring focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed max-md:w-full"
          onClick={handleExport}
          disabled={!canExport || exportMutation.isPending}
          data-testid="export-button"
        >
          {exportMutation.isPending ? t('common.loading') : t('packages.exportPackage')}
        </button>
      </div>
    </div>
  )
}

/**
 * Import Panel Component
 * Requirement 9.1: Display import options
 */
interface ImportPanelProps {
  onImportComplete: () => void
}

function ImportPanel({ onImportComplete }: ImportPanelProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const { showToast } = useToast()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [importResult, setImportResult] = useState<ImportResult | null>(null)

  const previewMutation = useMutation({
    mutationFn: (file: File) => previewImport(apiClient, file),
    onSuccess: (data) => {
      setPreview(data)
    },
    onError: (error: Error) => {
      showToast(error.message, 'error')
    },
  })

  const importMutation = useMutation({
    mutationFn: ({ file, dryRun }: { file: File; dryRun: boolean }) =>
      executeImport(apiClient, file, dryRun),
    onSuccess: (data, variables) => {
      setImportResult(data)
      if (!variables.dryRun && data.success) {
        showToast(t('packages.importSuccess'), 'success')
        onImportComplete()
      }
    },
    onError: (error: Error) => {
      showToast(error.message, 'error')
    },
  })

  const handleFileSelect = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0]
      if (file) {
        setSelectedFile(file)
        setPreview(null)
        setImportResult(null)
        previewMutation.mutate(file)
      }
    },
    [previewMutation]
  )

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault()
      const file = e.dataTransfer.files?.[0]
      if (file && file.name.endsWith('.json')) {
        setSelectedFile(file)
        setPreview(null)
        setImportResult(null)
        previewMutation.mutate(file)
      }
    },
    [previewMutation]
  )

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault()
  }, [])

  const handleDryRun = useCallback(() => {
    if (selectedFile) {
      importMutation.mutate({ file: selectedFile, dryRun: true })
    }
  }, [selectedFile, importMutation])

  const handleImport = useCallback(() => {
    if (selectedFile) {
      importMutation.mutate({ file: selectedFile, dryRun: false })
    }
  }, [selectedFile, importMutation])

  const handleReset = useCallback(() => {
    setSelectedFile(null)
    setPreview(null)
    setImportResult(null)
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }, [])

  return (
    <div className="rounded-md border border-border bg-card p-6" data-testid="import-panel">
      <h3 className="m-0 mb-1 text-lg font-semibold text-foreground">
        {t('packages.uploadPackage')}
      </h3>
      <p className="m-0 mb-6 text-sm text-muted-foreground">{t('packages.uploadDescription')}</p>

      <div
        className={cn(
          'flex flex-col items-center justify-center min-h-[150px] p-6 border-2 border-dashed border-border rounded-md bg-muted cursor-pointer transition-all duration-200',
          'hover:border-primary hover:bg-accent',
          'focus:outline-2 focus:outline-ring focus:outline-offset-2',
          selectedFile && 'border-green-500 bg-green-50 dark:bg-green-950'
        )}
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onClick={() => fileInputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => e.key === 'Enter' && fileInputRef.current?.click()}
        aria-label={t('packages.dropZoneLabel')}
        data-testid="drop-zone"
      >
        <input
          ref={fileInputRef}
          type="file"
          accept=".json"
          onChange={handleFileSelect}
          className="hidden"
          data-testid="file-input"
        />
        {selectedFile ? (
          <div className="flex items-center gap-2">
            <span className="text-2xl">
              <Package size={16} />
            </span>
            <span className="text-base font-medium text-foreground">{selectedFile.name}</span>
            <button
              type="button"
              className="flex items-center justify-center w-6 h-6 p-0 text-lg text-muted-foreground bg-transparent border-none rounded-full cursor-pointer transition-all duration-150 hover:bg-red-50 hover:text-destructive dark:hover:bg-red-950 focus:outline-2 focus:outline-ring focus:outline-offset-2"
              onClick={(e) => {
                e.stopPropagation()
                handleReset()
              }}
              aria-label={t('common.clear')}
              data-testid="clear-file-button"
            >
              &times;
            </button>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-2 text-center">
            <span className="text-[2rem]">
              <FolderOpen size={24} />
            </span>
            <span className="text-base font-medium text-foreground">
              {t('packages.dropZoneText')}
            </span>
            <span className="text-sm text-muted-foreground">{t('packages.dropZoneHint')}</span>
          </div>
        )}
      </div>

      {previewMutation.isPending && (
        <div className="flex justify-center p-6">
          <LoadingSpinner size="small" label={t('packages.analyzingPackage')} />
        </div>
      )}

      {preview && (
        <div
          className="mt-6 p-4 bg-muted border border-border rounded-md"
          data-testid="import-preview"
        >
          <h4 className="m-0 mb-4 text-base font-semibold text-foreground">
            {t('packages.preview')}
          </h4>
          <div className="flex gap-6 flex-wrap max-md:flex-col max-md:gap-2">
            <div className="flex flex-col gap-1">
              <span className="text-xs text-muted-foreground uppercase tracking-wide">
                {t('packages.toCreate')}
              </span>
              <span className="text-xl font-semibold text-foreground">
                {preview.creates.length}
              </span>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-xs text-muted-foreground uppercase tracking-wide">
                {t('packages.toUpdate')}
              </span>
              <span className="text-xl font-semibold text-foreground">
                {preview.updates.length}
              </span>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-xs text-muted-foreground uppercase tracking-wide">
                {t('packages.conflicts')}
              </span>
              <span className="text-xl font-semibold text-foreground">
                {preview.conflicts.length}
              </span>
            </div>
          </div>
        </div>
      )}

      {importResult && (
        <div
          className={cn(
            'mt-6 p-4 rounded-md',
            importResult.success
              ? 'bg-green-50 border border-green-500 dark:bg-green-950'
              : 'bg-red-50 border border-red-500 dark:bg-red-950'
          )}
          data-testid="import-result"
        >
          <h4
            className={cn(
              'm-0 mb-2 text-base font-semibold',
              importResult.success
                ? 'text-green-800 dark:text-green-300'
                : 'text-red-600 dark:text-red-300'
            )}
          >
            {importResult.success
              ? t('packages.importResultSuccess')
              : t('packages.importResultFailed')}
          </h4>
          <div className="flex gap-4 text-sm text-muted-foreground max-md:flex-col max-md:gap-1">
            <span>
              {t('packages.created')}: {importResult.created}
            </span>
            <span>
              {t('packages.updated')}: {importResult.updated}
            </span>
            <span>
              {t('packages.skipped')}: {importResult.skipped}
            </span>
          </div>
          {importResult.errors.length > 0 && (
            <div className="mt-4 pt-4 border-t border-red-200 dark:border-red-800">
              {importResult.errors.map((err, idx) => (
                <div key={idx} className="text-sm text-red-600 dark:text-red-300 mb-1">
                  {err.item.name}: {err.message}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <div className="flex justify-end gap-2 mt-6 pt-6 border-t border-border max-md:flex-col">
        <button
          type="button"
          className="px-6 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md cursor-pointer transition-all duration-150 hover:bg-muted hover:border-muted-foreground/50 focus:outline-2 focus:outline-ring focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed max-md:w-full"
          onClick={handleDryRun}
          disabled={!selectedFile || importMutation.isPending}
          data-testid="dry-run-button"
        >
          {t('packages.dryRun')}
        </button>
        <button
          type="button"
          className="px-6 py-2 text-sm font-medium text-primary-foreground bg-primary border-none rounded-md cursor-pointer transition-colors duration-200 hover:bg-primary/90 focus:outline-2 focus:outline-ring focus:outline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed max-md:w-full"
          onClick={handleImport}
          disabled={!selectedFile || importMutation.isPending}
          data-testid="import-button"
        >
          {importMutation.isPending ? t('common.loading') : t('packages.apply')}
        </button>
      </div>
    </div>
  )
}

/**
 * History Panel Component
 * Requirement 9.10: Display package history
 */
interface HistoryPanelProps {
  packages: Package[]
  isLoading: boolean
  error: Error | null
  onRetry: () => void
}

function HistoryPanel({
  packages,
  isLoading,
  error,
  onRetry,
}: HistoryPanelProps): React.ReactElement {
  const { t, formatDate } = useI18n()

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-[300px]">
        <LoadingSpinner label={t('common.loading')} />
      </div>
    )
  }

  if (error) {
    // Check if it's a 404 error (endpoint not implemented)
    const errorMessage = error.message || ''
    const isNotImplemented = errorMessage.includes('404') || errorMessage.includes('Not Found')

    if (isNotImplemented) {
      return (
        <div
          className="flex flex-col items-center justify-center p-12 text-center text-muted-foreground bg-muted rounded-md"
          data-testid="history-not-available"
        >
          <p className="m-0 text-base">{t('packages.historyNotAvailable')}</p>
          <p className="text-sm mt-2">{t('packages.historyNotAvailableHint')}</p>
        </div>
      )
    }

    return (
      <div className="flex items-center justify-center min-h-[200px]">
        <ErrorMessage error={error} onRetry={onRetry} />
      </div>
    )
  }

  if (packages.length === 0) {
    return (
      <div
        className="flex flex-col items-center justify-center p-12 text-center text-muted-foreground bg-muted rounded-md"
        data-testid="history-empty"
      >
        <p className="m-0 text-base">{t('packages.noHistory')}</p>
        <p className="text-sm mt-2">{t('packages.noHistoryHint')}</p>
      </div>
    )
  }

  return (
    <div
      className="overflow-x-auto border border-border rounded-md bg-card"
      data-testid="history-table"
    >
      <table className="w-full border-collapse text-sm">
        <thead className="bg-muted">
          <tr>
            <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
              {t('packages.packageName')}
            </th>
            <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
              {t('packages.type')}
            </th>
            <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
              {t('packages.status')}
            </th>
            <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
              {t('packages.items')}
            </th>
            <th className="p-4 text-left font-semibold text-foreground border-b-2 border-border whitespace-nowrap">
              {t('packages.date')}
            </th>
          </tr>
        </thead>
        <tbody>
          {packages.map((pkg) => (
            <tr
              key={pkg.id}
              className="transition-colors duration-150 hover:bg-accent"
              data-testid={`history-row-${pkg.id}`}
            >
              <td className="p-4 text-foreground border-b border-border/50 font-medium">
                {pkg.name}
              </td>
              <td className="p-4 text-foreground border-b border-border/50">
                <TypeBadge type={pkg.type} />
              </td>
              <td className="p-4 text-foreground border-b border-border/50">
                <StatusBadge status={pkg.status} />
              </td>
              <td className="p-4 text-muted-foreground border-b border-border/50">
                {pkg.items.length}
              </td>
              <td className="p-4 text-muted-foreground border-b border-border/50 whitespace-nowrap">
                {formatDate(new Date(pkg.createdAt), {
                  year: 'numeric',
                  month: 'short',
                  day: 'numeric',
                  hour: '2-digit',
                  minute: '2-digit',
                })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * PackagesPage Component
 *
 * Main component for package management with tabs for export, import, and history.
 *
 * Requirements:
 * - 9.1: Display export and import options
 * - 9.10: Display package history
 */
export function PackagesPage({ testId }: PackagesPageProps): React.ReactElement {
  const { t } = useI18n()
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  const [activeTab, setActiveTab] = useState<TabType>('export')

  const {
    data: packages = [],
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: ['package-history'],
    queryFn: () => fetchPackageHistory(apiClient),
    enabled: activeTab === 'history', // Only fetch when history tab is active
    retry: false, // Don't retry if endpoint doesn't exist
  })

  const handleExportComplete = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['package-history'] })
    setActiveTab('history')
  }, [queryClient])

  const handleImportComplete = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['package-history'] })
    setActiveTab('history')
  }, [queryClient])

  return (
    <div
      className="flex flex-col h-full min-h-0 p-6 w-full max-lg:p-4 max-md:p-2"
      data-testid={testId || 'packages-page'}
    >
      <header className="mb-6">
        <h1 className="m-0 text-2xl font-semibold text-foreground max-md:text-xl">
          {t('packages.title')}
        </h1>
      </header>

      <div
        className="flex gap-1 border-b-2 border-border mb-6 max-md:overflow-x-auto max-md:[&::-webkit-scrollbar]:hidden"
        role="tablist"
        aria-label={t('packages.title')}
      >
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'export'}
          aria-controls="export-panel"
          className={cn(
            'px-6 py-2 text-sm font-medium text-muted-foreground bg-transparent border-none border-b-2 border-transparent -mb-[2px] cursor-pointer transition-all duration-200 max-md:px-4 max-md:whitespace-nowrap',
            'hover:text-foreground',
            'focus:outline-2 focus:outline-ring focus:outline-offset-2',
            activeTab === 'export' && 'text-primary border-b-primary'
          )}
          onClick={() => setActiveTab('export')}
          data-testid="tab-export"
        >
          {t('packages.export')}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'import'}
          aria-controls="import-panel"
          className={cn(
            'px-6 py-2 text-sm font-medium text-muted-foreground bg-transparent border-none border-b-2 border-transparent -mb-[2px] cursor-pointer transition-all duration-200 max-md:px-4 max-md:whitespace-nowrap',
            'hover:text-foreground',
            'focus:outline-2 focus:outline-ring focus:outline-offset-2',
            activeTab === 'import' && 'text-primary border-b-primary'
          )}
          onClick={() => setActiveTab('import')}
          data-testid="tab-import"
        >
          {t('packages.import')}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'history'}
          aria-controls="history-panel"
          className={cn(
            'px-6 py-2 text-sm font-medium text-muted-foreground bg-transparent border-none border-b-2 border-transparent -mb-[2px] cursor-pointer transition-all duration-200 max-md:px-4 max-md:whitespace-nowrap',
            'hover:text-foreground',
            'focus:outline-2 focus:outline-ring focus:outline-offset-2',
            activeTab === 'history' && 'text-primary border-b-primary'
          )}
          onClick={() => setActiveTab('history')}
          data-testid="tab-history"
        >
          {t('packages.history')}
        </button>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto">
        {activeTab === 'export' && (
          <div id="export-panel" role="tabpanel" aria-labelledby="tab-export">
            <ExportPanel onExportComplete={handleExportComplete} />
          </div>
        )}
        {activeTab === 'import' && (
          <div id="import-panel" role="tabpanel" aria-labelledby="tab-import">
            <ImportPanel onImportComplete={handleImportComplete} />
          </div>
        )}
        {activeTab === 'history' && (
          <div id="history-panel" role="tabpanel" aria-labelledby="tab-history">
            <HistoryPanel
              packages={packages}
              isLoading={isLoading}
              error={error as Error | null}
              onRetry={() => refetch()}
            />
          </div>
        )}
      </div>
    </div>
  )
}

export default PackagesPage
