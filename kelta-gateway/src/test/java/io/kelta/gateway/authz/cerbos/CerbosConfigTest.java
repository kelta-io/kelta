package io.kelta.gateway.authz.cerbos;

import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CheckResult;
import dev.cerbos.sdk.builders.Principal;
import dev.cerbos.sdk.builders.Resource;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("CerbosConfig (Gateway)")
class CerbosConfigTest {

    @Test
    @DisplayName("Channel builder applies plaintext and configured keepalive")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void configuresKeepalive() {
        ManagedChannelBuilder builder = mock(ManagedChannelBuilder.class, RETURNS_SELF);

        CerbosConfig.configureChannel(builder, 45, 7, true);

        verify(builder).usePlaintext();
        verify(builder).keepAliveTime(45, TimeUnit.SECONDS);
        verify(builder).keepAliveTimeout(7, TimeUnit.SECONDS);
        verify(builder).keepAliveWithoutCalls(true);
    }

    @Test
    @DisplayName("Failed first warmup rebuilds the channel before retrying")
    void rebuildsChannelAfterFailedWarmup() {
        CerbosBlockingClient client = mock(CerbosBlockingClient.class);
        CerbosChannel channel = mock(CerbosChannel.class);
        when(client.check(any(Principal.class), any(Resource.class), anyString()))
                .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException())
                .thenReturn(mock(CheckResult.class));

        boolean warmed = CerbosConfig.warmUp(client, channel, 10, 0);

        assertThat(warmed).isTrue();
        verify(client, times(2)).check(any(Principal.class), any(Resource.class), anyString());
        verify(channel, times(1)).rebuild();
    }

    @Test
    @DisplayName("Successful first warmup keeps the original channel")
    void keepsChannelWhenFirstWarmupSucceeds() {
        CerbosBlockingClient client = mock(CerbosBlockingClient.class);
        CerbosChannel channel = mock(CerbosChannel.class);
        when(client.check(any(Principal.class), any(Resource.class), anyString()))
                .thenReturn(mock(CheckResult.class));

        assertThat(CerbosConfig.warmUp(client, channel, 10, 0)).isTrue();
        verify(channel, never()).rebuild();
    }

    @Test
    @DisplayName("Warmup reports failure after exhausting attempts")
    void reportsFailureAfterMaxAttempts() {
        CerbosBlockingClient client = mock(CerbosBlockingClient.class);
        CerbosChannel channel = mock(CerbosChannel.class);
        when(client.check(any(Principal.class), any(Resource.class), anyString()))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        assertThat(CerbosConfig.warmUp(client, channel, 3, 0)).isFalse();
        verify(channel, times(3)).rebuild();
    }

    @Test
    @DisplayName("Rebuild swaps in a new ManagedChannel and shuts the old one down")
    void rebuildSwapsDelegate() {
        ManagedChannel first = mock(ManagedChannel.class);
        ManagedChannel second = mock(ManagedChannel.class);
        when(first.authority()).thenReturn("cerbos:3593");
        when(second.authority()).thenReturn("cerbos:3593");
        @SuppressWarnings("unchecked")
        Supplier<ManagedChannel> factory = mock(Supplier.class);
        when(factory.get()).thenReturn(first, second);

        CerbosChannel channel = new CerbosChannel(factory);
        channel.rebuild();
        channel.resetConnection();

        verify(first).shutdown();
        verify(first, never()).enterIdle();
        verify(second).enterIdle();
        verify(second, never()).shutdown();
    }
}
