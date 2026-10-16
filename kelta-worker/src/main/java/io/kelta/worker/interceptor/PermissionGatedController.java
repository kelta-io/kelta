package io.kelta.worker.interceptor;

/**
 * Marks a controller whose every handler is gated on a system permission and returns platform
 * resources that are not records of any collection (sandbox environments, promotions),
 * exempting its handlers from {@link CerbosRecordAuthorizationAdvice}'s record-level filter.
 *
 * <p>The advice reads a {@code data} object or list under {@code /api/{segment}} as records of
 * collection {@code segment} and checks the request method's action on them. For a segment that
 * is no collection, only profiles with VIEW_ALL_DATA (read) or MODIFY_ALL_DATA (create, edit,
 * delete) pass, so a caller holding the controller's own permission — MANAGE_SANDBOXES for
 * {@code POST /api/promotions/{id}/execute} — without those data-wide grants got
 * {@code {"data": null}} with a 200 after the action had already run.
 *
 * <p>A marker <b>interface</b>, not an annotation, for the same reason as
 * {@link SelfScopedController}: a plain type test the native image cannot silently drop.
 *
 * <p>Implementing this is a promise: every handler of the controller checks its system
 * permission (fail-closed on a missing identity) before it reads or returns anything, and none
 * returns collection records — a controller that does uses {@link DerivedDataController} or
 * keeps the advice. Use {@link DerivedDataController} when access follows a source record.
 */
public interface PermissionGatedController {
}
