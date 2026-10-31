package io.kelta.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("LoginMfaPolicy — the MFA step after a correct password, shared by the browser and direct login")
class LoginMfaPolicyTest {

    private final TotpService totpService = mock(TotpService.class);
    private final PasswordPolicyService passwordPolicyService = mock(PasswordPolicyService.class);
    private final LoginMfaPolicy policy = new LoginMfaPolicy(totpService, passwordPolicyService);

    @Test
    @DisplayName("an enrolled user gets the challenge, whatever the tenant policy says")
    void enrolledUserIsChallenged() {
        when(totpService.isEnrolled("user-1")).thenReturn(true);

        assertThat(policy.requiredStep("user-1", "tenant-1")).isEqualTo(LoginMfaPolicy.Step.CHALLENGE);
    }

    @Test
    @DisplayName("an unenrolled user of a tenant that requires MFA must set it up")
    void policyRequiresSetup() {
        when(totpService.isEnrolled("user-1")).thenReturn(false);
        when(passwordPolicyService.isMfaRequired("tenant-1")).thenReturn(true);

        assertThat(policy.requiredStep("user-1", "tenant-1")).isEqualTo(LoginMfaPolicy.Step.SETUP);
    }

    @Test
    @DisplayName("no enrollment and no tenant requirement — the password completes the sign-in")
    void plainUserNeedsNoStep() {
        when(totpService.isEnrolled("user-1")).thenReturn(false);
        when(passwordPolicyService.isMfaRequired("tenant-1")).thenReturn(false);

        assertThat(policy.requiredStep("user-1", "tenant-1")).isEqualTo(LoginMfaPolicy.Step.NONE);
    }

    @Test
    @DisplayName("without a TotpService no step is required (the success handler's long-standing behaviour)")
    void noTotpServiceMeansNoStep() {
        LoginMfaPolicy withoutTotp = new LoginMfaPolicy(null, passwordPolicyService);

        assertThat(withoutTotp.requiredStep("user-1", "tenant-1")).isEqualTo(LoginMfaPolicy.Step.NONE);
        verifyNoInteractions(passwordPolicyService);
    }
}
