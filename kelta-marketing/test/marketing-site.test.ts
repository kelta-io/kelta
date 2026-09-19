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

  // PLT-278: the manifest KLT-268 shipped named a namespace and workload the
  // cluster does not have, so ArgoCD never created anything and kelta.io answered
  // the ingress default backend for a day. These three guards pin the cluster
  // contract that was verified against the live cluster; see the manifest header.
  it('deploys into the emf namespace under the emf-<service> convention', () => {
    const manifests = read('kelta-marketing', 'k8s', 'deployment.yaml');
    const namespaces = [...manifests.matchAll(/^\s*namespace: (\S+)$/gm)].map((m) => m[1]);
    expect(namespaces).toHaveLength(3); // Deployment + Service + Ingress
    expect(new Set(namespaces)).toEqual(new Set(['emf']));

    // Every object, container, and backend is named emf-marketing. The one
    // other `name:` in the file is the imagePullSecrets reference, which names
    // a Secret, not a workload — it has its own guard below.
    const names = [...manifests.matchAll(/^\s*-? ?name: (\S+)$/gm)]
      .map((m) => m[1])
      .filter((n) => n !== 'harbor-registry');
    expect(names).not.toContain('kelta-marketing');
    expect(new Set(names)).toEqual(new Set(['emf-marketing']));
  });

  // The emf Harbor project is private. Without this the kubelet gets "pull
  // access denied" and the site sits in ImagePullBackOff even though CI's
  // authenticated verify_image_exists passed — which is exactly what happened
  // from first deploy until 2026-09-19.
  it('pulls from Harbor with the harbor-registry secret like every other emf-* Deployment', () => {
    const manifests = read('kelta-marketing', 'k8s', 'deployment.yaml');
    expect(manifests).toMatch(/^\s*imagePullSecrets:\n\s*- name: harbor-registry$/m);
  });

  // Traefik's websecure entrypoint does not enable TLS by default and, in v3,
  // the Ingress provider sets router TLS only from this annotation. Without it
  // the router is plaintext, never matches an HTTPS request, and kelta.io
  // answers Traefik's 404.
  it('opts the Ingress router into TLS and serves the existing emf-tls wildcard', () => {
    const manifests = read('kelta-marketing', 'k8s', 'deployment.yaml');
    expect(manifests).toMatch(/^\s*traefik\.ingress\.kubernetes\.io\/router\.tls: "true"$/m);
    expect(manifests).toMatch(/^\s*secretName: emf-tls$/m);
  });

  it('targets Traefik: no nginx ingress class and no nginx-only annotations', () => {
    const manifests = read('kelta-marketing', 'k8s', 'deployment.yaml');
    expect(manifests).not.toMatch(/^\s*ingressClassName:/m);
    expect(manifests).not.toMatch(/^\s*nginx\.ingress\.kubernetes\.io\//m);
  });

  it('leaves TLS to the wildcard certificate Traefik already serves', () => {
    const manifests = read('kelta-marketing', 'k8s', 'deployment.yaml');
    expect(manifests).not.toMatch(/^\s*cert-manager\.io\/cluster-issuer:/m);
    expect(manifests).not.toMatch(/^\s*secretName: kelta-io-tls$/m);
  });

  it('smoke-fails instead of skipping when the marketing Deployment is absent', () => {
    const workflow = read('.github', 'workflows', 'build-and-publish-containers.yml');
    expect(workflow).toContain('DEPLOY=emf-marketing');
    expect(workflow).not.toContain('not deployed in ${NS} yet (argo sync pending)');
  });
});
