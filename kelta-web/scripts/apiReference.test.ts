// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  PACKAGES,
  REPO_ROOT,
  createApiProgram,
  pagePath,
  renderApiReference,
} from './gen-api-reference.mjs';

describe('generated API reference freshness', () => {
  const program = createApiProgram();

  it.each(PACKAGES.map((pkg) => [pagePath(pkg), pkg] as const))(
    '%s matches the package exports — run `npm run gen:api-reference` when this fails',
    (path, pkg) => {
      expect(readFileSync(join(REPO_ROOT, path), 'utf-8')).toBe(renderApiReference(program, pkg));
    }
  );

  it('lists every export of each entry point under its own heading', () => {
    const checker = program.getTypeChecker();
    for (const pkg of PACKAGES) {
      const source = program.getSourceFile(
        join(REPO_ROOT, 'kelta-web/packages', pkg.dir, 'src/index.ts')
      );
      const exported = checker.getExportsOfModule(checker.getSymbolAtLocation(source!)!);
      const page = renderApiReference(program, pkg);
      expect(exported.length).toBeGreaterThan(0);
      for (const symbol of exported) {
        expect(page, `${pkg.name} export ${symbol.name}`).toContain(`\n### ${symbol.name}\n`);
      }
    }
  });

  it('carries the GENERATED header and no checkout path, import() qualifier or date', () => {
    for (const pkg of PACKAGES) {
      const page = renderApiReference(program, pkg);
      expect(page.startsWith('<!-- GENERATED ')).toBe(true);
      expect(page).toContain('do not edit by hand');
      expect(page).not.toContain(REPO_ROOT);
      expect(page).not.toMatch(/import\("/);
      expect(page).not.toMatch(/\b20\d\d-\d\d-\d\d\b/);
    }
  });

  it('renders the first doc-comment paragraph and the signature of a documented export', () => {
    const page = renderApiReference(program, PACKAGES.find((p) => p.dir === 'plugin-sdk')!);
    expect(page).toContain(
      '### ComponentRegistry\n\nRegistry for custom field renderers and page components\n\n```ts\nclass ComponentRegistry {'
    );
  });
});
