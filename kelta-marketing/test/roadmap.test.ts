import { describe, expect, it } from 'vitest';
import { roadmap } from '../src/data/roadmap';

const FORBIDDEN = /pricing|\$\d|\bplans?\b|\btiers?\b|launch(ing|ed)?\b|announc/i;

describe('roadmap data', () => {
  it('has an ISO last-updated date', () => {
    expect(roadmap.updated).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('has three columns, each with at least one item', () => {
    expect(roadmap.columns).toHaveLength(3);
    for (const column of roadmap.columns) {
      expect(column.items.length, column.title).toBeGreaterThan(0);
    }
  });

  it('names no pricing, plans, tiers, launches or announcements', () => {
    for (const item of roadmap.columns.flatMap((column) => column.items)) {
      expect(item).not.toMatch(FORBIDDEN);
    }
  });
});
