package dev.dogmilian.nexus.fault;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.io.IOException;

/**
 * Per-client Outbound buffer for mechanical backpressure handling.
 * Enforces a strict memory limit per client and provides metrics for the Watchdog.
 */
public final class OutboundRingBuffer {
    private final ByteBuffer[] queue;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final int capacity;

    public int consecutiveFailedFlushes = 0;

    public OutboundRingBuffer(int capacity) {
        this.capacity = capacity;
        this.queue = new ByteBuffer[capacity];
    }

    public synchronized boolean enqueue(ByteBuffer buffer) {
        if (size == capacity) {
            return false; // Extreme Backpressure: Client buffer is entirely saturated
        }
        queue[tail] = buffer;
        tail = (tail + 1) % capacity;
        size++;
        return true;
    }

    public synchronized boolean flush(SocketChannel channel) throws IOException {
        if (size == 0) return true;

        ByteBuffer buffer = queue[head];
        channel.write(buffer);

        if (!buffer.hasRemaining()) {
            queue[head] = null; // GC Release
            head = (head + 1) % capacity;
            size--;
            consecutiveFailedFlushes = 0;
            return true;
        } else {
            // Socket SO_SNDBUF is full, TCP Window is closed
            consecutiveFailedFlushes++;
            return false;
        }
    }
}
