package io.kelta.modules.billing;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A member who cancels and re-subscribes has several subscription rows, and an unsorted query
 * returns the oldest first — picking the wrong one bills a member for a plan they do not get.
 */
@DisplayName("BillingCollections")
class BillingCollectionsTest {

    /**
     * Serves fixed rows per collection through the real {@link BillingCollections} query path and
     * records each request. Dynamic proxies rather than Mockito, for the reason given in
     * {@code EntitlementResolverTest}.
     */
    static final class StubStore {
        final Map<String, List<Map<String, Object>>> rowsByCollection = new LinkedHashMap<>();
        final List<QueryRequest> requests = new ArrayList<>();

        StubStore rows(String collection, List<Map<String, Object>> rows) {
            rowsByCollection.put(collection, rows);
            return this;
        }

        BillingCollections collections() {
            QueryEngine engine = (QueryEngine) Proxy.newProxyInstance(
                    QueryEngine.class.getClassLoader(), new Class<?>[]{QueryEngine.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "executeQuery" -> {
                            QueryRequest request = (QueryRequest) args[1];
                            requests.add(request);
                            String name = ((CollectionDefinition) args[0]).name();
                            yield QueryResult.of(rowsByCollection.getOrDefault(name, List.of()),
                                    rowsByCollection.getOrDefault(name, List.of()).size(),
                                    request.pagination());
                        }
                        case "getById" -> {
                            String name = ((CollectionDefinition) args[0]).name();
                            yield rowsByCollection.getOrDefault(name, List.of()).stream()
                                    .filter(row -> args[1].equals(row.get("id")))
                                    .findFirst();
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
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
    }

    static Map<String, Object> subscription(String id, String status, String currentPeriodEnd,
                                            String createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("planId", "plan-" + id);
        row.put("status", status);
        row.put("currentPeriodEnd", currentPeriodEnd == null ? null : Instant.parse(currentPeriodEnd));
        row.put("createdAt", createdAt == null ? null : Instant.parse(createdAt));
        return row;
    }

    @Test
    @DisplayName("A canceled row returned first loses to the member's newer active row")
    void activeRowBeatsOlderCanceledRow() {
        StubStore store = new StubStore().rows(BillingCollections.SUBSCRIPTIONS, List.of(
                subscription("old", "canceled", "2026-03-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                subscription("new", "active", "2026-11-01T00:00:00Z", "2026-10-01T00:00:00Z")));

        Optional<Map<String, Object>> chosen = store.collections().findSubscriptionByUserId("u1");

        assertThat(chosen).map(row -> row.get("id")).contains("new");
    }

    @Test
    @DisplayName("An entitling row wins even when a canceled row has a later period end")
    void entitlingRowBeatsLaterCanceledRow() {
        Optional<Map<String, Object>> chosen = BillingCollections.currentSubscription(List.of(
                subscription("live", "past_due", "2026-10-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                subscription("dead", "canceled", "2026-12-01T00:00:00Z", "2026-02-01T00:00:00Z")));

        assertThat(chosen).map(row -> row.get("id")).contains("live");
    }

    @Test
    @DisplayName("All of a member's rows are fetched (bounded), not just the first")
    void fetchesEveryRowBeforeChoosing() {
        StubStore store = new StubStore().rows(BillingCollections.SUBSCRIPTIONS, List.of());

        store.collections().findSubscriptionByUserId("u1");

        assertThat(store.requests).singleElement()
                .satisfies(request -> assertThat(request.pagination().pageSize())
                        .isEqualTo(BillingCollections.MAX_SUBSCRIPTIONS_PER_MEMBER)
                        .isGreaterThan(1));
    }

    @Test
    @DisplayName("With only canceled rows the newest by period end is returned — what /me shows")
    void newestCanceledByPeriodEnd() {
        StubStore store = new StubStore().rows(BillingCollections.SUBSCRIPTIONS, List.of(
                subscription("older", "canceled", "2026-03-01T00:00:00Z", "2026-06-01T00:00:00Z"),
                subscription("newer", "canceled", "2026-09-01T00:00:00Z", "2026-02-01T00:00:00Z")));

        assertThat(store.collections().findSubscriptionByUserId("u1"))
                .map(row -> row.get("id")).contains("newer");
    }

    @Test
    @DisplayName("Canceled rows with the same period end fall back to createdAt")
    void canceledTieBrokenByCreatedAt() {
        Optional<Map<String, Object>> chosen = BillingCollections.currentSubscription(List.of(
                subscription("first", "canceled", "2026-09-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                subscription("second", "canceled", "2026-09-01T00:00:00Z", "2026-05-01T00:00:00Z"),
                subscription("undated", "canceled", "2026-09-01T00:00:00Z", null)));

        assertThat(chosen).map(row -> row.get("id")).contains("second");
    }

    @Test
    @DisplayName("A row with no period end sorts oldest")
    void missingPeriodEndSortsLast() {
        Optional<Map<String, Object>> chosen = BillingCollections.currentSubscription(List.of(
                subscription("undated", "canceled", null, "2026-10-01T00:00:00Z"),
                subscription("dated", "canceled", "2026-01-01T00:00:00Z", "2025-01-01T00:00:00Z")));

        assertThat(chosen).map(row -> row.get("id")).contains("dated");
    }

    @Test
    @DisplayName("Among live rows the latest period end wins, whatever the live status")
    void latestLiveRowWins() {
        Optional<Map<String, Object>> chosen = BillingCollections.currentSubscription(List.of(
                subscription("a", "active", "2026-11-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                subscription("t", "trialing", "2026-12-15T00:00:00Z", "2026-02-01T00:00:00Z"),
                subscription("p", "past_due", "2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z"),
                subscription("c", "canceled", "2027-01-01T00:00:00Z", "2026-10-01T00:00:00Z")));

        assertThat(chosen).map(row -> row.get("id")).contains("t");
    }

    @Test
    @DisplayName("A member with no rows has no subscription")
    void noRowsIsEmpty() {
        assertThat(BillingCollections.currentSubscription(List.of())).isEmpty();
    }
}
