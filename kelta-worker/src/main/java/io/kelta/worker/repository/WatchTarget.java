package io.kelta.worker.repository;

/**
 * Something watchable, read from {@code watch_target}.
 *
 * <p>{@code metadata} carries the raw JSON text of the source-specific extras
 * column; the platform stores and returns it without interpreting it.
 *
 * <p>{@code synthetic} marks a test target driven only by the admin availability-event
 * injector: pollers never list it and their reports for it are dropped.
 */
public record WatchTarget(
        String id,
        String tenantId,
        String source,
        String externalId,
        String name,
        String category,
        String metadata,
        boolean active,
        boolean synthetic) {

    /** A real (non-synthetic) target. */
    public WatchTarget(String id, String tenantId, String source, String externalId, String name,
                       String category, String metadata, boolean active) {
        this(id, tenantId, source, externalId, name, category, metadata, active, false);
    }
}
