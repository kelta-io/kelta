package io.kelta.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the gateway native image against Netty capturing its monotonic clock on the build host.
 *
 * <p>Netty measures scheduler time from an origin taken in a static initializer
 * ({@code START_TIME = System.nanoTime()}). grpc-netty-shaded (Cerbos SDK) ships
 * {@code native-image.properties} that initialize its whole shaded Netty package at build time,
 * so the origin became the build node's {@code CLOCK_MONOTONIC}. On a node whose clock is behind
 * the build node's, Netty time is negative, every deadline clamps to {@code Long.MAX_VALUE} and
 * {@code deadline - now} overflows: every scheduled task runs at once. In production that made
 * every Cerbos check fail ({@code DEADLINE_EXCEEDED}, then {@code Keepalive failed}) and the
 * gateway denied every API call for eight hours. Netty 4.2's own jars carry the same blanket
 * flag; the native build currently excludes their config in favour of the reachability metadata,
 * which is the only reason Reactor Netty was not hit too.
 *
 * <p>A JVM build hides this entirely: on the JVM every class is initialized at run time.
 */
@DisplayName("Native image: Netty clock origins are captured at run time")
class NativeImageClockOriginTest {

    private static final String NETTY_JAR_MARKER = "META-INF/io.netty.versions.properties";
    private static final String RUN_TIME_INIT = "--initialize-at-run-time=";

    /**
     * Read the monotonic clock in a static initializer but are not scheduler time origins, and
     * Netty's own native-image.properties already initialize them at run time.
     */
    private static final Set<String> RUN_TIME_BY_NETTY = Set.of(
            "io.netty.util.internal.ThreadLocalRandom",
            "io.grpc.netty.shaded.io.netty.util.internal.ThreadLocalRandom");

    @Test
    @DisplayName("every Netty class that reads System.nanoTime() in <clinit> is initialized at run time")
    void everyNettyClockOriginIsRunTimeInitialized() throws Exception {
        Set<String> clockOrigins = nettyClassesReadingNanoTimeInStaticInit();
        Set<String> runTime = nativeRunTimeInitializedClasses();

        // Sanity: the scan sees both Netty copies, and every pinned class is still a clock
        // origin — a stale entry means Netty moved its origin and the flag no longer covers it.
        assertThat(clockOrigins)
                .as("Netty classes capturing System.nanoTime() in a static initializer")
                .contains("io.grpc.netty.shaded.io.netty.util.concurrent.AbstractScheduledEventExecutor",
                        "io.netty.util.concurrent.SystemTicker")
                .containsAll(runTime);

        List<String> buildTimeOrigins = clockOrigins.stream()
                .filter(c -> !runTime.contains(c) && !RUN_TIME_BY_NETTY.contains(c))
                .toList();
        assertThat(buildTimeOrigins)
                .as("Netty classes that would capture the build host's monotonic clock — add them to "
                        + "the native profile's %s build arg in kelta-gateway/pom.xml", RUN_TIME_INIT)
                .isEmpty();
    }

    /** Classes in every Netty jar on the classpath whose static initializer calls System.nanoTime(). */
    private static Set<String> nettyClassesReadingNanoTimeInStaticInit() throws IOException {
        Set<String> found = new TreeSet<>();
        Enumeration<URL> markers = NativeImageClockOriginTest.class.getClassLoader().getResources(NETTY_JAR_MARKER);
        for (URL marker : Collections.list(markers)) {
            if (!(marker.openConnection() instanceof JarURLConnection connection)) {
                continue;
            }
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            if (readsNanoTimeInStaticInit(in.readAllBytes())) {
                                found.add(entry.getName().replace('/', '.').replaceAll("\\.class$", ""));
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static boolean readsNanoTimeInStaticInit(byte[] classFile) {
        boolean[] reads = {false};
        new ClassReader(classFile).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (!"<clinit>".equals(name)) {
                    return null;
                }
                return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if ("java/lang/System".equals(owner) && "nanoTime".equals(method)) {
                            reads[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return reads[0];
    }

    /** Class names listed in the native profile's --initialize-at-run-time build args. */
    private static Set<String> nativeRunTimeInitializedClasses() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        NodeList buildArgs = pom.getElementsByTagName("buildArg");
        List<String> classes = new ArrayList<>();
        for (int i = 0; i < buildArgs.getLength(); i++) {
            String arg = buildArgs.item(i).getTextContent().trim();
            if (arg.startsWith(RUN_TIME_INIT)) {
                for (String name : arg.substring(RUN_TIME_INIT.length()).split(",")) {
                    classes.add(name.trim());
                }
            }
        }
        return new TreeSet<>(classes);
    }
}
