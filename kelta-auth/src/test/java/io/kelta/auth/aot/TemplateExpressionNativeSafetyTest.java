package io.kelta.auth.aot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kelta-auth ships as a GraalVM native image, where a method call inside a Thymeleaf
 * expression (e.g. {@code ${providers.isEmpty()}}) fails at render time unless the
 * receiver's class is registered for reflection — and JDK collection implementations are
 * not. Templates must test booleans the controller computes instead.
 */
class TemplateExpressionNativeSafetyTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** A {@code ${...}} expression containing {@code .name(} — a method call. */
    private static final Pattern METHOD_CALL = Pattern.compile("\\$\\{[^}]*\\.[A-Za-z_][A-Za-z0-9_]*\\(");

    @Test
    @DisplayName("templates call no methods inside ${...} expressions")
    void noMethodCallsInTemplateExpressions() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = METHOD_CALL.matcher(lines.get(i));
                    if (m.find()) {
                        offenders.add(TEMPLATES.relativize(file) + ":" + (i + 1) + " " + m.group());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
