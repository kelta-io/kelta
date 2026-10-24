/**
 * Layout Editor Context
 *
 * Provides all state management for the WYSIWYG layout editor using
 * React Context + useReducer. Tracks layout sections, field placements,
 * related lists, selection state, drag-and-drop, undo/redo history,
 * and device preview mode.
 */

import React, { createContext, useContext, useReducer, useCallback, useMemo } from 'react'
import { uuid } from '@/utils/uuid'
import type { RailBlockDto, RecordHeaderConfigDto } from '@/hooks/usePageLayout'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface EditorFieldPlacement {
  id: string
  fieldId: string
  fieldName?: string
  fieldType?: string
  fieldDisplayName?: string
  columnNumber: number
  columnSpan?: number
  sortOrder: number
  requiredOnLayout: boolean
  readOnlyOnLayout: boolean
  labelOverride?: string
  helpTextOverride?: string
  visibilityRule?: string
}

export interface EditorSection {
  id: string
  heading: string
  columns: number
  sortOrder: number
  collapsed: boolean
  style: string
  sectionType: string
  tabGroup?: string
  tabLabel?: string
  visibilityRule?: string
  fields: EditorFieldPlacement[]
}

export interface EditorRelatedList {
  id: string
  relatedCollectionId: string
  relationshipFieldId: string
  displayColumns: string
  sortField?: string
  sortDirection: string
  rowLimit: number
  sortOrder: number
}

export interface AvailableField {
  id: string
  name: string
  displayName: string
  type: string
  required?: boolean
}

interface DragSource {
  type: 'palette-field' | 'canvas-field' | 'section'
  fieldId?: string
  fieldPlacementId?: string
  sectionId?: string
  sourceSectionId?: string
  sourceColumn?: number
}

export type PreviewDevice = 'desktop' | 'tablet' | 'mobile'

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

interface UndoableSnapshot {
  sections: EditorSection[]
  relatedLists: EditorRelatedList[]
}

interface LayoutEditorState {
  collectionId: string | null
  sections: EditorSection[]
  relatedLists: EditorRelatedList[]
  /** Detail-page record header overrides; null/undefined = auto-derive */
  headerConfig: RecordHeaderConfigDto | null
  /** Detail-page side-rail blocks; null/empty = auto-derive system info card */
  railBlocks: RailBlockDto[] | null
  availableFields: AvailableField[]
  placedFieldIds: Set<string>
  selectedSectionId: string | null
  selectedFieldPlacementId: string | null
  isDirty: boolean
  undoStack: UndoableSnapshot[]
  redoStack: UndoableSnapshot[]
  previewDevice: PreviewDevice
  dragSource: DragSource | null
}

const MAX_UNDO_STACK_SIZE = 50

const initialState: LayoutEditorState = {
  collectionId: null,
  sections: [],
  relatedLists: [],
  headerConfig: null,
  railBlocks: null,
  availableFields: [],
  placedFieldIds: new Set<string>(),
  selectedSectionId: null,
  selectedFieldPlacementId: null,
  isDirty: false,
  undoStack: [],
  redoStack: [],
  previewDevice: 'desktop',
  dragSource: null,
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

type LayoutEditorAction =
  | {
      type: 'SET_LAYOUT'
      payload: {
        collectionId: string
        sections: EditorSection[]
        relatedLists: EditorRelatedList[]
        headerConfig: RecordHeaderConfigDto | null
        railBlocks: RailBlockDto[] | null
      }
    }
  | { type: 'SET_HEADER_CONFIG'; payload: { headerConfig: RecordHeaderConfigDto | null } }
  | { type: 'SET_RAIL_BLOCKS'; payload: { railBlocks: RailBlockDto[] | null } }
  | { type: 'SET_AVAILABLE_FIELDS'; payload: { fields: AvailableField[] } }
  | { type: 'ADD_SECTION'; payload: { sectionType?: string } }
  | { type: 'REMOVE_SECTION'; payload: { sectionId: string } }
  | { type: 'UPDATE_SECTION'; payload: { sectionId: string; updates: Partial<EditorSection> } }
  | { type: 'MOVE_SECTION'; payload: { fromIndex: number; toIndex: number } }
  | {
      type: 'ADD_FIELD'
      payload: {
        fieldId: string
        fieldName: string
        fieldType: string
        fieldDisplayName: string
        sectionId: string
        columnNumber: number
        sortOrder: number
      }
    }
  | { type: 'REMOVE_FIELD'; payload: { fieldPlacementId: string } }
  | {
      type: 'MOVE_FIELD'
      payload: {
        fieldPlacementId: string
        targetSectionId: string
        targetColumn: number
        targetSortOrder: number
      }
    }
  | {
      type: 'UPDATE_FIELD_PLACEMENT'
      payload: { fieldPlacementId: string; updates: Partial<EditorFieldPlacement> }
    }
  | { type: 'ADD_RELATED_LIST'; payload: { relatedList: Omit<EditorRelatedList, 'id'> } }
  | { type: 'REMOVE_RELATED_LIST'; payload: { relatedListId: string } }
  | {
      type: 'UPDATE_RELATED_LIST'
      payload: { relatedListId: string; updates: Partial<EditorRelatedList> }
    }
  | { type: 'SELECT_SECTION'; payload: { sectionId: string | null } }
  | { type: 'SELECT_FIELD'; payload: { fieldPlacementId: string | null } }
  | { type: 'CLEAR_SELECTION' }
  | { type: 'SET_DRAG_SOURCE'; payload: { source: DragSource | null } }
  | { type: 'SET_PREVIEW_DEVICE'; payload: { device: PreviewDevice } }
  | { type: 'UNDO' }
  | { type: 'REDO' }
  | { type: 'MARK_SAVED' }

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Compute the set of field IDs that are currently placed in any section.
 */
function computePlacedFieldIds(sections: EditorSection[]): Set<string> {
  const ids = new Set<string>()
  for (const section of sections) {
    for (const field of section.fields) {
      ids.add(field.fieldId)
    }
  }
  return ids
}

/**
 * Push the current data-bearing state onto the undo stack and clear redo.
 * Returns partial state updates to merge.
 */
function pushUndo(state: LayoutEditorState): Pick<LayoutEditorState, 'undoStack' | 'redoStack'> {
  const snapshot: UndoableSnapshot = {
    sections: structuredClone(state.sections),
    relatedLists: structuredClone(state.relatedLists),
  }
  const undoStack = [...state.undoStack, snapshot]
  if (undoStack.length > MAX_UNDO_STACK_SIZE) {
    undoStack.shift()
  }
  return { undoStack, redoStack: [] }
}

// ---------------------------------------------------------------------------
// Reducer
// ---------------------------------------------------------------------------

function layoutEditorReducer(
  state: LayoutEditorState,
  action: LayoutEditorAction
): LayoutEditorState {
  switch (action.type) {
    // -- Initialization (no undo) ------------------------------------------

    case 'SET_LAYOUT': {
      const { collectionId, sections, relatedLists, headerConfig, railBlocks } = action.payload
      return {
        ...state,
        collectionId,
        sections,
        relatedLists,
        headerConfig,
        railBlocks,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: false,
        undoStack: [],
        redoStack: [],
        selectedSectionId: null,
        selectedFieldPlacementId: null,
      }
    }

    case 'SET_HEADER_CONFIG': {
      return {
        ...state,
        headerConfig: action.payload.headerConfig,
        isDirty: true,
      }
    }

    case 'SET_RAIL_BLOCKS': {
      return {
        ...state,
        railBlocks: action.payload.railBlocks,
        isDirty: true,
      }
    }

    case 'SET_AVAILABLE_FIELDS': {
      return {
        ...state,
        availableFields: action.payload.fields,
      }
    }

    // -- Sections -----------------------------------------------------------

    case 'ADD_SECTION': {
      const undo = pushUndo(state)
      const maxSortOrder = state.sections.reduce((max, s) => Math.max(max, s.sortOrder), -1)
      const newSection: EditorSection = {
        id: uuid(),
        heading: 'New Section',
        columns: 2,
        sortOrder: maxSortOrder + 1,
        collapsed: false,
        style: 'default',
        sectionType: action.payload.sectionType ?? 'fields',
        fields: [],
      }
      const sections = [...state.sections, newSection]
      return {
        ...state,
        sections,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: true,
        ...undo,
      }
    }

    case 'REMOVE_SECTION': {
      const undo = pushUndo(state)
      const sections = state.sections.filter((s) => s.id !== action.payload.sectionId)
      return {
        ...state,
        sections,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: true,
        selectedSectionId:
          state.selectedSectionId === action.payload.sectionId ? null : state.selectedSectionId,
        selectedFieldPlacementId: null,
        ...undo,
      }
    }

    case 'UPDATE_SECTION': {
      const undo = pushUndo(state)
      const sections = state.sections.map((s) =>
        s.id === action.payload.sectionId
          ? {
              ...s,
              ...action.payload.updates,
              id: s.id,
              fields: action.payload.updates.fields ?? s.fields,
            }
          : s
      )
      return {
        ...state,
        sections,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: true,
        ...undo,
      }
    }

    case 'MOVE_SECTION': {
      const { fromIndex, toIndex } = action.payload
      if (
        fromIndex < 0 ||
        fromIndex >= state.sections.length ||
        toIndex < 0 ||
        toIndex >= state.sections.length ||
        fromIndex === toIndex
      ) {
        return state
      }
      const undo = pushUndo(state)
      const sorted = [...state.sections].sort((a, b) => a.sortOrder - b.sortOrder)
      const [moved] = sorted.splice(fromIndex, 1)
      sorted.splice(toIndex, 0, moved)
      const sections = sorted.map((s, i) => ({ ...s, sortOrder: i }))
      return {
        ...state,
        sections,
        isDirty: true,
        ...undo,
      }
    }

    // -- Fields -------------------------------------------------------------

    case 'ADD_FIELD': {
      const {
        fieldId,
        fieldName,
        fieldType,
        fieldDisplayName,
        sectionId,
        columnNumber,
        sortOrder,
      } = action.payload
      const undo = pushUndo(state)
      const newPlacement: EditorFieldPlacement = {
        id: uuid(),
        fieldId,
        fieldName,
        fieldType,
        fieldDisplayName,
        columnNumber,
        sortOrder,
        requiredOnLayout: false,
        readOnlyOnLayout: false,
      }
      const sections = state.sections.map((s) => {
        if (s.id !== sectionId) return s
        // Insert and re-sort fields in the target column
        const updatedFields = [...s.fields, newPlacement].sort((a, b) => {
          if (a.columnNumber !== b.columnNumber) return a.columnNumber - b.columnNumber
          return a.sortOrder - b.sortOrder
        })
        return { ...s, fields: updatedFields }
      })
      return {
        ...state,
        sections,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: true,
        ...undo,
      }
    }

    case 'REMOVE_FIELD': {
      const undo = pushUndo(state)
      const sections = state.sections.map((s) => ({
        ...s,
        fields: s.fields.filter((f) => f.id !== action.payload.fieldPlacementId),
      }))
      return {
        ...state,
        sections,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: true,
        selectedFieldPlacementId:
          state.selectedFieldPlacementId === action.payload.fieldPlacementId
            ? null
            : state.selectedFieldPlacementId,
        ...undo,
      }
    }

    case 'MOVE_FIELD': {
      const { fieldPlacementId, targetSectionId, targetColumn, targetSortOrder } = action.payload
      const undo = pushUndo(state)

      // Find the field placement across all sections
      let movedField: EditorFieldPlacement | null = null
      for (const section of state.sections) {
        const found = section.fields.find((f) => f.id === fieldPlacementId)
        if (found) {
          movedField = { ...found }
          break
        }
      }
      if (!movedField) return state

      // Remove from source, add to target
      const sections = state.sections.map((s) => {
        // Remove from this section if present
        const withoutMoved = s.fields.filter((f) => f.id !== fieldPlacementId)

        if (s.id === targetSectionId) {
          // Add the moved field with updated position
          const updated: EditorFieldPlacement = {
            ...movedField!,
            columnNumber: targetColumn,
            sortOrder: targetSortOrder,
          }
          const newFields = [...withoutMoved, updated].sort((a, b) => {
            if (a.columnNumber !== b.columnNumber) return a.columnNumber - b.columnNumber
            return a.sortOrder - b.sortOrder
          })
          return { ...s, fields: newFields }
        }

        return { ...s, fields: withoutMoved }
      })

      return {
        ...state,
        sections,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: true,
        ...undo,
      }
    }

    case 'UPDATE_FIELD_PLACEMENT': {
      const undo = pushUndo(state)
      const sections = state.sections.map((s) => ({
        ...s,
        fields: s.fields.map((f) =>
          f.id === action.payload.fieldPlacementId
            ? { ...f, ...action.payload.updates, id: f.id }
            : f
        ),
      }))
      return {
        ...state,
        sections,
        placedFieldIds: computePlacedFieldIds(sections),
        isDirty: true,
        ...undo,
      }
    }

    // -- Related Lists ------------------------------------------------------

    case 'ADD_RELATED_LIST': {
      const undo = pushUndo(state)
      const newRelatedList: EditorRelatedList = {
        id: uuid(),
        ...action.payload.relatedList,
      }
      return {
        ...state,
        relatedLists: [...state.relatedLists, newRelatedList],
        isDirty: true,
        ...undo,
      }
    }

    case 'REMOVE_RELATED_LIST': {
      const undo = pushUndo(state)
      return {
        ...state,
        relatedLists: state.relatedLists.filter((r) => r.id !== action.payload.relatedListId),
        isDirty: true,
        ...undo,
      }
    }

    case 'UPDATE_RELATED_LIST': {
      const undo = pushUndo(state)
      return {
        ...state,
        relatedLists: state.relatedLists.map((r) =>
          r.id === action.payload.relatedListId ? { ...r, ...action.payload.updates, id: r.id } : r
        ),
        isDirty: true,
        ...undo,
      }
    }

    // -- Selection ----------------------------------------------------------

    case 'SELECT_SECTION': {
      return {
        ...state,
        selectedSectionId: action.payload.sectionId,
        selectedFieldPlacementId: null,
      }
    }

    case 'SELECT_FIELD': {
      return {
        ...state,
        selectedFieldPlacementId: action.payload.fieldPlacementId,
        selectedSectionId: null,
      }
    }

    case 'CLEAR_SELECTION': {
      return {
        ...state,
        selectedSectionId: null,
        selectedFieldPlacementId: null,
      }
    }

    // -- Drag & Drop --------------------------------------------------------

    case 'SET_DRAG_SOURCE': {
      return {
        ...state,
        dragSource: action.payload.source,
      }
    }

    // -- Preview ------------------------------------------------------------

    case 'SET_PREVIEW_DEVICE': {
      return {
        ...state,
        previewDevice: action.payload.device,
      }
    }

    // -- Undo / Redo --------------------------------------------------------

    case 'UNDO': {
      if (state.undoStack.length === 0) return state
      const undoStack = [...state.undoStack]
      const snapshot = undoStack.pop()!
      const redoStack: UndoableSnapshot[] = [
        ...state.redoStack,
        {
          sections: structuredClone(state.sections),
          relatedLists: structuredClone(state.relatedLists),
        },
      ]
      return {
        ...state,
        sections: snapshot.sections,
        relatedLists: snapshot.relatedLists,
        placedFieldIds: computePlacedFieldIds(snapshot.sections),
        isDirty: true,
        undoStack,
        redoStack,
        selectedSectionId: null,
        selectedFieldPlacementId: null,
      }
    }

    case 'REDO': {
      if (state.redoStack.length === 0) return state
      const redoStack = [...state.redoStack]
      const snapshot = redoStack.pop()!
      const undoStack: UndoableSnapshot[] = [
        ...state.undoStack,
        {
          sections: structuredClone(state.sections),
          relatedLists: structuredClone(state.relatedLists),
        },
      ]
      return {
        ...state,
        sections: snapshot.sections,
        relatedLists: snapshot.relatedLists,
        placedFieldIds: computePlacedFieldIds(snapshot.sections),
        isDirty: true,
        undoStack,
        redoStack,
        selectedSectionId: null,
        selectedFieldPlacementId: null,
      }
    }

    // -- Save ---------------------------------------------------------------

    case 'MARK_SAVED': {
      return {
        ...state,
        isDirty: false,
        undoStack: [],
        redoStack: [],
      }
    }

    default: {
      return state
    }
  }
}

// ---------------------------------------------------------------------------
// Context value interface
// ---------------------------------------------------------------------------

export interface LayoutEditorContextValue {
  state: LayoutEditorState
  addSection: (sectionType?: string) => void
  removeSection: (sectionId: string) => void
  updateSection: (sectionId: string, updates: Partial<EditorSection>) => void
  moveSection: (fromIndex: number, toIndex: number) => void
  addField: (
    fieldId: string,
    fieldName: string,
    fieldType: string,
    fieldDisplayName: string,
    sectionId: string,
    columnNumber: number,
    sortOrder: number
  ) => void
  removeField: (fieldPlacementId: string) => void
  moveField: (
    fieldPlacementId: string,
    targetSectionId: string,
    targetColumn: number,
    targetSortOrder: number
  ) => void
  updateFieldPlacement: (fieldPlacementId: string, updates: Partial<EditorFieldPlacement>) => void
  addRelatedList: (relatedList: Omit<EditorRelatedList, 'id'>) => void
  removeRelatedList: (relatedListId: string) => void
  updateRelatedList: (relatedListId: string, updates: Partial<EditorRelatedList>) => void
  selectSection: (sectionId: string | null) => void
  selectField: (fieldPlacementId: string | null) => void
  clearSelection: () => void
  setDragSource: (source: DragSource | null) => void
  setPreviewDevice: (device: PreviewDevice) => void
  setLayout: (
    collectionId: string,
    sections: EditorSection[],
    relatedLists: EditorRelatedList[],
    headerConfig?: RecordHeaderConfigDto | null,
    railBlocks?: RailBlockDto[] | null
  ) => void
  setAvailableFields: (fields: AvailableField[]) => void
  setHeaderConfig: (headerConfig: RecordHeaderConfigDto | null) => void
  setRailBlocks: (railBlocks: RailBlockDto[] | null) => void
  undo: () => void
  redo: () => void
  markSaved: () => void
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

const LayoutEditorContext = createContext<LayoutEditorContextValue | undefined>(undefined)

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

export interface LayoutEditorProviderProps {
  children: React.ReactNode
}

/**
 * Layout Editor Provider
 *
 * Wraps the layout editor UI to provide all editing state and action dispatchers
 * through React Context. Uses useReducer for predictable state transitions with
 * undo/redo support.
 */
export function LayoutEditorProvider({ children }: LayoutEditorProviderProps): React.ReactElement {
  const [state, dispatch] = useReducer(layoutEditorReducer, initialState)

  const addSection = useCallback((sectionType?: string) => {
    dispatch({ type: 'ADD_SECTION', payload: { sectionType } })
  }, [])

  const removeSection = useCallback((sectionId: string) => {
    dispatch({ type: 'REMOVE_SECTION', payload: { sectionId } })
  }, [])

  const updateSection = useCallback((sectionId: string, updates: Partial<EditorSection>) => {
    dispatch({ type: 'UPDATE_SECTION', payload: { sectionId, updates } })
  }, [])

  const moveSection = useCallback((fromIndex: number, toIndex: number) => {
    dispatch({ type: 'MOVE_SECTION', payload: { fromIndex, toIndex } })
  }, [])

  const addField = useCallback(
    (
      fieldId: string,
      fieldName: string,
      fieldType: string,
      fieldDisplayName: string,
      sectionId: string,
      columnNumber: number,
      sortOrder: number
    ) => {
      dispatch({
        type: 'ADD_FIELD',
        payload: {
          fieldId,
          fieldName,
          fieldType,
          fieldDisplayName,
          sectionId,
          columnNumber,
          sortOrder,
        },
      })
    },
    []
  )

  const removeField = useCallback((fieldPlacementId: string) => {
    dispatch({ type: 'REMOVE_FIELD', payload: { fieldPlacementId } })
  }, [])

  const moveField = useCallback(
    (
      fieldPlacementId: string,
      targetSectionId: string,
      targetColumn: number,
      targetSortOrder: number
    ) => {
      dispatch({
        type: 'MOVE_FIELD',
        payload: { fieldPlacementId, targetSectionId, targetColumn, targetSortOrder },
      })
    },
    []
  )

  const updateFieldPlacement = useCallback(
    (fieldPlacementId: string, updates: Partial<EditorFieldPlacement>) => {
      dispatch({
        type: 'UPDATE_FIELD_PLACEMENT',
        payload: { fieldPlacementId, updates },
      })
    },
    []
  )

  const addRelatedList = useCallback((relatedList: Omit<EditorRelatedList, 'id'>) => {
    dispatch({ type: 'ADD_RELATED_LIST', payload: { relatedList } })
  }, [])

  const removeRelatedList = useCallback((relatedListId: string) => {
    dispatch({ type: 'REMOVE_RELATED_LIST', payload: { relatedListId } })
  }, [])

  const updateRelatedList = useCallback(
    (relatedListId: string, updates: Partial<EditorRelatedList>) => {
      dispatch({ type: 'UPDATE_RELATED_LIST', payload: { relatedListId, updates } })
    },
    []
  )

  const selectSection = useCallback((sectionId: string | null) => {
    dispatch({ type: 'SELECT_SECTION', payload: { sectionId } })
  }, [])

  const selectField = useCallback((fieldPlacementId: string | null) => {
    dispatch({ type: 'SELECT_FIELD', payload: { fieldPlacementId } })
  }, [])

  const clearSelection = useCallback(() => {
    dispatch({ type: 'CLEAR_SELECTION' })
  }, [])

  const setDragSource = useCallback((source: DragSource | null) => {
    dispatch({ type: 'SET_DRAG_SOURCE', payload: { source } })
  }, [])

  const setPreviewDevice = useCallback((device: PreviewDevice) => {
    dispatch({ type: 'SET_PREVIEW_DEVICE', payload: { device } })
  }, [])

  const setLayout = useCallback(
    (
      collectionId: string,
      sections: EditorSection[],
      relatedLists: EditorRelatedList[],
      headerConfig: RecordHeaderConfigDto | null = null,
      railBlocks: RailBlockDto[] | null = null
    ) => {
      dispatch({
        type: 'SET_LAYOUT',
        payload: { collectionId, sections, relatedLists, headerConfig, railBlocks },
      })
    },
    []
  )

  const setAvailableFields = useCallback((fields: AvailableField[]) => {
    dispatch({ type: 'SET_AVAILABLE_FIELDS', payload: { fields } })
  }, [])

  const setHeaderConfig = useCallback((headerConfig: RecordHeaderConfigDto | null) => {
    dispatch({ type: 'SET_HEADER_CONFIG', payload: { headerConfig } })
  }, [])

  const setRailBlocks = useCallback((railBlocks: RailBlockDto[] | null) => {
    dispatch({ type: 'SET_RAIL_BLOCKS', payload: { railBlocks } })
  }, [])

  const undo = useCallback(() => {
    dispatch({ type: 'UNDO' })
  }, [])

  const redo = useCallback(() => {
    dispatch({ type: 'REDO' })
  }, [])

  const markSaved = useCallback(() => {
    dispatch({ type: 'MARK_SAVED' })
  }, [])

  const contextValue = useMemo<LayoutEditorContextValue>(
    () => ({
      state,
      addSection,
      removeSection,
      updateSection,
      moveSection,
      addField,
      removeField,
      moveField,
      updateFieldPlacement,
      addRelatedList,
      removeRelatedList,
      updateRelatedList,
      selectSection,
      selectField,
      clearSelection,
      setDragSource,
      setPreviewDevice,
      setLayout,
      setAvailableFields,
      setHeaderConfig,
      setRailBlocks,
      undo,
      redo,
      markSaved,
    }),
    [
      state,
      addSection,
      removeSection,
      updateSection,
      moveSection,
      addField,
      removeField,
      moveField,
      updateFieldPlacement,
      addRelatedList,
      removeRelatedList,
      updateRelatedList,
      selectSection,
      selectField,
      clearSelection,
      setDragSource,
      setPreviewDevice,
      setLayout,
      setAvailableFields,
      setHeaderConfig,
      setRailBlocks,
      undo,
      redo,
      markSaved,
    ]
  )

  return (
    <LayoutEditorContext.Provider value={contextValue}>{children}</LayoutEditorContext.Provider>
  )
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

/**
 * Hook to access the layout editor context.
 *
 * @throws Error if used outside of LayoutEditorProvider
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useLayoutEditor(): LayoutEditorContextValue {
  const context = useContext(LayoutEditorContext)
  if (context === undefined) {
    throw new Error('useLayoutEditor must be used within a LayoutEditorProvider')
  }
  return context
}
