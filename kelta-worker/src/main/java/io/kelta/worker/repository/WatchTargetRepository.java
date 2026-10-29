package io.kelta.worker.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Reads {@code watch_target}. Targets are authored through the
 * {@code watch-targets} system collection (admin UI, JSON:API, or a flow), so
 * this repository is read-only — the matcher only ever resolves them.
 *
 * <p>Hand-written SQL on {@link JdbcTemplate} — no JPA. Every method takes
 * {@code tenantId} explicitly and filters on it: the matcher runs off a NATS
 * event with no ambient tenant context, so it cannot rely on RLS alone.
 */
@Repository
public class WatchTargetRepository {

    private static final String COLUMNS = """
            id, tenant_id, source, external_id, name, category,
            metadata::text AS metadata, active, synthetic
            """;

    private static final RowMapper<WatchTarget> MAPPER = (rs, rowNum) -> new WatchTarget(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("source"),
            rs.getString("external_id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("metadata"),
            rs.getBoolean("active"),
            rs.getBoolean("synthetic"));

    private final JdbcTemplate jdbc;

    public WatchTargetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolves a poller's report to a target. {@code (tenant_id, source,
     * external_id)} is unique, so this is unambiguous by construction.
     */
    public Optional<WatchTarget> findBySourceAndExternalId(String tenantId, String source,
                                                           String externalId) {
        if (source == null || externalId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM watch_target "
                        + "WHERE tenant_id = ? AND source = ? AND external_id = ?",
                        MAPPER, tenantId, source, externalId)
                .stream().findFirst();
    }

    public Optional<WatchTarget> findById(String tenantId, String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM watch_target "
                        + "WHERE tenant_id = ? AND id = ?", MAPPER, tenantId, id)
                .stream().findFirst();
    }

    /**
     * Active targets for a source — what a poller asks for to know its work list. Synthetic
     * targets are excluded: only the admin injector drives them, so no poller is ever asked to
     * poll one.
     */
    public List<WatchTarget> findActiveBySource(String tenantId, String source) {
        return jdbc.query("SELECT " + COLUMNS + " FROM watch_target "
                + "WHERE tenant_id = ? AND source = ? AND active AND NOT synthetic ORDER BY name",
                MAPPER, tenantId, source);
    }
}
