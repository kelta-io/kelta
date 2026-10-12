import React, { useState, useCallback } from 'react'
import { CheckCircle2, XCircle, AlertCircle, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ScrollArea } from '@/components/ui/scroll-area'
import { FileDropzone } from '@/components/FileDropzone/FileDropzone'
import { useApi } from '@/context/ApiContext'

interface RowError {
  row: number
  message: string
}

interface ImportResult {
  rowsProcessed: number
  rowsImported: number
  errorCount: number
  errors: RowError[]
  /** Columns left out of every record because the caller may not write those fields. */
  skippedColumns?: string[]
}

export interface CsvImportDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  collectionName: string
  onImported: () => void
}

type Phase = 'idle' | 'uploading' | 'done'

export function CsvImportDialog({
  open,
  onOpenChange,
  collectionName,
  onImported,
}: CsvImportDialogProps): React.ReactElement {
  const { apiClient } = useApi()
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>('idle')
  const [result, setResult] = useState<ImportResult | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)

  const reset = useCallback(() => {
    setFile(null)
    setFileError(null)
    setPhase('idle')
    setResult(null)
    setUploadError(null)
  }, [])

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) reset()
      onOpenChange(next)
    },
    [onOpenChange, reset]
  )

  const handleFile = useCallback((f: File) => {
    setFile(f)
    setFileError(null)
    setUploadError(null)
    setResult(null)
  }, [])

  const handleImport = useCallback(async () => {
    if (!file) return
    setPhase('uploading')
    setUploadError(null)

    const formData = new FormData()
    formData.append('file', file)

    try {
      const data = await apiClient.postFormData<ImportResult>(
        `/api/collections/${collectionName}/import/csv`,
        formData
      )
      setResult(data)
      setPhase('done')
      if (data.rowsImported > 0) {
        onImported()
      }
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'message' in err
          ? String((err as { message: unknown }).message)
          : 'Import failed'
      setUploadError(msg)
      setPhase('idle')
    }
  }, [apiClient, collectionName, file, onImported])

  const isSuccess = result && result.errorCount === 0
  const isPartial = result && result.rowsImported > 0 && result.errorCount > 0
  const isAllFailed = result && result.rowsImported === 0 && result.errorCount > 0

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Import CSV</DialogTitle>
          <DialogDescription>
            Upload a CSV file to bulk-create records. The header row must match your field names
            exactly. System fields (id, createdAt, etc.) are ignored.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {phase !== 'done' && (
            <FileDropzone
              accept=".csv"
              maxBytes={10 * 1024 * 1024}
              onFile={handleFile}
              onError={setFileError}
              hint={
                file ? (
                  <span className="text-sm font-medium text-foreground">{file.name}</span>
                ) : (
                  <span className="text-sm text-muted-foreground">
                    Drop a .csv file here or click to browse
                  </span>
                )
              }
            />
          )}

          {fileError && (
            <p className="flex items-center gap-1.5 text-sm text-destructive">
              <AlertCircle className="h-4 w-4 shrink-0" />
              {fileError}
            </p>
          )}

          {uploadError && (
            <p className="flex items-center gap-1.5 text-sm text-destructive">
              <XCircle className="h-4 w-4 shrink-0" />
              {uploadError}
            </p>
          )}

          {result && (
            <div className="space-y-3 rounded-md border p-4">
              <div className="flex items-center gap-2">
                {isSuccess && <CheckCircle2 className="h-5 w-5 text-green-600" />}
                {isPartial && <AlertCircle className="h-5 w-5 text-yellow-600" />}
                {isAllFailed && <XCircle className="h-5 w-5 text-destructive" />}
                <span className="text-sm font-medium">
                  {result.rowsImported} of {result.rowsProcessed} row
                  {result.rowsProcessed !== 1 ? 's' : ''} imported
                </span>
              </div>

              {result.skippedColumns && result.skippedColumns.length > 0 && (
                <p
                  className="text-xs text-muted-foreground"
                  data-testid="csv-import-skipped-columns"
                >
                  Not imported (you cannot edit these fields): {result.skippedColumns.join(', ')}
                </p>
              )}

              {result.errors.length > 0 && (
                <ScrollArea className="h-40">
                  <ul className="space-y-1">
                    {result.errors.map((e, idx) => (
                      <li key={idx} className="text-xs text-muted-foreground">
                        <span className="font-medium text-destructive">Row {e.row}:</span>{' '}
                        {e.message}
                      </li>
                    ))}
                  </ul>
                </ScrollArea>
              )}
            </div>
          )}
        </div>

        <DialogFooter>
          {phase === 'done' ? (
            <Button onClick={() => handleOpenChange(false)}>Done</Button>
          ) : (
            <>
              <Button variant="outline" onClick={() => handleOpenChange(false)}>
                Cancel
              </Button>
              <Button onClick={handleImport} disabled={!file || phase === 'uploading'}>
                {phase === 'uploading' && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                Import
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
