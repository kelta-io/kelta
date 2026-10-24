/**
 * PolicyTestPanel Component
 *
 * Allows admins to test Cerbos authorization policies by specifying a user,
 * resource, and action, then checking whether the policy allows or denies.
 */

import React, { useState, useCallback } from 'react'
import { Play, CheckCircle, XCircle } from 'lucide-react'
import { useMutation } from '@tanstack/react-query'
import { useApi } from '@/context/ApiContext'
import { useCollectionSummaries } from '@/hooks/useCollectionSummaries'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

const ACTIONS = ['read', 'create', 'edit', 'delete'] as const
const RESOURCE_KINDS = ['record', 'field', 'collection', 'system_feature'] as const

interface TestResult {
  allowed: boolean
  email: string
  resourceKind: string
  resourceId: string
  action: string
}

export interface PolicyTestPanelProps {
  /** Pre-filled profile ID */
  profileId?: string
  /** Pre-filled tenant ID */
  tenantId?: string
  /** Test ID for the component */
  testId?: string
}

export function PolicyTestPanel({
  profileId,
  tenantId,
  testId = 'policy-test-panel',
}: PolicyTestPanelProps): React.ReactElement {
  const { apiClient } = useApi()
  const { summaries: collections } = useCollectionSummaries()

  const [form, setForm] = useState({
    email: '',
    resourceKind: 'record' as string,
    resourceId: '',
    collectionId: '',
    action: 'read' as string,
  })

  const [result, setResult] = useState<TestResult | null>(null)

  const testMutation = useMutation({
    mutationFn: async () => {
      const response = await apiClient.post('/api/admin/authorization/test', {
        email: form.email,
        profileId,
        tenantId,
        resourceKind: form.resourceKind,
        resourceId: form.resourceId || 'test',
        action: form.action,
        resourceAttributes: {
          collectionId: form.collectionId,
        },
      })
      return response as TestResult
    },
    onSuccess: (data) => {
      setResult(data)
    },
  })

  const handleTest = useCallback(() => {
    testMutation.mutate()
  }, [testMutation])

  return (
    <div className="space-y-4" data-testid={testId}>
      <h3 className="text-sm font-medium text-foreground">Test policy</h3>

      <div className="space-y-3 rounded-lg border border-border bg-card p-4">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <Label className="mb-1 text-xs">User Email</Label>
            <Input
              value={form.email}
              onChange={(e) => setForm((prev) => ({ ...prev, email: e.target.value }))}
              placeholder="user@example.com"
              data-testid={`${testId}-email-input`}
            />
          </div>
          <div>
            <Label className="mb-1 text-xs">Resource Kind</Label>
            <Select
              value={form.resourceKind}
              onValueChange={(v) => setForm((prev) => ({ ...prev, resourceKind: v }))}
            >
              <SelectTrigger data-testid={`${testId}-kind-select`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {RESOURCE_KINDS.map((k) => (
                  <SelectItem key={k} value={k}>
                    {k}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div>
            <Label className="mb-1 text-xs">Collection</Label>
            <Select
              value={form.collectionId}
              onValueChange={(v) => setForm((prev) => ({ ...prev, collectionId: v }))}
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
              value={form.action}
              onValueChange={(v) => setForm((prev) => ({ ...prev, action: v }))}
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
        </div>

        <div>
          <Label className="mb-1 text-xs">Resource ID (optional)</Label>
          <Input
            value={form.resourceId}
            onChange={(e) => setForm((prev) => ({ ...prev, resourceId: e.target.value }))}
            placeholder="Leave blank for general test"
            data-testid={`${testId}-resource-id-input`}
          />
        </div>

        <Button
          onClick={handleTest}
          disabled={!form.email || testMutation.isPending}
          data-testid={`${testId}-test-button`}
        >
          <Play size={14} className="mr-1" />
          {testMutation.isPending ? 'Testing...' : 'Test'}
        </Button>

        {/* Result */}
        {result && (
          <div
            className={`flex items-center gap-2 rounded-lg px-4 py-3 text-sm ${
              result.allowed
                ? 'bg-green-50 text-green-800 dark:bg-green-950 dark:text-green-300'
                : 'bg-red-50 text-red-800 dark:bg-red-950 dark:text-red-300'
            }`}
            data-testid={`${testId}-result`}
          >
            {result.allowed ? <CheckCircle size={16} /> : <XCircle size={16} />}
            <span className="font-medium">{result.allowed ? 'ALLOWED' : 'DENIED'}</span>
            <span className="text-xs">
              {result.email} / {result.resourceKind} / {result.action}
            </span>
          </div>
        )}
      </div>
    </div>
  )
}
