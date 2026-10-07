package io.kelta.runtime.context;

import io.kelta.runtime.context.CallerContext.Access;
import io.kelta.runtime.context.CallerContext.OwnerScope;
import io.kelta.runtime.context.CallerContext.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CallerContext")
class CallerContextTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("is empty outside a bound scope (internal tier)")
    void emptyWhenUnbound() {
        assertThat(CallerContext.current()).isEmpty();
    }

    @Test
    @DisplayName("callAs binds the caller for the duration of the call only")
    void callAsBindsCaller() {
        CallerContext alice = new CallerContext(ALICE, UserType.PORTAL, false, false);

        String seen = CallerContext.callAs(alice, () -> CallerContext.current().orElseThrow().userId());

        assertThat(seen).isEqualTo(ALICE);
        assertThat(CallerContext.current()).isEmpty();
    }

    @Test
    @DisplayName("user type parses PORTAL case-insensitively and defaults to INTERNAL")
    void parsesUserType() {
        assertThat(UserType.parse("portal")).isEqualTo(UserType.PORTAL);
        assertThat(UserType.parse("PORTAL")).isEqualTo(UserType.PORTAL);
        assertThat(UserType.parse("INTERNAL")).isEqualTo(UserType.INTERNAL);
        assertThat(UserType.parse(null)).isEqualTo(UserType.INTERNAL);
        assertThat(new CallerContext(ALICE, null, false, false).userType()).isEqualTo(UserType.INTERNAL);
    }

    @Test
    @DisplayName("NONE scopes nobody; PORTAL scopes only portal callers")
    void noneAndPortalScopes() {
        CallerContext member = new CallerContext(ALICE, UserType.PORTAL, false, false);
        CallerContext staff = new CallerContext(ALICE, UserType.INTERNAL, false, false);

        assertThat(member.ownerScoped(OwnerScope.NONE, Access.READ)).isFalse();
        assertThat(member.ownerScoped(OwnerScope.PORTAL, Access.READ)).isTrue();
        assertThat(staff.ownerScoped(OwnerScope.PORTAL, Access.WRITE)).isFalse();
        assertThat(staff.ownerScoped(null, Access.WRITE)).isFalse();
    }

    @Test
    @DisplayName("ALL scopes everyone except staff holding the matching *_ALL_DATA bypass")
    void allScopeBypasses() {
        CallerContext viewer = new CallerContext(ALICE, UserType.INTERNAL, true, false);
        CallerContext modifier = new CallerContext(ALICE, UserType.INTERNAL, false, true);
        CallerContext member = new CallerContext(ALICE, UserType.PORTAL, true, true);

        assertThat(viewer.ownerScoped(OwnerScope.ALL, Access.READ)).isFalse();
        assertThat(viewer.ownerScoped(OwnerScope.ALL, Access.WRITE)).isTrue();
        assertThat(modifier.ownerScoped(OwnerScope.ALL, Access.READ)).isTrue();
        assertThat(modifier.ownerScoped(OwnerScope.ALL, Access.WRITE)).isFalse();
        // A portal member never bypasses, whatever their flags say.
        assertThat(member.ownerScoped(OwnerScope.ALL, Access.READ)).isTrue();
        assertThat(member.ownerScoped(OwnerScope.ALL, Access.WRITE)).isTrue();
    }
}
