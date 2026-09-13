/**
 * RecordNotFoundState
 *
 * Terminal state for the object detail page when the collection or record
 * does not exist in the current workspace. Distinguishes "the collection is
 * unknown here" (a link carried over from another workspace — recents,
 * favorites, a pasted URL) from "the record is gone", so the user is not
 * left with a generic error or a spinner.
 */

import React from 'react'
import { AlertCircle } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import type { RecordLoadOutcome } from './recordLoadOutcome'

export interface RecordNotFoundStateProps {
  outcome: Exclude<RecordLoadOutcome, 'loaded' | 'error'>
  /** Collection API name from the URL (shown verbatim; it may not exist here). */
  collectionName: string
  /** Workspace slug for the message. */
  tenantSlug: string
  /** End-user app base path (e.g. "/acme/app"). */
  basePath: string
  onNavigate: (path: string) => void
}

export function RecordNotFoundState({
  outcome,
  collectionName,
  tenantSlug,
  basePath,
  onNavigate,
}: RecordNotFoundStateProps): React.ReactElement {
  const collectionMissing = outcome === 'collection-missing'
  return (
    <div className="space-y-4 p-6" data-testid="record-not-found">
      <Alert>
        <AlertCircle className="h-4 w-4" />
        <AlertTitle>Not found in this workspace</AlertTitle>
        <AlertDescription>
          {collectionMissing
            ? `There is no "${collectionName}" collection in the ${tenantSlug} workspace. ` +
              'This link may belong to another workspace you have signed in to.'
            : `This record does not exist in the ${tenantSlug} workspace. ` +
              'It may have been deleted, or the link may belong to another workspace.'}
        </AlertDescription>
      </Alert>
      <div className="flex gap-2">
        <Button variant="outline" onClick={() => onNavigate(`${basePath}/home`)}>
          Go to Home
        </Button>
        {!collectionMissing && (
          <Button variant="outline" onClick={() => onNavigate(`${basePath}/o/${collectionName}`)}>
            Back to list
          </Button>
        )}
      </div>
    </div>
  )
}
