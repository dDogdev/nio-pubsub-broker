package dev.dogmilian.nexus.engine;

import dev.dogmilian.nexus.protocol.VectorizedFrameDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Payload carrier for the NexusRingBuffer.
 */
public final class NexusEvent {
    public static final int MAX_PAYLOAD_SIZE = 4096;
    public VectorizedFrameDecoder.Header header;
    // Pre-allocated Direct Buffer (Zero-GC Hot Path)
    public final ByteBuffer payload = ByteBuffer.allocateDirect(MAX_PAYLOAD_SIZE);
    public SocketChannel sourceChannel;

    public void clear() {
        this.header = null;
        this.payload.clear();
        this.sourceChannel = null;
    }
}
