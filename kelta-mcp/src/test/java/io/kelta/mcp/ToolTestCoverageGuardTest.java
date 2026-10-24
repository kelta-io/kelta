package io.kelta.mcp;

import io.kelta.mcp.tool.AdminTool;
import io.kelta.mcp.tool.UserTool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every concrete {@link AdminTool} / {@link UserTool} must have a {@code <ClassName>Test} in the
 * test sources, so a new MCP tool cannot ship without a test of the body it puts on the wire.
 */
@DisplayName("Every MCP tool has a <ClassName>Test")
class ToolTestCoverageGuardTest {

    private static final String TOOL_PACKAGE = "io.kelta.mcp.tool";
    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

    @Test
    @DisplayName("every concrete tool under io.kelta.mcp.tool has a matching test class")
    void everyToolHasATest() throws IOException {
        SortedSet<String> tools = concreteToolNames(TOOL_PACKAGE);

        assertThat(tools).as("tools found under " + TOOL_PACKAGE).hasSizeGreaterThan(50);
        assertThat(missingTests(tools, testClassNames(TEST_SOURCES)))
                .as("MCP tools without a <ClassName>Test — add one asserting the JSON:API body on the wire")
                .isEmpty();
    }

    @Test
    @DisplayName("a planted tool with no test is reported as missing")
    void plantedToolWithoutTestFails() throws IOException {
        SortedSet<String> tools = concreteToolNames(ToolTestCoverageGuardTest.class.getPackageName());

        assertThat(tools).contains("UntestedPlantedTool", "CreateValidationRuleTool", "ListCollectionsTool");
        assertThat(missingTests(tools, testClassNames(TEST_SOURCES))).containsExactly("UntestedPlantedTool");
    }

    @Test
    @DisplayName("marker interfaces and abstract classes are not treated as tools")
    void excludesInterfacesAndAbstractClasses() {
        SortedSet<String> tools = concreteToolNames(ToolTestCoverageGuardTest.class.getPackageName());

        assertThat(tools).doesNotContain("AdminTool", "UserTool", "AbstractPlantedTool");
    }

    /** Simple names of the concrete, top-level-or-static classes implementing either marker interface. */
    static SortedSet<String> concreteToolNames(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AdminTool.class));
        scanner.addIncludeFilter(new AssignableTypeFilter(UserTool.class));
        SortedSet<String> names = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
            String className = candidate.getBeanClassName();
            names.add(className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1));
        }
        return names;
    }

    /** Simple names of every {@code *Test.java} under the given source root. */
    static Set<String> testClassNames(Path sourceRoot) throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("Test.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .collect(Collectors.toSet());
        }
    }

    static SortedSet<String> missingTests(Set<String> tools, Set<String> testClasses) {
        return tools.stream()
                .filter(tool -> !testClasses.contains(tool + "Test"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Planted outside {@code io.kelta.mcp.tool}, so only the planted-class scan sees it. */
    static class UntestedPlantedTool implements UserTool {
        @Override
        public SyncToolSpecification toSpecification() {
            return null;
        }
    }

    abstract static class AbstractPlantedTool implements AdminTool {
    }
}
