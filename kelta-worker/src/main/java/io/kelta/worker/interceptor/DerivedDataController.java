package io.kelta.worker.interceptor;

/**
 * Marks a controller whose responses carry data <em>computed from</em> records (dashboard
 * widget results, report runs), not the records themselves, exempting its handlers from
 * {@link CerbosRecordAuthorizationAdvice}'s record-level filter.
 *
 * <p>The advice reads a single {@code data} object as a record of the path's collection and
 * checks the request method's action on it. A derived resource such as {@code widget-data}
 * served by {@code POST /api/dashboards/{id}/components/{cid}/data} was therefore checked as a
 * {@code create} of a {@code dashboards} record: every caller without create access on
 * dashboards — a read-only VIEW_ANALYTICS profile, an API token — got {@code {"data": null}}
 * with a 200, while the widget had executed successfully.
 *
 * <p>A marker <b>interface</b>, not an annotation, for the same reason as
 * {@link SelfScopedController}: a plain type test the native image cannot silently drop.
 *
 * <p>Implementing this is a promise: before computing anything, the controller authorizes the
 * source record(s) the result is derived from with
 * {@link CerbosRecordAuthorizationAdvice#isRecordReadable} and answers a record the caller may
 * not read exactly like a missing one (404). It exempts only this controller's own handlers —
 * the generic collection route on the same path keeps full record-level filtering.
 */
public interface DerivedDataController {
}
