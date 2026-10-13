package io.kelta.worker.flow;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * Step-log snapshots are capped.
 *
 * <p>The engine logs the whole flow state as every step's input and output. A tenant's ingest
 * flows carried a merged catalog of several MB through each step and wrote ~66 MB of
 * {@code flow_step_log} per run — about 44 GB at 60-day retention, until the disk filled.
 * The cap bounds what is persisted; the running flow is unaffected.
 */
@DisplayName("JdbcFlowStore step-log snapshot cap")
class JdbcFlowStoreSnapshotCapTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Map<String, Object> stateOfSize(int approxBytes) {
        Map<String, Object> state = new HashMap<>();
        state.put("catalog", "x".repeat(approxBytes));
        state.put("provider", Map.of("id", "p1"));
        state.put("now", "2026-10-13T00:00:00Z");
        return state;
    }

    @Test
    @DisplayName("a snapshot under the cap is stored unchanged")
    void smallSnapshotRoundTrips() {
        JdbcFlowStore store = new JdbcFlowStore(mock(JdbcTemplate.class), objectMapper, 1024, null);
        Map<String, Object> state = Map.of("a", 1, "b", "two");

        Map<String, Object> stored = objectMapper.readValue(store.snapshotJson(state), MAP);

        assertThat(stored).isEqualTo(state);
    }

    @Test
    @DisplayName("a snapshot over the cap becomes a marker with the original size and top-level keys")
    void largeSnapshotBecomesMarker() {
        JdbcFlowStore store = new JdbcFlowStore(mock(JdbcTemplate.class), objectMapper, 1024, null);
        Map<String, Object> state = stateOfSize(10_000);
        int original = objectMapper.writeValueAsString(state).getBytes().length;

        String json = store.snapshotJson(state);
        Map<String, Object> stored = objectMapper.readValue(json, MAP);

        assertThat(json.length()).isLessThan(1024);
        assertThat(stored)
            .containsEntry("_truncated", true)
            .containsEntry("bytes", original)
            .containsEntry("maxBytes", 1024)
            .containsEntry("keys", List.of("catalog", "now", "provider"));
    }

    @Test
    @DisplayName("the marker lists at most 50 keys")
    void markerKeysAreBounded() {
        JdbcFlowStore store = new JdbcFlowStore(mock(JdbcTemplate.class), objectMapper, 256, null);
        Map<String, Object> state = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            state.put("k" + String.format("%03d", i), "v".repeat(20));
        }

        Map<String, Object> stored = objectMapper.readValue(store.snapshotJson(state), MAP);

        assertThat((List<?>) stored.get("keys")).hasSize(JdbcFlowStore.MAX_MARKER_KEYS);
    }

    @Test
    @DisplayName("a cap of 0 disables truncation")
    void zeroDisablesCap() {
        JdbcFlowStore store = new JdbcFlowStore(mock(JdbcTemplate.class), objectMapper, 0, null);
        Map<String, Object> state = stateOfSize(100_000);

        assertThat(objectMapper.readValue(store.snapshotJson(state), MAP)).isEqualTo(state);
    }

    @Test
    @DisplayName("a null snapshot stays null")
    void nullStaysNull() {
        JdbcFlowStore store = new JdbcFlowStore(mock(JdbcTemplate.class), objectMapper, 1024, null);

        assertThat(store.snapshotJson(null)).isNull();
    }

    @Test
    @DisplayName("logStepExecution and updateStepLog persist the capped JSON")
    void stepLogWritesAreCapped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcFlowStore store = new JdbcFlowStore(jdbc, objectMapper, 1024, null);
        Map<String, Object> big = stateOfSize(50_000);

        store.logStepExecution("e1", "s1", "Step", "Task", big, null, "RUNNING", null, null, null, 1);
        store.updateStepLog("log1", big, "SUCCEEDED", null, null, 5);

        List<String> jsonArgs = updateArguments(jdbc).stream()
            .filter(a -> a instanceof String s && s.startsWith("{"))
            .map(String.class::cast)
            .toList();
        assertThat(jsonArgs).hasSize(2).allSatisfy(j -> {
            assertThat(j.length()).isLessThan(1024);
            assertThat(j).contains("\"_truncated\":true");
        });
    }

    @Test
    @DisplayName("updateExecutionState keeps the full state: a WAITING flow resumes from it")
    void executionStateIsNotCapped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcFlowStore store = new JdbcFlowStore(jdbc, objectMapper, 1024, null);
        Map<String, Object> big = stateOfSize(50_000);

        store.updateExecutionState("e1", "s2", big, "WAITING", 3);

        assertThat(updateArguments(jdbc))
            .anySatisfy(a -> assertThat(a).isInstanceOf(String.class)
                .asString().hasSizeGreaterThan(50_000).doesNotContain("_truncated"));
    }

    /** Every bind argument passed to {@code JdbcTemplate.update(String, Object...)} on the mock. */
    private static List<Object> updateArguments(JdbcTemplate jdbc) {
        return mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("update"))
            .flatMap(i -> Arrays.stream(i.getArguments()).skip(1))
            .flatMap(a -> a instanceof Object[] arr ? Arrays.stream(arr) : Stream.of(a))
            .toList();
    }

    @Test
    @DisplayName("metrics count bytes written after the cap and truncations")
    void metricsRecordBytesAndTruncations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcFlowStore store = new JdbcFlowStore(mock(JdbcTemplate.class), objectMapper, 1024, registry);

        String small = store.snapshotJson(Map.of("a", 1));
        String marker = store.snapshotJson(stateOfSize(10_000));

        assertThat(registry.get("kelta_flow_step_log_snapshots_truncated").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("kelta_flow_step_log_bytes").counter().count())
            .isEqualTo((double) (small.getBytes().length + marker.getBytes().length));
    }
}
