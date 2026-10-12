/**
 * Detail-page block types — duplicated from `@/hooks/usePageLayout` in
 * kelta-ui/app so this package doesn't take a hard dependency on the
 * consumer's hook surface. The consumer's types are structurally compatible
 * (same shape, same `kind` discriminants).
 */

/**
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export interface RecordHeaderConfigDto {
  titleFields?: string[];
  avatarFrom?: string[];
  metaFields?: Array<{ key: string; icon?: string; prefix?: string }>;
}

export type RailBlockDto =
  | {
      kind: 'metadataCard';
      config: {
        title: string;
        rows: Array<{ label: string; value: string; mono?: boolean }>;
      };
    }
  | { kind: 'statStrip'; config: { tiles: Array<Record<string, unknown>> } }
  | { kind: 'scoreCard'; config: Record<string, unknown> }
  | {
      kind: 'tagsCard';
      config: { title: string; tags: Array<{ label: string; tone?: string }> };
    }
  | {
      kind: 'aiCard';
      config: { title: string; summary: string; actions?: Array<{ label: string }> };
    }
  | {
      kind: 'timeline';
      config: { title: string; events: Array<Record<string, unknown>> };
    };
