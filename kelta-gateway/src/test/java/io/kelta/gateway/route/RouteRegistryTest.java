package io.kelta.gateway.route;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RouteRegistry class.
 * Tests basic functionality and thread-safety.
 */
class RouteRegistryTest {
    
    private RouteRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = new RouteRegistry();
    }
    
    @Test
    void testAddRoute() {
        RouteDefinition route = createRoute("users-collection", "/api/users/**");
        
        registry.addRoute(route);
        
        assertEquals(1, registry.size());
        Optional<RouteDefinition> found = registry.findByPath("/api/users/**");
        assertTrue(found.isPresent());
        assertEquals(route, found.get());
    }
    
    @Test
    void testAddRouteReplacesExisting() {
        RouteDefinition route1 = createRoute("users-collection-v1", "/api/users/**");
        RouteDefinition route2 = createRoute("users-collection-v2", "/api/users/**");
        
        registry.addRoute(route1);
        registry.addRoute(route2);
        
        assertEquals(1, registry.size());
        Optional<RouteDefinition> found = registry.findByPath("/api/users/**");
        assertTrue(found.isPresent());
        assertEquals("users-collection-v2", found.get().getId());
    }
    
    @Test
    void testAddNullRoute() {
        registry.addRoute(null);
        
        assertEquals(0, registry.size());
        assertTrue(registry.isEmpty());
    }
    
    @Test
    void testAddRouteWithNullPath() {
        RouteDefinition route = new RouteDefinition(
            "invalid-route",
            null,
            "http://backend:8080",
            "collection"
        );
        
        registry.addRoute(route);
        
        assertEquals(0, registry.size());
    }
    
    @Test
    void testAddRouteWithEmptyPath() {
        RouteDefinition route = new RouteDefinition(
            "invalid-route",
            "",
            "http://backend:8080",
            "collection"
        );
        
        registry.addRoute(route);
        
        assertEquals(0, registry.size());
    }
    
    @Test
    void testRemoveRoute() {
        RouteDefinition route = createRoute("users-collection", "/api/users/**");
        
        registry.addRoute(route);
        assertEquals(1, registry.size());
        
        registry.removeRoute("users-collection");
        
        assertEquals(0, registry.size());
        assertFalse(registry.findByPath("/api/users/**").isPresent());
    }
    
    @Test
    void testRemoveNonExistentRoute() {
        RouteDefinition route = createRoute("users-collection", "/api/users/**");
        registry.addRoute(route);
        
        registry.removeRoute("non-existent-id");
        
        assertEquals(1, registry.size());
    }
    
    @Test
    void testRemoveNullRouteId() {
        RouteDefinition route = createRoute("users-collection", "/api/users/**");
        registry.addRoute(route);
        
        registry.removeRoute(null);
        
        assertEquals(1, registry.size());
    }
    
    @Test
    void testUpdateRoute() {
        RouteDefinition route1 = createRoute("users-collection", "/api/users/**");
        registry.addRoute(route1);
        
        // Update with same ID but different path
        RouteDefinition route2 = new RouteDefinition(
            "users-collection",
            "/api/v2/users/**",
            "http://user-service-v2:8080",
            "users"
        );
        
        registry.updateRoute(route2);
        
        assertEquals(1, registry.size());
        assertFalse(registry.findByPath("/api/users/**").isPresent());
        assertTrue(registry.findByPath("/api/v2/users/**").isPresent());
    }
    
    @Test
    void testUpdateRouteSamePathNeverDropsRoute() throws Exception {
        // Regression: updateRoute used to remove-then-add, leaving a window with no
        // route for the collection — a concurrent reader (SCG route rebuild) caught
        // mid-update saw 404s until the next config event.
        registry.addRoute(createRoute("users-collection", "/api/users/**"));

        java.util.concurrent.atomic.AtomicBoolean missing = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            while (running.get()) {
                if (registry.findByPath("/api/users/**").isEmpty()) {
                    missing.set(true);
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 5_000; i++) {
                registry.updateRoute(createRoute("users-collection", "/api/users/**"));
            }
        } finally {
            running.set(false);
            reader.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(missing.get(), "route must never be absent during same-path updates");
        assertEquals(1, registry.size());
    }

    @Test
    void testUpdateNullRoute() {
        RouteDefinition route = createRoute("users-collection", "/api/users/**");
        registry.addRoute(route);
        
        registry.updateRoute(null);
        
        assertEquals(1, registry.size());
    }
    
    @Test
    void testFindByPath() {
        RouteDefinition route = createRoute("users-collection", "/api/users/**");
        registry.addRoute(route);
        
        Optional<RouteDefinition> found = registry.findByPath("/api/users/**");
        
        assertTrue(found.isPresent());
        assertEquals(route, found.get());
    }
    
    @Test
    void testFindByPathNotFound() {
        Optional<RouteDefinition> found = registry.findByPath("/api/nonexistent/**");
        
        assertFalse(found.isPresent());
    }
    
    @Test
    void testFindByNullPath() {
        Optional<RouteDefinition> found = registry.findByPath(null);
        
        assertFalse(found.isPresent());
    }
    
    @Test
    void testFindByEmptyPath() {
        Optional<RouteDefinition> found = registry.findByPath("");
        
        assertFalse(found.isPresent());
    }
    
    @Test
    void testGetAllRoutes() {
        RouteDefinition route1 = createRoute("users-collection", "/api/users/**");
        RouteDefinition route2 = createRoute("posts-collection", "/api/posts/**");
        RouteDefinition route3 = createRoute("comments-collection", "/api/comments/**");
        
        registry.addRoute(route1);
        registry.addRoute(route2);
        registry.addRoute(route3);
        
        List<RouteDefinition> allRoutes = registry.getAllRoutes();
        
        assertEquals(3, allRoutes.size());
        assertTrue(allRoutes.contains(route1));
        assertTrue(allRoutes.contains(route2));
        assertTrue(allRoutes.contains(route3));
    }
    
    @Test
    void testGetAllRoutesReturnsDefensiveCopy() {
        RouteDefinition route = createRoute("users-collection", "/api/users/**");
        registry.addRoute(route);
        
        List<RouteDefinition> allRoutes = registry.getAllRoutes();
        allRoutes.clear();
        
        // Original registry should still have the route
        assertEquals(1, registry.size());
    }
    
    @Test
    void testClear() {
        registry.addRoute(createRoute("users-collection", "/api/users/**"));
        registry.addRoute(createRoute("posts-collection", "/api/posts/**"));
        registry.addRoute(createRoute("comments-collection", "/api/comments/**"));
        
        assertEquals(3, registry.size());
        
        registry.clear();
        
        assertEquals(0, registry.size());
        assertTrue(registry.isEmpty());
    }
    
    @Test
    void testConcurrentAddOperations() throws InterruptedException {
        int threadCount = 10;
        int routesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < routesPerThread; j++) {
                        String id = "route-" + threadId + "-" + j;
                        String path = "/api/thread" + threadId + "/resource" + j + "/**";
                        registry.addRoute(createRoute(id, path));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        assertEquals(threadCount * routesPerThread, registry.size());
    }
    
    @Test
    void testConcurrentMixedOperations() throws InterruptedException {
        // Pre-populate with some routes
        for (int i = 0; i < 50; i++) {
            registry.addRoute(createRoute("route-" + i, "/api/resource" + i + "/**"));
        }
        
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        // Thread 1: Add routes
        executor.submit(() -> {
            try {
                for (int i = 50; i < 100; i++) {
                    registry.addRoute(createRoute("route-" + i, "/api/resource" + i + "/**"));
                }
            } finally {
                latch.countDown();
            }
        });
        
        // Thread 2: Remove routes
        executor.submit(() -> {
            try {
                for (int i = 0; i < 25; i++) {
                    registry.removeRoute("route-" + i);
                }
            } finally {
                latch.countDown();
            }
        });
        
        // Thread 3: Update routes
        executor.submit(() -> {
            try {
                for (int i = 25; i < 50; i++) {
                    registry.updateRoute(createRoute("route-" + i, "/api/v2/resource" + i + "/**"));
                }
            } finally {
                latch.countDown();
            }
        });
        
        // Thread 4: Read routes
        executor.submit(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    registry.findByPath("/api/resource" + i + "/**");
                    registry.getAllRoutes();
                }
            } finally {
                latch.countDown();
            }
        });
        
        // Thread 5: Read routes
        executor.submit(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    registry.getAllRoutes();
                    registry.size();
                }
            } finally {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify registry is in a consistent state
        int finalSize = registry.size();
        assertEquals(finalSize, registry.getAllRoutes().size());
        assertTrue(finalSize > 0); // Should have some routes remaining
    }
    
    @Test
    void wildcardMatchRequiresSegmentBoundary() {
        // Regression: /api/inventory/** must not shadow /api/inventory-items —
        // a raw startsWith authorized hyphenated sibling collections against
        // the wrong route's collection (found via cross-tenant name overlap).
        registry.addRoute(createRoute("inventory", "/api/inventory/**"));
        registry.addRoute(createRoute("inventory-items", "/api/inventory-items/**"));

        assertEquals("inventory-items",
            registry.findByPath("/api/inventory-items").orElseThrow().getId());
        assertEquals("inventory-items",
            registry.findByPath("/api/inventory-items/123").orElseThrow().getId());
        assertEquals("inventory",
            registry.findByPath("/api/inventory").orElseThrow().getId());
        assertEquals("inventory",
            registry.findByPath("/api/inventory/123").orElseThrow().getId());
    }

    @Test
    void singleSegmentWildcardRequiresSegmentBoundary() {
        registry.addRoute(createRoute("inventory", "/api/inventory/*"));

        assertTrue(registry.findByPath("/api/inventory/123").isPresent());
        assertFalse(registry.findByPath("/api/inventory-items").isPresent());
        assertFalse(registry.findByPath("/api/inventory-items/123").isPresent());
        assertFalse(registry.findByPath("/api/inventory/1/2").isPresent());
    }

    @Test
    void dynamicCollectionRouteDoesNotShadowMemberStaticRoute() {
        // Member-facing static route registered first (bootstrap)…
        registry.addRoute(createRoute("static-watches", "/api/watches/**"));
        // …then the 'watches' system collection's generic route tries to take the
        // same path. It must be ignored so the API_ACCESS-only controller route wins
        // (otherwise per-resource Cerbos runs and a portal member gets 403).
        registry.addRoute(createRoute("3307cdef-collection-uuid", "/api/watches/**"));

        RouteDefinition resolved = registry.findByPath("/api/watches").orElseThrow();
        assertEquals("static-watches", resolved.getId(),
            "the static member route must own /api/watches, not the collection route");
    }

    @Test
    void updateRouteAlsoRejectsShadowOfMemberStaticRoute() {
        registry.addRoute(createRoute("static-wins", "/api/wins/**"));
        // NATS collection-update path must not clobber it either.
        registry.updateRoute(createRoute("wins-collection-uuid", "/api/wins/**"));

        assertEquals("static-wins", registry.findByPath("/api/wins").orElseThrow().getId());
    }

    @Test
    void nonMemberStaticRouteIsStillOverwritableByCollectionRoute() {
        // A config collection (e.g. flows) relies on its generic route's per-resource
        // Cerbos check — the dynamic route may still replace the bootstrap static one.
        registry.addRoute(createRoute("static-flows", "/api/flows/**"));
        registry.addRoute(createRoute("flows-collection-uuid", "/api/flows/**"));

        RouteDefinition resolved = registry.findByPath("/api/flows").orElseThrow();
        assertEquals("flows-collection-uuid", resolved.getId(),
            "non-member static routes keep last-write-wins so per-resource Cerbos still applies");
    }

        @Test
    void platformOwnedPrefixesCannotBeTakenOverByATenantCollection() {
        // ConfigEventListener builds "/api/<collectionName>/**" for every collection, and this
        // registry replaces by path with no tenant in the key -- so a collection named "modules",
        // "files" or "images" would take the prefix over for EVERY tenant. Module HTTP routes,
        // signed-JAR upload, file serving and image transforms all hang off these.
        for (String path : List.of("/api/modules/**", "/api/files/**", "/api/images/**")) {
            registry.addRoute(createRoute("static-" + path, path));
            registry.addRoute(createRoute("collection-uuid-pretending-to-own-" + path, path));

            Optional<RouteDefinition> owner = registry.findByPath(path);
            assertTrue(owner.isPresent(), path + " lost its route entirely");
            assertEquals("static-" + path, owner.get().getId(),
                    path + " was taken over by a dynamic collection route");
        }
    }

private RouteDefinition createRoute(String id, String path) {
        return new RouteDefinition(
            id,
            path,
            "http://backend-" + id + ":8080",
            "collection-" + id
        );
    }

    // ---- tenant-scoped resolution: same path, different tenants (parent + sandbox clone, two customers)

    private static RouteDefinition tenantRoute(String id, String path, String name, String tenantId) {
        return new RouteDefinition(id, path, "http://worker:80", name, null, 0, tenantId);
    }

    @Test
    void sameNamedCollectionsInTwoTenantsBothResolveToTheirOwn() {
        registry.addRoute(tenantRoute("coll-a", "/api/tasks/**", "tasks", "tenant-a"));
        registry.addRoute(tenantRoute("coll-b", "/api/tasks/**", "tasks", "tenant-b"));

        assertEquals("coll-a", registry.findByPath("/api/tasks/123", "tenant-a").orElseThrow().getId());
        assertEquals("coll-b", registry.findByPath("/api/tasks/123", "tenant-b").orElseThrow().getId());
        assertEquals("coll-a", registry.findByPath("/api/tasks", "tenant-a").orElseThrow().getId());
        // one proxy route per path, both entries retained
        assertEquals(1, registry.getRoutesByPath().size());
        assertEquals(2, registry.getAllRoutes().size());
        assertEquals(1, registry.size());
    }

    @Test
    void aLaterTenantDoesNotShadowAnEarlierOne() {
        registry.addRoute(tenantRoute("coll-parent", "/api/tasks/**", "tasks", "tenant-parent"));
        registry.updateRoute(tenantRoute("coll-sandbox", "/api/tasks/**", "tasks", "tenant-sandbox"));

        assertEquals("coll-parent", registry.findByPath("/api/tasks/1", "tenant-parent").orElseThrow().getId());
        assertEquals("coll-sandbox", registry.findByPath("/api/tasks/1", "tenant-sandbox").orElseThrow().getId());
    }

    @Test
    void anotherTenantsRouteIsReturnedWhenTheCallerHasNoneSoAuthorizationStillRuns() {
        registry.addRoute(tenantRoute("coll-b", "/api/orders/**", "orders", "tenant-b"));

        Optional<RouteDefinition> seenByA = registry.findByPath("/api/orders/9", "tenant-a");
        assertTrue(seenByA.isPresent(), "a real collection lives at the path — the filter must check it, not fall through");
        assertEquals("coll-b", seenByA.get().getId());
    }

    @Test
    void platformWideRouteWinsOverAnotherTenantsButNotOverTheCallersOwn() {
        registry.addRoute(new RouteDefinition("static-flows", "/api/flows/**", "http://worker:80", "flows"));
        registry.addRoute(tenantRoute("coll-b-flows", "/api/flows/**", "flows", "tenant-b"));

        assertEquals("static-flows", registry.findByPath("/api/flows/1", "tenant-a").orElseThrow().getId());
        assertEquals("static-flows", registry.findByPath("/api/flows/1").orElseThrow().getId());
        assertEquals("coll-b-flows", registry.findByPath("/api/flows/1", "tenant-b").orElseThrow().getId());
    }

    @Test
    void removingOneTenantsRouteLeavesTheOthersAtThatPath() {
        registry.addRoute(tenantRoute("coll-a", "/api/tasks/**", "tasks", "tenant-a"));
        registry.addRoute(tenantRoute("coll-b", "/api/tasks/**", "tasks", "tenant-b"));

        registry.removeRoute("coll-b");

        assertEquals("coll-a", registry.findByPath("/api/tasks/1", "tenant-a").orElseThrow().getId());
        assertEquals("coll-a", registry.findByPath("/api/tasks/1", "tenant-b").orElseThrow().getId(),
                "with tenant-b's collection gone, tenant-a's is the only one at the path (and will deny tenant-b)");
        registry.removeRoute("coll-a");
        assertTrue(registry.findByPath("/api/tasks/1", "tenant-a").isEmpty());
        assertEquals(0, registry.size(), "an emptied path is dropped");
    }

    @Test
    void renamePrunesOnlyThatTenantsStaleEntry() {
        registry.addRoute(tenantRoute("coll-a", "/api/jobs/**", "jobs", "tenant-a"));
        registry.addRoute(tenantRoute("coll-b", "/api/jobs/**", "jobs", "tenant-b"));

        registry.updateRoute(tenantRoute("coll-a", "/api/work/**", "work", "tenant-a"));

        assertEquals("coll-a", registry.findByPath("/api/work/1", "tenant-a").orElseThrow().getId());
        assertEquals("coll-b", registry.findByPath("/api/jobs/1", "tenant-b").orElseThrow().getId());
        assertEquals("coll-b", registry.findByPath("/api/jobs/1", "tenant-a").orElseThrow().getId(),
                "tenant-a no longer has jobs; tenant-b's route is what remains at that path");
    }
}
