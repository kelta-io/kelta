package io.kelta.runtime.context;

import io.kelta.runtime.context.CallerContext.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ActingUser")
class ActingUserTest {

    private static final String CALLER = "aaaaaaaa-0000-4000-8000-00000000000a";
    private static final String ACTOR = "bbbbbbbb-0000-4000-8000-00000000000b";

    private static CallerContext caller() {
        return new CallerContext(CALLER, UserType.INTERNAL, false, false);
    }

    @Test
    @DisplayName("is empty when nothing is bound")
    void emptyWhenUnbound() {
        assertThat(ActingUser.current()).isEmpty();
    }

    @Test
    @DisplayName("falls back to the request caller")
    void fallsBackToCaller() {
        CallerContext.runAs(caller(), () -> assertThat(ActingUser.current()).contains(CALLER));
    }

    @Test
    @DisplayName("an explicit binding wins over the request caller")
    void bindingWinsOverCaller() {
        CallerContext.runAs(caller(), () ->
                ActingUser.runAs(ACTOR, () -> assertThat(ActingUser.current()).contains(ACTOR)));
        assertThat(CallerContext.callAs(caller(), () -> ActingUser.callAs(ACTOR, ActingUser::current)))
                .contains(ACTOR);
    }

    @Test
    @DisplayName("a null or blank user binds nothing, so the enclosing attribution still applies")
    void blankBindsNothing() {
        CallerContext.runAs(caller(), () -> {
            ActingUser.runAs(null, () -> assertThat(ActingUser.current()).contains(CALLER));
            ActingUser.runAs("  ", () -> assertThat(ActingUser.current()).contains(CALLER));
            assertThat(ActingUser.callAs(null, ActingUser::current)).contains(CALLER);
        });
        ActingUser.runAs(null, () -> assertThat(ActingUser.current()).isEmpty());
    }
}
