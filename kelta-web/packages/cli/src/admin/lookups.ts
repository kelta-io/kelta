import type { AxiosInstance } from 'axios';
import { CliError, EXIT } from '../errors.js';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface JsonApiResource {
  id: string;
  attributes?: Record<string, unknown>;
}

interface ListBody {
  data?: JsonApiResource[];
}

interface SingleBody {
  data?: JsonApiResource;
}

const LOOKUP_RETRIES = 3;
const LOOKUP_RETRY_DELAY_MS = 500;

/**
 * Resolve a collection name (or UUID passthrough) to its id. A just-created
 * collection can briefly 404 on pods whose NATS cache refresh hasn't landed,
 * so a NOT_FOUND is retried a few times with backoff before failing.
 */
export async function collectionIdByName(axios: AxiosInstance, nameOrId: string): Promise<string> {
  if (UUID_RE.test(nameOrId)) return nameOrId;
  const notFound = (): CliError =>
    new CliError(`Collection "${nameOrId}" not found`, {
      code: 'NOT_FOUND',
      exitCode: EXIT.NOT_FOUND,
    });
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await axios.get<SingleBody>(`/api/collections/${nameOrId}`);
      const id = response.data.data?.id;
      // a 2xx without an id is an authoritative miss — don't retry
      if (!id) throw notFound();
      return id;
    } catch (error) {
      if (error instanceof CliError) throw error;
      const status = (error as { response?: { status?: number } }).response?.status;
      if (status !== undefined && status !== 404) throw error;
      if (attempt >= LOOKUP_RETRIES) throw notFound();
    }
    await new Promise((resolve) => setTimeout(resolve, LOOKUP_RETRY_DELAY_MS * (attempt + 1)));
  }
}

/** Field name → field id map for one collection (filter is on collectionId, never name). */
export async function fieldIdsByName(
  axios: AxiosInstance,
  collectionId: string
): Promise<Map<string, string>> {
  const response = await axios.get<ListBody>(
    `/api/fields?filter[collectionId][EQ]=${collectionId}&page[size]=200`
  );
  const map = new Map<string, string>();
  for (const field of response.data.data ?? []) {
    const name = field.attributes?.name;
    if (typeof name === 'string') map.set(name, field.id);
  }
  return map;
}

/** Resolve a global picklist name (or UUID passthrough) to its id. */
export async function picklistIdByName(axios: AxiosInstance, nameOrId: string): Promise<string> {
  if (UUID_RE.test(nameOrId)) return nameOrId;
  const response = await axios.get<ListBody>(
    `/api/global-picklists?filter[name][EQ]=${encodeURIComponent(nameOrId)}&page[size]=1`
  );
  const id = response.data.data?.[0]?.id;
  if (!id) {
    throw new CliError(`Picklist "${nameOrId}" not found`, {
      code: 'NOT_FOUND',
      exitCode: EXIT.NOT_FOUND,
    });
  }
  return id;
}

/** Resolve a UI menu name (or UUID passthrough) to its id. */
export async function menuIdByName(axios: AxiosInstance, nameOrId: string): Promise<string> {
  if (UUID_RE.test(nameOrId)) return nameOrId;
  const response = await axios.get<ListBody>(
    `/api/ui-menus?filter[name][EQ]=${encodeURIComponent(nameOrId)}&page[size]=1`
  );
  const id = response.data.data?.[0]?.id;
  if (!id) {
    throw new CliError(`Menu "${nameOrId}" not found`, {
      code: 'NOT_FOUND',
      exitCode: EXIT.NOT_FOUND,
    });
  }
  return id;
}
