import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useEditor, EditorContent } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import Link from '@tiptap/extension-link'
import Underline from '@tiptap/extension-underline'
import Placeholder from '@tiptap/extension-placeholder'
import {
  Bold,
  Code,
  Heading1,
  Heading2,
  Italic,
  Link as LinkIcon,
  List,
  ListOrdered,
  Plus,
  Redo,
  Underline as UnderlineIcon,
  Undo,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { MergeTagNode, tokenizeMergeTags } from './MergeTagNode'

export interface RichTextEditorProps {
  /** Current HTML value. Merge tags as `{{path}}` are tokenised on load. */
  value: string
  /** Called with the serialized HTML on every change. */
  onChange: (html: string) => void
  /** Placeholder text shown when the editor is empty. */
  placeholder?: string
  /** Disable all editing. */
  disabled?: boolean
  /** Click handler for the toolbar's "Insert field" button. */
  onInsertFieldClick?: () => void
  /** Whether the Insert-Field toolbar button is disabled. */
  insertFieldDisabled?: boolean
  testId?: string
  /** Id for the editable content element (so a `<label htmlFor>` can target it). */
  id?: string
  /** Accessible name for the editable content element. */
  'aria-label'?: string
  /** Marks the editable content invalid (e.g. a record-form field error). */
  'aria-invalid'?: boolean
  /** Id of the element describing the editable content (e.g. its field-error alert). */
  'aria-describedby'?: string
  /** Marks the editable content as required. */
  'aria-required'?: boolean
}

export interface RichTextEditorHandle {
  /** Inserts a literal merge tag at the current selection. */
  insertMergeTag: (expression: string) => void
  /** Returns true if the editor is currently focused. */
  isFocused: () => boolean
}

/**
 * WYSIWYG editor for HTML email bodies, with first-class support for
 * `{{expression}}` merge tags rendered as inline chips.
 *
 * Output of {@link RichTextEditorProps.onChange} is plain HTML compatible
 * with the backend {@code MergeFieldRenderer} — merge tags serialize as
 * `<span data-merge-tag data-expression="…">{{…}}</span>` which the backend
 * renderer happily sees as plain text containing `{{…}}` and resolves
 * accordingly.
 */
export const RichTextEditor = React.forwardRef<RichTextEditorHandle, RichTextEditorProps>(
  function RichTextEditor(
    {
      value,
      onChange,
      placeholder,
      disabled = false,
      onInsertFieldClick,
      insertFieldDisabled,
      testId = 'rich-text-editor',
      id,
      'aria-label': ariaLabel,
      'aria-invalid': ariaInvalid,
      'aria-describedby': ariaDescribedBy,
      'aria-required': ariaRequired,
    },
    ref
  ) {
    // Optional a11y/id attributes for the contenteditable — only the ones supplied are set, so
    // existing callers (email templates, page builder) render exactly as before.
    const contentA11y: Record<string, string> = {}
    if (id) contentA11y.id = id
    if (ariaLabel) contentA11y['aria-label'] = ariaLabel
    if (ariaInvalid !== undefined) contentA11y['aria-invalid'] = String(ariaInvalid)
    if (ariaDescribedBy) contentA11y['aria-describedby'] = ariaDescribedBy
    if (ariaRequired) contentA11y['aria-required'] = 'true'
    if (id || ariaLabel) {
      contentA11y.role = 'textbox'
      contentA11y['aria-multiline'] = 'true'
    }
    const [showHtmlSource, setShowHtmlSource] = useState(false)
    const lastEmittedRef = useRef<string>('')

    const editor = useEditor({
      extensions: [
        StarterKit.configure({
          heading: { levels: [1, 2, 3] },
        }),
        Underline,
        Link.configure({
          openOnClick: false,
          autolink: true,
          HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' },
        }),
        Placeholder.configure({
          placeholder: placeholder ?? 'Write your email…',
        }),
        MergeTagNode,
      ],
      content: tokenizeMergeTags(value || ''),
      editable: !disabled,
      editorProps: {
        attributes: {
          class:
            'tiptap prose prose-sm dark:prose-invert max-w-none px-4 py-3 min-h-[300px] focus:outline-none',
          'data-testid': `${testId}-content`,
          ...contentA11y,
        },
      },
      onUpdate: ({ editor: ed }) => {
        const html = ed.getHTML()
        lastEmittedRef.current = html
        onChange(html)
      },
    })

    // Keep editor content in sync when the parent supplies a new value
    // (e.g. switching templates) without echoing back the value we just
    // emitted.
    useEffect(() => {
      if (!editor) return
      if (value === lastEmittedRef.current) return
      const tokenised = tokenizeMergeTags(value || '')
      editor.commands.setContent(tokenised, { emitUpdate: false })
    }, [editor, value])

    useEffect(() => {
      if (!editor) return
      editor.setEditable(!disabled)
    }, [editor, disabled])

    const insertMergeTag = useCallback(
      (expression: string) => {
        if (!editor) return
        editor
          .chain()
          .focus()
          .insertContent({
            type: 'mergeTag',
            attrs: { expression },
          })
          .run()
      },
      [editor]
    )

    React.useImperativeHandle(
      ref,
      () => ({
        insertMergeTag,
        isFocused: () => !!editor?.isFocused,
      }),
      [editor, insertMergeTag]
    )

    const handleSetLink = useCallback(() => {
      if (!editor) return
      const previous = editor.getAttributes('link').href as string | undefined
      const url = window.prompt('Link URL', previous ?? 'https://')
      if (url === null) return
      if (url === '') {
        editor.chain().focus().extendMarkRange('link').unsetLink().run()
        return
      }
      editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run()
    }, [editor])

    if (!editor) {
      return (
        <div className="rounded-md border border-border bg-muted/20 p-4 text-xs text-muted-foreground">
          Loading editor…
        </div>
      )
    }

    return (
      <div
        className={cn(
          'flex flex-col overflow-hidden rounded-md border border-border bg-background',
          disabled && 'opacity-60'
        )}
        data-testid={testId}
      >
        <div className="flex flex-wrap items-center gap-1 border-b border-border bg-muted/30 px-2 py-1.5">
          <ToolbarButton
            label="Bold"
            active={editor.isActive('bold')}
            onClick={() => editor.chain().focus().toggleBold().run()}
            icon={<Bold className="h-3.5 w-3.5" />}
            testId={`${testId}-bold`}
          />
          <ToolbarButton
            label="Italic"
            active={editor.isActive('italic')}
            onClick={() => editor.chain().focus().toggleItalic().run()}
            icon={<Italic className="h-3.5 w-3.5" />}
            testId={`${testId}-italic`}
          />
          <ToolbarButton
            label="Underline"
            active={editor.isActive('underline')}
            onClick={() => editor.chain().focus().toggleUnderline().run()}
            icon={<UnderlineIcon className="h-3.5 w-3.5" />}
            testId={`${testId}-underline`}
          />
          <ToolbarSeparator />
          <ToolbarButton
            label="Heading 1"
            active={editor.isActive('heading', { level: 1 })}
            onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
            icon={<Heading1 className="h-3.5 w-3.5" />}
            testId={`${testId}-h1`}
          />
          <ToolbarButton
            label="Heading 2"
            active={editor.isActive('heading', { level: 2 })}
            onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
            icon={<Heading2 className="h-3.5 w-3.5" />}
            testId={`${testId}-h2`}
          />
          <ToolbarSeparator />
          <ToolbarButton
            label="Bulleted list"
            active={editor.isActive('bulletList')}
            onClick={() => editor.chain().focus().toggleBulletList().run()}
            icon={<List className="h-3.5 w-3.5" />}
            testId={`${testId}-bullet-list`}
          />
          <ToolbarButton
            label="Numbered list"
            active={editor.isActive('orderedList')}
            onClick={() => editor.chain().focus().toggleOrderedList().run()}
            icon={<ListOrdered className="h-3.5 w-3.5" />}
            testId={`${testId}-ordered-list`}
          />
          <ToolbarSeparator />
          <ToolbarButton
            label="Link"
            active={editor.isActive('link')}
            onClick={handleSetLink}
            icon={<LinkIcon className="h-3.5 w-3.5" />}
            testId={`${testId}-link`}
          />
          <ToolbarSeparator />
          <ToolbarButton
            label="Undo"
            onClick={() => editor.chain().focus().undo().run()}
            disabled={!editor.can().undo()}
            icon={<Undo className="h-3.5 w-3.5" />}
            testId={`${testId}-undo`}
          />
          <ToolbarButton
            label="Redo"
            onClick={() => editor.chain().focus().redo().run()}
            disabled={!editor.can().redo()}
            icon={<Redo className="h-3.5 w-3.5" />}
            testId={`${testId}-redo`}
          />
          <div className="ml-auto flex items-center gap-1">
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="h-7 gap-1 px-2 text-xs"
              onClick={onInsertFieldClick}
              disabled={insertFieldDisabled || !onInsertFieldClick}
              data-testid={`${testId}-insert-field`}
            >
              <Plus className="h-3 w-3" />
              Insert field
            </Button>
            <ToolbarButton
              label={showHtmlSource ? 'Show preview' : 'View HTML source'}
              active={showHtmlSource}
              onClick={() => setShowHtmlSource((v) => !v)}
              icon={<Code className="h-3.5 w-3.5" />}
              testId={`${testId}-html-toggle`}
            />
          </div>
        </div>
        {showHtmlSource ? (
          <textarea
            value={editor.getHTML()}
            onChange={(e) => {
              editor.commands.setContent(tokenizeMergeTags(e.target.value), {
                emitUpdate: true,
              })
            }}
            className="min-h-[300px] w-full resize-y bg-background px-4 py-3 font-mono text-xs text-foreground outline-none"
            disabled={disabled}
            data-testid={`${testId}-html-source`}
          />
        ) : (
          <EditorContent editor={editor} />
        )}
      </div>
    )
  }
)

function ToolbarSeparator(): React.ReactElement {
  return <span className="mx-1 h-5 w-px bg-border" aria-hidden="true" />
}

interface ToolbarButtonProps {
  label: string
  icon: React.ReactNode
  active?: boolean
  disabled?: boolean
  onClick: () => void
  testId?: string
}

function ToolbarButton({
  label,
  icon,
  active,
  disabled,
  onClick,
  testId,
}: ToolbarButtonProps): React.ReactElement {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={label}
      aria-label={label}
      aria-pressed={!!active}
      className={cn(
        'flex h-7 w-7 items-center justify-center rounded text-muted-foreground transition-colors',
        'hover:bg-muted hover:text-foreground',
        'disabled:cursor-not-allowed disabled:opacity-40',
        active && 'bg-primary/10 text-primary'
      )}
      data-testid={testId}
    >
      {icon}
    </button>
  )
}
