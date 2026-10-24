/**
 * Detail-page components. All live in `@kelta/components` now — this
 * barrel re-exports them so existing imports through `@/components/detail`
 * keep working.
 */

export {
  RecordHeader,
  FieldSection,
  MetadataCard,
  Crumb,
  RailRenderer,
  // DropdownMenu primitives re-exported so RecordHeader's `moreMenu`
  // slot is composed from the SAME instance RecordHeader renders
  // (mixing the app's local radix primitives splits the radix context
  // and the menu items never become interactive).
  DropdownMenuItem,
  DropdownMenuSeparator,
} from '@kelta/components'
export type {
  RecordHeaderMetaField,
  RecordHeaderAction,
  FieldSectionRenderContext,
  MetadataRow,
} from '@kelta/components'
