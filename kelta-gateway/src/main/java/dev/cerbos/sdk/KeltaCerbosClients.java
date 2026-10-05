package dev.cerbos.sdk;

import io.grpc.Channel;

/**
 * Builds a {@link CerbosBlockingClient} over a caller-supplied gRPC {@link Channel}.
 *
 * <p>{@link CerbosClientBuilder} (SDK 0.12.0) builds its own channel and exposes no
 * keepalive or channel-reset knobs, and the client constructor that accepts a channel is
 * package-private. This class lives in the SDK package solely to reach that constructor,
 * so the gateway can own channel construction (keepalive, rebuild after a failed warmup).
 */
public final class KeltaCerbosClients {

    private KeltaCerbosClients() {
    }

    public static CerbosBlockingClient blockingClient(Channel channel, long timeoutMillis) {
        return new CerbosBlockingClient(channel, timeoutMillis, null);
    }
}
