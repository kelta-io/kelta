package io.kelta.modules.billing;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionChangeListener;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quota hook decides whether a member's create goes through, so each case here is one a bug
 * would silently turn into "uncapped" or "blocked for everyone". The resolver is real, over fake
 * collections, so the value the hook reads is the one {@link EntitlementResolver} actually merges.
 */
@DisplayName("MemberEntitlementQuotaHook")
class MemberEntitlementQuotaHookTest {

    private static final String COLLECTION = "snapshots_taken";
    private static final String USER = "user-1";
    private static final String MESSAGE = "Snapshots are not in your plan.";

    private FakeCollections collections;
    private FakeQueryEngine queryEngine;
    private MemberEntitlementQuotaHook hook;

    @BeforeEach
    void setUp() {
        collections = new FakeCollections();
        queryEngine = new FakeQueryEngine();
        hook = new MemberEntitlementQuotaHook(collections, new EntitlementResolver(collections),
                new FakeRegistry(), queryEngine);
        collections.rules = List.of(Map.of(
                "collectionName", COLLECTION,
                "limitKey", "snapshots",
                "appliesTo", "ALL",
                "message", MESSAGE));
    }

    private BeforeSaveResult create() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", "x");
        record.put(MemberEntitlementQuotaHook.OWNER_FIELD, USER);
        return hook.beforeCreate(COLLECTION, record, "tenant-1");
    }

    private void defaultPlan(Map<String, Object> entitlements) {
        collections.withActivePlans(plan("plan-default", "DEFAULT", entitlements));
    }

    private static Map<String, Object> plan(String id, String kind, Map<String, Object> ent) {
        return Map.of("id", id, "code", id, "kind", kind, "entitlements", ent);
    }

    private static void assertRejectedWith(BeforeSaveResult result, String message) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).extracting(BeforeSaveResult.ValidationError::message)
                .containsExactly(message);
    }

    // ---------------------------------------------------------------- Boolean gates

    @Test
    @DisplayName("boolean false rejects with the rule's message and never counts")
    void booleanFalseRejects() {
        defaultPlan(Map.of("snapshots", false));

        assertRejectedWith(create(), MESSAGE);
        assertThat(queryEngine.aggregateCalls).isZero();
    }

    @Test
    @DisplayName("boolean true allows without a count query")
    void booleanTrueAllows() {
        defaultPlan(Map.of("snapshots", true));

        assertThat(create().isSuccess()).isTrue();
        assertThat(queryEngine.aggregateCalls).isZero();
    }

    @Test
    @DisplayName("a live pass granting true OR-merges over DEFAULT false and lifts the block")
    void passLiftsBooleanGate() {
        defaultPlan(Map.of("snapshots", false));
        collections.withPlan(plan("plan-pass", "ONE_TIME", Map.of("snapshots", true)));
        collections.passes = List.of(Map.of("planId", "plan-pass"));

        assertThat(create().isSuccess()).isTrue();
        assertThat(queryEngine.aggregateCalls).isZero();
    }

    // ---------------------------------------------------------------- Numeric caps

    @Test
    @DisplayName("under the numeric limit allows after one count")
    void underLimitAllows() {
        defaultPlan(Map.of("snapshots", 3));
        queryEngine.count = 2L;

        assertThat(create().isSuccess()).isTrue();
        assertThat(queryEngine.aggregateCalls).isEqualTo(1);
        assertThat(queryEngine.lastFilters).extracting(FilterCondition::fieldName)
                .containsExactly(MemberEntitlementQuotaHook.OWNER_FIELD);
    }

    @Test
    @DisplayName("at the numeric limit rejects with the rule's message")
    void atLimitRejects() {
        defaultPlan(Map.of("snapshots", 3));
        queryEngine.count = 3L;

        assertRejectedWith(create(), MESSAGE);
        assertThat(queryEngine.aggregateCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("a limit of 0 rejects without counting")
    void zeroRejects() {
        defaultPlan(Map.of("snapshots", 0));

        assertRejectedWith(create(), MESSAGE);
        assertThat(queryEngine.aggregateCalls).isZero();
    }

    @Test
    @DisplayName("a missing key allows without counting")
    void missingKeyAllows() {
        defaultPlan(Map.of("somethingElse", 5));

        assertThat(create().isSuccess()).isTrue();
        assertThat(queryEngine.aggregateCalls).isZero();
    }

    @Test
    @DisplayName("a negative limit means unlimited — allows without counting")
    void negativeIsUnlimited() {
        defaultPlan(Map.of("snapshots", -1));
        queryEngine.count = 1_000L;

        assertThat(create().isSuccess()).isTrue();
        assertThat(queryEngine.aggregateCalls).isZero();
    }

    @Test
    @DisplayName("a numeric string is parsed like an integer limit")
    void numericStringIsParsed() {
        defaultPlan(Map.of("snapshots", " 2 "));
        queryEngine.count = 2L;

        assertRejectedWith(create(), MESSAGE);
    }

    @Test
    @DisplayName("an unusable value fails open")
    void unusableValueFailsOpen() {
        defaultPlan(Map.of("snapshots", "lots"));

        assertThat(create().isSuccess()).isTrue();
        assertThat(queryEngine.aggregateCalls).isZero();
    }

    // ---------------------------------------------------------------- Fakes

    /** Hand-written for the same reason as {@code EntitlementResolverTest}'s: no inline mock-maker. */
    private static final class FakeCollections extends BillingCollections {
        private final Map<String, Map<String, Object>> plansById = new LinkedHashMap<>();
        private List<Map<String, Object>> active = List.of();
        private List<Map<String, Object>> passes = List.of();
        private List<Map<String, Object>> rules = List.of();

        FakeCollections() {
            super(null, null);
        }

        @Override
        public List<Map<String, Object>> activeRulesForCollection(String collectionName) {
            return rules.stream().filter(r -> collectionName.equals(r.get("collectionName"))).toList();
        }

        @Override
        public List<Map<String, Object>> activePlans() {
            return active;
        }

        @Override
        public Optional<Map<String, Object>> findPlanById(String id) {
            return Optional.ofNullable(plansById.get(id));
        }

        @Override
        public Optional<Map<String, Object>> findSubscriptionByUserId(String userId) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> livePasses(String userId, Instant now) {
            return passes;
        }

        @SafeVarargs
        final void withActivePlans(Map<String, Object>... plans) {
            active = List.of(plans);
            for (Map<String, Object> p : plans) {
                withPlan(p);
            }
        }

        void withPlan(Map<String, Object> p) {
            plansById.put(String.valueOf(p.get("id")), p);
        }
    }

    private static final class FakeQueryEngine implements QueryEngine {
        private int aggregateCalls;
        private long count;
        private List<FilterCondition> lastFilters = List.of();

        @Override
        public Map<String, Object> aggregate(CollectionDefinition definition,
                                             List<FilterCondition> filters,
                                             List<AggregationSpec> specs) {
            aggregateCalls++;
            lastFilters = List.copyOf(filters);
            return Map.of(specs.getFirst().alias(), count);
        }

        @Override
        public QueryResult executeQuery(CollectionDefinition definition, QueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Map<String, Object>> getById(CollectionDefinition definition, String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> create(CollectionDefinition definition, Map<String, Object> data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Map<String, Object>> update(CollectionDefinition definition, String id,
                                                    Map<String, Object> data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(CollectionDefinition definition, String id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeRegistry implements CollectionRegistry {
        private final CollectionDefinition definition = CollectionDefinition.builder()
                .name(COLLECTION)
                .addField(FieldDefinition.string("name"))
                .build();

        @Override
        public CollectionDefinition get(String collectionName) {
            return COLLECTION.equals(collectionName) ? definition : null;
        }

        @Override
        public void register(CollectionDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> getAllCollectionNames() {
            return Set.of(COLLECTION);
        }

        @Override
        public List<CollectionDefinition> getAllForCurrentTenant() {
            return List.of(definition);
        }

        @Override
        public void unregister(String collectionName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addListener(CollectionChangeListener listener) {
        }

        @Override
        public void removeListener(CollectionChangeListener listener) {
        }
    }
}
