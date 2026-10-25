package io.kelta.worker.listener;

import io.kelta.runtime.flow.FlowExecutionData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the flow-delete cascade and {@link FlowDeletionGuardHook}'s status set against the
 * shipped Flyway migrations, the same way {@code CollectionDeletionGuardSchemaTest} does for
 * collections.
 *
 * <p>A flow that had ever run was undeletable because {@code flow_execution_flow_id_fkey}
 * (V1__baseline) had no ON DELETE action. V216 fixed it; this test fails if any FK to
 * {@code flow(id)} or {@code flow_execution(id)} — including one a later migration adds —
 * is left without {@code ON DELETE CASCADE}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Flow delete cascade schema conformance")
class FlowDeletionCascadeSchemaTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final Pattern FLOW_FK = Pattern.compile(
            "(?:ADD CONSTRAINT (\\w+)\\s+FOREIGN KEY \\([^)]+\\)\\s+)?"
                    + "REFERENCES\\s+(?:public\\.)?(flow|flow_execution)\\s*\\(\\s*id\\s*\\)([^,;]*)",
            Pattern.CASE_INSENSITIVE);

    private String allSql;

    @BeforeAll
    void readMigrations() throws IOException {
        assertThat(MIGRATIONS)
                .as("migration directory (test must run with the module as cwd)")
                .exists();
        StringBuilder combined = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            // Version order, as Flyway applies them: a later re-add must win over the baseline.
            List<Path> ordered = files
                    .filter(f -> f.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(this::migrationVersion))
                    .toList();
            for (Path p : ordered) {
                combined.append(Files.readString(p)).append('\n');
            }
        }
        allSql = combined.toString();
    }

    private int migrationVersion(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** Final ON DELETE tail per FK to flow(id)/flow_execution(id); later migrations win. */
    private Map<String, String> flowForeignKeys() {
        Map<String, String> fks = new LinkedHashMap<>();
        Matcher m = FLOW_FK.matcher(allSql);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : "inline " + m.group(2) + "@" + m.start();
            fks.put(name, m.group(3).strip());
        }
        return fks;
    }

    @Test
    @DisplayName("every FK to flow(id) or flow_execution(id) ends up ON DELETE CASCADE")
    void everyFlowForeignKeyCascades() {
        Map<String, String> fks = flowForeignKeys();

        assertThat(fks).containsKeys(
                "flow_execution_flow_id_fkey",
                "flow_version_flow_id_fkey",
                "flow_step_log_execution_id_fkey",
                "flow_pending_resume_execution_id_fkey");
        fks.forEach((name, tail) -> assertThat(tail)
                .as("%s — a delete action other than CASCADE makes a flow that has run "
                        + "undeletable, or orphans its run history", name)
                .containsIgnoringCase("ON DELETE CASCADE"));
    }

    @Test
    @DisplayName("V216 re-adds flow_execution_flow_id_fkey idempotently")
    void migrationIsIdempotent() throws IOException {
        String v216 = Files.readString(
                MIGRATIONS.resolve("V216__flow_execution_cascade_on_flow_delete.sql"));
        assertThat(v216).contains("DROP CONSTRAINT IF EXISTS flow_execution_flow_id_fkey");
    }

    @Test
    @DisplayName("the guard refuses exactly the statuses the engine treats as non-terminal")
    void guardedStatusesAreTheNonTerminalOnes() {
        Matcher m = Pattern.compile("CONSTRAINT chk_flow_status CHECK \\((.*?)\\)\\)\\)", Pattern.DOTALL)
                .matcher(allSql);
        assertThat(m.find()).as("chk_flow_status on flow_execution").isTrue();
        List<String> statuses = Pattern.compile("'(\\w+)'").matcher(m.group(1)).results()
                .map(r -> r.group(1))
                .toList();
        assertThat(statuses).contains("RUNNING", "WAITING", "COMPLETED", "FAILED", "CANCELLED");

        List<String> nonTerminal = statuses.stream()
                .filter(status -> !withStatus(status).isTerminal())
                .toList();

        assertThat(nonTerminal).containsExactlyInAnyOrderElementsOf(FlowDeletionGuardHook.ACTIVE_STATUSES);
    }

    private static FlowExecutionData withStatus(String status) {
        return new FlowExecutionData(null, null, null, status, null, null, null, null, null, null,
                0, null, null, false, null, null);
    }
}
