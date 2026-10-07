package io.kelta.worker.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code P.id} is the caller's email, so own-record rules comparing a UUID column with it never
 * matched. {@code $CURRENT_USER} now compiles to {@code P.attr.userId}, and legacy {@code == P.id}
 * comparisons are rewritten — but only against UUID-typed fields.
 */
@DisplayName("CerbosPolicySyncService CEL generation")
class CerbosPolicySyncCelTest {

    private static final Set<String> UUID_FIELDS = Set.of("createdBy", "updatedBy", "member");

    @Test
    @DisplayName("visual $CURRENT_USER compiles to P.attr.userId")
    void currentUserCompilesToUserIdAttr() {
        assertThat(CerbosPolicySyncService.convertVisualToCel("createdBy", "equals", "$CURRENT_USER"))
                .isEqualTo("R.attr.createdBy == P.attr.userId");
        assertThat(CerbosPolicySyncService.convertVisualToCel("status", "equals", "open"))
                .isEqualTo("R.attr.status == \"open\"");
    }

    @Test
    @DisplayName("legacy == P.id against createdBy or a LOOKUP field is rewritten to P.attr.userId")
    void rewritesUuidComparisons() {
        assertThat(CerbosPolicySyncService.rewriteLegacyPrincipalId("R.attr.createdBy == P.id", UUID_FIELDS))
                .isEqualTo("R.attr.createdBy == P.attr.userId");
        assertThat(CerbosPolicySyncService.rewriteLegacyPrincipalId("P.id == R.attr.member", UUID_FIELDS))
                .isEqualTo("P.attr.userId == R.attr.member");
        assertThat(CerbosPolicySyncService.rewriteLegacyPrincipalId(
                "R.attr.status == \"open\" && R.attr.updatedBy!=P.id", UUID_FIELDS))
                .isEqualTo("R.attr.status == \"open\" && R.attr.updatedBy!=P.attr.userId");
    }

    @Test
    @DisplayName("an email comparison such as R.attr.ownerEmail == P.id is left unchanged")
    void leavesEmailComparisonsAlone() {
        String rule = "R.attr.ownerEmail == P.id";
        assertThat(CerbosPolicySyncService.rewriteLegacyPrincipalId(rule, UUID_FIELDS)).isEqualTo(rule);
        String reversed = "P.id == R.attr.ownerEmail || R.attr.createdBy == P.id";
        assertThat(CerbosPolicySyncService.rewriteLegacyPrincipalId(reversed, UUID_FIELDS))
                .isEqualTo("P.id == R.attr.ownerEmail || R.attr.createdBy == P.attr.userId");
    }

    @Test
    @DisplayName("does not touch look-alike identifiers")
    void ignoresLookAlikes() {
        String rule = "R.attr.createdBy == P.idx || R.attr.createdBy == P.id.lowerAscii()";
        assertThat(CerbosPolicySyncService.rewriteLegacyPrincipalId(rule, UUID_FIELDS)).isEqualTo(rule);
        String nested = "R.attr.createdByX == P.id";
        assertThat(CerbosPolicySyncService.rewriteLegacyPrincipalId(nested, UUID_FIELDS)).isEqualTo(nested);
    }
}
