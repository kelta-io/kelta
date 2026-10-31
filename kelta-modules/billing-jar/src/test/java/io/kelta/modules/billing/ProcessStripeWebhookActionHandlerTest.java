package io.kelta.modules.billing;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscriptions created before module routes received the platform user UUID carry the member's
 * email in Stripe metadata, and Stripe replays it on every renewal. Each case here is a way the
 * webhook could key a paying member's row by something {@link EntitlementResolver} never looks up.
 */
@DisplayName("ProcessStripeWebhookActionHandler — member resolution")
class ProcessStripeWebhookActionHandlerTest {

    private static final String TENANT = "t1";
    private static final String SECRET = "whsec_test";
    private static final String MEMBER = "6f1c2a4e-9b7d-4e1a-8c3f-2d5e7a9b0c1d";
    private static final String EMAIL = "someone@example.com";

    private InMemoryStore store;
    private BillingCollections collections;
    private ProcessStripeWebhookActionHandler handler;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        collections = store.collections();
        CredentialResolverPort credentials = (tenantId, reference, purpose) ->
                new ResolvedCredential("c1", reference, "API_KEY",
                        Map.of("webhookSecret", SECRET), Map.of(), Instant.now());
        handler = new ProcessStripeWebhookActionHandler(collections, credentials,
                new StripeSignatureVerifier(), new ObjectMapper());

        store.insert(BillingCollections.PLANS, row("plan-free", Map.of(
                "code", "FREE", "kind", "DEFAULT", "active", true,
                "entitlements", Map.of("watches", 1))));
        store.insert(BillingCollections.PLANS, row("plan-pro", Map.of(
                "code", "PRO", "kind", "SUBSCRIPTION", "active", true,
                "stripePriceId", "price_pro", "entitlements", Map.of("watches", 25))));
    }

    @Test
    @DisplayName("A legacy email in metadata.userId resolves through the customer's UUID mapping")
    void legacyEmailMetadataResolvesThroughCustomerMapping() {
        store.insert(BillingCollections.CUSTOMERS, row("cust-row", Map.of(
                "userId", MEMBER, "stripeCustomerId", "cus_1")));

        ActionResult result = deliver(subscriptionEvent("sub_1", "cus_1", EMAIL));

        assertThat(result.successful()).isTrue();
        assertThat(store.rows(BillingCollections.SUBSCRIPTIONS))
                .singleElement()
                .satisfies(sub -> {
                    assertThat(sub).containsEntry("userId", MEMBER);
                    assertThat(sub).containsEntry("planId", "plan-pro");
                });
        assertThat(new EntitlementResolver(collections).resolve(MEMBER).planCode())
                .isEqualTo("PRO");
    }

    @Test
    @DisplayName("A UUID in metadata.userId is used unchanged")
    void uuidMetadataIsUsedUnchanged() {
        ActionResult result = deliver(subscriptionEvent("sub_2", "cus_2", MEMBER));

        assertThat(result.successful()).isTrue();
        assertThat(store.rows(BillingCollections.SUBSCRIPTIONS))
                .singleElement()
                .satisfies(sub -> assertThat(sub).containsEntry("userId", MEMBER));

        MemberEntitlements entitlements = new EntitlementResolver(collections).resolve(MEMBER);
        assertThat(entitlements.planCode()).isEqualTo("PRO");
        assertThat(entitlements.subscriptionStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("An email with no UUID customer mapping is skipped, never written under the email")
    void emailWithoutUuidMappingIsSkipped() {
        store.insert(BillingCollections.CUSTOMERS, row("cust-row", Map.of(
                "userId", EMAIL, "stripeCustomerId", "cus_3")));

        ActionResult result = deliver(subscriptionEvent("sub_3", "cus_3", EMAIL));

        assertThat(result.successful()).isTrue();
        assertThat(store.rows(BillingCollections.SUBSCRIPTIONS)).isEmpty();
    }

    @Test
    @DisplayName("Checkout completion with an email client_reference_id maps the customer by UUID")
    void legacyCheckoutCompletionKeepsUuidMapping() {
        store.insert(BillingCollections.CUSTOMERS, row("cust-row", Map.of(
                "userId", MEMBER, "stripeCustomerId", "cus_4")));

        deliver("{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{"
                + "\"id\":\"cs_4\",\"mode\":\"subscription\",\"customer\":\"cus_4\","
                + "\"client_reference_id\":\"" + EMAIL + "\","
                + "\"metadata\":{\"userId\":\"" + EMAIL + "\"}}}}");

        assertThat(store.rows(BillingCollections.CUSTOMERS))
                .singleElement()
                .satisfies(c -> assertThat(c).containsEntry("userId", MEMBER));
    }

    @Test
    @DisplayName("Only canonical UUIDs count as a platform user id")
    void isUuid() {
        assertThat(ProcessStripeWebhookActionHandler.isUuid(MEMBER)).isTrue();
        assertThat(ProcessStripeWebhookActionHandler.isUuid(EMAIL)).isFalse();
        assertThat(ProcessStripeWebhookActionHandler.isUuid("u1")).isFalse();
        assertThat(ProcessStripeWebhookActionHandler.isUuid(null)).isFalse();
    }

    // ------------------------------------------------------------- Helpers

    private ActionResult deliver(String body) {
        long ts = Instant.now().getEpochSecond();
        String signature = "t=" + ts + ",v1=" + hmac(ts + "." + body);
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("rawBody", body);
        resolved.put("headers", Map.of("stripe-signature", signature));
        return handler.execute(ActionContext.builder()
                .tenantId(TENANT).resolvedData(resolved).build());
    }

    private static String subscriptionEvent(String subscriptionId, String customerId,
                                            String metadataUserId) {
        return "{\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{"
                + "\"id\":\"" + subscriptionId + "\",\"customer\":\"" + customerId + "\","
                + "\"status\":\"active\",\"current_period_end\":4102444800,"
                + "\"metadata\":{\"userId\":\"" + metadataUserId + "\"},"
                + "\"items\":{\"data\":[{\"price\":{\"id\":\"price_pro\"}}]}}}}";
    }

    private static String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> row(String id, Map<String, Object> values) {
        Map<String, Object> row = new LinkedHashMap<>(values);
        row.put("id", id);
        return row;
    }

    /**
     * Rows per collection behind the real {@link BillingCollections}, matching EQ filters and
     * applying creates and updates. Dynamic proxies rather than Mockito, for the reason given in
     * {@code EntitlementResolverTest}.
     */
    private static final class InMemoryStore {
        private final Map<String, List<Map<String, Object>>> rowsByCollection =
                new LinkedHashMap<>();

        void insert(String collection, Map<String, Object> row) {
            rowsByCollection.computeIfAbsent(collection, k -> new ArrayList<>())
                    .add(new LinkedHashMap<>(row));
        }

        List<Map<String, Object>> rows(String collection) {
            return rowsByCollection.getOrDefault(collection, List.of());
        }

        @SuppressWarnings("unchecked")
        BillingCollections collections() {
            QueryEngine engine = (QueryEngine) Proxy.newProxyInstance(
                    QueryEngine.class.getClassLoader(), new Class<?>[]{QueryEngine.class},
                    (proxy, method, args) -> {
                        String name = ((CollectionDefinition) args[0]).name();
                        return switch (method.getName()) {
                            case "executeQuery" -> {
                                QueryRequest request = (QueryRequest) args[1];
                                List<Map<String, Object>> matched = rows(name).stream()
                                        .filter(r -> request.filters().stream()
                                                .allMatch(f -> matches(r, f)))
                                        .limit(request.pagination().pageSize())
                                        .toList();
                                yield QueryResult.of(matched, matched.size(),
                                        request.pagination());
                            }
                            case "getById" -> rows(name).stream()
                                    .filter(r -> args[1].equals(r.get("id")))
                                    .findFirst();
                            case "create" -> {
                                Map<String, Object> created =
                                        new LinkedHashMap<>((Map<String, Object>) args[1]);
                                created.put("id", UUID.randomUUID().toString());
                                insert(name, created);
                                yield created;
                            }
                            case "update" -> {
                                Optional<Map<String, Object>> existing = rows(name).stream()
                                        .filter(r -> args[1].equals(r.get("id")))
                                        .findFirst();
                                existing.ifPresent(r -> r.putAll((Map<String, Object>) args[2]));
                                yield existing;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
            CollectionRegistry registry = (CollectionRegistry) Proxy.newProxyInstance(
                    CollectionRegistry.class.getClassLoader(),
                    new Class<?>[]{CollectionRegistry.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("get")) {
                            return CollectionDefinition.builder()
                                    .name((String) args[0])
                                    .addField(FieldDefinition.string("userId", 100))
                                    .build();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            return new BillingCollections(engine, registry);
        }

        private static boolean matches(Map<String, Object> row, FilterCondition filter) {
            return java.util.Objects.equals(row.get(filter.fieldName()), filter.value());
        }
    }
}
