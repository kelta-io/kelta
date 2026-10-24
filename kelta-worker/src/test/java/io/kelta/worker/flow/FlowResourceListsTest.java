package io.kelta.worker.flow;

import io.kelta.runtime.flow.FlowDefinitionValidator;
import io.kelta.runtime.module.core.CoreActionsModule;
import io.kelta.runtime.module.integration.IntegrationModule;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.module.KeltaModule;
import io.kelta.runtime.workflow.module.ModuleContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the Task resource lists an author reads — the kelta-ai flow-generation prompt and the
 * {@code docs/authoring/flows.md} section — to what the platform accepts: every registered
 * handler key minus the resources {@link FlowDefinitionValidator} rejects at save.
 */
@DisplayName("Flow resource lists match the accepted handlers")
class FlowResourceListsTest {

    private static final Path PROMPT_SOURCE =
            Path.of("..", "kelta-ai", "src", "main", "java", "io", "kelta", "ai", "service",
                    "FlowGenerationService.java");
    private static final Path FLOWS_DOC = Path.of("..", "docs", "authoring", "flows.md");

    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z_]*");
    private static final Pattern DOC_BULLET = Pattern.compile("^- `([A-Z][A-Z_]*)`");

    private static Set<String> acceptedResources() {
        Map<Class<?>, Object> extensions = Map.of(
                JdbcTemplate.class, mock(JdbcTemplate.class),
                TransactionTemplate.class, mock(TransactionTemplate.class));
        ModuleContext context = new ModuleContext(null, null, null, new ObjectMapper(),
                new ActionHandlerRegistry(), extensions);

        Set<String> keys = new TreeSet<>();
        for (KeltaModule module : List.of(new CoreActionsModule(), new IntegrationModule())) {
            module.onStartup(context);
            module.getActionHandlers().stream().map(ActionHandler::getActionTypeKey).forEach(keys::add);
        }
        keys.removeAll(FlowDefinitionValidator.retiredResources());
        return keys;
    }

    private static Set<String> promptResources() throws IOException {
        List<String> lines = Files.readAllLines(PROMPT_SOURCE);
        int start = indexOfTrimmed(lines, "Available resources:", PROMPT_SOURCE);
        Set<String> keys = new TreeSet<>();
        for (int i = start + 1; i < lines.size() && !lines.get(i).isBlank(); i++) {
            String line = lines.get(i).trim();
            assertThat(line).as("prompt resource line").startsWith("- ").contains(":");
            for (String item : line.substring(line.indexOf(':') + 1).split(",")) {
                Matcher m = KEY.matcher(item.trim());
                assertThat(m.find()).as("resource key in prompt item '%s'", item).isTrue();
                keys.add(m.group());
            }
        }
        return keys;
    }

    private static Set<String> docResources() throws IOException {
        List<String> lines = Files.readAllLines(FLOWS_DOC);
        int start = indexOfTrimmed(lines, "### All accepted resources", FLOWS_DOC);
        Set<String> keys = new TreeSet<>();
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("#"); i++) {
            Matcher m = DOC_BULLET.matcher(lines.get(i));
            if (m.find()) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }

    private static int indexOfTrimmed(List<String> lines, String marker, Path source) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(marker)) {
                return i;
            }
        }
        throw new AssertionError("'" + marker + "' not found in " + source);
    }

    private static void assertSameKeys(String where, Set<String> listed, Set<String> accepted) {
        Set<String> missing = accepted.stream().filter(k -> !listed.contains(k))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> extra = listed.stream().filter(k -> !accepted.contains(k))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(listed)
                .as("%s must list exactly the accepted Task resources; missing %s, extra %s",
                        where, missing, extra)
                .isEqualTo(accepted);
    }

    @Test
    @DisplayName("the accepted set excludes the retired resources and keeps TRIGGER_FLOW and DECISION")
    void acceptedSetShape() {
        Set<String> accepted = acceptedResources();

        assertThat(accepted).doesNotContainAnyElementsOf(FlowDefinitionValidator.retiredResources());
        assertThat(accepted).contains("TRIGGER_FLOW", "DECISION", "SQL_QUERY");
        assertThat(FlowDefinitionValidator.retiredResources()).contains("DELAY", "CREATE_TASK");
    }

    @Test
    @DisplayName("FlowGenerationService prompt lists exactly the accepted resources")
    void promptListsAcceptedResources() throws IOException {
        assertSameKeys(PROMPT_SOURCE + " \"Available resources:\"", promptResources(), acceptedResources());
    }

    @Test
    @DisplayName("docs/authoring/flows.md lists exactly the accepted resources")
    void docListsAcceptedResources() throws IOException {
        assertSameKeys(FLOWS_DOC + " \"### All accepted resources\"", docResources(), acceptedResources());
    }
}
