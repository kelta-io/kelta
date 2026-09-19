/**
 * Test data factory for creating and cleaning up test entities.
 *
 * Uses the Kelta API directly to set up test state before tests
 * and tear it down afterward.
 */

export interface ApiClient {
  baseUrl: string;
  token: string;
  tenantSlug: string;
  /** Optional callback to refresh the token when a 401 is received. */
  refreshToken?: () => Promise<string>;
}

interface JsonApiResource {
  id: string;
  type: string;
  attributes: Record<string, unknown>;
}

interface JsonApiResponse {
  data: JsonApiResource | JsonApiResource[];
}

const MAX_RETRIES = 3;
const INITIAL_RETRY_DELAY_MS = 2_000;

/** How long to wait for storage to become ready after collection/field creation */
const STORAGE_READY_TIMEOUT_MS = 30_000;
const STORAGE_READY_POLL_MS = 2_000;
/**
 * Per-attempt cap for readiness-poll fetches. `fetch` has no default timeout,
 * so a single connection that hangs during a momentary gateway/worker
 * saturation spike (many collections created at once) would block the entire
 * poll loop on ONE request and burn the whole readiness budget — the loop
 * only re-checks its deadline between awaits. Bounding each attempt lets the
 * loop actually retry (dozens of attempts across the budget) and ride out a
 * transient spike instead of failing on the first stalled socket.
 */
const READY_FETCH_TIMEOUT_MS = 5_000;
/**
 * Longest Retry-After we are willing to sleep through on a 429. A tenant-window
 * rate-limit lockout (minutes) can never resolve within a test's budget —
 * sleeping through it just converts a diagnosable failure into an opaque
 * timeout. Beyond this cap we fail immediately with an explicit message.
 */
const MAX_RATE_LIMIT_WAIT_MS = 15_000;

/** Builds the loud, self-explaining error for a gateway 429. */
function rateLimitedError(context: string, retryAfterSeconds: number | null): Error {
  return new Error(
    `${context}: gateway returned 429 Too Many Requests — the tenant's API ` +
      `rate-limit window is exhausted (Retry-After: ${retryAfterSeconds ?? "?"}s). ` +
      `The e2e run is being throttled; this is NOT a storage/readiness problem.`,
  );
}

function retryAfterSeconds(response: Response): number | null {
  const header = response.headers.get("Retry-After");
  if (!header) return null;
  const parsed = parseInt(header, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

export class DataFactory {
  private createdEntities: Array<{ type: string; id: string }> = [];
  private currentToken: string;

  constructor(private readonly api: ApiClient) {
    this.currentToken = api.token;
  }

  private async request(
    method: string,
    path: string,
    body?: unknown,
  ): Promise<JsonApiResponse> {
    const url = `${this.api.baseUrl}/${this.api.tenantSlug}${path}`;

    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      const response = await fetch(url, {
        method,
        headers: {
          "Content-Type": "application/vnd.api+json",
          Authorization: `Bearer ${this.currentToken}`,
        },
        body: body ? JSON.stringify(body) : undefined,
      });

      // Retry on auth failure with token refresh
      if (
        response.status === 401 &&
        this.api.refreshToken &&
        attempt < MAX_RETRIES
      ) {
        this.currentToken = await this.api.refreshToken();
        continue;
      }

      // Fail fast on a rate-limit lockout longer than any sane retry budget —
      // sleeping through a multi-minute Retry-After only masks the real cause.
      if (response.status === 429) {
        const retryAfter = retryAfterSeconds(response);
        if (retryAfter !== null && retryAfter * 1_000 > MAX_RATE_LIMIT_WAIT_MS) {
          throw rateLimitedError(`API ${method} ${path}`, retryAfter);
        }
      }

      // Retry on rate limiting (429) and transient server errors (500-503)
      const isRetryable =
        response.status === 429 ||
        (response.status >= 500 && response.status <= 503);
      if (isRetryable && attempt < MAX_RETRIES) {
        const retryAfter = response.headers.get("Retry-After");
        const delayMs = retryAfter
          ? parseInt(retryAfter, 10) * 1_000
          : INITIAL_RETRY_DELAY_MS * Math.pow(2, attempt);
        await new Promise((resolve) => setTimeout(resolve, delayMs));
        continue;
      }

      if (!response.ok) {
        const error = await response.text();
        throw new Error(
          `API ${method} ${path} failed (${response.status}): ${error}`,
        );
      }

      if (response.status === 204) {
        return { data: { id: "", type: "", attributes: {} } };
      }

      return response.json() as Promise<JsonApiResponse>;
    }

    throw new Error(
      `API ${method} ${path} failed: max retries (${MAX_RETRIES}) exceeded`,
    );
  }

  /**
   * Poll the collection's record endpoint until storage is provisioned.
   *
   * After creating a collection and adding fields, the backend
   * asynchronously provisions the storage table. Attempting to
   * create records before it is ready returns a 500 storageError.
   * This method polls GET /api/{collectionName} until it stops
   * returning 500, ensuring the storage layer is ready.
   */
  async waitForStorageReady(
    collectionName: string,
    timeoutMs = STORAGE_READY_TIMEOUT_MS,
  ): Promise<void> {
    const url = `${this.api.baseUrl}/${this.api.tenantSlug}/api/${collectionName}`;
    const deadline = Date.now() + timeoutMs;

    while (Date.now() < deadline) {
      try {
        const response = await fetch(url, {
          method: "GET",
          headers: {
            "Content-Type": "application/vnd.api+json",
            Authorization: `Bearer ${this.currentToken}`,
          },
          signal: AbortSignal.timeout(READY_FETCH_TIMEOUT_MS),
        });

        // 200-level response means the gateway route is registered and storage is ready.
        // 404 means the gateway hasn't registered the dynamic route yet — keep polling.
        if (response.ok) {
          return;
        }
        // A rate-limit rejection will not clear within this poll's budget and
        // every further poll only extends the lockout — surface it immediately.
        if (response.status === 429) {
          throw rateLimitedError(
            `waitForStorageReady('${collectionName}')`,
            retryAfterSeconds(response),
          );
        }
      } catch (error) {
        if (error instanceof Error && error.message.includes("429")) {
          throw error;
        }
        // Network error — keep retrying
      }

      await new Promise((resolve) =>
        setTimeout(resolve, STORAGE_READY_POLL_MS),
      );
    }

    throw new Error(
      `Storage for collection '${collectionName}' not ready after ${timeoutMs}ms`,
    );
  }

  /**
   * Create a collection. Any override is passed through as a collection
   * attribute — e.g. `trackHistory: true` enables record versioning (every
   * record create/update/delete captured as a record_version snapshot).
   */
  async createCollection(
    overrides: Record<string, unknown> & { trackHistory?: boolean } = {},
  ): Promise<JsonApiResource> {
    const uniqueName = `e2e_test_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    const result = await this.request("POST", "/api/collections", {
      data: {
        type: "collections",
        attributes: {
          name: uniqueName,
          displayName: `E2E ${uniqueName}`,
          active: true,
          ...overrides,
        },
      },
    });

    const resource = result.data as JsonApiResource;
    this.createdEntities.push({ type: "collections", id: resource.id });
    return resource;
  }

  async addField(
    collectionId: string,
    field: {
      name: string;
      displayName: string;
      type: string;
      required?: boolean;
      referenceTarget?: string;
      fieldTypeConfig?: Record<string, unknown>;
    },
  ): Promise<JsonApiResource> {
    const result = await this.request(
      "POST",
      `/api/collections/${collectionId}/fields`,
      {
        data: {
          type: "fields",
          attributes: field,
        },
      },
    );
    // Field add propagates across worker pods asynchronously via NATS. Without
    // a barrier here, the very next createRecord may land on a pod whose
    // in-memory CollectionDefinition does not yet include the new field, and
    // its INSERT loop will silently drop the value — caught only later by a
    // surprising NULL or missing-attribute assertion.
    await this.waitForFieldVisible(collectionId, field.name);
    return result.data as JsonApiResource;
  }

  /**
   * Poll `GET /api/collections/{id}?include=fields` until a field with the
   * given name appears in the response's `included` array. Issues several
   * extra confirming reads in a row to bias for catching the slowest pod in
   * a multi-replica deployment — gateway routing is round-robin and a single
   * "yes" can land on a pod that has already refreshed while the next POST
   * lands on a stale one.
   */
  async waitForFieldVisible(
    collectionId: string,
    fieldName: string,
    timeoutMs = STORAGE_READY_TIMEOUT_MS,
  ): Promise<void> {
    const url = `${this.api.baseUrl}/${this.api.tenantSlug}/api/collections/${collectionId}?include=fields`;
    const deadline = Date.now() + timeoutMs;
    const requiredConsecutive = 2;
    let consecutive = 0;
    while (Date.now() < deadline) {
      try {
        const response = await fetch(url, {
          method: "GET",
          headers: {
            "Content-Type": "application/vnd.api+json",
            Authorization: `Bearer ${this.currentToken}`,
          },
          signal: AbortSignal.timeout(READY_FETCH_TIMEOUT_MS),
        });
        if (response.ok) {
          const body = (await response.json()) as {
            included?: Array<{
              type?: string;
              attributes?: Record<string, unknown>;
            }>;
          };
          const present = (body.included ?? []).some(
            (r) =>
              r &&
              r.type === "fields" &&
              r.attributes &&
              (r.attributes.name as string | undefined) === fieldName,
          );
          if (present) {
            consecutive += 1;
            if (consecutive >= requiredConsecutive) return;
          } else {
            consecutive = 0;
          }
        } else if (response.status === 429) {
          throw rateLimitedError(
            `waitForFieldVisible('${fieldName}')`,
            retryAfterSeconds(response),
          );
        } else {
          consecutive = 0;
        }
      } catch (error) {
        if (error instanceof Error && error.message.includes("429")) {
          throw error;
        }
        consecutive = 0;
      }
      await new Promise((resolve) =>
        setTimeout(resolve, STORAGE_READY_POLL_MS / 4),
      );
    }
    throw new Error(
      `Field '${fieldName}' on collection '${collectionId}' not visible after ${timeoutMs}ms`,
    );
  }

  async createRecord(
    collectionName: string,
    attributes: Record<string, unknown>,
  ): Promise<JsonApiResource> {
    // Dynamic collection routes propagate asynchronously across worker pods.
    // POST may transiently return 404 even after waitForStorageReady (GET)
    // succeeds, because the GET may hit a pod that lazily loaded the
    // collection while POST hits a different pod that hasn't yet. Retry
    // a few times to handle this race condition.
    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        const result = await this.request("POST", `/api/${collectionName}`, {
          data: {
            type: collectionName,
            attributes,
          },
        });

        const resource = result.data as JsonApiResource;
        this.createdEntities.push({ type: collectionName, id: resource.id });
        return resource;
      } catch (error) {
        const is404 = error instanceof Error && error.message.includes("(404)");
        if (is404 && attempt < MAX_RETRIES) {
          await new Promise((resolve) =>
            setTimeout(resolve, STORAGE_READY_POLL_MS),
          );
          continue;
        }
        throw error;
      }
    }
    throw new Error(
      `createRecord for '${collectionName}' failed after ${MAX_RETRIES} retries`,
    );
  }

  async getRecord(
    collectionName: string,
    id: string,
  ): Promise<JsonApiResource> {
    const result = await this.request(
      "GET",
      `/api/${collectionName}/${id}`,
    );
    return result.data as JsonApiResource;
  }

  /**
   * Create a default DETAIL page layout with field sections for a collection.
   *
   * Mirrors the JSON:API shapes the layout designer and the MCP
   * `create_layout` tool use: POST /api/page-layouts, then one
   * POST /api/layout-sections per section and one POST /api/layout-fields per
   * placement. `usePageLayout` resolves the layout via the
   * `isDefault=true` + `layoutType=DETAIL` fallback, so no layout assignment
   * is needed.
   */
  async createDetailLayout(
    collectionId: string,
    sections: Array<{
      heading: string;
      columns?: number;
      fields: Array<{ fieldId: string }>;
    }>,
  ): Promise<{ layoutId: string; sectionIds: string[] }> {
    const layoutResult = await this.request("POST", "/api/page-layouts", {
      data: {
        type: "page-layouts",
        attributes: {
          collectionId,
          name: `E2E layout ${Date.now()}`,
          layoutType: "DETAIL",
          isDefault: true,
        },
      },
    });
    const layoutId = (layoutResult.data as JsonApiResource).id;
    this.createdEntities.push({ type: "page-layouts", id: layoutId });

    const sectionIds: string[] = [];
    for (const [sectionIndex, section] of sections.entries()) {
      const sectionResult = await this.request("POST", "/api/layout-sections", {
        data: {
          type: "layout-sections",
          attributes: {
            layoutId,
            heading: section.heading,
            columns: section.columns ?? 2,
            sortOrder: sectionIndex,
            sectionType: "STANDARD",
          },
        },
      });
      const sectionId = (sectionResult.data as JsonApiResource).id;
      sectionIds.push(sectionId);

      for (const [fieldIndex, field] of section.fields.entries()) {
        await this.request("POST", "/api/layout-fields", {
          data: {
            type: "layout-fields",
            attributes: {
              sectionId,
              fieldId: field.fieldId,
              columnNumber: 0,
              sortOrder: fieldIndex,
            },
          },
        });
      }
    }

    return { layoutId, sectionIds };
  }

  /**
   * Poll GET /api/collections/{id} until the collection is visible (2xx) or timeout.
   *
   * Collection create propagates asynchronously across worker pods via NATS. The
   * POST returns success as soon as the record is committed on the originating
   * pod, but the LIST endpoint on another pod may not yet reflect the new row.
   */
  async waitForCollectionVisible(
    collectionId: string,
    timeoutMs = STORAGE_READY_TIMEOUT_MS,
  ): Promise<void> {
    const url = `${this.api.baseUrl}/${this.api.tenantSlug}/api/collections/${collectionId}`;
    const deadline = Date.now() + timeoutMs;

    while (Date.now() < deadline) {
      try {
        const response = await fetch(url, {
          method: "GET",
          headers: {
            "Content-Type": "application/vnd.api+json",
            Authorization: `Bearer ${this.currentToken}`,
          },
        });
        if (response.ok) return;
      } catch {
        // Network error — keep retrying
      }
      await new Promise((resolve) =>
        setTimeout(resolve, STORAGE_READY_POLL_MS),
      );
    }

    throw new Error(
      `Collection '${collectionId}' not visible after ${timeoutMs}ms`,
    );
  }

  /**
   * Poll GET /api/collections/{id} until the collection is gone (404) or timeout.
   *
   * After DELETE returns, the soft-delete may take a moment to propagate to the
   * list endpoint on other pods. This ensures the deletion is fully visible
   * before the caller asserts against the UI.
   */
  async waitForCollectionDeleted(
    collectionId: string,
    timeoutMs = STORAGE_READY_TIMEOUT_MS,
  ): Promise<void> {
    const url = `${this.api.baseUrl}/${this.api.tenantSlug}/api/collections/${collectionId}`;
    const deadline = Date.now() + timeoutMs;

    while (Date.now() < deadline) {
      try {
        const response = await fetch(url, {
          method: "GET",
          headers: {
            "Content-Type": "application/vnd.api+json",
            Authorization: `Bearer ${this.currentToken}`,
          },
        });
        if (response.status === 404) return;
      } catch {
        // Network error — keep retrying
      }
      await new Promise((resolve) =>
        setTimeout(resolve, STORAGE_READY_POLL_MS),
      );
    }

    throw new Error(
      `Collection '${collectionId}' still visible after ${timeoutMs}ms`,
    );
  }

  /**
   * Poll GET /api/collections (LIST) until a collection with the given name is
   * present, or timeout. The admin UI loads its table from the LIST endpoint,
   * which is served from per-pod state and may lag the per-id GET after a
   * NATS-propagated create. Use this in addition to `waitForCollectionVisible`
   * before reloading the UI.
   */
  async waitForCollectionInList(
    name: string,
    timeoutMs = STORAGE_READY_TIMEOUT_MS,
  ): Promise<void> {
    await this.pollCollectionList(
      name,
      (present) => present,
      timeoutMs,
      `Collection '${name}' not in LIST after ${timeoutMs}ms`,
    );
  }

  /**
   * Poll GET /api/collections (LIST) until no collection with the given name is
   * present, or timeout. Mirror of `waitForCollectionInList` for deletes.
   */
  async waitForCollectionGoneFromList(
    name: string,
    timeoutMs = STORAGE_READY_TIMEOUT_MS,
  ): Promise<void> {
    await this.pollCollectionList(
      name,
      (present) => !present,
      timeoutMs,
      `Collection '${name}' still in LIST after ${timeoutMs}ms`,
    );
  }

  private async pollCollectionList(
    name: string,
    predicate: (present: boolean) => boolean,
    timeoutMs: number,
    timeoutMessage: string,
  ): Promise<void> {
    const url = `${this.api.baseUrl}/${this.api.tenantSlug}/api/collections?page[size]=1000`;
    const deadline = Date.now() + timeoutMs;

    while (Date.now() < deadline) {
      try {
        const response = await fetch(url, {
          method: "GET",
          headers: {
            "Content-Type": "application/vnd.api+json",
            Authorization: `Bearer ${this.currentToken}`,
          },
        });
        if (response.ok) {
          const body = (await response.json()) as JsonApiResponse;
          const data = Array.isArray(body.data) ? body.data : [body.data];
          const present = data.some((r) => r?.attributes?.name === name);
          if (predicate(present)) return;
        }
      } catch {
        // Network error — keep retrying
      }
      await new Promise((resolve) =>
        setTimeout(resolve, STORAGE_READY_POLL_MS),
      );
    }

    throw new Error(timeoutMessage);
  }

  async cleanup(): Promise<void> {
    for (const entity of this.createdEntities.reverse()) {
      // Collections accumulate child rows (field_history, record_version, attachments…)
      // that the server's CollectionDeletionGuardHook refuses to destroy without
      // ?force=true — and that, before the V181/V182 FK cascades, made a used collection
      // undeletable outright. An unforced (or silently swallowed) delete here leaked
      // e2e_test_* collections into the target tenant — the litter that piled up in the
      // couchpicks product tenant. Force the collection delete, and surface a failure
      // instead of hiding it so a future leak is visible in the test log.
      const path =
        entity.type === "collections"
          ? `/api/collections/${entity.id}?force=true`
          : `/api/${entity.type}/${entity.id}`;
      try {
        await this.request("DELETE", path);
      } catch (err) {
        if (entity.type === "collections") {
          console.warn(
            `[data-factory] failed to delete collection ${entity.id} during cleanup — it may leak: ${String(err)}`,
          );
        }
        // Non-collection cleanup failures are non-fatal and ignored.
      }
    }
    this.createdEntities = [];
  }

  /**
   * Register an externally-created collection (e.g. one created through the admin UI
   * in a wizard test) for force-delete at {@link cleanup}, resolving its id from the
   * LIST endpoint by name. UI-created collections are not otherwise tracked, so without
   * this they have no teardown and leak into the target tenant.
   */
  async trackCollectionByName(
    name: string,
    timeoutMs = STORAGE_READY_TIMEOUT_MS,
  ): Promise<void> {
    const url = `${this.api.baseUrl}/${this.api.tenantSlug}/api/collections?page[size]=1000`;
    const deadline = Date.now() + timeoutMs;

    while (Date.now() < deadline) {
      try {
        const response = await fetch(url, {
          method: "GET",
          headers: {
            "Content-Type": "application/vnd.api+json",
            Authorization: `Bearer ${this.currentToken}`,
          },
        });
        if (response.ok) {
          const body = (await response.json()) as JsonApiResponse;
          const data = Array.isArray(body.data) ? body.data : [body.data];
          const match = data.find((r) => r?.attributes?.name === name);
          if (match?.id) {
            this.createdEntities.push({ type: "collections", id: match.id });
            return;
          }
        }
      } catch {
        // Network error — keep retrying
      }
      await new Promise((resolve) =>
        setTimeout(resolve, STORAGE_READY_POLL_MS),
      );
    }
    console.warn(
      `[data-factory] trackCollectionByName('${name}') did not resolve an id within ${timeoutMs}ms — it may leak`,
    );
  }
}
