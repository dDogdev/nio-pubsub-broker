package dev.dogmilian.nexus.fault;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Monitors connection health and cuts slow consumers mercilessly.
 * In HFT environments, it's better to drop a slow connection than stall the exchange.
 */
public final class SlowConsumerWatchdog {
    private static final int MAX_FAILED_FLUSHES = 3;

    public static void checkAndExecute(SelectionKey key, OutboundRingBuffer buffer, SocketChannel channel) {
        if (buffer.consecutiveFailedFlushes >= MAX_FAILED_FLUSHES) {
            System.err.println("[WATCHDOG] Decapitating slow consumer. Socket SO_SNDBUF persistently full: " + channel);
            executeDecapitation(key, channel);
        }
    }

    public static void executeDecapitation(SelectionKey key, SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {}
        if (key != null) {
            key.cancel();
        }
    }
}
