/**
 * NotesSection Component
 *
 * Displays a notes feed on the record detail page, allowing users to
 * add, edit, and delete text notes associated with a record.
 *
 * Features:
 * - Fetches notes from the API with graceful 404 fallback ("coming soon")
 * - Reverse-chronological display of notes
 * - Inline textarea for adding new notes (no modal)
 * - Edit and delete actions on each note
 * - Relative time formatting for timestamps
 * - Accessible with ARIA attributes and data-testid markers
 */

import React, { useState, useCallback } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { MessageSquarePlus, Pencil, Trash2 } from 'lucide-react'
import { useI18n } from '../../context/I18nContext'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import type { ApiClient } from '../../services/apiClient'
import type { Note } from '../../hooks/useRecordContext'
import { formatRelativeTime } from '../../utils/formatRelativeTime'

/**
 * Props for the NotesSection component
 */
export interface NotesSectionProps {
  /** UUID of the collection */
  collectionId: string
  /** UUID of the record */
  recordId: string
  /** Authenticated API client instance */
  apiClient: ApiClient
  /** Pre-fetched notes data (from useRecordContext) */
  notes?: Note[]
  /** Callback to invalidate the parent query cache */
  onMutate?: () => void
}

/**
 * NotesSection Component
 *
 * Displays and manages notes for a specific record. If the backend
 * notes API is not yet available (404), a "coming soon" placeholder
 * is shown instead.
 *
 * @example
 * ```tsx
 * <NotesSection
 *   collectionId="abc-123"
 *   recordId="def-456"
 *   apiClient={apiClient}
 * />
 * ```
 */
export function NotesSection({
  collectionId,
  recordId,
  apiClient,
  notes: notesProp,
  onMutate,
}: NotesSectionProps): React.ReactElement {
  const { t } = useI18n()
  const queryClient = useQueryClient()

  // UI state
  const [isAdding, setIsAdding] = useState(false)
  const [newContent, setNewContent] = useState('')
  const [editingNoteId, setEditingNoteId] = useState<string | null>(null)
  const [editContent, setEditContent] = useState('')

  // Use pre-fetched data from props (via useRecordContext)
  const notes = notesProp

  const invalidateCache = useCallback(() => {
    // Invalidate both the combined record-context and legacy notes queries
    queryClient.invalidateQueries({ queryKey: ['record-context', collectionId, recordId] })
    queryClient.invalidateQueries({ queryKey: ['notes', collectionId, recordId] })
    onMutate?.()
  }, [queryClient, collectionId, recordId, onMutate])

  // Create note mutation
  const createMutation = useMutation({
    mutationFn: async (content: string) => {
      return apiClient.postResource<Note>(`/api/notes`, {
        collectionId,
        recordId,
        content,
      })
    },
    onSuccess: () => {
      invalidateCache()
      setNewContent('')
      setIsAdding(false)
    },
  })

  // Update note mutation
  const updateMutation = useMutation({
    mutationFn: async ({ noteId, content }: { noteId: string; content: string }) => {
      return apiClient.putResource<Note>(`/api/notes/${noteId}`, { content })
    },
    onSuccess: () => {
      invalidateCache()
      setEditingNoteId(null)
      setEditContent('')
    },
  })

  // Delete note mutation
  const deleteMutation = useMutation({
    mutationFn: async (noteId: string) => {
      return apiClient.deleteResource(`/api/notes/${noteId}`)
    },
    onSuccess: () => {
      invalidateCache()
    },
  })

  // Handlers
  const handleAddClick = useCallback(() => {
    setIsAdding(true)
    setNewContent('')
  }, [])

  const handleCancelAdd = useCallback(() => {
    setIsAdding(false)
    setNewContent('')
  }, [])

  const handleSaveNew = useCallback(() => {
    const trimmed = newContent.trim()
    if (!trimmed) return
    createMutation.mutate(trimmed)
  }, [newContent, createMutation])

  const handleEditClick = useCallback((note: Note) => {
    setEditingNoteId(note.id)
    setEditContent(note.content)
  }, [])

  const handleCancelEdit = useCallback(() => {
    setEditingNoteId(null)
    setEditContent('')
  }, [])

  const handleSaveEdit = useCallback(() => {
    if (!editingNoteId) return
    const trimmed = editContent.trim()
    if (!trimmed) return
    updateMutation.mutate({ noteId: editingNoteId, content: trimmed })
  }, [editingNoteId, editContent, updateMutation])

  const handleDelete = useCallback(
    (noteId: string) => {
      if (window.confirm(t('notes.confirmDelete'))) {
        deleteMutation.mutate(noteId)
      }
    },
    [deleteMutation, t]
  )

  // Sort notes reverse chronological
  const notesList = Array.isArray(notes) ? notes : []
  const sortedNotes = [...notesList].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )

  return (
    <section
      className="bg-background border border-border rounded-lg overflow-hidden"
      aria-labelledby="notes-heading"
      data-testid="notes-section"
    >
      {/* Header */}
      <div className="flex justify-between items-center p-4 border-b border-border bg-muted/50 max-md:flex-col max-md:items-start max-md:gap-2">
        <h3 id="notes-heading" className="m-0 text-base font-semibold text-foreground">
          {t('notes.title')}
        </h3>
        {!isAdding && (
          <Button
            variant="outline"
            size="xs"
            onClick={handleAddClick}
            data-testid="notes-add-button"
          >
            <MessageSquarePlus size={14} aria-hidden="true" />
            {t('notes.addNote')}
          </Button>
        )}
      </div>

      {/* Add Note Editor */}
      {isAdding && (
        <div className="p-4 border-b border-border max-md:p-2" data-testid="notes-editor">
          <textarea
            className={cn(
              'w-full min-h-[80px] p-2 border border-border rounded text-sm font-[inherit]',
              'text-foreground bg-background resize-y box-border',
              'focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/15'
            )}
            value={newContent}
            onChange={(e) => setNewContent(e.target.value)}
            placeholder={t('notes.placeholder')}
            autoFocus
            data-testid="notes-editor-textarea"
          />
          <div className="flex gap-2 mt-2 justify-end">
            <Button
              variant="outline"
              size="sm"
              onClick={handleCancelAdd}
              data-testid="notes-cancel-button"
            >
              {t('notes.cancel')}
            </Button>
            <Button
              size="sm"
              onClick={handleSaveNew}
              disabled={!newContent.trim() || createMutation.isPending}
              data-testid="notes-save-button"
            >
              {createMutation.isPending ? t('common.saving') : t('notes.save')}
            </Button>
          </div>
        </div>
      )}

      {/* Notes List */}
      {sortedNotes.length === 0 && !isAdding ? (
        <div
          className="flex flex-col items-center justify-center px-6 py-8 text-center"
          data-testid="notes-empty"
        >
          <p className="m-0 text-sm text-muted-foreground">{t('notes.empty')}</p>
        </div>
      ) : (
        <div className="flex flex-col" role="list" aria-label={t('notes.title')}>
          {sortedNotes.map((note, index) => (
            <div
              key={note.id}
              className={cn(
                'p-4 max-md:p-2',
                index < sortedNotes.length - 1 && 'border-b border-border'
              )}
              role="listitem"
              data-testid={`note-${note.id}`}
            >
              {editingNoteId === note.id ? (
                /* Edit mode */
                <div data-testid={`note-edit-${note.id}`}>
                  <textarea
                    className={cn(
                      'w-full min-h-[80px] p-2 border border-border rounded text-sm font-[inherit]',
                      'text-foreground bg-background resize-y box-border',
                      'focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/15'
                    )}
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                    autoFocus
                    data-testid={`note-edit-textarea-${note.id}`}
                  />
                  <div className="flex gap-2 mt-2 justify-end">
                    <Button variant="outline" size="sm" onClick={handleCancelEdit}>
                      {t('notes.cancel')}
                    </Button>
                    <Button
                      size="sm"
                      onClick={handleSaveEdit}
                      disabled={!editContent.trim() || updateMutation.isPending}
                    >
                      {updateMutation.isPending ? t('common.saving') : t('notes.save')}
                    </Button>
                  </div>
                </div>
              ) : (
                /* View mode */
                <>
                  <p className="m-0 text-sm text-foreground whitespace-pre-wrap leading-relaxed">
                    {note.content}
                  </p>
                  <div className="flex items-center gap-1 mt-2 text-xs text-muted-foreground">
                    <span>{note.createdBy}</span>
                    <span>&middot;</span>
                    <time dateTime={note.createdAt}>
                      {formatRelativeTime(note.createdAt, { style: 'long' })}
                    </time>
                  </div>
                  <div className="flex gap-2 mt-2">
                    <Button
                      variant="ghost"
                      size="xs"
                      className="text-muted-foreground hover:text-primary"
                      onClick={() => handleEditClick(note)}
                      aria-label={t('notes.edit')}
                      data-testid={`note-edit-button-${note.id}`}
                    >
                      <Pencil size={12} aria-hidden="true" />
                      {t('notes.edit')}
                    </Button>
                    <Button
                      variant="ghost"
                      size="xs"
                      className="text-muted-foreground hover:text-destructive"
                      onClick={() => handleDelete(note.id)}
                      aria-label={t('notes.delete')}
                      data-testid={`note-delete-button-${note.id}`}
                    >
                      <Trash2 size={12} aria-hidden="true" />
                      {t('notes.delete')}
                    </Button>
                  </div>
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
