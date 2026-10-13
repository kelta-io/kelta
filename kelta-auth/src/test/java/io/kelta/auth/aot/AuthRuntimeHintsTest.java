package io.kelta.auth.aot;

import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.model.KeltaUserDetailsMixin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the native-image hints for the principal stored in the OAuth2 authorization store:
 * Jackson deserializes it through the mixin's static {@code restore} creator and reads its
 * fields reflectively, so both must stay reachable in the native image.
 */
@DisplayName("Auth native-image hints — stored principal")
class AuthRuntimeHintsTest {

    private final RuntimeHints hints = registered();

    private static RuntimeHints registered() {
        RuntimeHints hints = new RuntimeHints();
        new AuthRuntimeHints().registerHints(hints, AuthRuntimeHintsTest.class.getClassLoader());
        return hints;
    }

    @Test
    @DisplayName("the principal's JSON creator is invocable on both the class and its mixin")
    void restoreCreatorIsInvocable() {
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(KeltaUserDetails.class, "restore")
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(KeltaUserDetailsMixin.class, "restore")
                .test(hints)).isTrue();
    }

    @Test
    @DisplayName("the principal's serialized fields are reflectively readable")
    void fieldsAreReadable() {
        for (String field : new String[] {"id", "tenantId", "localCredential", "userType"}) {
            assertThat(RuntimeHintsPredicates.reflection().onFieldAccess(KeltaUserDetails.class, field)
                    .test(hints)).as("field %s", field).isTrue();
        }
    }
}
