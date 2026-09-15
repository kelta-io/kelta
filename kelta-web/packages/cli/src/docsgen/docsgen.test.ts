// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { allCommands } from '../registry/registry.js';
import { buildManifest } from '../registry/manifest.js';
import { renderAgentsMd, renderCommandsMd } from './render.js';
import { AGENT_GUIDE } from './agentGuide.js';
import { AUTHORING_DOCS } from './authoringDocs.js';

function committed(name: string): string {
  return readFileSync(fileURLToPath(new URL(`../../${name}`, import.meta.url)), 'utf-8');
}

/** docs/authoring/*.md lives at the repo root — five levels up from here. */
function repoRoot(relativePath: string): string {
  return readFileSync(
    fileURLToPath(new URL(`../../../../../${relativePath}`, import.meta.url)),
    'utf-8'
  );
}

describe('generated docs freshness', () => {
  it('AGENTS.md matches the source guide — run `npm run gen:docs` when this fails', () => {
    expect(committed('AGENTS.md')).toBe(renderAgentsMd());
  });

  it('COMMANDS.md matches the registry — run `npm run gen:docs` when this fails', () => {
    expect(committed('COMMANDS.md')).toBe(renderCommandsMd(buildManifest(allCommands)));
  });

  it.each(Object.keys(AUTHORING_DOCS))(
    'authoringDocs.ts["%s"] matches docs/authoring/%s.md — keep the embedded copy in sync',
    (topic) => {
      expect(AUTHORING_DOCS[topic]).toBe(repoRoot(`docs/authoring/${topic}.md`));
    }
  );
});

describe('AGENT_GUIDE operator vocabulary', () => {
  it('lists every FilterOperator plus the `any` alias — keep in sync with runtime-core', () => {
    const filterOperatorSource = repoRoot(
      'kelta-platform/runtime/runtime-core/src/main/java/io/kelta/runtime/query/FilterOperator.java'
    );
    const enumBody = filterOperatorSource.slice(
      filterOperatorSource.indexOf('public enum FilterOperator'),
      filterOperatorSource.indexOf('private static final Map')
    );
    const operatorNames = [...enumBody.matchAll(/^\s*([A-Z][A-Z_]*)[,;]\s*$/gm)].map((m) =>
      m[1].toLowerCase()
    );
    expect(operatorNames.length).toBeGreaterThan(0);
    for (const op of [...operatorNames, 'any']) {
      expect(AGENT_GUIDE, `AGENT_GUIDE should mention operator "${op}"`).toContain(op);
    }
  });
});
