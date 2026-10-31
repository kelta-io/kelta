package io.kelta.auth.service;

/**
 * Decides whether a password sign-in has to continue with a second factor before any token is
 * issued.
 *
 * <p>Every password sign-in path asks this one rule, so the paths cannot drift apart: the browser
 * form login (the success handler in {@code AuthorizationServerConfig}) sends the user to the MFA
 * step it names, and {@code POST /auth/direct-login}, which has no second step, refuses the
 * account instead of minting tokens.
 *
 * <ul>
 *   <li>{@link Step#CHALLENGE} — the user has a verified TOTP enrollment;</li>
 *   <li>{@link Step#SETUP} — the tenant's password policy requires MFA and the user is not
 *       enrolled yet;</li>
 *   <li>{@link Step#NONE} — neither: the password alone completes the sign-in.</li>
 * </ul>
 *
 * <p>Without a {@link TotpService} (only in tests that leave it out) no step is required — the
 * behaviour the browser success handler has always had in that case.
 */
public final class LoginMfaPolicy {

    /** The second step a password sign-in must take, if any. */
    public enum Step {
        NONE,
        CHALLENGE,
        SETUP
    }

    private final TotpService totpService;
    private final PasswordPolicyService passwordPolicyService;

    public LoginMfaPolicy(TotpService totpService, PasswordPolicyService passwordPolicyService) {
        this.totpService = totpService;
        this.passwordPolicyService = passwordPolicyService;
    }

    /** The step the user of {@code tenantId} must complete after a correct password. */
    public Step requiredStep(String userId, String tenantId) {
        if (totpService == null) {
            return Step.NONE;
        }
        if (totpService.isEnrolled(userId)) {
            return Step.CHALLENGE;
        }
        if (passwordPolicyService.isMfaRequired(tenantId)) {
            return Step.SETUP;
        }
        return Step.NONE;
    }
}
