package io.kelta.auth.session;

import org.springframework.lang.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the session authentication for a sign-in path that does not go through a Spring
 * Security authentication provider: the portal magic link and the OIDC / SAML federation
 * handlers, which swap the upstream authentication for one wrapping the platform user.
 *
 * <p>Since Spring Security 7.0.5 the authorization server's {@code JwtGenerator} takes the ID
 * token's {@code auth_time} from the newest {@link FactorGrantedAuthority} on the principal
 * (spring-security#18282) and fails the {@code authorization_code} token request with
 * "authenticationTime cannot be null" when there is none. Spring Security's own providers stamp
 * one (form login adds {@code FACTOR_PASSWORD}); a hand-built token must carry the factor the
 * user actually presented, or every OpenID Connect sign-in through it ends in a 500 from
 * {@code /oauth2/token}.
 */
public final class SignInAuthentication {

    private SignInAuthentication() {
    }

    /**
     * An authenticated token for {@code user} with the user's authorities plus the sign-in's
     * factors: the {@link FactorGrantedAuthority} entries of {@code upstream} (keeping their
     * issued-at, so {@code auth_time} is when the user really authenticated), or, when it has
     * none, {@code factorAuthority} issued now.
     *
     * @param upstream        the authentication this sign-in replaces, or {@code null}
     * @param factorAuthority the factor the path verified, a {@code FactorGrantedAuthority.*_AUTHORITY}
     */
    public static UsernamePasswordAuthenticationToken authenticated(
            UserDetails user, @Nullable Authentication upstream, String factorAuthority) {
        List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
        List<FactorGrantedAuthority> factors = upstream == null ? List.of()
                : upstream.getAuthorities().stream()
                        .filter(FactorGrantedAuthority.class::isInstance)
                        .map(FactorGrantedAuthority.class::cast)
                        .toList();
        if (factors.isEmpty()) {
            authorities.add(FactorGrantedAuthority.withAuthority(factorAuthority).build());
        } else {
            authorities.addAll(factors);
        }
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
