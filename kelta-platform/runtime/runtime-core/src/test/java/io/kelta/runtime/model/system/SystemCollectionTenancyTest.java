package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemCollectionTenancyTest {

    @Test
    @DisplayName("tenants is self-scoped: each row is a tenant, keyed by its own id")
    void tenantsIsSelfScoped() {
        assertTrue(SystemCollectionTenancy.isSelfScoped(SystemCollectionDefinitions.tenants()));
    }

    @Test
    @DisplayName("tenants is the only self-scoped system collection")
    void noOtherSystemCollectionIsSelfScoped() {
        List<String> selfScoped = SystemCollectionDefinitions.byName().values().stream()
                .filter(SystemCollectionTenancy::isSelfScoped)
                .map(CollectionDefinition::name)
                .toList();

        assertEquals(List.of("tenants"), selfScoped);
    }

    @Test
    @DisplayName("a user collection that happens to be called 'tenants' is not self-scoped")
    void userCollectionNamedTenantsIsNotSelfScoped() {
        CollectionDefinition userTenants = new CollectionDefinitionBuilder()
                .name("tenants")
                .displayName("Tenants")
                .addField(FieldDefinition.requiredString("name"))
                .build();

        assertFalse(SystemCollectionTenancy.isSelfScoped(userTenants));
        assertFalse(SystemCollectionTenancy.isSelfScoped(null));
    }

    @Test
    @DisplayName("self-scoped and tenant_id-scoped are disjoint")
    void selfScopedIsNotTenantScoped() {
        assertFalse(SystemCollectionTenancy.isTenantScoped(SystemCollectionDefinitions.tenants()));
    }
}
