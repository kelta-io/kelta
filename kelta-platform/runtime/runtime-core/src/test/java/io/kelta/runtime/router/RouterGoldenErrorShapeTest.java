package io.kelta.runtime.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Holds every {@code router-golden/*.json} case answering a 4xx to the JSON:API error-envelope
 * rule in {@code conventions.md}: a non-empty {@code errors[]} whose every entry carries a
 * non-blank {@code code} and a {@code meta.requestId}. The goldens normalise the requestId's
 * value, so only its presence is checked.
 */
class RouterGoldenErrorShapeTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/router-golden");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("every 4xx golden carries errors[].code and meta.requestId on each error")
    void every4xxGoldenHasCodeAndRequestId() throws Exception {
        List<Path> goldens;
        try (Stream<Path> files = Files.list(GOLDEN_DIR)) {
            goldens = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(goldens.isEmpty(), "no goldens found in " + GOLDEN_DIR);

        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Path golden : goldens) {
            JsonNode doc = mapper.readTree(golden.toFile());
            int status = doc.get("status").asInt();
            if (status < 400 || status >= 500) {
                continue;
            }
            checked++;
            String name = golden.getFileName().toString();
            JsonNode errors = doc.path("body").path("errors");
            if (!errors.isArray() || errors.isEmpty()) {
                violations.add(name + ": " + status + " without a non-empty errors[]");
                continue;
            }
            for (int i = 0; i < errors.size(); i++) {
                JsonNode error = errors.get(i);
                JsonNode code = error.get("code");
                if (code == null || !code.isString() || code.asString().isBlank()) {
                    violations.add(name + ": errors[" + i + "] has no code");
                }
                JsonNode requestId = error.path("meta").get("requestId");
                if (requestId == null || requestId.isNull() || requestId.asString().isBlank()) {
                    violations.add(name + ": errors[" + i + "] has no meta.requestId");
                }
            }
        }

        assertFalse(checked == 0, "no 4xx goldens found");
        assertEquals(List.of(), violations);
    }
}
