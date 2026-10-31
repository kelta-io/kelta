package io.kelta.worker.controller;

import io.kelta.runtime.context.ActingUser;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A support write on a member's rows runs as the member for the owner checks, but stays
 * attributed to the support actor: the record-changed event, the flows it starts and the history
 * entries name who did it.
 */
@DisplayName("SupportPermissions.writeAs")
class SupportPermissionsWriteAsTest {

    private static final String STAFF = "cccccccc-0000-4000-8000-00000000000c";
    private static final String MEMBER = "aaaaaaaa-0000-4000-8000-00000000000a";

    private record Seen(String callerId, UserType callerType, String actingUser) {
    }

    private static Seen observe() {
        CallerContext caller = CallerContext.current().orElseThrow();
        return new Seen(caller.userId(), caller.userType(), ActingUser.current().orElse(null));
    }

    @Test
    @DisplayName("writing as another member re-binds the caller and keeps the staff actor as acting user")
    void onBehalfWriteKeepsStaffAttribution() {
        CallerContext staff = new CallerContext(STAFF, UserType.INTERNAL, false, true);

        Seen seen = CallerContext.callAs(staff, () -> SupportPermissions.writeAs(MEMBER, SupportPermissionsWriteAsTest::observe));

        assertThat(seen.callerId()).isEqualTo(MEMBER);
        assertThat(seen.callerType()).isEqualTo(UserType.PORTAL);
        assertThat(seen.actingUser()).isEqualTo(STAFF);
    }

    @Test
    @DisplayName("writing as yourself changes nothing")
    void selfWriteUnchanged() {
        CallerContext member = new CallerContext(MEMBER, UserType.PORTAL, false, false);

        Seen seen = CallerContext.callAs(member, () -> SupportPermissions.writeAs(MEMBER, SupportPermissionsWriteAsTest::observe));

        assertThat(seen.callerId()).isEqualTo(MEMBER);
        assertThat(seen.actingUser()).isEqualTo(MEMBER);
    }
}
