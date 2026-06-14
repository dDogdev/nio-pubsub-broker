package dev.dogmilian.nexus.engine;

import dev.dogmilian.nexus.protocol.VectorizedFrameDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Payload carrier for the NexusRingBuffer.
 */
public final class NexusEvent {
    public VectorizedFrameDecoder.Header header;
    public ByteBuffer payload;
    public SocketChannel sourceChannel;

    public void clear() {
        this.header = null;
        this.payload = null;
        this.sourceChannel = null;
    }
}
