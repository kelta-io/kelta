package io.kelta.runtime.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TenantContext")
class TenantContextTest {

    @Test
    @DisplayName("Should bind and read tenant ID inside a scope")
    void shouldBindTenantIdInScope() {
        String seen = TenantContext.callWithTenant("tenant-123", TenantContext::get);
        assertEquals("tenant-123", seen);
    }

    @Test
    @DisplayName("Should bind tenant ID and slug together")
    void shouldBindTenantIdAndSlug() {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<String> slug = new AtomicReference<>();
        TenantContext.runWithTenant("tenant-123", "acme-corp", () -> {
            id.set(TenantContext.get());
            slug.set(TenantContext.getSlug());
        });
        assertEquals("tenant-123", id.get());
        assertEquals("acme-corp", slug.get());
    }

    @Test
    @DisplayName("Should return null when nothing is bound")
    void shouldReturnNullWhenNotBound() {
        assertNull(TenantContext.get());
        assertNull(TenantContext.getSlug());
    }

    @Test
    @DisplayName("Binding ends with the scope")
    void bindingEndsWithScope() {
        TenantContext.runWithTenant("tenant-123", "acme-corp", () -> { });
        assertNull(TenantContext.get());
        assertNull(TenantContext.getSlug());
    }

    @Test
    @DisplayName("An inner callWithTenant shadows the outer binding")
    void innerScopeShadowsOuter() {
        String seen = TenantContext.callWithTenant("outer",
                () -> TenantContext.callWithTenant("inner", TenantContext::get));
        assertEquals("inner", seen);
    }

    @Test
    @DisplayName("IfUnbound binds tenant and slug when nothing is bound")
    void ifUnboundBindsWhenUnbound() {
        String seen = TenantContext.callWithTenantIfUnbound("tenant-123", "acme-corp",
                () -> TenantContext.get() + "/" + TenantContext.getSlug());
        assertEquals("tenant-123/acme-corp", seen);
    }

    @Test
    @DisplayName("IfUnbound keeps an outer tenant binding and fills only the unbound slug")
    void ifUnboundKeepsOuterBinding() {
        String seen = TenantContext.callWithTenant("outer",
                () -> TenantContext.callWithTenantIfUnbound("inner", "inner-slug",
                        () -> TenantContext.get() + "/" + TenantContext.getSlug()));
        assertEquals("outer/inner-slug", seen);
    }

    @Test
    @DisplayName("IfUnbound keeps an outer slug binding and fills only the unbound tenant")
    void ifUnboundKeepsOuterSlug() {
        String seen = ScopedValue.where(TenantContext.CURRENT_TENANT_SLUG, "outer-slug")
                .call(() -> TenantContext.callWithTenantIfUnbound("inner", "inner-slug",
                        () -> TenantContext.get() + "/" + TenantContext.getSlug()));
        assertEquals("inner/outer-slug", seen);
    }

    @Test
    @DisplayName("IfUnbound never binds a null or blank tenant (that is the RLS bypass setting)")
    void ifUnboundIgnoresBlankValues() {
        AtomicReference<Boolean> tenantBound = new AtomicReference<>();
        AtomicReference<Boolean> slugBound = new AtomicReference<>();
        TenantContext.runWithTenantIfUnbound(" ", null, () -> {
            tenantBound.set(TenantContext.CURRENT_TENANT.isBound());
            slugBound.set(TenantContext.CURRENT_TENANT_SLUG.isBound());
        });
        assertFalse(tenantBound.get());
        assertFalse(slugBound.get());
    }
}
