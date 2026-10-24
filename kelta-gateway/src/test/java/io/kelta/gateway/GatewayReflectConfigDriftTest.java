package io.kelta.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.RecordComponentVisitor;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the gateway's native-image reflection list for event payloads.
 *
 * <p>The gateway runs as a GraalVM native image, so an {@code io.kelta.runtime.event} payload it
 * deserializes with Jackson must be in its {@code reflect-config.json}, or it binds to an empty
 * object at runtime (a JVM build hides this). The gateway's file deliberately lists a subset of
 * the payload package, so — unlike the worker's {@code EventPayloadReflectConfigTest}, which
 * requires every payload — this derives the set the gateway actually needs from its compiled
 * classes: every {@code *Payload} type a gateway class file names, plus every event-package type
 * reachable through those payloads' fields (nested {@code CollectionChangedPayload$FieldPayload},
 * the {@code ChangeType} enum).
 */
@DisplayName("gateway reflect-config covers the event payloads it references")
class GatewayReflectConfigDriftTest {

    static final String REFLECT_CONFIG =
            "META-INF/native-image/io.kelta/kelta-gateway/reflect-config.json";

    private static final String EVENT_PACKAGE_PATH = "io/kelta/runtime/event/";
    private static final Pattern EVENT_TYPE =
            Pattern.compile(Pattern.quote(EVENT_PACKAGE_PATH) + "[A-Za-z0-9_$]+");
    private static final int CONSTANT_UTF8 = 1;

    @Test
    @DisplayName("every event payload a gateway class references is registered")
    void realReflectConfigCoversReferencedPayloads() {
        Set<String> required = requiredEventTypes();

        // Sanity: the class-file scan reached the listeners and the field walk reached nested types.
        assertThat(required).contains(
                "io.kelta.runtime.event.CollectionChangedPayload",
                "io.kelta.runtime.event.CollectionChangedPayload$FieldPayload",
                "io.kelta.runtime.event.RecordChangedPayload");

        assertThat(missingFrom(REFLECT_CONFIG))
                .as("event types the gateway references but %s does not register — the native "
                        + "gateway will deserialize these as empty objects; add an allDeclared* entry",
                        REFLECT_CONFIG)
                .isEmpty();
    }

    @Test
    @DisplayName("a reflect-config missing RecordChangedPayload is reported")
    void reportsPayloadMissingFromFixture() {
        assertThat(missingFrom("reflect-config-drift/missing-record-changed-payload.json"))
                .containsExactly("io.kelta.runtime.event.RecordChangedPayload");
    }

    /** Required event types (sorted, dotted binary names) absent from the given reflect-config resource. */
    static List<String> missingFrom(String reflectConfigResource) {
        Set<String> registered = registeredNames(reflectConfigResource);
        return requiredEventTypes().stream()
                .filter(name -> !registered.contains(name))
                .sorted()
                .toList();
    }

    static Set<String> requiredEventTypes() {
        Deque<String> pending = new ArrayDeque<>();
        for (String type : eventTypesReferencedByGatewayClasses()) {
            if (type.endsWith("Payload")) {
                pending.add(type);
            }
        }
        Set<String> required = new TreeSet<>();
        while (!pending.isEmpty()) {
            String internalName = pending.poll();
            if (required.add(internalName)) {
                pending.addAll(eventTypesInFields(internalName));
            }
        }
        return required.stream()
                .map(name -> name.replace('/', '.'))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every event-package internal name in the constant pools of the compiled {@code io.kelta.gateway} classes. */
    private static Set<String> eventTypesReferencedByGatewayClasses() {
        Path gatewayPackage = mainClassesRoot().resolve("io/kelta/gateway");
        Set<String> referenced = new HashSet<>();
        try (Stream<Path> files = Files.walk(gatewayPackage)) {
            files.filter(file -> file.toString().endsWith(".class"))
                    .forEach(file -> referenced.addAll(eventTypesInConstantPool(read(file))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return referenced;
    }

    /**
     * Scans every CONSTANT_Utf8 entry, which covers class references, field and method
     * descriptors and generic signatures alike.
     */
    private static Set<String> eventTypesInConstantPool(ClassReader reader) {
        Set<String> found = new HashSet<>();
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0 || reader.readByte(offset - 1) != CONSTANT_UTF8) {
                continue;
            }
            int length = reader.readUnsignedShort(offset);
            String utf8 = new String(reader.readBytes(offset + 2, length), StandardCharsets.UTF_8);
            Matcher matcher = EVENT_TYPE.matcher(utf8);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    /** Event-package types named by a class's field (and record component) types, generics included. */
    private static Set<String> eventTypesInFields(String internalName) {
        Set<String> found = new HashSet<>();
        String resource = internalName + ".class";
        try (InputStream in = GatewayReflectConfigDriftTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("class file for %s on the test classpath", internalName).isNotNull();
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    if ((access & Opcodes.ACC_STATIC) == 0) {
                        collect(descriptor, found);
                        collect(signature, found);
                    }
                    return null;
                }

                @Override
                public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
                                                                   String signature) {
                    collect(descriptor, found);
                    collect(signature, found);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static void collect(String typeText, Set<String> into) {
        if (typeText == null) {
            return;
        }
        Matcher matcher = EVENT_TYPE.matcher(typeText);
        while (matcher.find()) {
            into.add(matcher.group());
        }
    }

    private static Set<String> registeredNames(String reflectConfigResource) {
        Set<String> registered = new HashSet<>();
        try (InputStream in = new ClassPathResource(reflectConfigResource).getInputStream()) {
            JsonNode config = new ObjectMapper().readTree(in);
            config.forEach(node -> registered.add(node.get("name").asText()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return registered;
    }

    private static Path mainClassesRoot() {
        try {
            return Path.of(GatewayApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClassReader read(Path classFile) {
        try {
            return new ClassReader(Files.readAllBytes(classFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
