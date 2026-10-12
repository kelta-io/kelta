package io.kelta.worker.service.entitlement;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.service.EntitlementProvider;
import io.kelta.runtime.module.service.MemberEntitlements;
import io.kelta.runtime.module.service.ModuleServiceRegistry;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Entitlements come only from a tenant's installed module; without one, a member has none.
 *
 * <p>The no-module answer is pinned exactly: it must stay what a tenant without billing got from
 * the former compiled-in service ({@link MemberEntitlements#EMPTY}), because the callers' defaults
 * — uncapped watches, ungated push/email, never-sent SMS — are built on it.
 *
 * <p>The tenant binding matters as much: a module reads through the query engine, which resolves
 * the tenant's <b>schema</b> from {@code TenantContext}'s slug. A null slug does not fail — it reads
 * the public schema — so asserting the id alone would pass against a broken binding.
 */
@DisplayName("ModuleEntitlementService")
class ModuleEntitlementServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String SLUG = "acme";
    private static final String MEMBER = "user-1";

    private ModuleServiceRegistry registry;
    private TenantSlugResolver slugResolver;
    private ModuleEntitlementService service;

    @BeforeEach
    void setUp() {
        registry = new ModuleServiceRegistry();
        slugResolver = mock(TenantSlugResolver.class);
        when(slugResolver.resolveSlug(TENANT)).thenReturn(Optional.of(SLUG));
        service = new ModuleEntitlementService(registry, slugResolver);
    }

    private static MemberEntitlements pro() {
        return new MemberEntitlements("PRO", "active",
                Map.of("maxActiveWatches", 25, "channels", List.of("email", "sms"), "beta", true));
    }

    @Nested
    @DisplayName("with no billing module installed")
    class NoModule {

        @Test
        @DisplayName("resolves EMPTY — no plan, no status, no values")
        void resolvesEmpty() {
            MemberEntitlements resolved = service.resolve(TENANT, MEMBER);

            assertThat(resolved).isEqualTo(MemberEntitlements.EMPTY);
            assertThat(resolved.planCode()).isNull();
            assertThat(resolved.subscriptionStatus()).isNull();
            assertThat(resolved.values()).isEmpty();
        }

        @Test
        @DisplayName("every typed lookup falls through to the caller's default")
        void typedLookupsUseCallerDefaults() {
            // WatchController asks with Integer.MAX_VALUE as "uncapped".
            assertThat(service.intLimit(TENANT, MEMBER, "maxActiveWatches", Integer.MAX_VALUE))
                    .isEqualTo(Integer.MAX_VALUE);
            assertThat(service.boolLimit(TENANT, MEMBER, "beta", false)).isFalse();
            // An empty channel list is what makes AlertDispatchService drop billable channels.
            assertThat(service.listLimit(TENANT, MEMBER, "channels")).isEmpty();
        }

        @Test
        @DisplayName("needs no slug lookup")
        void noSlugLookup() {
            service.resolve(TENANT, MEMBER);

            verify(slugResolver, never()).resolveSlug(any());
        }

        @Test
        @DisplayName("resolves EMPTY when runtime modules are off and no registry bean exists")
        void noRegistry() {
            ModuleEntitlementService withoutModules = new ModuleEntitlementService(null, null);

            assertThat(withoutModules.resolve(TENANT, MEMBER)).isEqualTo(MemberEntitlements.EMPTY);
        }

        @Test
        @DisplayName("a module for one tenant never answers for another")
        void moduleIsTenantScoped() {
            registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> pro());

            assertThat(service.resolve("tenant-2", MEMBER)).isEqualTo(MemberEntitlements.EMPTY);
        }
    }

    @Test
    @DisplayName("blank tenant or member resolves EMPTY without calling the module")
    void blankIdsResolveEmpty() {
        AtomicInteger calls = new AtomicInteger();
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> {
            calls.incrementAndGet();
            return pro();
        });

        assertThat(service.resolve(null, MEMBER)).isEqualTo(MemberEntitlements.EMPTY);
        assertThat(service.resolve(" ", MEMBER)).isEqualTo(MemberEntitlements.EMPTY);
        assertThat(service.resolve(TENANT, null)).isEqualTo(MemberEntitlements.EMPTY);
        assertThat(service.resolve(TENANT, "")).isEqualTo(MemberEntitlements.EMPTY);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a module-published provider answers for its tenant")
    void moduleAnswers() {
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> pro());

        assertThat(service.resolve(TENANT, MEMBER).planCode()).isEqualTo("PRO");
        assertThat(service.intLimit(TENANT, MEMBER, "maxActiveWatches", 3)).isEqualTo(25);
        assertThat(service.boolLimit(TENANT, MEMBER, "beta", false)).isTrue();
        assertThat(service.listLimit(TENANT, MEMBER, "channels")).containsExactly("email", "sms");
    }

    @Test
    @DisplayName("module answers are read fresh on every call, never cached")
    void neverCached() {
        AtomicInteger calls = new AtomicInteger();
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> {
            calls.incrementAndGet();
            return pro();
        });

        service.resolve(TENANT, MEMBER);
        service.resolve(TENANT, MEMBER);

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("the provider is called with the tenant SLUG bound, not just the id")
    void bindsTenantSlugAroundTheCall() {
        AtomicReference<String> seenId = new AtomicReference<>();
        AtomicReference<String> seenSlug = new AtomicReference<>();
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> {
            seenId.set(TenantContext.get());
            seenSlug.set(TenantContext.getSlug());
            return pro();
        });

        service.resolve(TENANT, MEMBER);

        assertThat(seenId).hasValue(TENANT);
        assertThat(seenSlug).hasValue(SLUG);
    }

    @Test
    @DisplayName("an unresolvable slug refuses the module and resolves EMPTY")
    void refusesModuleWhenSlugUnknown() {
        when(slugResolver.resolveSlug(TENANT)).thenReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> {
            calls.incrementAndGet();
            return pro();
        });

        // Answering from the public schema is worse than answering with no entitlements.
        assertThat(service.resolve(TENANT, MEMBER)).isEqualTo(MemberEntitlements.EMPTY);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("an already-bound matching context is not rebound")
    void doesNotRebindAnAlreadyBoundContext() {
        AtomicReference<String> seenSlug = new AtomicReference<>();
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> {
            seenSlug.set(TenantContext.getSlug());
            return pro();
        });

        TenantContext.runWithTenant(TENANT, "bound-by-the-request",
                () -> service.resolve(TENANT, MEMBER));

        assertThat(seenSlug).hasValue("bound-by-the-request");
        verify(slugResolver, never()).resolveSlug(any());
    }

    @Test
    @DisplayName("a throwing module resolves EMPTY rather than taking the alert path down")
    void moduleFailureResolvesEmpty() {
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> {
            throw new IllegalStateException("module blew up");
        });

        assertThat(service.resolve(TENANT, MEMBER)).isEqualTo(MemberEntitlements.EMPTY);
        // Never wider than a tenant with no billing: SMS stays ungranted.
        assertThat(service.listLimit(TENANT, MEMBER, "channels")).isEmpty();
    }

    @Test
    @DisplayName("a module returning null resolves EMPTY rather than NPEing downstream")
    void nullFromModuleBecomesEmpty() {
        registry.register(TENANT, EntitlementProvider.class, (EntitlementProvider) (t, u) -> null);

        assertThat(service.resolve(TENANT, MEMBER)).isEqualTo(MemberEntitlements.EMPTY);
        assertThat(service.intLimit(TENANT, MEMBER, "maxActiveWatches", 3)).isEqualTo(3);
    }
}
