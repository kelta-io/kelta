package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("CollectionLifecycleManager — ownership columns round-trip")
class CollectionLifecycleManagerOwnershipTest {

    private CollectionLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new CollectionLifecycleManager(
                mock(CollectionRegistry.class),
                mock(StorageAdapter.class),
                mock(JdbcTemplate.class),
                new ObjectMapper());
    }

    private static Map<String, Object> row(Object ownerField, Object ownerScope, Object ownerScopeReads) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "watchlists");
        row.put("owner_field", ownerField);
        row.put("owner_scope", ownerScope);
        row.put("owner_scope_reads", ownerScopeReads);
        return row;
    }

    @Test
    @DisplayName("reads owner_field, owner_scope and owner_scope_reads into the definition")
    void readsOwnershipColumns() {
        CollectionDefinition def = manager.buildDefinitionFromDb("c1", "watchlists",
                row("member", "PORTAL", false));

        assertThat(def.ownerField()).isEqualTo("member");
        assertThat(def.ownerScope()).isEqualTo(OwnerScope.PORTAL);
        assertThat(def.ownerScopeReads()).isFalse();
        assertThat(def.isOwnerScoped()).isTrue();
    }

    @Test
    @DisplayName("missing columns default to unowned, NONE and reads scoped")
    void defaultsWhenAbsent() {
        CollectionDefinition def = manager.buildDefinitionFromDb("c1", "watchlists", row(null, null, null));

        assertThat(def.ownerField()).isNull();
        assertThat(def.ownerScope()).isEqualTo(OwnerScope.NONE);
        assertThat(def.ownerScopeReads()).isTrue();
        assertThat(def.isOwnerScoped()).isFalse();
    }

    @Test
    @DisplayName("survives withIncrementedVersion / withFields copies")
    void survivesCopies() {
        CollectionDefinition def = manager.buildDefinitionFromDb("c1", "watchlists",
                row("createdBy", "ALL", true));

        CollectionDefinition copy = def.withIncrementedVersion().withFields(def.fields());

        assertThat(copy.ownerField()).isEqualTo("createdBy");
        assertThat(copy.ownerScope()).isEqualTo(OwnerScope.ALL);
        assertThat(copy.ownerScopeReads()).isTrue();
    }
}
