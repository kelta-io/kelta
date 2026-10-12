package io.kelta.runtime.messaging.nats;

import io.nats.client.JetStreamManagement;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JetStreamInitializer")
class JetStreamInitializerTest {

    /**
     * Regression for the telehealth outage: the video-session and chat subjects
     * had no stream, so JetStream publishes got no ack and cancelled, and the
     * gateway's push consumers failed with [SUB-90007] No matching streams.
     */
    @Test
    @DisplayName("provisions the video-session and chat streams when absent")
    void createsVideoAndChatStreams() throws Exception {
        NatsConnectionManager connectionManager = mock(NatsConnectionManager.class);
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(connectionManager.jetStreamManagement()).thenReturn(jsm);
        // Every stream is absent → getStreamInfo throws → addStream is invoked.
        when(jsm.getStreamInfo(anyString())).thenThrow(new IOException("stream not found"));

        new JetStreamInitializer(connectionManager).initializeStreams();

        ArgumentCaptor<StreamConfiguration> captor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm, atLeastOnce()).addStream(captor.capture());
        List<StreamConfiguration> created = captor.getAllValues();

        assertThat(created)
                .as("KELTA_VIDEO_SESSION stream capturing kelta.video.session.>")
                .anyMatch(c -> "KELTA_VIDEO_SESSION".equals(c.getName())
                        && c.getSubjects().contains("kelta.video.session.>"));
        assertThat(created)
                .as("KELTA_CHAT stream capturing both chat subjects")
                .anyMatch(c -> "KELTA_CHAT".equals(c.getName())
                        && c.getSubjects().containsAll(
                                List.of("kelta.chat.message.>", "kelta.chat.conversation.>")));
        assertThat(created)
                .as("KELTA_AVAILABILITY stream capturing kelta.availability.>")
                .anyMatch(c -> "KELTA_AVAILABILITY".equals(c.getName())
                        && c.getSubjects().contains("kelta.availability.>"));
    }
}
