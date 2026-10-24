/**
 * File type utility functions for attachment display.
 *
 * Pure utility module with no React imports. Provides MIME type
 * classification, icon mapping, and file size formatting.
 */

import type { LucideIcon } from 'lucide-react'
import { Image, FileText, Video, Music, Code, Table, Archive, File } from 'lucide-react'

/**
 * Category of a file based on its MIME type.
 */
type FileCategory =
  | 'image'
  | 'pdf'
  | 'video'
  | 'audio'
  | 'text'
  | 'code'
  | 'spreadsheet'
  | 'archive'
  | 'other'

/**
 * Information about a file type for display purposes.
 */
export interface FileTypeInfo {
  /** Broad category of the file */
  category: FileCategory
  /** Lucide icon component to render */
  icon: LucideIcon
  /** Tailwind color class for the icon */
  color: string
  /** Whether the file can be previewed inline in the browser */
  isPreviewable: boolean
}

/** MIME types that map to the 'code' category */
const CODE_TYPES = new Set([
  'text/html',
  'text/css',
  'text/javascript',
  'application/javascript',
  'application/json',
  'application/xml',
  'text/xml',
])

/** MIME types that map to the 'text' category */
const TEXT_TYPES = new Set(['text/plain', 'text/csv', 'text/markdown'])

/** MIME type prefixes/patterns that map to 'spreadsheet' */
const SPREADSHEET_TYPES = new Set([
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.template',
  'application/vnd.oasis.opendocument.spreadsheet',
])

/** MIME types that map to the 'archive' category */
const ARCHIVE_TYPES = new Set([
  'application/zip',
  'application/gzip',
  'application/x-tar',
  'application/x-7z-compressed',
  'application/x-rar-compressed',
  'application/x-bzip2',
])

/** Category to icon + color mapping */
const CATEGORY_DISPLAY: Record<FileCategory, { icon: LucideIcon; color: string }> = {
  image: { icon: Image, color: 'text-emerald-500' },
  pdf: { icon: FileText, color: 'text-red-500' },
  video: { icon: Video, color: 'text-purple-500' },
  audio: { icon: Music, color: 'text-orange-500' },
  text: { icon: FileText, color: 'text-gray-500' },
  code: { icon: Code, color: 'text-cyan-500' },
  spreadsheet: { icon: Table, color: 'text-green-600' },
  archive: { icon: Archive, color: 'text-yellow-600' },
  other: { icon: File, color: 'text-muted-foreground' },
}

/** Categories that support inline browser preview */
const PREVIEWABLE_CATEGORIES: Set<FileCategory> = new Set([
  'image',
  'pdf',
  'video',
  'audio',
  'text',
  'code',
])

/**
 * Normalize a MIME content type string by stripping parameters
 * (e.g., charset) and lowering case.
 */
function normalizeMimeType(contentType: string | null | undefined): string {
  if (!contentType) return ''
  return contentType.split(';')[0].trim().toLowerCase()
}

/**
 * Determine the category of a file from its MIME content type.
 */
function categorize(mimeType: string): FileCategory {
  if (!mimeType) return 'other'

  if (mimeType.startsWith('image/')) return 'image'
  if (mimeType === 'application/pdf') return 'pdf'
  if (mimeType.startsWith('video/')) return 'video'
  if (mimeType.startsWith('audio/')) return 'audio'
  if (TEXT_TYPES.has(mimeType)) return 'text'
  if (CODE_TYPES.has(mimeType)) return 'code'
  if (SPREADSHEET_TYPES.has(mimeType)) return 'spreadsheet'
  if (ARCHIVE_TYPES.has(mimeType)) return 'archive'

  return 'other'
}

/**
 * Get display information for a file based on its MIME content type.
 *
 * @param contentType - The MIME content type (e.g., "image/png", "application/pdf")
 * @returns Icon, color, category, and preview capability
 */
export function getFileTypeInfo(contentType: string | null | undefined): FileTypeInfo {
  const mime = normalizeMimeType(contentType)
  const category = categorize(mime)
  const display = CATEGORY_DISPLAY[category]
  return {
    category,
    icon: display.icon,
    color: display.color,
    isPreviewable: PREVIEWABLE_CATEGORIES.has(category),
  }
}

/**
 * Check if a content type represents an image.
 *
 * @param contentType - The MIME content type
 * @returns true if the content type is an image type
 */
export function isImageType(contentType: string | null | undefined): boolean {
  const mime = normalizeMimeType(contentType)
  return mime.startsWith('image/')
}

/**
 * Check if a content type can be previewed inline in the browser.
 *
 * @param contentType - The MIME content type
 * @returns true if the file can be rendered inline
 */
export function isPreviewable(contentType: string | null | undefined): boolean {
  const mime = normalizeMimeType(contentType)
  const category = categorize(mime)
  return PREVIEWABLE_CATEGORIES.has(category)
}

/**
 * Format a file size in bytes to a human-readable string.
 *
 * @param bytes - The file size in bytes
 * @returns Formatted string (e.g., "1.5 MB", "0 B")
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const k = 1024
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  const size = (bytes / Math.pow(k, i)).toFixed(i > 0 ? 1 : 0)
  return `${size} ${units[i]}`
}
