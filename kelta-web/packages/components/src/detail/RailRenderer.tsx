/**
 * RailRenderer — dispatches an ordered list of `RailBlockDto` items to the
 * appropriate per-kind component. Unknown kinds are silently skipped so
 * adding a new server-side kind doesn't crash older clients.
 */

import React from 'react';
import { MetadataCard } from './MetadataCard';
import { StatStrip } from './StatStrip';
import { ScoreCard, type ScoreCardConfig } from './ScoreCard';
import { TagsCard, type TagTone } from './TagsCard';
import { AICard } from './AICard';
import { Timeline, type TimelineEvent, type TimelineTone } from './Timeline';
import type { StatTileConfig } from './StatStrip';
import type { RailBlockDto } from './types';
import { toText } from '../utils/text';

export interface RailRendererProps {
  blocks: RailBlockDto[];
}

export function RailRenderer({ blocks }: RailRendererProps): React.ReactElement {
  return (
    <>
      {blocks.map((block, idx) => {
        const key = `${block.kind}-${idx}`;
        switch (block.kind) {
          case 'metadataCard':
            return <MetadataCard key={key} config={block.config} />;
          case 'statStrip':
            return (
              <StatStrip key={key} tiles={block.config.tiles as unknown as StatTileConfig[]} />
            );
          case 'scoreCard':
            return <ScoreCard key={key} config={block.config as unknown as ScoreCardConfig} />;
          case 'tagsCard':
            return (
              <TagsCard
                key={key}
                config={{
                  title: block.config.title,
                  tags: block.config.tags.map((t) => ({
                    label: t.label,
                    tone: (t.tone as TagTone | undefined) ?? 'default',
                  })),
                }}
              />
            );
          case 'aiCard':
            return (
              <AICard
                key={key}
                config={{
                  title: block.config.title,
                  summary: block.config.summary,
                  actions: (block.config.actions ?? []).map((a) => ({
                    label: a.label,
                    onClick: () => undefined,
                  })),
                }}
              />
            );
          case 'timeline':
            return (
              <Timeline
                key={key}
                config={{
                  title: block.config.title,
                  events: (block.config.events as unknown[]).map((e) => {
                    const ev = e as Record<string, unknown>;
                    return {
                      at: toText(ev.at ?? ''),
                      title: toText(ev.title ?? ''),
                      body: ev.body ? toText(ev.body) : undefined,
                      tone: (ev.tone as TimelineTone | undefined) ?? 'default',
                    } satisfies TimelineEvent;
                  }),
                }}
              />
            );
          default:
            return null;
        }
      })}
    </>
  );
}
