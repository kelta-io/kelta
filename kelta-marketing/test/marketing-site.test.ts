import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const repoRoot = join(import.meta.dirname, '..', '..');
const marketingRoot = join(repoRoot, 'kelta-marketing');
const read = (...parts: string[]) => readFileSync(join(repoRoot, ...parts), 'utf8');

describe('public routes', () => {
  // CHARTER.md §2 makes publishing pricing a red-zone [DECISION]; none has been
  // made, so the marketing site must not ship a /pricing route or link to one.
  it('has no pricing page', () => {
    const pages = readdirSync(join(marketingRoot, 'src', 'pages'));
    expect(pages).not.toContain('pricing.astro');
  });

  it('links to no pricing route', () => {
    const sources = readdirSync(join(marketingRoot, 'src'), { recursive: true, withFileTypes: true })
      .filter((entry) => entry.isFile())
      .map((entry) => readFileSync(join(entry.parentPath, entry.name), 'utf8'));

    expect(sources.filter((source) => source.includes('/pricing'))).toEqual([]);
  });

  it('serves a real 404 for unknown paths instead of falling back to the home page', () => {
    expect(existsSync(join(marketingRoot, 'src', 'pages', '404.astro'))).toBe(true);

    const nginx = read('kelta-marketing', 'nginx.conf');
    expect(nginx).toContain('try_files $uri $uri/ $uri.html =404;');
    expect(nginx).toContain('error_page 404 /404.html;');
    expect(nginx).not.toContain('$uri.html /index.html');
  });
});

describe('deploy wiring', () => {
  it('is built and pushed to Harbor by the Build and Deploy workflow', () => {
    const workflow = read('.github', 'workflows', 'build-and-publish-containers.yml');
    expect(workflow).toContain('kelta-marketing/Dockerfile');
    expect(read('.github', 'path-filters.yml')).toContain("- 'kelta-marketing/**'");
  });

  it('runs the Harbor image, not the never-built ghcr one', () => {
    const manifests = read('kelta-marketing', 'k8s', 'deployment.yaml');
    expect(manifests).toContain('image: harbor.rzware.com/emf/emf-marketing:latest');
    expect(manifests).not.toContain('ghcr.io');
  });
});
