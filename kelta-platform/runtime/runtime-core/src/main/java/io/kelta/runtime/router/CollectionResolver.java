package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.registry.CollectionOnDemandLoader;
import io.kelta.runtime.registry.CollectionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves the collection definitions a {@link DynamicCollectionRouter} request names: a
 * collection by URL segment (registry first, then the optional on-demand loader) and a
 * parent/child pair for the sub-resource routes.
 *
 * <p>Logs under {@link DynamicCollectionRouter}'s logger so the existing log lines (notably the
 * INFO {@code Collection '<segment>' not in registry} that {@code concerns.md} tells operators to
 * look for) keep their category.
 */
final class CollectionResolver {

    private static final Logger logger = LoggerFactory.getLogger(DynamicCollectionRouter.class);

    private final CollectionRegistry registry;
    private final Supplier<CollectionOnDemandLoader> onDemandLoader;

    CollectionResolver(CollectionRegistry registry, Supplier<CollectionOnDemandLoader> onDemandLoader) {
        this.registry = registry;
        this.onDemandLoader = onDemandLoader;
    }

    /**
     * Resolves a collection definition by name, with on-demand loading fallback.
     *
     * <p>First checks the local registry. If not found and an on-demand loader is configured,
     * attempts to load the collection from the control plane using the tenant ID from the
     * request's {@code X-Tenant-ID} header.
     *
     * @param collectionName the collection name
     * @param request the HTTP servlet request (used to extract tenant ID)
     * @return the collection definition, or {@code null} if not found
     */
    CollectionDefinition resolve(String collectionName, HttpServletRequest request) {
        // TenantContext is already set before this method is called,
        // so registry.get() will use the tenant-scoped key automatically
        CollectionDefinition definition = registry.get(collectionName);
        if (definition != null) {
            return definition;
        }

        // Fallback: try to load on demand from the control plane
        CollectionOnDemandLoader loader = onDemandLoader.get();
        if (loader != null) {
            String tenantId = request.getHeader("X-Tenant-ID");
            logger.info("Collection '{}' not in registry, attempting on-demand load (tenantId={})",
                    collectionName, tenantId);
            try {
                definition = loader.load(collectionName, tenantId);
                if (definition != null) {
                    logger.info("Successfully loaded collection '{}' on demand", collectionName);
                }
            } catch (Exception e) {
                logger.warn("On-demand load failed for collection '{}': {}", collectionName, e.getMessage());
            }
        }

        return definition;
    }

    /**
     * Resolves a sub-resource relationship between parent and child collections.
     *
     * @param parentName the parent collection name
     * @param childName the child collection name
     * @param request the HTTP request (for on-demand loading)
     * @return the SubResourceRelation, or null if not found
     */
    SubResourceRelation resolveSubResource(String parentName, String childName,
                                           HttpServletRequest request) {
        CollectionDefinition parentDef = resolve(parentName, request);
        if (parentDef == null) {
            logger.debug("Parent collection '{}' not found", parentName);
            return null;
        }

        CollectionDefinition childDef = resolve(childName, request);
        if (childDef == null) {
            logger.debug("Child collection '{}' not found", childName);
            return null;
        }

        Optional<SubResourceRelation> relation = SubResourceResolver.resolve(parentDef, childDef);
        if (relation.isEmpty()) {
            logger.debug("No relationship found between parent '{}' and child '{}'",
                    parentName, childName);
            return null;
        }

        return relation.get();
    }
}
