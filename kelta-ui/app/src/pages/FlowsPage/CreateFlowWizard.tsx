import React, { useState } from 'react'
import { toast } from 'sonner'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { useApi } from '@/context/ApiContext'
import { getTenantSlug } from '@/context/TenantContext'
import { cn } from '@/lib/utils'
import type { FlowType, NatsTriggerConfig, TriggerConfig } from '@/pages/FlowDesignerPage/types'
import { FlowTypeStep } from './steps/FlowTypeStep'
import { TriggerConfigStep } from './steps/TriggerConfigStep'
import { NameAndCreateStep } from './steps/NameAndCreateStep'

interface CreateFlowWizardProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

const STEPS = ['Type', 'Trigger', 'Name'] as const
type Step = (typeof STEPS)[number]

const MINIMAL_DEFINITION = {
  StartAt: 'Start',
  States: { Start: { Type: 'Succeed' } },
}

export function CreateFlowWizard({ open, onOpenChange }: CreateFlowWizardProps) {
  const navigate = useNavigate()
  const { keltaClient } = useApi()
  const queryClient = useQueryClient()

  const [step, setStep] = useState<Step>('Type')
  const [flowType, setFlowType] = useState<FlowType | null>(null)
  const [triggerConfig, setTriggerConfig] = useState<Partial<TriggerConfig>>({})
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const stepIndex = STEPS.indexOf(step)

  const reset = () => {
    setStep('Type')
    setFlowType(null)
    setTriggerConfig({})
    setName('')
    setDescription('')
  }

  const handleOpenChange = (open: boolean) => {
    if (!open) reset()
    onOpenChange(open)
  }

  const createMutation = useMutation({
    mutationFn: async () => {
      if (!flowType || !name.trim()) return
      const payload = {
        name: name.trim(),
        description: description.trim() || null,
        flowType,
        active: false,
        definition: MINIMAL_DEFINITION,
        triggerConfig: Object.keys(triggerConfig).length > 0 ? triggerConfig : null,
      }
      const resp = await keltaClient.admin.flows.create('', '', payload)
      return resp
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['flows'] })
      toast.success('Flow created successfully')
      handleOpenChange(false)
      if (data?.id) {
        navigate(`/${getTenantSlug()}/flows/${data.id}/design`)
      }
    },
    onError: (err: Error) => {
      toast.error(err.message || 'Failed to create flow')
    },
  })

  const canGoNext = () => {
    switch (step) {
      case 'Type':
        return flowType !== null
      case 'Trigger':
        if (flowType === 'NATS_TRIGGERED') {
          // NATS-triggered flows require a topic to name the trigger subject
          const topic = (triggerConfig as Partial<NatsTriggerConfig>).topic
          return typeof topic === 'string' && topic.trim().length > 0
        }
        return true // Other trigger configs are optional
      case 'Name':
        return name.trim().length > 0
    }
  }

  const goNext = () => {
    const idx = STEPS.indexOf(step)
    if (idx < STEPS.length - 1) {
      setStep(STEPS[idx + 1])
    }
  }

  const goBack = () => {
    const idx = STEPS.indexOf(step)
    if (idx > 0) {
      setStep(STEPS[idx - 1])
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[640px]">
        <DialogHeader>
          <DialogTitle>Create flow</DialogTitle>
        </DialogHeader>

        {/* Step indicator */}
        <div className="flex items-center gap-2 border-b border-border pb-4">
          {STEPS.map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <div className="h-px flex-1 bg-border" />}
              <div className="flex items-center gap-1.5">
                <div
                  className={cn(
                    'flex h-6 w-6 items-center justify-center rounded-full text-xs font-semibold',
                    i < stepIndex
                      ? 'bg-primary text-primary-foreground'
                      : i === stepIndex
                        ? 'bg-primary text-primary-foreground'
                        : 'bg-muted text-muted-foreground'
                  )}
                >
                  {i + 1}
                </div>
                <span
                  className={cn(
                    'text-xs font-medium',
                    i <= stepIndex ? 'text-foreground' : 'text-muted-foreground'
                  )}
                >
                  {s}
                </span>
              </div>
            </React.Fragment>
          ))}
        </div>

        {/* Step content */}
        <div className="min-h-[300px] py-2">
          {step === 'Type' && <FlowTypeStep value={flowType} onChange={setFlowType} />}
          {step === 'Trigger' && flowType && (
            <TriggerConfigStep
              flowType={flowType}
              config={triggerConfig}
              onChange={setTriggerConfig}
            />
          )}
          {step === 'Name' && flowType && (
            <NameAndCreateStep
              name={name}
              description={description}
              flowType={flowType}
              onNameChange={setName}
              onDescriptionChange={setDescription}
            />
          )}
        </div>

        <DialogFooter className="flex items-center justify-between sm:justify-between">
          <Button
            variant="outline"
            onClick={stepIndex === 0 ? () => handleOpenChange(false) : goBack}
          >
            {stepIndex === 0 ? 'Cancel' : 'Back'}
          </Button>
          {step === 'Name' ? (
            <Button
              onClick={() => createMutation.mutate()}
              disabled={!canGoNext() || createMutation.isPending}
            >
              {createMutation.isPending ? 'Creating...' : 'Create & Open Designer'}
            </Button>
          ) : (
            <Button onClick={goNext} disabled={!canGoNext()}>
              Next
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
