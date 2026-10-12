/**
 * Thin authenticated client for the tenant API, for specs that seed or verify state
 * through endpoints that are not plain JSON:API collection CRUD (approvals, chat,
 * telehealth, `/api/me/identity`, `/api/admin/users/...`). Collection CRUD with
 * teardown belongs in {@link DataFactory}.
 *
 * It never throws on a non-2xx status: callers assert on `status` so a failure
 * message can carry the response body.
 */
import { getApiToken } from "../fixtures/auth-tokens";

export interface ApiResponse<T = unknown> {
  status: number;
  body: T;
}

export type ApiRequest = <T = unknown>(
  method: string,
  path: string,
  body?: unknown,
) => Promise<ApiResponse<T>>;

/**
 * Build a request function bound to one tenant. `path` starts at `/api/...`; the
 * tenant slug is prefixed. The bearer token is the e2e admin's (see
 * {@link getApiToken}) unless `token` is given.
 */
export function tenantApi(options: {
  apiBaseUrl: string;
  tenantSlug: string;
  token?: string;
}): ApiRequest {
  return async <T = unknown>(
    method: string,
    path: string,
    body?: unknown,
  ): Promise<ApiResponse<T>> => {
    const token = options.token ?? (await getApiToken());
    const response = await fetch(
      `${options.apiBaseUrl}/${options.tenantSlug}${path}`,
      {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/vnd.api+json",
          Accept: "application/vnd.api+json, application/json",
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      },
    );
    const text = await response.text();
    let parsed: unknown = null;
    if (text) {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = { raw: text };
      }
    }
    return { status: response.status, body: parsed as T };
  };
}

/** The calling user's platform_user UUID (`GET /api/me/identity`). */
export async function myUserId(api: ApiRequest): Promise<string> {
  const identity = await api<{ userId?: string }>("GET", "/api/me/identity");
  if (identity.status !== 200 || !identity.body?.userId) {
    throw new Error(
      `GET /api/me/identity failed (${identity.status}): ${JSON.stringify(identity.body)}`,
    );
  }
  return identity.body.userId;
}
