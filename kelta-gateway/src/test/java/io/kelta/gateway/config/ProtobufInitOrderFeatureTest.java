package io.kelta.gateway.config;

import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the gateway native image against protobuf's class-initialization cycle.
 *
 * <p>The native profile initializes {@code com.google.protobuf} at build time, so points-to analysis
 * runs protobuf static initializers in the order its parallel workers first reach the classes. When
 * {@code JavaFeaturesProto} is reached first, its {@code <clinit>} asks for
 * {@code DescriptorProtos.getDescriptor()}; {@code DescriptorProtos.<clinit>} resolves features via
 * {@code Descriptors.getJavaEditionDefaults()}, which registers {@code JavaFeaturesProto.java_}
 * before {@code JavaFeaturesProto} has reached {@code java_.internalInit(...)}, and
 * {@code GeneratedExtension.getDescriptor()} throws "getDescriptor() called before internalInit()".
 * #1734 changed no dependency, only the gateway code reachable from Cerbos calls, and that was enough
 * to change which protobuf class analysis initialized first: the image stopped building on
 * {@code main} and no PR job builds native images. {@link ProtobufInitOrderFeature} initializes
 * {@code DescriptorProtos} before analysis starts.
 *
 * <p>A JVM build hides this: classes initialize when code first runs them, and every path that reads
 * {@code JavaFeaturesProto} goes through a descriptor that {@code DescriptorProtos} has already built.
 */
@DisplayName("Native image: protobuf descriptor classes initialize in a fixed order")
class ProtobufInitOrderFeatureTest {

    private static final String JAVA_FEATURES_PROTO = "com.google.protobuf.JavaFeaturesProto";

    @Test
    @DisplayName("protobuf still fails when JavaFeaturesProto initializes first")
    void javaFeaturesProtoFirstStillFails() throws Exception {
        try (URLClassLoader protobuf = freshProtobufLoader()) {
            assertThatThrownBy(() -> Class.forName(JAVA_FEATURES_PROTO, true, protobuf))
                    .as("the cycle the feature works around; if protobuf fixed it, the feature can go")
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("getDescriptor() called before internalInit()");
        }
    }

    @Test
    @DisplayName("the feature's order initializes JavaFeaturesProto cleanly")
    void featureOrderInitializesCleanly() throws Exception {
        try (URLClassLoader protobuf = freshProtobufLoader()) {
            assertThatCode(() -> {
                ProtobufInitOrderFeature.initializeInOrder(protobuf);
                Class.forName(JAVA_FEATURES_PROTO, true, protobuf);
            }).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the native build registers the feature and keeps protobuf at build time")
    void nativeBuildRegistersFeature() throws Exception {
        assertThat(nativeBuildArgs())
                .as("native profile build args in kelta-gateway/pom.xml")
                .contains("--features=" + ProtobufInitOrderFeature.class.getName(),
                        "--initialize-at-build-time=com.google.protobuf");
    }

    /** A loader over the protobuf jar alone, so this JVM's already-initialized classes don't count. */
    private static URLClassLoader freshProtobufLoader() throws Exception {
        URL jar = Descriptors.class.getProtectionDomain().getCodeSource().getLocation();
        return new URLClassLoader(new URL[] {jar}, null);
    }

    private static List<String> nativeBuildArgs() throws Exception {
        NodeList buildArgs = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile())
                .getElementsByTagName("buildArg");
        List<String> args = new ArrayList<>();
        for (int i = 0; i < buildArgs.getLength(); i++) {
            args.add(buildArgs.item(i).getTextContent().trim());
        }
        return args;
    }
}
