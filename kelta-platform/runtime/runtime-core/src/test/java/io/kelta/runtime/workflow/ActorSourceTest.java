package io.kelta.runtime.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActorSource")
class ActorSourceTest {

    @Test
    @DisplayName("only an initiator is a person who acted; run-as and owner are fallbacks")
    void fallback() {
        assertFalse(ActorSource.INITIATOR.isFallback());
        assertTrue(ActorSource.RUN_AS.isFallback());
        assertTrue(ActorSource.OWNER.isFallback());
    }

    @Test
    @DisplayName("a stored name reads back; a blank or unknown one is unknown (null)")
    void fromName() {
        for (ActorSource source : ActorSource.values()) {
            assertEquals(source, ActorSource.fromName(source.name()));
        }
        assertNull(ActorSource.fromName(null));
        assertNull(ActorSource.fromName(" "));
        assertNull(ActorSource.fromName("initiator"));
        assertNull(ActorSource.fromName("CALLER"));
    }
}
