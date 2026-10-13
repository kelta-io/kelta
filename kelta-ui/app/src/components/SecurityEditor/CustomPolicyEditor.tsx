/**
 * CustomPolicyEditor Component
 *
 * Manages custom ABAC rules for a profile. Supports both a visual rule builder
 * (dropdown-based) and an advanced CEL expression editor.
 *
 * Each rule defines a condition under which an action on a collection is
 * allowed or denied for the profile.
 */

import React, { useState, useCallback } from 'react'
import { toast } from 'sonner'
import { Plus, Trash2, Pencil, ToggleLeft, ToggleRight } from 'lucide-react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '@/context/ApiContext'
import { useCollectionSummaries } from '@/hooks/useCollectionSummaries'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { CelExpressionEditor } from './CelExpressionEditor'

interface CustomRule {
  id: string
  profileId: string
  tenantId: string
  collectionId: string
  action: string
  effect: string
  conditionType: 'visual' | 'cel'
  condition: VisualCondition | CelCondition
  enabled: boolean
}

interface VisualCondition {
  field: string
  operator: string
  value: string
}

interface CelCondition {
  expression: string
}

const ACTIONS = ['read', 'create', 'edit', 'delete'] as const
const EFFECTS = ['allow', 'deny'] as const
const OPERATORS = [
  { value: 'equals', label: 'equals' },
  { value: 'not_equals', label: 'not equals' },
  { value: 'in', label: 'in' },
  { value: 'contains', label: 'contains' },
  { value: 'greater_than', label: 'greater than' },
  { value: 'less_than', label: 'less than' },
] as const

export interface CustomPolicyEditorProps {
  /** Profile ID */
  profileId: string
  /** Tenant ID */
  tenantId: string
  /** Whether the editor is read-only */
  readOnly?: boolean
  /** Test ID for the component */
  testId?: string
}

interface RuleFormState {
  collectionId: string
  action: string
  effect: string
  field: string
  operator: string
  value: string
  celExpression: string
  enabled: boolean
}

const EMPTY_FORM: RuleFormState = {
  collectionId: '',
  action: 'read',
  effect: 'deny',
  field: '',
  operator: 'equals',
  value: '',
  celExpression: '',
  enabled: true,
}

export function CustomPolicyEditor({
  profileId,
  tenantId,
  readOnly = false,
  testId = 'custom-policy-editor',
}: CustomPolicyEditorProps): React.ReactElement {
  const { apiClient } = useApi()
  const queryClient = useQueryClient()
  const { summaries: collections } = useCollectionSummaries()

  // Fetch existing rules
  const { data: rules = [] } = useQuery<CustomRule[]>({
    queryKey: ['custom-rules', profileId],
    queryFn: async () => {
      const response = await apiClient.get(`/api/admin/profiles/${profileId}/custom-rules`)
      return Array.isArray(response) ? response : []
    },
    enabled: !!profileId,
  })

  // Create rule mutation
  const createMutation = useMutation({
    mutationFn: async (rule: Partial<CustomRule>) => {
      return apiClient.post(`/api/admin/profiles/${profileId}/custom-rules`, rule)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['custom-rules', profileId] })
      toast.success('Rule created successfully')
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to create rule')
    },
  })

  // Update rule mutation
  const updateMutation = useMutation({
    mutationFn: async ({ ruleId, data }: { ruleId: string; data: Partial<CustomRule> }) => {
      return apiClient.put(`/api/admin/profiles/${profileId}/custom-rules/${ruleId}`, data)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['custom-rules', profileId] })
      toast.success('Rule updated successfully')
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update rule')
    },
  })

  // Delete rule mutation
  const deleteMutation = useMutation({
    mutationFn: async (ruleId: string) => {
      return apiClient.delete(`/api/admin/profiles/${profileId}/custom-rules/${ruleId}`)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['custom-rules', profileId] })
      toast.success('Rule deleted')
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete rule')
    },
  })

  // Form state
  const [showForm, setShowForm] = useState(false)
  const [isAdvanced, setIsAdvanced] = useState(false)
  const [editingRuleId, setEditingRuleId] = useState<string | null>(null)
  const [formData, setFormData] = useState<RuleFormState>(EMPTY_FORM)

  const resetForm = useCallback(() => {
    setShowForm(false)
    setEditingRuleId(null)
    setIsAdvanced(false)
    setFormData(EMPTY_FORM)
  }, [])

  const handleAddNew = useCallback(() => {
    setEditingRuleId(null)
    setFormData(EMPTY_FORM)
    setIsAdvanced(false)
    setShowForm(true)
  }, [])

  const handleEditRule = useCallback((rule: CustomRule) => {
    const isCel = rule.conditionType === 'cel'
    const condition = rule.condition as VisualCondition | CelCondition
    setEditingRuleId(rule.id)
    setIsAdvanced(isCel)
    setFormData({
      collectionId: rule.collectionId,
      action: rule.action,
      effect: rule.effect,
      field: isCel ? '' : ((condition as VisualCondition).field ?? ''),
      operator: isCel ? 'equals' : ((condition as VisualCondition).operator ?? 'equals'),
      value: isCel ? '' : ((condition as VisualCondition).value ?? ''),
      celExpression: isCel ? ((condition as CelCondition).expression ?? '') : '',
      enabled: rule.enabled,
    })
    setShowForm(true)
  }, [])

  const handleSaveRule = useCallback(() => {
    const condition = isAdvanced
      ? { expression: formData.celExpression }
      : { field: formData.field, operator: formData.operator, value: formData.value }

    const ruleData: Partial<CustomRule> = {
      tenantId,
      collectionId: formData.collectionId,
      action: formData.action,
      effect: formData.effect,
      conditionType: isAdvanced ? 'cel' : 'visual',
      condition,
      enabled: formData.enabled,
    }

    if (editingRuleId) {
      updateMutation.mutate({ ruleId: editingRuleId, data: ruleData })
    } else {
      createMutation.mutate(ruleData)
    }

    resetForm()
  }, [createMutation, updateMutation, editingRuleId, tenantId, formData, isAdvanced, resetForm])

  const getCollectionName = useCallback(
    (collectionId: string) => {
      const col = collections.find((c) => c.id === collectionId)
      return col?.displayName ?? col?.name ?? collectionId
    },
    [collections]
  )

  const isSaving = createMutation.isPending || updateMutation.isPending

  return (
    <div className="space-y-4" data-testid={testId}>
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-foreground">Custom authorization rules</h3>
        {!readOnly && (
          <Button
            variant="outline"
            size="sm"
            onClick={handleAddNew}
            disabled={showForm}
            data-testid={`${testId}-add-button`}
          >
            <Plus size={14} className="mr-1" />
            Add Rule
          </Button>
        )}
      </div>

      {/* Rule form (create or edit) */}
      {showForm && (
        <div
          className="space-y-3 rounded-lg border border-border bg-card p-4"
          data-testid={`${testId}-form`}
        >
          <div className="text-sm font-medium text-foreground">
            {editingRuleId ? 'Edit Rule' : 'New Rule'}
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <Label className="mb-1 text-xs">Collection</Label>
              <Select
                value={formData.collectionId}
                onValueChange={(v) => setFormData((prev) => ({ ...prev, collectionId: v }))}
              >
                <SelectTrigger data-testid={`${testId}-collection-select`}>
                  <SelectValue placeholder="Select collection" />
                </SelectTrigger>
                <SelectContent>
                  {collections.map((col) => (
                    <SelectItem key={col.id} value={col.id}>
                      {col.displayName ?? col.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label className="mb-1 text-xs">Action</Label>
              <Select
                value={formData.action}
                onValueChange={(v) => setFormData((prev) => ({ ...prev, action: v }))}
              >
                <SelectTrigger data-testid={`${testId}-action-select`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ACTIONS.map((a) => (
                    <SelectItem key={a} value={a}>
                      {a}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label className="mb-1 text-xs">Effect</Label>
              <Select
                value={formData.effect}
                onValueChange={(v) => setFormData((prev) => ({ ...prev, effect: v }))}
              >
                <SelectTrigger data-testid={`${testId}-effect-select`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {EFFECTS.map((e) => (
                    <SelectItem key={e} value={e}>
                      {e}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          {/* Mode toggle */}
          <div className="flex items-center gap-2">
            <Switch
              checked={isAdvanced}
              onCheckedChange={setIsAdvanced}
              data-testid={`${testId}-advanced-toggle`}
            />
            <Label className="text-xs text-muted-foreground">
              {isAdvanced ? 'Advanced (CEL)' : 'Visual'}
            </Label>
          </div>

          {/* Condition editor */}
          {isAdvanced ? (
            <CelExpressionEditor
              value={formData.celExpression}
              onChange={(v) => setFormData((prev) => ({ ...prev, celExpression: v }))}
              testId={`${testId}-cel`}
            />
          ) : (
            <div className="grid grid-cols-3 gap-3">
              <div>
                <Label className="mb-1 text-xs">Field</Label>
                <Input
                  value={formData.field}
                  onChange={(e) => setFormData((prev) => ({ ...prev, field: e.target.value }))}
                  placeholder="e.g., status"
                  data-testid={`${testId}-field-input`}
                />
              </div>
              <div>
                <Label className="mb-1 text-xs">Operator</Label>
                <Select
                  value={formData.operator}
                  onValueChange={(v) => setFormData((prev) => ({ ...prev, operator: v }))}
                >
                  <SelectTrigger data-testid={`${testId}-operator-select`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {OPERATORS.map((op) => (
                      <SelectItem key={op.value} value={op.value}>
                        {op.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label className="mb-1 text-xs">Value</Label>
                <Input
                  value={formData.value}
                  onChange={(e) => setFormData((prev) => ({ ...prev, value: e.target.value }))}
                  placeholder="e.g., closed"
                  data-testid={`${testId}-value-input`}
                />
              </div>
            </div>
          )}

          {/* Enabled toggle for edits */}
          {editingRuleId && (
            <div className="flex items-center gap-2">
              <Switch
                checked={formData.enabled}
                onCheckedChange={(v) => setFormData((prev) => ({ ...prev, enabled: v }))}
                data-testid={`${testId}-enabled-toggle`}
              />
              <Label className="text-xs text-muted-foreground">
                {formData.enabled ? 'Enabled' : 'Disabled'}
              </Label>
            </div>
          )}

          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={resetForm}>
              Cancel
            </Button>
            <Button
              size="sm"
              onClick={handleSaveRule}
              disabled={isSaving}
              data-testid={`${testId}-save-button`}
            >
              {isSaving ? 'Saving...' : editingRuleId ? 'Update Rule' : 'Create Rule'}
            </Button>
          </div>
        </div>
      )}

      {/* Existing rules list */}
      {rules.length === 0 ? (
        <div className="rounded-lg border border-border bg-card px-4 py-8 text-center text-sm text-muted-foreground">
          No custom rules defined. Click &quot;Add Rule&quot; to create one.
        </div>
      ) : (
        <div className="space-y-2">
          {rules.map((rule) => (
            <div
              key={rule.id}
              className="flex items-center justify-between rounded-lg border border-border bg-card px-4 py-3"
              data-testid={`${testId}-rule-${rule.id}`}
            >
              <div className="flex items-center gap-3">
                {rule.enabled ? (
                  <ToggleRight size={16} className="text-green-500" />
                ) : (
                  <ToggleLeft size={16} className="text-muted-foreground" />
                )}
                <div className="text-sm">
                  <span className="font-medium capitalize">{rule.effect}</span>{' '}
                  <span className="text-muted-foreground">{rule.action}</span>{' '}
                  <span className="text-muted-foreground">on</span>{' '}
                  <span className="font-medium">{getCollectionName(rule.collectionId)}</span>
                  {rule.conditionType === 'cel' ? (
                    <span className="ml-2 text-xs text-muted-foreground">
                      [CEL: {(rule.condition as CelCondition).expression}]
                    </span>
                  ) : (
                    <span className="ml-2 text-xs text-muted-foreground">
                      [when {(rule.condition as VisualCondition).field}{' '}
                      {(rule.condition as VisualCondition).operator}{' '}
                      {(rule.condition as VisualCondition).value}]
                    </span>
                  )}
                </div>
              </div>
              {!readOnly && (
                <div className="flex gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleEditRule(rule)}
                    disabled={showForm}
                    data-testid={`${testId}-edit-${rule.id}`}
                  >
                    <Pencil size={14} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => deleteMutation.mutate(rule.id)}
                    disabled={deleteMutation.isPending}
                    data-testid={`${testId}-delete-${rule.id}`}
                  >
                    <Trash2 size={14} />
                  </Button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default CustomPolicyEditor
