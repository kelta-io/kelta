/**
 * CollectionWizardPage Component
 *
 * A multi-step wizard for creating new collections.
 * Steps: Basics -> Fields -> Authorization -> Review & Create
 *
 * Replaces the simple CollectionFormPage for the /collections/new route,
 * providing a guided experience with template selection, field configuration,
 * and optional authorization setup.
 */

import React, { useState, useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  Check,
  ChevronRight,
  ChevronLeft,
  Plus,
  Trash2,
  Pencil,
  Users,
  Building2,
  CheckSquare,
  FileText,
  Wand2,
} from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { getTenantSlug } from '../../context/TenantContext'

import { useApi } from '../../context/ApiContext'
import { LoadingSpinner } from '../../components'
import { FieldEditor, type FieldDefinition } from '../../components/FieldEditor'
import { useCollectionSummaries } from '../../hooks/useCollectionSummaries'
import { cn } from '@/lib/utils'

/**
 * Props for CollectionWizardPage component
 */
export interface CollectionWizardPageProps {
  /** Optional test ID for testing */
  testId?: string
}

/**
 * Basics step data
 */
interface BasicsData {
  name: string
  displayName: string
  description: string
  active: boolean
}

/**
 * Profile permission data for a single profile
 */
interface ProfilePermission {
  profileId: string
  profileName: string
  canCreate: boolean
  canRead: boolean
  canEdit: boolean
  canDelete: boolean
}

/**
 * Step definitions
 */
const STEPS = [
  { number: 1, label: 'Basics' },
  { number: 2, label: 'Fields' },
  { number: 3, label: 'Authorization' },
  { number: 4, label: 'Review' },
] as const

/**
 * Template field type (subset of FieldDefinition for template defaults)
 */
type TemplateField = Pick<FieldDefinition, 'name' | 'displayName' | 'type' | 'required'>

/**
 * Template definitions
 */
const TEMPLATES: Record<
  string,
  { name: string; icon: 'contact' | 'account' | 'task' | 'custom'; fields: TemplateField[] }
> = {
  contact: {
    name: 'Contact',
    icon: 'contact',
    fields: [
      { name: 'name', displayName: 'Name', type: 'string', required: true },
      { name: 'email', displayName: 'Email', type: 'email', required: false },
      { name: 'phone', displayName: 'Phone', type: 'phone', required: false },
      { name: 'company', displayName: 'Company', type: 'string', required: false },
    ],
  },
  account: {
    name: 'Account',
    icon: 'account',
    fields: [
      { name: 'name', displayName: 'Name', type: 'string', required: true },
      { name: 'industry', displayName: 'Industry', type: 'string', required: false },
      { name: 'revenue', displayName: 'Revenue', type: 'currency', required: false },
      { name: 'website', displayName: 'Website', type: 'url', required: false },
    ],
  },
  task: {
    name: 'Task',
    icon: 'task',
    fields: [
      { name: 'title', displayName: 'Title', type: 'string', required: true },
      { name: 'status', displayName: 'Status', type: 'string', required: false },
      { name: 'priority', displayName: 'Priority', type: 'string', required: false },
      { name: 'due_date', displayName: 'Due Date', type: 'date', required: false },
    ],
  },
  custom: {
    name: 'Custom',
    icon: 'custom',
    fields: [],
  },
}

/**
 * Generate a unique field ID
 */
function generateFieldId(): string {
  return `field-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`
}

/**
 * Convert display name to collection/field name format
 * Lowercase, spaces to underscores, strip special chars
 */
function toFieldName(displayName: string): string {
  return displayName
    .toLowerCase()
    .replace(/\s+/g, '_')
    .replace(/[^a-z0-9_]/g, '')
    .replace(/^[^a-z]/, '')
    .replace(/_+/g, '_')
    .replace(/_$/, '')
}

/**
 * Build a full FieldDefinition from template defaults
 */
function templateFieldToDefinition(tf: TemplateField): FieldDefinition {
  return {
    id: generateFieldId(),
    name: tf.name,
    displayName: tf.displayName,
    type: tf.type,
    required: tf.required,
    unique: false,
    indexed: false,
    order: 0,
  }
}

/**
 * Get the template icon component
 */
function getTemplateIcon(icon: string): React.ReactNode {
  switch (icon) {
    case 'contact':
      return <Users size={20} />
    case 'account':
      return <Building2 size={20} />
    case 'task':
      return <CheckSquare size={20} />
    case 'custom':
      return <Wand2 size={20} />
    default:
      return <FileText size={20} />
  }
}

/**
 * Validation errors for basics step
 */
interface BasicsErrors {
  name?: string
  displayName?: string
}

/**
 * CollectionWizardPage Component
 *
 * A 4-step wizard for creating new collections:
 * 1. Basics - collection name, service, storage mode
 * 2. Fields - template selection and field configuration via FieldEditor dialog
 * 3. Authorization - profile object permissions
 * 4. Review - summary and create
 */
export function CollectionWizardPage({
  testId = 'collection-wizard-page',
}: CollectionWizardPageProps): React.ReactElement {
  const navigate = useNavigate()
  const { t } = useI18n()
  const { keltaClient } = useApi()

  // Wizard state
  const [currentStep, setCurrentStep] = useState(1)
  const [basics, setBasics] = useState<BasicsData>({
    name: '',
    displayName: '',
    description: '',
    active: true,
  })
  const [fields, setFields] = useState<FieldDefinition[]>([])
  const [selectedTemplate, setSelectedTemplate] = useState<string | null>(null)
  const [profilePermissions, setProfilePermissions] = useState<ProfilePermission[]>([])
  const [isCreating, setIsCreating] = useState(false)
  const [basicsErrors, setBasicsErrors] = useState<BasicsErrors>({})

  // Field editor modal state
  const [fieldEditorOpen, setFieldEditorOpen] = useState(false)
  const [editingField, setEditingField] = useState<FieldDefinition | undefined>(undefined)

  // Collection summaries for master_detail reference dropdown
  const { summaries: allCollectionSummaries } = useCollectionSummaries()

  // Global picklists for picklist field dropdown
  const {
    data: globalPicklists = [],
    isLoading: globalPicklistsLoading,
    isError: globalPicklistsError,
    refetch: refetchGlobalPicklists,
  } = useQuery({
    queryKey: ['picklists'],
    queryFn: () => keltaClient.admin.picklists.listGlobal(),
    enabled: fieldEditorOpen,
  })

  // Fetch profiles
  const { data: profilesData, isLoading: profilesLoading } = useQuery({
    queryKey: ['profiles'],
    queryFn: () => keltaClient.admin.profiles.list(),
  })

  const profiles = useMemo(
    () => (profilesData ?? []) as Array<{ id: string; name: string }>,
    [profilesData]
  )

  // Initialize profile permissions when profiles are loaded
  useMemo(() => {
    if (profiles.length > 0 && profilePermissions.length === 0) {
      setProfilePermissions(
        profiles.map((p: { id: string; name: string }) => ({
          profileId: p.id,
          profileName: p.name,
          canCreate: false,
          canRead: false,
          canEdit: false,
          canDelete: false,
        }))
      )
    }
  }, [profiles, profilePermissions.length])

  // Basics field handlers
  const handleDisplayNameChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const displayName = e.target.value
    const generatedName = toFieldName(displayName)
    setBasics((prev) => ({
      ...prev,
      displayName,
      name: generatedName,
    }))
    setBasicsErrors((prev) => ({ ...prev, displayName: undefined, name: undefined }))
  }, [])

  const handleNameChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setBasics((prev) => ({ ...prev, name: e.target.value }))
    setBasicsErrors((prev) => ({ ...prev, name: undefined }))
  }, [])

  const handleDescriptionChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setBasics((prev) => ({ ...prev, description: e.target.value }))
  }, [])

  const handleActiveChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setBasics((prev) => ({ ...prev, active: e.target.checked }))
  }, [])

  // Template selection handler
  const handleTemplateSelect = useCallback((templateKey: string) => {
    setSelectedTemplate(templateKey)
    const template = TEMPLATES[templateKey]
    if (template) {
      setFields(template.fields.map(templateFieldToDefinition))
    }
  }, [])

  // Field editor handlers
  const handleAddField = useCallback(() => {
    setEditingField(undefined)
    setFieldEditorOpen(true)
  }, [])

  const handleEditField = useCallback(
    (fieldId: string) => {
      const field = fields.find((f) => f.id === fieldId)
      if (field) {
        setEditingField(field)
        setFieldEditorOpen(true)
      }
    },
    [fields]
  )

  const handleRemoveField = useCallback((fieldId: string) => {
    setFields((prev) => prev.filter((f) => f.id !== fieldId))
  }, [])

  const handleFieldSave = useCallback(
    async (fieldData: FieldDefinition) => {
      if (editingField) {
        // Update existing field
        setFields((prev) => prev.map((f) => (f.id === editingField.id ? fieldData : f)))
      } else {
        // Add new field
        setFields((prev) => [...prev, fieldData])
      }
      setFieldEditorOpen(false)
      setEditingField(undefined)
    },
    [editingField]
  )

  const handleFieldCancel = useCallback(() => {
    setFieldEditorOpen(false)
    setEditingField(undefined)
  }, [])

  // Profile permission handlers
  const handlePermissionToggle = useCallback(
    (profileId: string, permission: keyof Omit<ProfilePermission, 'profileId' | 'profileName'>) => {
      setProfilePermissions((prev) =>
        prev.map((p) => (p.profileId === profileId ? { ...p, [permission]: !p[permission] } : p))
      )
    },
    []
  )

  const handleSkipAuth = useCallback(() => {
    setProfilePermissions((prev) =>
      prev.map((p) => ({
        ...p,
        canCreate: false,
        canRead: false,
        canEdit: false,
        canDelete: false,
      }))
    )
    setCurrentStep(4)
  }, [])

  // Validation
  const validateBasics = useCallback((): boolean => {
    const errors: BasicsErrors = {}
    if (!basics.name) {
      errors.name = t('collectionForm.validation.nameRequired')
    } else if (!/^[a-z][a-z0-9_]*$/.test(basics.name)) {
      errors.name = t('collectionForm.validation.nameFormat')
    } else if (basics.name.length > 50) {
      errors.name = t('collectionForm.validation.nameTooLong')
    }
    if (!basics.displayName) {
      errors.displayName = t('collectionForm.validation.displayNameRequired')
    } else if (basics.displayName.length > 100) {
      errors.displayName = t('collectionForm.validation.displayNameTooLong')
    }
    setBasicsErrors(errors)
    return Object.keys(errors).length === 0
  }, [basics, t])

  // Step navigation
  const handleNext = useCallback(() => {
    if (currentStep === 1) {
      if (!validateBasics()) return
    }
    setCurrentStep((prev) => Math.min(prev + 1, 4))
  }, [currentStep, validateBasics])

  const handleBack = useCallback(() => {
    setCurrentStep((prev) => Math.max(prev - 1, 1))
  }, [])

  const handleCancel = useCallback(() => {
    navigate(`/${getTenantSlug()}/collections`)
  }, [navigate])

  // Create collection
  const handleCreate = useCallback(async () => {
    setIsCreating(true)
    try {
      // Step 1: Create the collection
      const created = await keltaClient.admin.collections.create({
        name: basics.name,
        displayName: basics.displayName,
        description: basics.description || '',
        active: basics.active,
      } as unknown as import('@kelta/sdk').CollectionDefinition)

      const collectionId = created.id!

      // Step 2: Create fields
      for (const field of fields) {
        if (field.name) {
          await keltaClient.admin.fields.add(collectionId, {
            name: field.name,
            displayName: field.displayName,
            type: field.type,
            required: field.required,
            unique: field.unique,
            indexed: field.indexed,
            defaultValue: field.defaultValue,
            description: field.description,
            referenceTarget: field.referenceTarget,
            fieldTypeConfig: field.fieldTypeConfig,
            constraints: field.constraints,
            trackHistory: field.trackHistory,
          } as unknown as import('@kelta/sdk').FieldDefinition)
        }
      }

      // Step 3: Create profile object permissions for profiles with any permission set
      const configuredPermissions = profilePermissions.filter(
        (p) => p.canCreate || p.canRead || p.canEdit || p.canDelete
      )

      for (const perm of configuredPermissions) {
        await keltaClient.admin.profiles.setObjectPermissions(perm.profileId, collectionId, {
          canCreate: perm.canCreate,
          canRead: perm.canRead,
          canEdit: perm.canEdit,
          canDelete: perm.canDelete,
        })
      }

      toast.success(t('success.created', { item: 'Collection' }))
      navigate(`/${getTenantSlug()}/collections/${collectionId}`)
    } catch (error: unknown) {
      console.error('Failed to create collection:', error)
      // Extract JSON:API error details from the response when available
      let message = t('errors.generic')
      if (error && typeof error === 'object' && 'response' in error) {
        const axiosError = error as {
          response?: { data?: { errors?: Array<{ detail?: string }> } }
        }
        const apiErrors = axiosError.response?.data?.errors
        if (apiErrors && apiErrors.length > 0) {
          message =
            apiErrors
              .map((e) => e.detail)
              .filter(Boolean)
              .join('; ') || message
        }
      } else if (error instanceof Error) {
        message = error.message
      }
      toast.error(message)
    } finally {
      setIsCreating(false)
    }
  }, [keltaClient, basics, fields, profilePermissions, navigate, t])

  // Check if any profile permissions are configured
  const hasAuthConfigured = useMemo(
    () => profilePermissions.some((p) => p.canCreate || p.canRead || p.canEdit || p.canDelete),
    [profilePermissions]
  )

  // Render step indicator
  const renderStepIndicator = useCallback(() => {
    return (
      <div
        className="flex items-center justify-center mb-8 px-4 max-sm:flex-wrap max-sm:gap-2"
        data-testid="wizard-step-indicator"
      >
        {STEPS.map((step, index) => {
          const isCompleted = step.number < currentStep
          const isCurrent = step.number === currentStep
          const isFuture = step.number > currentStep

          return (
            <React.Fragment key={step.number}>
              <div
                className="flex items-center gap-2 cursor-default"
                data-testid={`wizard-step-${step.number}`}
              >
                <div
                  className={cn(
                    'flex items-center justify-center w-8 h-8 rounded-full text-sm font-semibold shrink-0 transition-all duration-200 motion-reduce:transition-none',
                    isCompleted && 'border-2 border-green-600 bg-green-600 text-white',
                    isCurrent && 'border-2 border-primary bg-primary text-white',
                    isFuture &&
                      'border-2 border-border text-muted-foreground bg-transparent dark:border-gray-600 dark:text-gray-400'
                  )}
                  data-testid={`wizard-step-circle-${step.number}`}
                >
                  {isCompleted ? <Check size={14} /> : step.number}
                </div>
                <span
                  className={cn(
                    'text-sm font-medium text-muted-foreground whitespace-nowrap dark:text-gray-400 max-sm:hidden',
                    isCompleted && 'text-green-600',
                    isCurrent && 'text-primary font-semibold'
                  )}
                >
                  {step.label}
                </span>
              </div>
              {index < STEPS.length - 1 && (
                <div
                  className={cn(
                    'flex-[0_0_3rem] h-0.5 mx-2 bg-border dark:bg-gray-600 max-sm:hidden',
                    !isFuture && 'bg-green-600'
                  )}
                />
              )}
            </React.Fragment>
          )
        })}
      </div>
    )
  }, [currentStep])

  // Render Step 1: Basics
  const renderBasicsStep = useCallback(() => {
    return (
      <div data-testid="wizard-step-basics">
        <h2 className="text-xl font-semibold text-foreground m-0 mb-6 dark:text-gray-100">
          {t('collections.createCollection')}
        </h2>
        <div className="flex flex-col gap-5 max-w-[600px]">
          {/* Display Name */}
          <div className="flex flex-col gap-1">
            <label
              htmlFor="wizard-display-name"
              className="flex items-center gap-1 text-sm font-medium text-foreground dark:text-gray-100"
            >
              {t('collections.displayName')}
              <span className="text-destructive font-semibold" aria-hidden="true">
                *
              </span>
            </label>
            <input
              id="wizard-display-name"
              type="text"
              className={cn(
                'px-3 py-2 text-base leading-normal text-foreground bg-background border border-border rounded-md transition-all duration-150 motion-reduce:transition-none dark:text-gray-100 dark:bg-gray-700 dark:border-gray-600',
                'focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/25',
                'disabled:bg-muted disabled:text-muted-foreground disabled:cursor-not-allowed dark:disabled:bg-gray-800 dark:disabled:text-gray-500',
                'placeholder:text-muted-foreground dark:placeholder:text-gray-500',
                basicsErrors.displayName &&
                  'border-destructive focus:border-destructive focus:ring-destructive/25'
              )}
              value={basics.displayName}
              onChange={handleDisplayNameChange}
              placeholder={t('collectionForm.displayNamePlaceholder')}
              aria-required="true"
              aria-invalid={!!basicsErrors.displayName}
              data-testid="wizard-display-name-input"
            />
            {basicsErrors.displayName && (
              <span
                className="flex items-center gap-1 text-sm text-destructive mt-1"
                role="alert"
                data-testid="wizard-display-name-error"
              >
                {basicsErrors.displayName}
              </span>
            )}
          </div>

          {/* Collection Name (auto-generated) */}
          <div className="flex flex-col gap-1">
            <label
              htmlFor="wizard-name"
              className="flex items-center gap-1 text-sm font-medium text-foreground dark:text-gray-100"
            >
              {t('collections.collectionName')}
              <span className="text-destructive font-semibold" aria-hidden="true">
                *
              </span>
            </label>
            <input
              id="wizard-name"
              type="text"
              className={cn(
                'px-3 py-2 text-base leading-normal text-foreground bg-background border border-border rounded-md transition-all duration-150 motion-reduce:transition-none dark:text-gray-100 dark:bg-gray-700 dark:border-gray-600',
                'focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/25',
                'placeholder:text-muted-foreground dark:placeholder:text-gray-500',
                basicsErrors.name &&
                  'border-destructive focus:border-destructive focus:ring-destructive/25'
              )}
              value={basics.name}
              onChange={handleNameChange}
              placeholder={t('collectionForm.namePlaceholder')}
              aria-required="true"
              aria-invalid={!!basicsErrors.name}
              data-testid="wizard-name-input"
            />
            {basicsErrors.name && (
              <span
                className="flex items-center gap-1 text-sm text-destructive mt-1"
                role="alert"
                data-testid="wizard-name-error"
              >
                {basicsErrors.name}
              </span>
            )}
            <span className="text-xs text-muted-foreground mt-1 dark:text-gray-400">
              {t('collectionForm.nameHint')}
            </span>
          </div>

          {/* Description */}
          <div className="flex flex-col gap-1">
            <label
              htmlFor="wizard-description"
              className="flex items-center gap-1 text-sm font-medium text-foreground dark:text-gray-100"
            >
              {t('collections.description')}
              <span className="text-xs font-normal text-muted-foreground ml-1 dark:text-gray-400">
                ({t('common.optional')})
              </span>
            </label>
            <textarea
              id="wizard-description"
              className="px-3 py-2 text-base leading-normal text-foreground bg-background border border-border rounded-md resize-y min-h-[80px] transition-all duration-150 motion-reduce:transition-none focus:outline-none focus:border-primary focus:ring-[3px] focus:ring-primary/25 placeholder:text-muted-foreground dark:text-gray-100 dark:bg-gray-700 dark:border-gray-600 dark:placeholder:text-gray-500"
              value={basics.description}
              onChange={handleDescriptionChange}
              placeholder={t('collectionForm.descriptionPlaceholder')}
              rows={3}
              data-testid="wizard-description-input"
            />
          </div>

          {/* Active Toggle */}
          <div className="flex flex-col gap-1">
            <div className="flex items-center gap-2">
              <input
                id="wizard-active"
                type="checkbox"
                className="w-[1.125rem] h-[1.125rem] accent-primary cursor-pointer"
                checked={basics.active}
                onChange={handleActiveChange}
                data-testid="wizard-active-checkbox"
              />
              <label
                htmlFor="wizard-active"
                className="text-base text-foreground cursor-pointer dark:text-gray-100"
              >
                {t('collections.active')}
              </label>
            </div>
            <span className="text-xs text-muted-foreground mt-1 dark:text-gray-400">
              {t('collectionForm.activeHint')}
            </span>
          </div>
        </div>
      </div>
    )
  }, [
    basics,
    basicsErrors,
    t,
    handleDisplayNameChange,
    handleNameChange,
    handleDescriptionChange,
    handleActiveChange,
  ])

  // Render Step 2: Fields
  const renderFieldsStep = useCallback(() => {
    return (
      <div data-testid="wizard-step-fields">
        <h2 className="text-xl font-semibold text-foreground m-0 mb-6 dark:text-gray-100">
          {t('collections.fields')}
        </h2>

        {/* Template Selection */}
        <div className="mb-6">
          <p className="text-sm font-medium text-muted-foreground m-0 mb-3 uppercase tracking-wider dark:text-gray-400">
            Choose a template
          </p>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(180px,1fr))] gap-3 max-sm:grid-cols-2">
            {Object.entries(TEMPLATES).map(([key, template]) => (
              <button
                key={key}
                type="button"
                className={cn(
                  'flex flex-col items-center gap-2 p-4 border-2 border-border rounded-md bg-card cursor-pointer transition-all duration-150 motion-reduce:transition-none dark:border-gray-600 dark:bg-gray-800',
                  'hover:border-primary hover:bg-primary/5 dark:hover:border-primary dark:hover:bg-primary/10',
                  selectedTemplate === key &&
                    'border-primary bg-primary/[0.08] dark:border-primary dark:bg-primary/15'
                )}
                onClick={() => handleTemplateSelect(key)}
                data-testid={`wizard-template-${key}`}
              >
                <div
                  className={cn(
                    'flex items-center justify-center w-10 h-10 rounded-md bg-muted text-muted-foreground dark:bg-gray-700 dark:text-gray-400',
                    selectedTemplate === key && 'bg-primary text-white'
                  )}
                >
                  {getTemplateIcon(template.icon)}
                </div>
                <span className="text-sm font-medium text-foreground dark:text-gray-100">
                  {template.name}
                </span>
                <span className="text-xs text-muted-foreground dark:text-gray-400">
                  {template.fields.length === 0 ? 'No fields' : `${template.fields.length} fields`}
                </span>
              </button>
            ))}
          </div>
        </div>

        {/* Fields List */}
        <div className="mt-6">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-base font-semibold text-foreground m-0 dark:text-gray-100">
              {t('collections.fields')}
            </h3>
            <button
              type="button"
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-primary bg-transparent border border-dashed border-primary rounded-md cursor-pointer transition-colors duration-150 motion-reduce:transition-none hover:bg-primary/[0.08] dark:text-blue-400 dark:border-blue-400 dark:hover:bg-primary/15"
              onClick={handleAddField}
              data-testid="wizard-add-field-button"
            >
              <Plus size={14} />
              {t('collections.addField')}
            </button>
          </div>

          {fields.length === 0 ? (
            <div
              className="p-8 text-center text-muted-foreground text-sm dark:text-gray-400"
              data-testid="wizard-no-fields"
            >
              {t('fieldsPanel.noFields')}. {t('fieldsPanel.addFieldHint')}
            </div>
          ) : (
            <table
              className="w-full border-collapse border border-border rounded-md overflow-hidden dark:border-gray-700"
              data-testid="wizard-fields-table"
            >
              <thead>
                <tr>
                  <th className="px-3 py-2.5 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                    {t('collections.fieldName')}
                  </th>
                  <th className="px-3 py-2.5 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                    {t('collections.fieldType')}
                  </th>
                  <th className="px-3 py-2.5 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                    {t('fields.validation.required')}
                  </th>
                  <th className="px-3 py-2.5 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600"></th>
                </tr>
              </thead>
              <tbody>
                {fields.map((field) => (
                  <tr
                    key={field.id}
                    className="last:[&>td]:border-b-0"
                    data-testid={`wizard-field-row-${field.id}`}
                  >
                    <td className="px-3 py-2 text-sm text-foreground border-b border-border align-middle dark:text-gray-100 dark:border-gray-600">
                      {field.displayName || field.name}
                      {field.displayName && (
                        <>
                          <br />
                          <small className="text-muted-foreground dark:text-gray-400">
                            {field.name}
                          </small>
                        </>
                      )}
                    </td>
                    <td className="px-3 py-2 text-sm text-foreground border-b border-border align-middle dark:text-gray-100 dark:border-gray-600">
                      {t(`fields.types.${field.type}`)}
                    </td>
                    <td className="px-3 py-2 text-sm text-foreground border-b border-border align-middle dark:text-gray-100 dark:border-gray-600">
                      {field.required ? (
                        <span className="inline-flex items-center px-1.5 py-0.5 text-[0.6875rem] font-medium rounded-full bg-primary/10 text-primary">
                          {t('fields.validation.required')}
                        </span>
                      ) : (
                        '\u2014'
                      )}
                    </td>
                    <td className="px-3 py-2 text-sm text-foreground border-b border-border align-middle dark:text-gray-100 dark:border-gray-600">
                      <div className="flex items-center gap-1">
                        <button
                          type="button"
                          className="flex items-center justify-center p-1 border-none bg-transparent text-muted-foreground cursor-pointer rounded transition-colors duration-150 motion-reduce:transition-none hover:text-primary hover:bg-primary/10 dark:text-gray-400"
                          onClick={() => handleEditField(field.id)}
                          aria-label={`Edit field ${field.displayName || field.name}`}
                          data-testid={`wizard-field-edit-${field.id}`}
                        >
                          <Pencil size={16} />
                        </button>
                        <button
                          type="button"
                          className="flex items-center justify-center p-1 border-none bg-transparent text-muted-foreground cursor-pointer rounded transition-colors duration-150 motion-reduce:transition-none hover:text-destructive hover:bg-destructive/10 dark:text-gray-400"
                          onClick={() => handleRemoveField(field.id)}
                          aria-label={`Remove field ${field.displayName || field.name}`}
                          data-testid={`wizard-field-remove-${field.id}`}
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    )
  }, [
    fields,
    selectedTemplate,
    t,
    handleTemplateSelect,
    handleAddField,
    handleEditField,
    handleRemoveField,
  ])

  // Permission columns for the grid
  const PERMISSION_COLUMNS = useMemo(
    () =>
      [
        { key: 'canCreate', label: 'Create' },
        { key: 'canRead', label: 'Read' },
        { key: 'canEdit', label: 'Edit' },
        { key: 'canDelete', label: 'Delete' },
      ] as const,
    []
  )

  // Render Step 3: Authorization
  const renderAuthorizationStep = useCallback(() => {
    return (
      <div data-testid="wizard-step-authorization">
        <h2 className="text-xl font-semibold text-foreground m-0 mb-6 dark:text-gray-100">
          {t('authorization.title')}
        </h2>
        <h3 className="text-base font-medium text-foreground m-0 mb-2 dark:text-gray-100">
          Who can access this collection?
        </h3>
        <p className="text-sm text-muted-foreground m-0 mb-6 dark:text-gray-400">
          Configure object permissions for each profile. This is optional and can be configured
          later.
        </p>

        <button
          type="button"
          className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-primary bg-primary/[0.08] border border-primary rounded-md cursor-pointer mb-6 transition-colors duration-150 motion-reduce:transition-none hover:bg-primary/15 dark:text-blue-400 dark:border-blue-400 dark:bg-primary/15 dark:hover:bg-primary/25"
          onClick={handleSkipAuth}
          data-testid="wizard-skip-auth-button"
        >
          <ChevronRight size={16} />
          Skip — Configure Later
        </button>

        {profilesLoading ? (
          <div className="flex items-center justify-center p-8">
            <LoadingSpinner size="medium" label="Loading profiles..." />
          </div>
        ) : profilePermissions.length === 0 ? (
          <div className="p-8 text-center text-muted-foreground text-sm dark:text-gray-400">
            No profiles found. Permissions can be configured after creation.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table
              className="w-full border-collapse border border-border rounded-md overflow-hidden dark:border-gray-700"
              data-testid="wizard-permissions-table"
            >
              <thead>
                <tr>
                  <th className="px-3 py-2.5 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                    Profile
                  </th>
                  {PERMISSION_COLUMNS.map((col) => (
                    <th
                      key={col.key}
                      className="px-3 py-2.5 text-xs font-semibold text-center uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600"
                    >
                      {col.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {profilePermissions.map((perm) => (
                  <tr
                    key={perm.profileId}
                    className="last:[&>td]:border-b-0"
                    data-testid={`wizard-perm-row-${perm.profileId}`}
                  >
                    <td className="px-3 py-2 text-sm font-medium text-foreground border-b border-border align-middle dark:text-gray-100 dark:border-gray-600">
                      {perm.profileName}
                    </td>
                    {PERMISSION_COLUMNS.map((col) => (
                      <td
                        key={col.key}
                        className="px-3 py-2 text-center border-b border-border align-middle dark:border-gray-600"
                      >
                        <input
                          type="checkbox"
                          className="w-4 h-4 accent-primary cursor-pointer"
                          checked={perm[col.key]}
                          onChange={() => handlePermissionToggle(perm.profileId, col.key)}
                          aria-label={`${perm.profileName} ${col.label}`}
                          data-testid={`wizard-perm-${perm.profileId}-${col.key}`}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    )
  }, [
    profilePermissions,
    profilesLoading,
    t,
    handleSkipAuth,
    handlePermissionToggle,
    PERMISSION_COLUMNS,
  ])

  // Render Step 4: Review
  const renderReviewStep = useCallback(() => {
    return (
      <div data-testid="wizard-step-review">
        <h2 className="text-xl font-semibold text-foreground m-0 mb-6 dark:text-gray-100">
          Review & Create
        </h2>

        {isCreating ? (
          <div
            className="flex flex-col items-center justify-center gap-4 p-12"
            data-testid="wizard-creating"
          >
            <LoadingSpinner size="large" label="Creating collection..." />
            <span className="text-base text-muted-foreground dark:text-gray-400">
              Creating collection...
            </span>
          </div>
        ) : (
          <>
            {/* Collection Section */}
            <div className="mb-6 pb-6 border-b border-border dark:border-gray-700 last:border-b-0 last:mb-0 last:pb-0">
              <h3 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground m-0 mb-4 dark:text-gray-400">
                Collection
              </h3>
              <div className="grid grid-cols-[140px_1fr] gap-x-4 gap-y-2 text-sm max-sm:grid-cols-1 max-sm:gap-y-1">
                <span className="font-medium text-muted-foreground dark:text-gray-400">
                  {t('collections.displayName')}
                </span>
                <span
                  className="text-foreground break-words dark:text-gray-100"
                  data-testid="wizard-review-display-name"
                >
                  {basics.displayName}
                </span>

                <span className="font-medium text-muted-foreground max-sm:font-semibold dark:text-gray-400">
                  {t('collections.collectionName')}
                </span>
                <span
                  className="text-foreground break-words dark:text-gray-100"
                  data-testid="wizard-review-name"
                >
                  {basics.name}
                </span>

                <span className="font-medium text-muted-foreground max-sm:font-semibold dark:text-gray-400">
                  {t('collections.description')}
                </span>
                <span
                  className="text-foreground break-words dark:text-gray-100"
                  data-testid="wizard-review-description"
                >
                  {basics.description || t('common.none')}
                </span>

                <span className="font-medium text-muted-foreground max-sm:font-semibold dark:text-gray-400">
                  {t('collections.status')}
                </span>
                <span
                  className="text-foreground break-words dark:text-gray-100"
                  data-testid="wizard-review-status"
                >
                  {basics.active ? t('collections.active') : t('collections.inactive')}
                </span>
              </div>
            </div>

            {/* Fields Section */}
            <div className="mb-6 pb-6 border-b border-border dark:border-gray-700 last:border-b-0 last:mb-0 last:pb-0">
              <h3 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground m-0 mb-4 dark:text-gray-400">
                {t('collections.fields')}
              </h3>
              {fields.length === 0 ? (
                <span
                  className="text-sm text-muted-foreground italic dark:text-gray-400"
                  data-testid="wizard-review-no-fields"
                >
                  No fields configured. Fields can be added after creation.
                </span>
              ) : (
                <table
                  className="w-full border-collapse border border-border rounded-md overflow-hidden mt-2 dark:border-gray-700"
                  data-testid="wizard-review-fields-table"
                >
                  <thead>
                    <tr>
                      <th className="px-3 py-2 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                        {t('collections.fieldName')}
                      </th>
                      <th className="px-3 py-2 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                        {t('collections.fieldType')}
                      </th>
                      <th className="px-3 py-2 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                        {t('fields.validation.required')}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {fields
                      .filter((f) => f.name)
                      .map((field) => (
                        <tr key={field.id} className="last:[&>td]:border-b-0">
                          <td
                            className="px-3 py-2 text-sm text-foreground border-b border-border dark:text-gray-100 dark:border-gray-600"
                            data-testid={`wizard-review-field-name-${field.id}`}
                          >
                            {field.displayName || field.name}
                            {field.displayName && (
                              <>
                                <br />
                                <small className="text-muted-foreground dark:text-gray-400">
                                  {field.name}
                                </small>
                              </>
                            )}
                          </td>
                          <td
                            className="px-3 py-2 text-sm text-foreground border-b border-border dark:text-gray-100 dark:border-gray-600"
                            data-testid={`wizard-review-field-type-${field.id}`}
                          >
                            {t(`fields.types.${field.type}`)}
                          </td>
                          <td
                            className="px-3 py-2 text-sm text-foreground border-b border-border dark:text-gray-100 dark:border-gray-600"
                            data-testid={`wizard-review-field-required-${field.id}`}
                          >
                            {field.required ? (
                              <span className="inline-flex items-center px-1.5 py-0.5 text-[0.6875rem] font-medium rounded-full bg-primary/10 text-primary">
                                {t('fields.validation.required')}
                              </span>
                            ) : (
                              '\u2014'
                            )}
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              )}
            </div>

            {/* Authorization Section */}
            <div className="mb-6 pb-6 border-b border-border dark:border-gray-700 last:border-b-0 last:mb-0 last:pb-0">
              <h3 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground m-0 mb-4 dark:text-gray-400">
                {t('authorization.title')}
              </h3>
              {!hasAuthConfigured ? (
                <span
                  className="text-sm text-muted-foreground italic dark:text-gray-400"
                  data-testid="wizard-review-no-auth"
                >
                  Not configured — will use defaults
                </span>
              ) : (
                <div className="overflow-x-auto" data-testid="wizard-review-auth">
                  <table className="w-full border-collapse border border-border rounded-md overflow-hidden dark:border-gray-700">
                    <thead>
                      <tr>
                        <th className="px-3 py-2 text-xs font-semibold text-left uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                          Profile
                        </th>
                        <th className="px-3 py-2 text-xs font-semibold text-center uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                          Create
                        </th>
                        <th className="px-3 py-2 text-xs font-semibold text-center uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                          Read
                        </th>
                        <th className="px-3 py-2 text-xs font-semibold text-center uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                          Edit
                        </th>
                        <th className="px-3 py-2 text-xs font-semibold text-center uppercase tracking-wider text-muted-foreground bg-muted border-b border-border dark:text-gray-400 dark:bg-gray-700 dark:border-gray-600">
                          Delete
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {profilePermissions
                        .filter((p) => p.canCreate || p.canRead || p.canEdit || p.canDelete)
                        .map((perm) => (
                          <tr key={perm.profileId} className="last:[&>td]:border-b-0">
                            <td className="px-3 py-2 text-sm font-medium text-foreground border-b border-border dark:text-gray-100 dark:border-gray-600">
                              {perm.profileName}
                            </td>
                            {(['canCreate', 'canRead', 'canEdit', 'canDelete'] as const).map(
                              (key) => (
                                <td
                                  key={key}
                                  className="px-3 py-2 text-sm text-center border-b border-border dark:border-gray-600"
                                >
                                  {perm[key] ? (
                                    <Check size={16} className="inline-block text-green-600" />
                                  ) : (
                                    <span className="text-muted-foreground">{'\u2014'}</span>
                                  )}
                                </td>
                              )
                            )}
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    )
  }, [basics, fields, profilePermissions, hasAuthConfigured, isCreating, t])

  // Render current step content
  const renderCurrentStep = useCallback(() => {
    switch (currentStep) {
      case 1:
        return renderBasicsStep()
      case 2:
        return renderFieldsStep()
      case 3:
        return renderAuthorizationStep()
      case 4:
        return renderReviewStep()
      default:
        return null
    }
  }, [currentStep, renderBasicsStep, renderFieldsStep, renderAuthorizationStep, renderReviewStep])

  return (
    <div className="flex flex-col h-full p-8 overflow-y-auto max-sm:p-4" data-testid={testId}>
      <header className="mb-6">
        <h1 className="text-[1.75rem] font-semibold text-foreground m-0 dark:text-gray-100">
          {t('collections.createCollection')}
        </h1>
      </header>

      {renderStepIndicator()}

      <div className="bg-card border border-border rounded-lg shadow-sm p-8 flex-1 min-h-0 overflow-y-auto dark:bg-gray-800 dark:border-gray-700 dark:shadow-md">
        {renderCurrentStep()}
      </div>

      {/* Navigation */}
      <div
        className="flex justify-between items-center mt-6 pt-6 border-t border-border dark:border-gray-700 max-sm:flex-col-reverse max-sm:gap-3"
        data-testid="wizard-navigation"
      >
        <div className="flex gap-3 max-sm:w-full">
          <button
            type="button"
            className="inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-normal text-foreground bg-muted border border-border rounded-md cursor-pointer transition-all duration-150 motion-reduce:transition-none hover:enabled:bg-accent focus:outline-none focus:ring-[3px] focus:ring-primary/25 disabled:opacity-50 disabled:cursor-not-allowed dark:text-gray-100 dark:bg-gray-700 dark:border-gray-600 dark:hover:enabled:bg-gray-600 max-sm:w-full"
            onClick={handleCancel}
            disabled={isCreating}
            data-testid="wizard-cancel-button"
          >
            {t('common.cancel')}
          </button>
        </div>
        <div className="flex gap-3 max-sm:w-full">
          {currentStep > 1 && (
            <button
              type="button"
              className="inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-normal text-foreground bg-muted border border-border rounded-md cursor-pointer transition-all duration-150 motion-reduce:transition-none hover:enabled:bg-accent focus:outline-none focus:ring-[3px] focus:ring-primary/25 disabled:opacity-50 disabled:cursor-not-allowed dark:text-gray-100 dark:bg-gray-700 dark:border-gray-600 dark:hover:enabled:bg-gray-600 max-sm:w-full"
              onClick={handleBack}
              disabled={isCreating}
              data-testid="wizard-back-button"
            >
              <ChevronLeft size={16} />
              {t('common.back')}
            </button>
          )}
          {currentStep < 4 && (
            <button
              type="button"
              className="inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-normal text-white bg-primary border border-transparent rounded-md cursor-pointer transition-all duration-150 motion-reduce:transition-none hover:enabled:bg-primary/90 focus:outline-none focus:ring-[3px] focus:ring-primary/25 disabled:opacity-50 disabled:cursor-not-allowed max-sm:w-full"
              onClick={handleNext}
              disabled={isCreating}
              data-testid="wizard-next-button"
            >
              {t('common.next')}
              <ChevronRight size={16} />
            </button>
          )}
          {currentStep === 4 && (
            <button
              type="button"
              className="inline-flex items-center justify-center gap-2 px-6 py-2 text-base font-medium leading-normal text-white bg-green-600 border border-transparent rounded-md cursor-pointer transition-all duration-150 motion-reduce:transition-none hover:enabled:bg-green-700 focus:outline-none focus:ring-[3px] focus:ring-primary/25 disabled:opacity-50 disabled:cursor-not-allowed max-sm:w-full"
              onClick={handleCreate}
              disabled={isCreating}
              data-testid="wizard-create-button"
            >
              {isCreating ? (
                <>
                  <LoadingSpinner size="small" />
                  Creating...
                </>
              ) : (
                <>
                  <Check size={16} />
                  Create Collection
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {/* Field Editor Modal */}
      {fieldEditorOpen && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1000] p-4 overflow-y-auto max-sm:p-0 max-sm:items-end"
          role="presentation"
          onMouseDown={handleFieldCancel}
        >
          {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions */}
          <div
            className="bg-card rounded-lg shadow-xl max-w-[600px] w-full max-h-[90vh] overflow-y-auto p-6 dark:shadow-2xl max-sm:max-w-full max-sm:max-h-[95vh] max-sm:rounded-b-none"
            onClick={(e) => e.stopPropagation()}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <FieldEditor
              collectionId=""
              field={editingField}
              collections={allCollectionSummaries.map((c) => ({
                id: c.id,
                name: c.name,
                displayName: c.displayName || c.name,
              }))}
              picklists={(globalPicklists as Array<{ id: string; name: string }>).map(
                (p: { id: string; name: string }) => ({
                  id: p.id,
                  name: p.name,
                })
              )}
              picklistsLoading={globalPicklistsLoading}
              picklistsError={globalPicklistsError}
              onRetryPicklists={() => {
                void refetchGlobalPicklists()
              }}
              onSave={handleFieldSave}
              onCancel={handleFieldCancel}
            />
          </div>
        </div>
      )}
    </div>
  )
}
