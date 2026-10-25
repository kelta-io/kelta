package io.kelta.auth.aot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the native-image hints derived from OpenSAML's own configuration files. The
 * federation beans are always registered, so a native image initializes OpenSAML at
 * startup; a missing hint turns SAML off (see {@code OpenSamlStatus}).
 */
@DisplayName("OpenSAML native-image hints")
class OpenSamlRuntimeHintsTest {

    private final ClassLoader loader = getClass().getClassLoader();
    private RuntimeHints hints;

    @BeforeEach
    void register() {
        hints = new RuntimeHints();
        new AuthRuntimeHints().registerHints(hints, loader);
    }

    private boolean constructible(String className) {
        return RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(className))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .test(hints);
    }

    @Test
    @DisplayName("finds the XMLObject provider configs every OpenSAML initializer loads")
    void derivesConfigsFromInitializers() {
        assertThat(OpenSamlRuntimeHints.xmlObjectProviderConfigs(loader,
                "org.opensaml.saml.config.impl.XMLObjectProviderInitializer"))
                .contains("saml2-protocol-config.xml", "saml2-assertion-config.xml", "saml2-metadata-config.xml");
        assertThat(OpenSamlRuntimeHints.classNamesInConfig(loader, "saml2-protocol-config.xml"))
                .contains("org.opensaml.saml.saml2.core.impl.AuthnRequestBuilder",
                        "org.opensaml.saml.saml2.core.impl.LogoutRequestMarshaller");
    }

    @Test
    @DisplayName("registers the config resources and their builders, marshallers and unmarshallers")
    void registersConfigResourcesAndClasses() {
        for (String resource : new String[] {
                "META-INF/services/org.opensaml.core.config.Initializer",
                "default-config.xml", "schema-config.xml",
                "saml2-protocol-config.xml", "saml2-assertion-config.xml",
                "signature-config.xml",
                "credential-criteria-registry.properties"}) {
            assertThat(RuntimeHintsPredicates.resource().forResource(resource).test(hints))
                    .as("resource %s", resource).isTrue();
        }
        // OpenSAML 5.2 moved core's Initializer provider to the impl package; the old
        // org.opensaml.core.xml.config.XMLObjectProviderInitializer is deprecated and unlisted.
        assertThat(constructible("org.opensaml.core.xml.config.impl.XMLObjectProviderInitializer")).isTrue();
        assertThat(constructible("org.opensaml.saml.saml2.core.impl.ResponseUnmarshaller")).isTrue();
        assertThat(constructible("org.opensaml.saml.saml2.core.impl.LogoutRequestBuilder")).isTrue();
        assertThat(constructible(
                "org.opensaml.security.credential.criteria.impl.EvaluableEntityIDCredentialCriterion")).isTrue();
    }

    @Test
    @DisplayName("every hard-coded OpenSAML name still exists, so an upgrade cannot drop hints silently")
    void hardCodedNamesExist() throws Exception {
        for (String service : OpenSamlRuntimeHints.SERVICE_INTERFACES) {
            assertThat(loader.getResource("META-INF/services/" + service)).as("service file for %s", service).isNotNull();
        }
        for (String registry : OpenSamlRuntimeHints.REGISTRY_PROPERTIES) {
            assertThat(loader.getResource(registry)).as("registry %s", registry).isNotNull();
        }
        assertThat(Class.forName(OpenSamlRuntimeHints.XML_OBJECT_PROVIDER_INITIALIZER, false, loader)).isNotNull();
    }

    @Test
    @DisplayName("registers Santuario's reflectively created signature algorithms")
    void registersSantuario() {
        assertThat(constructible("org.apache.xml.security.algorithms.implementations.SignatureBaseRSA$SignatureRSASHA256"))
                .isTrue();
        assertThat(constructible("org.apache.xml.security.c14n.implementations.Canonicalizer20010315ExclOmitComments"))
                .isTrue();
    }
}
