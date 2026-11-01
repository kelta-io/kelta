package io.kelta.worker.service;

import io.kelta.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Bootstraps the worker by loading all active collections from the database on startup.
 *
 * <p>On {@link ApplicationReadyEvent}, queries the database directly for all active
 * collections and registers each one via {@link CollectionLifecycleManager}.
 * This ensures every worker can serve every collection, allowing the K8s Service
 * to load-balance requests across all worker pods.
 *
 * <p><b>Registration only — no DDL.</b> Applying each collection's schema here meant every
 * replica ran {@code CREATE TABLE}/{@code reconcileSchema} for every collection at the same
 * time against the same database: the same work N times over, racing itself. Schema is now
 * applied once by {@link io.kelta.worker.runner.SchemaBootstrapRunner} in the migrate Job,
 * which ArgoCD runs as a PreSync hook — so by the time these pods start, the tables are
 * already correct. Set {@code kelta.storage.schema-bootstrap.enabled=true} to restore the old
 * behaviour (single-pod deployments, or local runs with no migrate Job).
 *
 * <p>There is no control plane dependency. The worker reads collection definitions
 * directly from the shared database. Runtime schema changes are handled by the
 * existing NATS {@code collection-changed} listener, which still applies DDL.
 *
 * @since 1.0.0
 */
@Component
public class WorkerBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(WorkerBootstrapService.class);

    private static final String SELECT_ACTIVE_COLLECTIONS =
            "SELECT id, name FROM collection WHERE active = true";

    private final WorkerProperties workerProperties;
    private final JdbcTemplate jdbcTemplate;
    private final CollectionLifecycleManager lifecycleManager;
    private final boolean applySchema;
    /** Set once every active collection has been registered; see {@link #ensureBootstrapped}. */
    private volatile boolean bootstrapped;

    public WorkerBootstrapService(WorkerProperties workerProperties,
                                   JdbcTemplate jdbcTemplate,
                                   CollectionLifecycleManager lifecycleManager,
                                   @Value("${kelta.storage.schema-bootstrap.enabled:false}")
                                   boolean applySchema) {
        this.workerProperties = workerProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleManager = lifecycleManager;
        this.applySchema = applySchema;
    }

    /**
     * Loads all active collections from the database when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Worker '{}' starting bootstrap from database (applySchema={})",
                workerProperties.getId(), applySchema);

        try {
            ensureBootstrapped();
            log.info("Worker '{}' successfully bootstrapped all collections",
                    workerProperties.getId());
        } catch (Exception e) {
            log.error("Failed to bootstrap collections from database. " +
                    "Worker will start with no collections loaded.", e);
        }
    }

    /**
     * Registers every active collection now if no bootstrap has finished yet. A record-event
     * consumer may see a message before {@code ApplicationReadyEvent}'s bootstrap completes (NATS
     * subscriptions start in the same event, and a restarted pod is handed its unacknowledged
     * backlog at once), so it calls this first: a tenant collection that is not registered yet
     * would otherwise read as "not found" and fail that work for good. No tenant may be bound:
     * the query reads every tenant's collections.
     */
    public void ensureBootstrapped() {
        if (bootstrapped) {
            return;
        }
        synchronized (this) {
            if (!bootstrapped) {
                initializeAllCollections();
                bootstrapped = true;
            }
        }
    }

    /**
     * Queries all active collections from the database and initializes each one
     * on this worker.
     */
    private void initializeAllCollections() {
        List<Map<String, Object>> collections = jdbcTemplate.queryForList(SELECT_ACTIVE_COLLECTIONS);

        log.info("Found {} active collections to initialize", collections.size());

        for (Map<String, Object> collection : collections) {
            String collectionId = (String) collection.get("id");
            String collectionName = (String) collection.get("name");

            if (collectionId != null) {
                try {
                    lifecycleManager.initializeCollection(collectionId, applySchema);
                } catch (Exception e) {
                    log.warn("Failed to initialize collection '{}' (id={}): {}",
                            collectionName, collectionId, e.getMessage());
                }
            }
        }
    }
}
