package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.OwnerScope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system collections whose owner guarding comes from ownership metadata (member data
 * ownership slice 4) rather than a bespoke per-collection hook.
 */
class SystemCollectionOwnershipTest {

    static Stream<Arguments> ownedSystemCollections() {
        return Stream.of(
                Arguments.of(SystemCollectionDefinitions.watches(), "memberId", true),
                Arguments.of(SystemCollectionDefinitions.wins(), "memberId", true),
                Arguments.of(SystemCollectionDefinitions.userUiPreferences(), "userId", true),
                Arguments.of(SystemCollectionDefinitions.notes(), "createdBy", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownedSystemCollections")
    @DisplayName("declares its owner field, ALL scope and read scoping")
    void declaresOwnership(CollectionDefinition definition, String ownerField, boolean ownerScopeReads) {
        assertThat(definition.ownerField()).isEqualTo(ownerField);
        assertThat(definition.ownerScope()).isEqualTo(OwnerScope.ALL);
        assertThat(definition.ownerScopeReads()).isEqualTo(ownerScopeReads);
        assertThat(definition.isOwnerScoped()).isTrue();
        assertThat(definition.getField(ownerField)).isNotNull();
    }

    @Test
    @DisplayName("only these four system collections are owner-scoped")
    void noOtherSystemCollectionIsOwnerScoped() {
        List<String> owned = SystemCollectionDefinitions.byName().values().stream()
                .filter(CollectionDefinition::isOwnerScoped)
                .map(CollectionDefinition::name)
                .sorted()
                .toList();

        assertThat(owned).containsExactly("notes", "user-ui-preferences", "watches", "wins");
    }
}
