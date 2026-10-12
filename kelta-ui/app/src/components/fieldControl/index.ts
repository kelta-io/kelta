/**
 * FieldControl registry — the single source of truth for how a field type renders in view, edit,
 * and inline-edit modes. See `.claude/docs/specs/unified-record/1-field-control-registry.md`.
 *
 * Lives in `kelta-ui/app`, co-located with the reused controls. It moves to `@kelta/components` only
 * when a plugin or module UI bundle needs it (conventions.md → Component reuse, "Extract on need").
 */
export type {
  FieldControl,
  FieldControlContext,
  FieldViewProps,
  FieldEditProps,
  FieldInlineProps,
  PartialFieldControl,
} from './types'
export { getFieldControl, registerFieldControl, resetFieldControls } from './registry'
export { CONTROLS } from './controls'
export { FieldControlView } from './shared'
