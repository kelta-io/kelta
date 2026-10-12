package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SystemCollectionDefinitions#all()} is assembled from the per-section classes
 * ({@code CoreSystemCollections}, {@code UiSystemCollections}, …); {@code byName()} must index the
 * same definitions in the same order.
 */
@DisplayName("SystemCollectionDefinitions registry")
class SystemCollectionDefinitionsSplitTest {

    @Test
    @DisplayName("byName() preserves all() order")
    void byNamePreservesOrder() {
        List<String> fromAll = SystemCollectionDefinitions.all().stream()
            .map(CollectionDefinition::name)
            .toList();
        assertEquals(fromAll, List.copyOf(SystemCollectionDefinitions.byName().keySet()));
    }
}
