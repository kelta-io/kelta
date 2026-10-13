package io.kelta.auth.config;

import io.kelta.auth.controller.ForcePasswordChangeController;
import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.KeltaUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * Form-login failure handling. A sign-in whose password was right but must be changed
 * ({@link CredentialsExpiredException}, raised after the password check) continues at
 * {@code /change-password} for exactly the user this request loaded — the tenant + user id that
 * {@link KeltaUserDetailsService} left in {@link KeltaUserDetailsService#LOADED_USER_ATTRIBUTE} —
 * via {@link ForcePasswordChangeController#begin}. Every other failure returns to the form.
 */
public class LoginFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        Object loaded = request.getAttribute(KeltaUserDetailsService.LOADED_USER_ATTRIBUTE);
        request.removeAttribute(KeltaUserDetailsService.LOADED_USER_ATTRIBUTE);
        if (isCredentialsExpired(exception)
                && loaded instanceof KeltaUserDetails user
                && !user.isCredentialsNonExpired()) {
            ForcePasswordChangeController.begin(request, user);
            response.sendRedirect("/change-password");
            return;
        }
        response.sendRedirect("/login?error");
    }

    private static boolean isCredentialsExpired(AuthenticationException exception) {
        return exception instanceof CredentialsExpiredException
                || exception.getCause() instanceof CredentialsExpiredException;
    }
}
