package io.kelta.worker.runner;

import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static io.kelta.worker.runner.BaselineIdentityProviderReconciler.BASELINE_INTERNAL_PROVIDER_ID;
import static io.kelta.worker.runner.BaselineIdentityProviderReconciler.BASELINE_ISSUER;
import static io.kelta.worker.runner.BaselineIdentityProviderReconciler.DEACTIVATE_EXTERNAL;
import static io.kelta.worker.runner.BaselineIdentityProviderReconciler.DEFAULT_TENANT_ID;
import static io.kelta.worker.runner.BaselineIdentityProviderReconciler.REPAIR_INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the #1591 repair's guard conditions. The SQL itself (every guard lives in its WHERE)
 * was also run against a real Postgres with the baseline rows — see the PR.
 */
class BaselineIdentityProviderReconcilerTest {

    private static final String LOCAL_ISSUER = "http://auth.localhost:8081";

    private JdbcTemplate jdbc;
    private RecordEventPublisher publisher;
    private ObjectProvider<RecordEventPublisher> publisherProvider;
    private PlatformTransactionManager txManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        publisher = mock(RecordEventPublisher.class);
        publisherProvider = mock(ObjectProvider.class);
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private BaselineIdentityProviderReconciler reconciler(String issuer) {
        return new BaselineIdentityProviderReconciler(jdbc, txManager, publisherProvider, issuer);
    }

    @Test
    @DisplayName("production-shaped config (issuer = baseline issuer) touches nothing")
    void productionIsNoOp() {
        assertThat(reconciler(BASELINE_ISSUER).reconcile()).isEmpty();
        assertThat(reconciler(BASELINE_ISSUER + "/").reconcile()).isEmpty();
        verifyNoInteractions(jdbc, publisher);
    }

    @Test
    @DisplayName("no configured issuer touches nothing")
    void blankIssuerIsNoOp() {
        assertThat(reconciler("").reconcile()).isEmpty();
        assertThat(reconciler(null).reconcile()).isEmpty();
        verifyNoInteractions(jdbc, publisher);
    }

    @Test
    @DisplayName("fresh local install: internal issuer repointed, dead externals deactivated, all broadcast")
    void freshInstallIsRepaired() {
        when(jdbc.update(eq(REPAIR_INTERNAL), any(Object[].class))).thenReturn(1);
        when(jdbc.update(eq(DEACTIVATE_EXTERNAL), any(Object[].class))).thenReturn(1);

        List<String> changed = reconciler(LOCAL_ISSUER + "/").reconcile();

        verify(jdbc).update(REPAIR_INTERNAL, LOCAL_ISSUER, LOCAL_ISSUER + "/oauth2/jwks",
                BASELINE_INTERNAL_PROVIDER_ID, DEFAULT_TENANT_ID, BASELINE_ISSUER);
        verify(jdbc).update(DEACTIVATE_EXTERNAL, "local-keycloak", DEFAULT_TENANT_ID,
                "http://localhost:8180/realms/emf");
        verify(jdbc).update(DEACTIVATE_EXTERNAL, "kelta-api-provider", DEFAULT_TENANT_ID,
                "https://authentik.rzware.com/application/o/kelta-api/");
        assertThat(changed).containsExactlyInAnyOrder(
                BASELINE_INTERNAL_PROVIDER_ID, "local-keycloak", "kelta-api-provider");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlatformEvent<RecordChangedPayload>> events = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher, times(3)).publish(events.capture());
        assertThat(events.getAllValues()).allSatisfy(e -> {
            assertThat(e.getTenantId()).isEqualTo(DEFAULT_TENANT_ID);
            assertThat(e.getPayload().getCollectionName()).isEqualTo("oidc-providers");
        });
        assertThat(events.getAllValues())
                .filteredOn(e -> e.getPayload().getRecordId().equals(BASELINE_INTERNAL_PROVIDER_ID))
                .singleElement()
                .satisfies(e -> assertThat(e.getPayload().getData()).containsEntry("issuer", LOCAL_ISSUER));
    }

    @Test
    @DisplayName("already repaired (internal issuer no longer the baseline one): externals left alone")
    void alreadyRepairedLeavesExternalsAlone() {
        when(jdbc.update(eq(REPAIR_INTERNAL), any(Object[].class))).thenReturn(0);

        assertThat(reconciler(LOCAL_ISSUER).reconcile()).isEmpty();

        verify(jdbc, never()).update(eq(DEACTIVATE_EXTERNAL), any(Object[].class));
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("repair still succeeds when no event publisher is available")
    void repairsWithoutPublisher() {
        when(publisherProvider.getIfAvailable()).thenReturn(null);
        when(jdbc.update(eq(REPAIR_INTERNAL), any(Object[].class))).thenReturn(1);
        when(jdbc.update(eq(DEACTIVATE_EXTERNAL), any(Object[].class))).thenReturn(0);

        assertThat(reconciler(LOCAL_ISSUER).reconcile()).containsExactly(BASELINE_INTERNAL_PROVIDER_ID);
    }

    @Test
    @DisplayName("a publish failure does not undo or abort the repair")
    void publishFailureIsSwallowed() {
        when(jdbc.update(eq(REPAIR_INTERNAL), any(Object[].class))).thenReturn(1);
        when(jdbc.update(eq(DEACTIVATE_EXTERNAL), any(Object[].class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("nats down")).when(publisher).publish(any());

        assertThat(reconciler(LOCAL_ISSUER).reconcile()).hasSize(3);
        verify(publisher, times(3)).publish(any());
    }

    @Test
    @DisplayName("a failure never blocks startup")
    void failureDoesNotPropagate() {
        when(jdbc.update(eq(REPAIR_INTERNAL), any(Object[].class))).thenThrow(new IllegalStateException("db down"));

        reconciler(LOCAL_ISSUER).run(null);

        verifyNoInteractions(publisher);
    }
}
