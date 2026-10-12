/**
 * Public surface of the standalone typed-input widgets (slice 2f). `inputWidgets` is the descriptor
 * array consumed by `registerBuiltins`.
 */
export { inputDescriptors as inputWidgets } from './descriptors'

export { TextInput } from './TextInput'
export { NumberInput } from './NumberInput'
export { CheckboxInput } from './CheckboxInput'
export { DropdownInput } from './DropdownInput'
export { DatePickerInput } from './DatePickerInput'
export { LookupInput } from './LookupInput'
export { MultiPicklistInput } from './MultiPicklistInput'
export { RichTextInput } from './RichTextInput'

export { useFieldDef } from './useFieldDef'
export { usePicklistOptions, resolvePicklistSource } from '@/hooks/usePicklistOptions'
export { useLookupOptions } from './useLookupOptions'
export type { InputWidgetProps, InputControlProps } from './types'
