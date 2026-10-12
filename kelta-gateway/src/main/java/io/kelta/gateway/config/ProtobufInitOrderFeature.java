package io.kelta.gateway.config;

import org.graalvm.nativeimage.hosted.Feature;

import java.util.List;

/**
 * Native-image build hook that initializes protobuf's descriptor classes in the one order that works.
 *
 * <p>The native profile initializes {@code com.google.protobuf} at build time, and points-to analysis
 * initializes those classes in whatever order its parallel workers first reach them. protobuf-java
 * (4.32 through 4.36 at least) has a cycle: {@code JavaFeaturesProto.<clinit>} needs
 * {@code DescriptorProtos.getDescriptor()}, {@code DescriptorProtos.<clinit>} resolves features
 * through {@code Descriptors.getJavaEditionDefaults()}, and that registers
 * {@code JavaFeaturesProto.java_} — still uninitialized when {@code JavaFeaturesProto} started the
 * chain, so {@code GeneratedExtension.getDescriptor()} throws "called before internalInit()".
 * Starting from {@code DescriptorProtos} completes {@code JavaFeaturesProto} inside the cycle instead.
 *
 * <p>Registered by the {@code --features} build arg in the native profile of {@code pom.xml}.
 */
public final class ProtobufInitOrderFeature implements Feature {

    static final List<String> INIT_ORDER = List.of(
            "com.google.protobuf.Descriptors",
            "com.google.protobuf.DescriptorProtos",
            "com.google.protobuf.JavaFeaturesProto");

    @Override
    public String getDescription() {
        return "Initializes protobuf descriptor classes before analysis can reach them in a failing order";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        initializeInOrder(access.getApplicationClassLoader());
    }

    static void initializeInOrder(ClassLoader classLoader) {
        for (String className : INIT_ORDER) {
            try {
                Class.forName(className, true, classLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(className + " is not on the image classpath", e);
            }
        }
    }
}
