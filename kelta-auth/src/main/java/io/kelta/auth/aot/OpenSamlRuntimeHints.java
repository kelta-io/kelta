package io.kelta.auth.aot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.lang.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * GraalVM native-image hints for OpenSAML (SAML 2.0 federation).
 *
 * <p>OpenSAML is configured declaratively: {@code Initializer} service providers name XML
 * files whose {@code className} attributes are instantiated reflectively, criteria
 * registries are {@code .properties} files of class names, and Apache Santuario (XML
 * signatures) instantiates its algorithm implementations reflectively. None of this ships
 * native hints, so they are derived here at AOT time from the same files OpenSAML reads at
 * run time — a library upgrade that adds a provider is picked up without editing a list.
 *
 * <p>{@link io.kelta.auth.federation.OpenSamlStatus} is the backstop: if anything is still
 * missing, OpenSAML fails to initialize, SAML is disabled with an error, and the service
 * keeps running.
 */
public class OpenSamlRuntimeHints implements RuntimeHintsRegistrar {

    /** Service interfaces OpenSAML loads through {@link java.util.ServiceLoader}. */
    static final List<String> SERVICE_INTERFACES = List.of(
            "org.opensaml.core.config.Initializer",
            "org.opensaml.security.crypto.ec.NamedCurve",
            "org.opensaml.xmlsec.algorithm.AlgorithmDescriptor",
            "org.opensaml.xmlsec.agreement.KeyAgreementProcessor",
            "org.opensaml.xmlsec.agreement.impl.KeyAgreementParameterParser",
            "org.opensaml.xmlsec.signature.support.SignatureValidationProvider",
            "org.opensaml.xmlsec.signature.support.SignerProvider");

    /** Class-name registries OpenSAML loads from the classpath root. */
    static final List<String> REGISTRY_PROPERTIES = List.of(
            "credential-criteria-registry.properties",
            "entitydescriptor-criterion-predicate-registry.properties",
            "roledescriptor-criterion-predicate-registry.properties");

    /** Santuario packages whose classes are instantiated reflectively by algorithm URI. */
    static final List<String> SANTUARIO_PACKAGES = List.of(
            "org/apache/xml/security/algorithms/implementations",
            "org/apache/xml/security/c14n/implementations",
            "org/apache/xml/security/transforms/implementations",
            "org/apache/xml/security/keys/keyresolver/implementations",
            "org/apache/xml/security/utils/resolver/implementations");

    private static final String XML_OBJECT_PROVIDER_INITIALIZER =
            "org.opensaml.core.xml.config.AbstractXMLObjectProviderInitializer";

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        ClassLoader loader = classLoader != null ? classLoader : OpenSamlRuntimeHints.class.getClassLoader();
        for (String service : SERVICE_INTERFACES) {
            registerServiceProviders(hints, loader, service);
        }
        for (String registry : REGISTRY_PROPERTIES) {
            registerPropertiesRegistry(hints, loader, registry);
        }
        registerSantuario(hints, loader);
    }

    private void registerServiceProviders(RuntimeHints hints, ClassLoader loader, String service) {
        String file = "META-INF/services/" + service;
        hints.resources().registerPattern(file);
        for (String provider : readServiceFiles(loader, file)) {
            registerConstructors(hints, provider);
            for (String config : xmlObjectProviderConfigs(loader, provider)) {
                hints.resources().registerPattern(config);
                for (String className : classNamesInConfig(loader, config)) {
                    registerConstructors(hints, className);
                }
            }
        }
    }

    /** The XML config files an {@code AbstractXMLObjectProviderInitializer} loads, without a leading slash. */
    static List<String> xmlObjectProviderConfigs(ClassLoader loader, String initializer) {
        try {
            Class<?> base = Class.forName(XML_OBJECT_PROVIDER_INITIALIZER, false, loader);
            Class<?> type = Class.forName(initializer, false, loader);
            if (!base.isAssignableFrom(type)) {
                return List.of();
            }
            Method getConfigResources = base.getDeclaredMethod("getConfigResources");
            getConfigResources.setAccessible(true);
            String[] resources = (String[]) getConfigResources.invoke(type.getDeclaredConstructor().newInstance());
            List<String> configs = new ArrayList<>();
            for (String resource : resources) {
                configs.add(resource.startsWith("/") ? resource.substring(1) : resource);
            }
            return configs;
        } catch (ReflectiveOperationException | LinkageError e) {
            return List.of();
        }
    }

    /** Every {@code className} attribute in an OpenSAML XMLTooling config file. */
    static Set<String> classNamesInConfig(ClassLoader loader, String config) {
        Set<String> classNames = new LinkedHashSet<>();
        try (InputStream in = loader.getResourceAsStream(config)) {
            if (in == null) {
                return classNames;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(in);
            NodeList elements = document.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < elements.getLength(); i++) {
                String className = ((Element) elements.item(i)).getAttribute("className");
                if (!className.isBlank()) {
                    classNames.add(className.trim());
                }
            }
            return classNames;
        } catch (Exception e) {
            return classNames;
        }
    }

    private void registerPropertiesRegistry(RuntimeHints hints, ClassLoader loader, String registry) {
        hints.resources().registerPattern(registry);
        try (InputStream in = loader.getResourceAsStream(registry)) {
            if (in == null) {
                return;
            }
            Properties properties = new Properties();
            properties.load(in);
            for (String key : properties.stringPropertyNames()) {
                registerConstructors(hints, key);
                registerConstructors(hints, properties.getProperty(key).trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void registerSantuario(RuntimeHints hints, ClassLoader loader) {
        // I18n.init loads this bundle during OpenSAML's ApacheXMLSecurityInitializer.
        hints.resources().registerResourceBundle("org/apache/xml/security/resource/xmlsecurity");
        hints.resources().registerPattern("org/apache/xml/security/resource/*");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        for (String pkg : SANTUARIO_PACKAGES) {
            try {
                for (Resource resource : resolver.getResources("classpath*:" + pkg + "/*.class")) {
                    String file = resource.getFilename();
                    if (file != null) {
                        registerConstructors(hints, pkg.replace('/', '.') + "." + file.substring(0, file.length() - 6));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static List<String> readServiceFiles(ClassLoader loader, String file) {
        List<String> providers = new ArrayList<>();
        try {
            for (URL url : Collections.list(loader.getResources(file))) {
                try (InputStream in = url.openStream()) {
                    for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                        String provider = line.replaceFirst("#.*", "").trim();
                        if (!provider.isEmpty()) {
                            providers.add(provider);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return providers;
    }

    private static void registerConstructors(RuntimeHints hints, String className) {
        hints.reflection().registerType(TypeReference.of(className),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
