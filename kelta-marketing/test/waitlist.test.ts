import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';

const marketingRoot = join(import.meta.dirname, '..');
const page = readFileSync(join(marketingRoot, 'src', 'pages', 'waitlist.astro'), 'utf8');

const WAITLIST_ENDPOINT = 'https://api.kelta.io/rzware/api/kelta-waitlist';
const CONTACT_EMAIL = 'cklinker@rzware.com';
const NO_PRICING_SENTENCE = 'Kelta Cloud has no pricing yet.';

// kelta.io has no MX record, so a mailto: capture bounced every signup (KLT-482).
describe('waitlist page source', () => {
  it('builds no mailto: link and names only the published contact address', () => {
    expect(page).not.toContain('mailto:');
    expect(page.match(/[\w.+-]+@[\w-]+\.[\w.]+/g)).toEqual([CONTACT_EMAIL]);
    expect(readFileSync(join(marketingRoot, '..', 'SECURITY.md'), 'utf8')).toContain(CONTACT_EMAIL);
  });

  it('states under the form what is kept, why, how to be removed and that there is no pricing', () => {
    const [whenSet] = page.split(') : (');
    const privacy = whenSet.match(/<p id="waitlist-privacy"[^>]*>([\s\S]*?)<\/p>/)?.[1].replace(/\s+/g, ' ');
    expect(whenSet.indexOf('id="waitlist-privacy"')).toBeGreaterThan(whenSet.indexOf('</form>'));
    expect(privacy).toContain('We keep your email, where you signed up and when');
    expect(privacy).toContain('only to send you one email when Kelta Cloud opens');
    expect(privacy).toContain('reply to that email or write to {CONTACT_EMAIL}');
    expect(privacy).toContain(NO_PRICING_SENTENCE);
    expect(page).toContain(`const CONTACT_EMAIL = '${CONTACT_EMAIL}';`);
  });

  it('is built for production with the kelta.io capture endpoint', () => {
    const dockerfile = readFileSync(join(marketingRoot, 'Dockerfile'), 'utf8');
    expect(dockerfile).toContain(`ARG PUBLIC_WAITLIST_ENDPOINT=${WAITLIST_ENDPOINT}\n`);
    expect(dockerfile).toContain('ENV PUBLIC_WAITLIST_ENDPOINT=$PUBLIC_WAITLIST_ENDPOINT');
    // The workflow passes no override, so the Dockerfile default is what ships.
    const workflow = readFileSync(join(marketingRoot, '..', '.github', 'workflows', 'build-and-publish-containers.yml'), 'utf8');
    expect(workflow).not.toContain('PUBLIC_WAITLIST_ENDPOINT');
  });

  it('reads its capture destination from PUBLIC_WAITLIST_ENDPOINT', () => {
    expect(page).toContain('import.meta.env.PUBLIC_WAITLIST_ENDPOINT');
  });

  it('has a honeypot input', () => {
    expect(page).toMatch(/<input id="waitlist-website" name="website" type="text" tabindex="-1" autocomplete="off" \/>/);
  });

  it('renders the form only when an endpoint is set, and links GitHub releases otherwise', () => {
    const [whenSet, whenEmpty] = page.split(') : (');
    expect(whenSet).toContain('{endpoint ? (');
    expect(whenSet).toContain('<form id="waitlist-form"');
    expect(whenEmpty).not.toContain('<form');
    expect(page).toContain("const RELEASES_URL = 'https://github.com/kelta-io/kelta/releases';");
    expect(whenEmpty).toContain('href={RELEASES_URL}');
    expect(whenEmpty).toContain('not open yet');
  });

  // CHARTER.md §2: publishing pricing is a red-zone [DECISION] nobody has made.
  it.each([/pric(e|ing)/i, /\/mo(nth)?\b/, /\$\d/, /\bplans?\b/i, /\btiers?\b/i])('contains no pricing text (%s)', (pattern) => {
    expect(page.replace(NO_PRICING_SENTENCE, '')).not.toMatch(pattern);
  });
});

/** Runs the page's client script the way Astro's define:vars does: as a function of the vars. */
function runClientScript(endpoint: string, fields: { email: string; website?: string }) {
  const script = page.match(/<script define:vars=\{\{ endpoint \}\}>([\s\S]*?)<\/script>/)?.[1];
  expect(script).toBeDefined();

  let submit: ((event: { preventDefault: () => void }) => Promise<void>) | undefined;
  const status = { textContent: '', classList: { toggle: vi.fn() } };
  const elements: Record<string, unknown> = {
    'waitlist-form': {
      addEventListener: (_: string, handler: typeof submit) => (submit = handler),
      reset: vi.fn(),
    },
    'waitlist-email': { value: fields.email, checkValidity: () => /^\S+@\S+\.\S+$/.test(fields.email) },
    'waitlist-website': { value: fields.website ?? '' },
    'waitlist-status': status,
  };
  const document = { getElementById: (id: string) => elements[id] };
  const window = { location: { href: 'https://kelta.io/waitlist/' } };
  const fetch = vi.fn(async () => ({ ok: true }));

  new Function('endpoint', 'document', 'window', 'fetch', script!)(endpoint, document, window, fetch);
  return { fetch, status, submit: () => submit!({ preventDefault: () => {} }) };
}

describe('waitlist client script', () => {
  const endpoint = 'https://tenant.example/api/waitlist-signups';
  const signup = ['who', 'example.com'].join('@');

  it('sends one JSON:API POST typed by the endpoint’s last path segment', async () => {
    const { fetch, status, submit } = runClientScript(endpoint, { email: signup });
    await submit();

    expect(fetch).toHaveBeenCalledTimes(1);
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(endpoint);
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/vnd.api+json');
    expect(JSON.parse(init.body as string)).toEqual({
      data: { type: 'waitlist-signups', attributes: { email: signup, source: 'kelta.io/waitlist' } },
    });
    expect(status.textContent).toMatch(/on the list/);
  });

  it('ignores a trailing slash when deriving the type', async () => {
    const { fetch, submit } = runClientScript(`${endpoint}/`, { email: signup });
    await submit();

    const [, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(JSON.parse(init.body as string).data.type).toBe('waitlist-signups');
  });

  it('skips the request but shows success when the honeypot is filled', async () => {
    const { fetch, status, submit } = runClientScript(endpoint, { email: signup, website: 'spam' });
    await submit();

    expect(fetch).not.toHaveBeenCalled();
    expect(status.textContent).toMatch(/on the list/);
  });

  it('shows a failure naming no address on a non-2xx response', async () => {
    const run = runClientScript(endpoint, { email: signup });
    run.fetch.mockResolvedValueOnce({ ok: false });
    await run.submit();

    expect(run.status.textContent).toMatch(/not saved/);
    expect(run.status.textContent).not.toContain('@');
  });

  it('sends nothing for an invalid email', async () => {
    const { fetch, status, submit } = runClientScript(endpoint, { email: 'not-an-email' });
    await submit();

    expect(fetch).not.toHaveBeenCalled();
    expect(status.textContent).toBe('Enter a valid email address.');
  });
});
