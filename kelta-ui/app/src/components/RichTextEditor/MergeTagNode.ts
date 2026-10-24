import { Node, mergeAttributes } from '@tiptap/core'

/**
 * Inline node representing a single `{{expression}}` merge tag.
 *
 * In the editor it renders as a non-editable chip so users can see and select
 * the tag as a unit without accidentally breaking the braces. In serialized
 * HTML output the node uses a `<span data-merge-tag>` wrapper containing the
 * raw `{{expression}}` text, which round-trips through the backend
 * {@code MergeFieldRenderer} unchanged.
 *
 * Parse rules accept either the wrapper span or — when the editor loads
 * existing HTML — bare `{{...}}` text runs (handled by the editor's
 * {@code htmlToDoc} pre-pass before content is set).
 */
interface MergeTagAttributes {
  expression: string
}

export const MergeTagNode = Node.create({
  name: 'mergeTag',
  group: 'inline',
  inline: true,
  selectable: true,
  atom: true,

  addAttributes() {
    return {
      expression: {
        default: '',
        parseHTML: (el) => el.getAttribute('data-expression') ?? '',
        renderHTML: (attrs) => ({
          'data-expression': attrs.expression,
        }),
      },
    }
  },

  parseHTML() {
    return [
      {
        tag: 'span[data-merge-tag]',
      },
    ]
  },

  renderHTML({ HTMLAttributes, node }) {
    const expression = (node.attrs as MergeTagAttributes).expression ?? ''
    return [
      'span',
      mergeAttributes(HTMLAttributes, {
        'data-merge-tag': '',
        class:
          'inline-block rounded bg-primary/15 px-1.5 py-0.5 font-mono text-[0.85em] text-primary',
        contenteditable: 'false',
      }),
      `{{${expression}}}`,
    ]
  },

  renderText({ node }) {
    return `{{${(node.attrs as MergeTagAttributes).expression}}}`
  },
})

/**
 * Pre-process HTML before loading it into the editor: replace any literal
 * `{{expression}}` substrings in text nodes with merge-tag markup so they
 * become {@link MergeTagNode}s in the editor schema.
 *
 * The substitution is conservative — only top-level text content is rewritten;
 * `{{` inside attributes or already-marked merge-tag spans is left alone.
 */
export function tokenizeMergeTags(html: string): string {
  const MERGE_RE = /\{\{\s*([^}]+?)\s*\}\}/g

  // Use the browser's DOMParser when available so we don't accidentally
  // rewrite attribute values. In SSR/test environments without a DOM we fall
  // back to a string regex; merge-tag-in-attribute is not a real concern for
  // our content (the backend forbids it via Jsoup sanitisation).
  if (typeof window !== 'undefined' && typeof window.DOMParser !== 'undefined') {
    const doc = new window.DOMParser().parseFromString(
      `<div id="__root">${html}</div>`,
      'text/html'
    )
    const root = doc.getElementById('__root')
    if (!root) return html

    const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, null)
    const targets: Text[] = []
    let cursor: globalThis.Node | null = walker.nextNode()
    while (cursor) {
      if (cursor instanceof Text && MERGE_RE.test(cursor.nodeValue ?? '')) {
        targets.push(cursor)
      }
      MERGE_RE.lastIndex = 0
      cursor = walker.nextNode()
    }

    for (const textNode of targets) {
      const parent = textNode.parentNode
      if (!parent) continue
      // Skip text inside an existing merge-tag span — leave as-is.
      if (parent instanceof Element && parent.hasAttribute('data-merge-tag')) {
        continue
      }
      const fragment = doc.createDocumentFragment()
      const text = textNode.nodeValue ?? ''
      let lastIndex = 0
      MERGE_RE.lastIndex = 0
      let match: RegExpExecArray | null
      while ((match = MERGE_RE.exec(text)) !== null) {
        if (match.index > lastIndex) {
          fragment.appendChild(doc.createTextNode(text.slice(lastIndex, match.index)))
        }
        const span = doc.createElement('span')
        span.setAttribute('data-merge-tag', '')
        span.setAttribute('data-expression', match[1].trim())
        span.textContent = `{{${match[1].trim()}}}`
        fragment.appendChild(span)
        lastIndex = match.index + match[0].length
      }
      if (lastIndex < text.length) {
        fragment.appendChild(doc.createTextNode(text.slice(lastIndex)))
      }
      parent.replaceChild(fragment, textNode)
    }
    return root.innerHTML
  }

  // Fallback: plain string replacement.
  return html.replace(MERGE_RE, (_m, expr) => {
    const e = String(expr).trim()
    return `<span data-merge-tag data-expression="${e}">{{${e}}}</span>`
  })
}
