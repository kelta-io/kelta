package io.kelta.testharness.fixtures;

import io.kelta.testharness.KeltaStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Provisions the {@code threadline-clothing} e-commerce fixture tenant at harness
 * startup, entirely through the worker's admin REST API (via the gateway) — the
 * replacement for the Flyway V50 demo seed that the migration flatten removed.
 *
 * <p>Historically Flyway V50 seeded a {@code threadline-clothing} tenant with nine
 * e-commerce collections. Several scenarios ({@code RecordMerge}, {@code FieldHistory},
 * {@code FieldMaskingPermission}, {@code RecordShareWidening}, {@code CollectionLifecycle},
 * {@code TenantIsolation}) treat that tenant as a fixture. This class recreates a
 * lean version of it so those scenarios keep passing without V50:
 * <ul>
 *   <li>Creates the tenant by POSTing a record to the {@code tenants} system collection
 *       against the {@code default} tenant. The worker's {@code TenantLifecycleHook}
 *       (schema creation) + {@code TenantProvisioningHook} (default profiles, internal
 *       OIDC provider, admin user, Cerbos policy sync, PROVISIONING → ACTIVE) fire on
 *       create, yielding a fully routable ACTIVE tenant.</li>
 *   <li>Seeds three user collections through {@code POST /{slug}/api/collections} +
 *       {@code POST /{slug}/api/fields}:
 *       <ul>
 *         <li>{@code customers} — {@code name}, {@code first_name}, {@code last_name},
 *             and a UNIQUE {@code email}</li>
 *         <li>{@code orders} — {@code name}, {@code status}, {@code order_date},
 *             {@code subtotal}, {@code tax_amount}, {@code total_amount}, and a
 *             {@code customer} LOOKUP → customers</li>
 *         <li>{@code products} — {@code name}, {@code sku}</li>
 *       </ul></li>
 * </ul>
 *
 * <p>The provisioning hook seeds the tenant admin as {@code <slug>-admin@kelta.local}
 * (username {@code <slug>-admin}) with no usable password, so this fixture gives it the
 * harness-only credential ({@link AuthFixture#setProvisionedAdminCredential(String)}) —
 * {@link AuthFixture} derives that identity for any non-{@code default} slug, so
 * {@code auth.loginAsAdmin(ECOMMERCE_SLUG)} authenticates against this tenant.
 *
 * <p><b>Idempotent + run-once:</b> {@link #seedOnce()} short-circuits if the tenant is
 * already in the slug-map (repeated JVM runs against a shared CI database), and collection
 * creation tolerates a pre-existing collection. It is invoked exactly once from
 * {@link KeltaStack#start()} after the gateway is healthy.
 */
public final class EcommerceSeedFixture {

    private static final Logger log = LoggerFactory.getLogger(EcommerceSeedFixture.class);

    private final AuthFixture auth = new AuthFixture();
    private final TenantFixture tenants = new TenantFixture();

    /**
     * Creates the ecommerce fixture tenant and its collections if not already present.
     * Safe to call more than once; the guard on the slug-map keeps it idempotent.
     */
    public void seedOnce() {
        String existing = tenants.tenantIdForSlug(TenantFixture.ECOMMERCE_SLUG);
        if (existing != null) {
            log.info("Ecommerce fixture tenant '{}' already present ({}), skipping seed",
                    TenantFixture.ECOMMERCE_SLUG, existing);
            // Rerun against a pre-seeded CI schema: the collections exist in the DB but the
            // fresh gateway/worker only pick the tenant's dynamic routes up on the periodic
            // bootstrap refresh (observed ~2 minutes). Block here — once, for everyone —
            // so scenarios don't each burn (and overrun) their own wait budgets.
            // The credential is re-set too: a schema seeded before PLT-337 carries the old one.
            setAdminCredential();
            String token = auth.loginAsAdmin(TenantFixture.ECOMMERCE_SLUG);
            waitForStatus(gatewayClient(token),
                    "/" + TenantFixture.ECOMMERCE_SLUG + "/api/customers", HttpStatus.OK, 360);
            log.info("Ecommerce fixture routes are live after rerun warm-up");
            return;
        }

        log.info("Seeding ecommerce fixture tenant '{}' via admin API", TenantFixture.ECOMMERCE_SLUG);
        createTenant();
        waitForTenantActive();
        setAdminCredential();

        String token = auth.loginAsAdmin(TenantFixture.ECOMMERCE_SLUG);
        RestClient client = gatewayClient(token);
        String base = "/" + TenantFixture.ECOMMERCE_SLUG;

        // The tenant's own /api/collections route must be live before we create collections.
        waitForStatus(client, base + "/api/collections", HttpStatus.OK, 40);

        // customers — a UNIQUE email so duplicate-detection / merge scenarios have a match key.
        String customersId = createCollection(client, base, "customers", "Customers");
        addStringField(client, base, customersId, "name", false);
        addStringField(client, base, customersId, "first_name", false);
        addStringField(client, base, customersId, "last_name", false);
        addStringField(client, base, customersId, "email", true);

        // orders — a real inbound LOOKUP onto customers for the record-merge re-parent proof.
        String ordersId = createCollection(client, base, "orders", "Orders");
        addStringField(client, base, ordersId, "name", false);
        addStringField(client, base, ordersId, "status", false);
        addStringField(client, base, ordersId, "order_date", false);
        addDoubleField(client, base, ordersId, "subtotal");
        addDoubleField(client, base, ordersId, "tax_amount");
        addDoubleField(client, base, ordersId, "total_amount");
        addLookupField(client, base, ordersId, "customer", customersId);

        // products — a second collection with ≥2 fields (field-masking scenario needs ≥2 fields).
        String productsId = createCollection(client, base, "products", "Products");
        addStringField(client, base, productsId, "name", false);
        addStringField(client, base, productsId, "sku", false);

        log.info("Ecommerce fixture tenant '{}' seeded (customers, orders, products)",
                TenantFixture.ECOMMERCE_SLUG);
    }

    // ------------------------------------------------------------------
    // Tenant creation
    // ------------------------------------------------------------------

    /**
     * Creates the tenant record on the {@code default} tenant's {@code tenants} collection.
     * The record write triggers the worker's tenant lifecycle + provisioning hooks.
     */
    @SuppressWarnings("unchecked")
    private void createTenant() {
        String adminToken = auth.loginAsAdmin(TenantFixture.DEFAULT_SLUG);
        RestClient client = gatewayClient(adminToken);

        Map<String, Object> body = Map.of("data", Map.of(
                "type", "tenants",
                "attributes", Map.of(
                        "slug", TenantFixture.ECOMMERCE_SLUG,
                        "name", "Threadline Clothing Co.",
                        "edition", "ENTERPRISE")));

        ResponseEntity<Map> response = client.post()
                .uri("/" + TenantFixture.DEFAULT_SLUG + "/api/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "Failed to create ecommerce fixture tenant: HTTP " + response.getStatusCode());
        }
        log.info("Created ecommerce fixture tenant record (HTTP {})", response.getStatusCode());
    }

    /**
     * Gives the provisioned tenant admin the harness-only credential. {@code TenantProvisioningHook}
     * seeds every runtime-provisioned admin with no usable password (a real tenant claims it by
     * invite), so the harness writes its own — and clears {@code force_change_on_login}, which
     * would otherwise fail direct-login with "credentials have expired".
     */
    private void setAdminCredential() {
        int updated = AuthFixture.setProvisionedAdminCredential(TenantFixture.ECOMMERCE_SLUG);
        log.info("Set harness admin credential for '{}' ({} credential row(s))",
                TenantFixture.ECOMMERCE_SLUG, updated);
        if (updated == 0) {
            throw new IllegalStateException("No seeded admin credential for " + TenantFixture.ECOMMERCE_SLUG);
        }
    }

    /**
     * Polls the worker slug-map until the ecommerce tenant is registered. The
     * provisioning hook transitions it PROVISIONING → ACTIVE synchronously in
     * {@code afterCreate}, and the slug-map includes ACTIVE + PROVISIONING tenants,
     * so a present entry means the record write and schema creation completed.
     */
    private void waitForTenantActive() {
        for (int i = 0; i < 60; i++) {
            String id = tenants.tenantIdForSlug(TenantFixture.ECOMMERCE_SLUG);
            if (id != null) {
                log.info("Ecommerce fixture tenant '{}' registered in slug-map ({})",
                        TenantFixture.ECOMMERCE_SLUG, id);
                return;
            }
            sleep();
        }
        throw new AssertionError("Ecommerce fixture tenant '" + TenantFixture.ECOMMERCE_SLUG
                + "' never appeared in the worker slug-map after provisioning");
    }

    // ------------------------------------------------------------------
    // Collection + field creation (mirrors SandboxPromotionScenarioTest helpers)
    // ------------------------------------------------------------------

    /**
     * Creates a user collection, or returns the id of the existing one if a prior run
     * already seeded it. Waits for the dynamic record route so later work sees it live.
     */
    @SuppressWarnings("unchecked")
    private String createCollection(RestClient client, String base, String name, String displayName) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "collections",
                "attributes", Map.of(
                        "name", name,
                        "displayName", displayName,
                        "tenantScoped", true)));

        ResponseEntity<Map> response = client.post().uri(base + "/api/collections")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> { /* inspect status manually */ })
                .toEntity(Map.class);

        String id;
        if (response.getStatusCode().is2xxSuccessful()) {
            id = (String) ((Map<String, Object>) response.getBody().get("data")).get("id");
        } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
            id = lookupCollectionId(client, base, name);
            log.info("Collection '{}' already exists ({}), reusing", name, id);
        } else {
            throw new IllegalStateException(
                    "Failed to create collection '" + name + "': HTTP " + response.getStatusCode());
        }
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("No collection id resolved for '" + name + "'");
        }

        waitForStatus(client, base + "/api/" + name, HttpStatus.OK, 40);
        return id;
    }

    @SuppressWarnings("unchecked")
    private String lookupCollectionId(RestClient client, String base, String name) {
        Map<String, Object> body = client.get()
                .uri(base + "/api/collections?filter[name][eq]=" + name)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = body == null ? null : (List<Map<String, Object>>) body.get("data");
        if (data == null || data.isEmpty()) {
            return null;
        }
        return (String) data.get(0).get("id");
    }

    /**
     * Adds a STRING field. When {@code unique} is true, sets {@code uniqueConstraint}
     * so the physical table materializes a UNIQUE index (mirrors the MCP add_field body).
     */
    private void addStringField(RestClient client, String base, String collectionId,
                                String fieldName, boolean unique) {
        java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("collectionId", collectionId);
        attributes.put("name", fieldName);
        attributes.put("type", "STRING");
        if (unique) {
            attributes.put("uniqueConstraint", true);
        }

        Map<String, Object> body = Map.of("data", Map.of(
                "type", "fields",
                "attributes", attributes));

        createField(client, base, fieldName, body);
    }

    /** A DOUBLE (floating-point) field — e.g. the order money columns the merge scenario writes. */
    private void addDoubleField(RestClient client, String base, String collectionId, String fieldName) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "fields",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", fieldName,
                        "type", "DOUBLE")));
        createField(client, base, fieldName, body);
    }

    /** Mirrors the MCP add_field lookup body: referenceCollectionId as a to-one relationship. */
    private void addLookupField(RestClient client, String base, String collectionId,
                                String fieldName, String referenceCollectionId) {
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "fields",
                "attributes", Map.of(
                        "collectionId", collectionId,
                        "name", fieldName,
                        "type", "LOOKUP",
                        "relationshipName", fieldName),
                "relationships", Map.of(
                        "referenceCollectionId", Map.of(
                                "data", Map.of("type", "collections", "id", referenceCollectionId)))));

        createField(client, base, fieldName, body);
    }

    private void createField(RestClient client, String base, String fieldName, Map<String, Object> body) {
        ResponseEntity<Void> response = client.post().uri(base + "/api/fields")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve()
                .onStatus(s -> true, (req, resp) -> { /* inspect status manually */ })
                .toBodilessEntity();

        // 2xx = created; 409 = field already exists from a prior run (idempotent).
        if (!response.getStatusCode().is2xxSuccessful()
                && response.getStatusCode() != HttpStatus.CONFLICT) {
            throw new IllegalStateException(
                    "Failed to create field '" + fieldName + "': HTTP " + response.getStatusCode());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private RestClient gatewayClient(String token) {
        return RestClient.builder()
                .baseUrl(KeltaStack.gatewayBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    private void waitForStatus(RestClient client, String uri, HttpStatusCode expected, int maxAttempts) {
        HttpStatusCode lastStatus = null;
        String lastBody = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                org.springframework.http.ResponseEntity<String> resp = client.get().uri(uri).retrieve()
                        .onStatus(s -> true, (req, r) -> { })
                        .toEntity(String.class);
                lastStatus = resp.getStatusCode();
                lastBody = resp.getBody();
                if (lastStatus.equals(expected)) {
                    return;
                }
            } catch (RuntimeException ex) {
                lastBody = ex.getMessage();
            }
            sleep();
        }
        String body = lastBody == null ? "" : lastBody.substring(0, Math.min(300, lastBody.length()));
        throw new AssertionError("URL " + uri + " did not return " + expected + " after " + maxAttempts
                + " attempts (lastStatus=" + lastStatus + ", body=" + body + ")");
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
