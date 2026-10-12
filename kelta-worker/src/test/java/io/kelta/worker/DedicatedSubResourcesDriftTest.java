package io.kelta.worker;

import io.kelta.runtime.router.DynamicCollectionRouter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the gateway's list of dedicated sub-resource endpoints in step with the worker's
 * controllers.
 *
 * <p>The gateway reads {@code /api/{parent}/{parentId}/{child}[/{childId}]} under a collection
 * route as a child-resource request and authorizes it against the child collection when the
 * child segment names one. The worker serves some paths of that shape from a dedicated controller
 * instead ({@code GET /api/dashboards/{name}/tree}, …): Spring ranks the literal segment above
 * {@code DynamicCollectionRouter}'s {@code {childName}} variable, so the trailing segment is an
 * operation, not a collection, and the gateway must keep the parent route's own decision for it.
 * The gateway learns which paths those are from
 * {@code kelta-gateway/src/main/resources/authz/dedicated-sub-resources.txt}. This test discovers
 * every {@code @RequestMapping}-family handler on the worker classpath (worker and runtime
 * controllers) and fails when a mapping of that shape is missing from the file, or when the file
 * lists a mapping no controller declares any more.
 */
@DisplayName("gateway dedicated sub-resources match the worker's controller mappings")
class DedicatedSubResourcesDriftTest {

    private static final String SCAN_PACKAGE = "io.kelta";

    private static final Path GATEWAY_FILE =
            Path.of("..", "kelta-gateway", "src", "main", "resources", "authz", "dedicated-sub-resources.txt");

    /**
     * Runtime-installable modules ({@code kelta-modules/}) are not on the worker classpath; their
     * routes sit under {@code /api/modules/{moduleId}/...}, so an entry with this parent is not
     * reported as stale.
     */
    private static final String MODULES_PARENT = "modules";

    /** Normalized key ({@code METHOD /api/a/{}/b}) → the line as written, for every discovered mapping. */
    private static TreeMap<String, String> discovered;

    /**
     * Reads class metadata straight off the classpath rather than through
     * {@code ClassPathScanningCandidateComponentProvider}, which evaluates {@code @Conditional}s
     * and would silently skip a controller gated on a property (e.g. {@code CredentialController}
     * on {@code kelta.encryption.key}) — the gateway must know its paths whether or not this
     * build enables it.
     */
    @BeforeAll
    static void discover() throws IOException {
        discovered = new TreeMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        Resource[] classFiles = resolver.getResources(
                ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + SCAN_PACKAGE.replace('.', '/') + "/**/*.class");
        for (Resource classFile : classFiles) {
            AnnotationMetadata metadata = readers.getMetadataReader(classFile).getAnnotationMetadata();
            if (!metadata.isConcrete() || !metadata.isAnnotated(Controller.class.getName())) {
                continue;
            }
            Class<?> type = load(metadata.getClassName());
            if (type == DynamicCollectionRouter.class || isTestClass(type)) {
                continue;
            }
            collect(type);
        }
    }

    @Test
    @DisplayName("the scan finds known dedicated endpoints (sanity)")
    void scanFindsKnownEndpoints() {
        assertThat(discovered.keySet())
                .contains("GET /api/dashboards/{}/tree", "POST /api/attachments/{}/finalize");
    }

    @Test
    @DisplayName("every worker mapping of the child-resource shape is listed for the gateway")
    void everyMappingIsListed() throws IOException {
        Set<String> listed = readGatewayFile().keySet();

        List<String> missing = discovered.entrySet().stream()
                .filter(e -> !listed.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        assertThat(missing)
                .as("worker controller mappings of the shape /api/<literal>/{var}/<literal>[/...] that are "
                        + "absent from %s — the gateway would authorize them as child-resource requests "
                        + "whenever the trailing segment also names a collection. Add these lines:%n%s",
                        GATEWAY_FILE, String.join("\n", missing))
                .isEmpty();
    }

    @Test
    @DisplayName("no listed entry is stale")
    void noStaleEntries() throws IOException {
        List<String> stale = readGatewayFile().entrySet().stream()
                .filter(e -> !discovered.containsKey(e.getKey()))
                .filter(e -> !MODULES_PARENT.equals(parentOf(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();

        assertThat(stale)
                .as("entries in %s that no worker controller maps — remove them", GATEWAY_FILE)
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static void collect(Class<?> type) {
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String[] classPaths = classMapping != null && classMapping.path().length > 0
                ? classMapping.path() : new String[] {""};
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] methodPaths = mapping.path().length > 0 ? mapping.path() : new String[] {""};
            RequestMethod[] verbs = mapping.method().length > 0
                    ? mapping.method()
                    : classMapping != null ? classMapping.method() : new RequestMethod[0];
            List<String> methodNames = verbs.length == 0
                    ? List.of("*")
                    : Arrays.stream(verbs).map(RequestMethod::name).toList();
            for (String classPath : classPaths) {
                for (String methodPath : methodPaths) {
                    String pattern = combine(classPath, methodPath);
                    if (!hasChildResourceShape(pattern)) {
                        continue;
                    }
                    for (String verb : methodNames) {
                        discovered.putIfAbsent(normalize(verb, pattern), verb + " " + pattern);
                    }
                }
            }
        }
    }

    private static String combine(String classPath, String methodPath) {
        String a = classPath.endsWith("/") ? classPath.substring(0, classPath.length() - 1) : classPath;
        if (methodPath.isEmpty()) {
            return a;
        }
        return a + (methodPath.startsWith("/") ? methodPath : "/" + methodPath);
    }

    /**
     * {@code /api/<literal>/{var}/<literal>} or {@code /api/<literal>/{var}/<literal>/<literal|{var}>}
     * — the three- and four-segment depths of the router's child mappings.
     */
    static boolean hasChildResourceShape(String pattern) {
        if (!pattern.startsWith("/api/")) {
            return false;
        }
        String[] segments = pattern.substring("/api/".length()).split("/", -1);
        if (segments.length != 3 && segments.length != 4) {
            return false;
        }
        return isLiteral(segments[0]) && isVariable(segments[1]) && isLiteral(segments[2])
                && (segments.length == 3 || isLiteral(segments[3]) || isVariable(segments[3]));
    }

    private static boolean isLiteral(String segment) {
        return !segment.isEmpty() && segment.indexOf('{') < 0 && segment.indexOf('*') < 0;
    }

    private static boolean isVariable(String segment) {
        return segment.length() > 2 && segment.startsWith("{") && segment.endsWith("}")
                && segment.indexOf('{', 1) < 0 && segment.charAt(1) != '*';
    }

    /** {@code METHOD /api/a/{}/b}: variable names do not matter for matching. */
    private static String normalize(String method, String pattern) {
        return method + " " + pattern.replaceAll("\\{[^}]*}", "{}");
    }

    private static String parentOf(String key) {
        String path = key.substring(key.indexOf(' ') + 1);
        return path.substring("/api/".length()).split("/", 2)[0];
    }

    /** Normalized key → line, for every entry in the gateway file. */
    private static TreeMap<String, String> readGatewayFile() throws IOException {
        Path base = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path file = base.resolve(GATEWAY_FILE).normalize();
        assertThat(file).as("gateway dedicated sub-resources file").exists();
        TreeMap<String, String> entries = new TreeMap<>();
        Set<String> duplicates = new TreeSet<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            assertThat(parts).as("line '%s' must be 'METHOD /api/...'", line).hasSize(2);
            assertThat(hasChildResourceShape(parts[1]))
                    .as("line '%s' must have the shape /api/<literal>/{var}/<literal>[/...]", line).isTrue();
            if (entries.put(normalize(parts[0], parts[1]), line) != null) {
                duplicates.add(line);
            }
        }
        assertThat(duplicates).as("duplicate entries in %s", GATEWAY_FILE).isEmpty();
        return entries;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, DedicatedSubResourcesDriftTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load scanned controller " + className, e);
        }
    }

    private static boolean isTestClass(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL location = source != null ? source.getLocation() : null;
        return location != null && location.getPath().contains("test-classes");
    }
}
