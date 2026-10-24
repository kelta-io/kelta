package io.kelta.worker.interceptor;

/**
 * Marks a controller that scopes its own response data to the calling user — the member's own
 * rows, or the rows the caller holds a membership in (chat conversations they take part in,
 * threads of support mailboxes they are an agent of) — exempting it from
 * {@link CerbosRecordAuthorizationAdvice}'s record-level filter.
 *
 * <p>The advice removes any record the caller's <em>profile</em> is not granted at
 * the record level. A portal member holds no record grants at all, so applying it
 * to a member-facing endpoint empties the response — the member's own watches, wins,
 * devices, and billing state vanish while every write still succeeds, which reads as
 * data loss rather than a permission error. The same failure hit the telehealth
 * portal in July 2026 and was patched there with a path exclusion. Chat and the support
 * console carry no record grants either (their generic collection routes are admin-only), so
 * the filter emptied every conversation list and inbox for callers without VIEW_ALL_DATA.
 *
 * <p>A marker <b>interface</b>, not an annotation: the check is then a plain type
 * test with no reflection, which the native image cannot silently drop.
 *
 * <p>Implementing this is a promise: the controller resolves the acting user from the
 * gateway-stamped request headers (never the body) and, on every handler, returns only rows that
 * user owns or holds a membership in under the controller's own rule, refusing any other id —
 * 404 for member endpoints (a foreign id reads as missing), or an audited 403 (chat). Wider views
 * are gated by the controller too (chat's "all" view on MANAGE_CHAT, assigning a support thread
 * on the mailbox's manage role). It exempts only this controller's own handlers — the generic collection
 * route on the same path keeps full record-level filtering.
 */
public interface SelfScopedController {
}
