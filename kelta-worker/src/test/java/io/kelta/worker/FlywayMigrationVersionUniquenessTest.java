package io.kelta.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two migrations sharing a Flyway version each pass on their own branch and break every
 * Flyway run once both merge ("Found more than one migration with version N"). This catches
 * the collision in the unit-test phase instead of the Testcontainers integration tests.
 */
@DisplayName("Flyway migration versions are unique")
class FlywayMigrationVersionUniquenessTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED = Pattern.compile("^V([^_].*?)__.+\\.sql$");

    @Test
    @DisplayName("no two migrations in db/migration share a version")
    void migrationVersionsAreUnique() throws IOException {
        assertThat(MIGRATION_DIR).isDirectory();
        assertUniqueVersions(MIGRATION_DIR);
    }

    @Test
    @DisplayName("fails naming both files when two migrations share a version")
    void detectsDuplicateVersion(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V999__a.sql"), "SELECT 1;");
        Files.writeString(dir.resolve("V999__b.sql"), "SELECT 1;");

        assertThatThrownBy(() -> assertUniqueVersions(dir))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("version 999: V999__a.sql, V999__b.sql");
    }

    @Test
    @DisplayName("passes for a single migration")
    void passesForSingleMigration(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V999__a.sql"), "SELECT 1;");

        assertUniqueVersions(dir);
    }

    @Test
    @DisplayName("compares versions like Flyway: separators and leading zeros")
    void normalisesVersionsLikeFlyway(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V1_1__underscore.sql"), "SELECT 1;");
        Files.writeString(dir.resolve("V1.1__dot.sql"), "SELECT 1;");
        Files.writeString(dir.resolve("V007__padded.sql"), "SELECT 1;");
        Files.writeString(dir.resolve("V7__plain.sql"), "SELECT 1;");
        Files.writeString(dir.resolve("R__repeatable.sql"), "SELECT 1;");
        Files.writeString(dir.resolve("README.md"), "notes");

        assertThatThrownBy(() -> assertUniqueVersions(dir))
                .hasMessageContaining("version 1.1: V1.1__dot.sql, V1_1__underscore.sql")
                .hasMessageContaining("version 7: V007__padded.sql, V7__plain.sql")
                .hasMessageNotContaining("R__repeatable.sql")
                .hasMessageNotContaining("README.md");
    }

    static void assertUniqueVersions(Path dir) throws IOException {
        Map<String, TreeSet<String>> filesByVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(name -> {
                        Matcher m = VERSIONED.matcher(name);
                        if (m.matches()) {
                            filesByVersion.computeIfAbsent(normalise(m.group(1)), v -> new TreeSet<>()).add(name);
                        }
                    });
        }

        List<String> collisions = new ArrayList<>();
        filesByVersion.forEach((version, names) -> {
            if (names.size() > 1) {
                collisions.add("version " + version + ": " + String.join(", ", names));
            }
        });
        if (!collisions.isEmpty()) {
            throw new AssertionError("Flyway migrations in " + dir + " share a version; renumber the newer one:\n  "
                    + String.join("\n  ", collisions));
        }
    }

    /** Flyway's MigrationVersion: "_" is a separator, segments compare numerically, trailing zeros drop. */
    private static String normalise(String raw) {
        List<BigInteger> parts = Arrays.stream(raw.replace('_', '.').split("\\.", -1))
                .map(BigInteger::new)
                .collect(Collectors.toCollection(ArrayList::new));
        while (parts.size() > 1 && parts.getLast().signum() == 0) {
            parts.removeLast();
        }
        return parts.stream().map(BigInteger::toString).collect(Collectors.joining("."));
    }
}
