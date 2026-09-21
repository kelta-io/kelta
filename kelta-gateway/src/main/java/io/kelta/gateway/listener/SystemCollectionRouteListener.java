package io.kelta.gateway.listener;

import io.kelta.gateway.cache.GatewayCacheManager;
import io.kelta.gateway.filter.SystemCollectionResponseCacheFilter;
import io.kelta.gateway.route.RouteRefresher;
import io.kelta.gateway.route.RouteRegistry;
import io.kelta.runtime.event.RecordChangedPayload;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Listener that handles record change events for system collections.
 *
 * <p>When system collection records are modified, this listener:
 * <ol>
 *   <li>Refreshes gateway routes when {@code collections} records change</li>
 *   <li>Refreshes governor limit cache when {@code tenants} records change</li>
 * </ol>
 *
 * <p>Permission cache eviction is no longer needed — Cerbos policies are
 * synced directly from the worker when profiles change.
 *
 * @since 1.0.0
 */
@Component
public class SystemCollectionRouteListener {

    private static final Logger log = LoggerFactory.getLogger(SystemCollectionRouteListener.class);

    private final RouteRegistry routeRegistry;
    private final RouteRefresher routeRefresher;
    private final ObjectMapper objectMapper;
    private final GatewayCacheManager cacheManager;

    public SystemCollectionRouteListener(RouteRegistry routeRegistry,
                                          RouteRefresher routeRefresher,
                                          ObjectMapper objectMapper,
                                          GatewayCacheManager cacheManager) {
        this.routeRegistry = routeRegistry;
        this.routeRefresher = routeRefresher;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
    }

    public void onRecordChanged(String message) {
        try {
            var tree = objectMapper.readTree(message);

            var payloadNode = tree.has("payload") ? tree.get("payload") : tree;
            RecordChangedPayload payload = objectMapper.treeToValue(payloadNode, RecordChangedPayload.class);

            String collectionName = payload.getCollectionName();

            if (collectionName == null) {
                return;
            }

            if ("collections".equals(collectionName)) {
                log.info("Collection definition changed (recordId={}, changeType={}), refreshing routes",
                        payload.getRecordId(), payload.getChangeType());
                routeRefresher.refresh();
            }

            if ("tenants".equals(collectionName)) {
                log.info("Tenant record changed (recordId={}, changeType={}), refreshing governor limits",
                        payload.getRecordId(), payload.getChangeType());
                cacheManager.refreshGovernorLimitsFromWorker();
            }

            if ("tenant_custom_domains".equals(collectionName)) {
                String domain = payload.getData() != null
                        ? (String) payload.getData().get("domain") : null;
                log.info("Custom domain changed (recordId={}, changeType={}, domain={}), evicting domain cache",
                        payload.getRecordId(), payload.getChangeType(), domain);
                if (domain != null) {
                    cacheManager.removeCustomDomain(domain);
                } else {
                    // If we can't identify the specific domain, evict all custom domain entries
                    cacheManager.evictAllCustomDomains();
                }
            }

            // Evict gateway response cache for any cacheable system collection change
            if (SystemCollectionResponseCacheFilter.CACHEABLE_COLLECTIONS.contains(collectionName)) {
                log.info("System collection changed (collection={}, recordId={}, changeType={}), "
                                + "evicting gateway response cache",
                        collectionName, payload.getRecordId(), payload.getChangeType());
                cacheManager.evictSystemCollectionResponses(collectionName);
            }

        } catch (Exception e) {
            log.error("Error processing record change event: {}", e.getMessage(), e);
        }
    }
}
