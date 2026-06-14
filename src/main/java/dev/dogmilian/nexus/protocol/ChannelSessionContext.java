package dev.dogmilian.nexus.protocol;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.io.IOException;

/**
 * Reader State Machine strictly coupled with the SelectionKey.
 * Handles TCP fragmentation, MTU cuts, and buffers off-heap.
 * Protects the WorkerLoop by never blocking.
 */
public final class ChannelSessionContext {
    public enum State {
        AWAITING_HEADER,
        READING_PAYLOAD
    }

    private State state = State.AWAITING_HEADER;
    public final ByteBuffer buffer;
    public final SelectionKey key;
    public final SocketChannel channel;
    public final dev.dogmilian.nexus.fault.OutboundRingBuffer outbound = new dev.dogmilian.nexus.fault.OutboundRingBuffer(256);
    
    private VectorizedFrameDecoder.Header currentHeader;

    public ChannelSessionContext(SelectionKey key, SocketChannel channel, ByteBuffer buffer) {
        this.key = key;
        this.channel = channel;
        this.buffer = buffer;
    }

    public void read() throws IOException {
        int bytesRead = channel.read(buffer);
        if (bytesRead == -1) {
            close();
            return;
        }

        // Prepare buffer for reading data
        buffer.flip();

        while (buffer.hasRemaining()) {
            if (state == State.AWAITING_HEADER) {
                if (buffer.remaining() < 8) {
                    break; // TCP Fragmentation: Wait for more bytes to form the 8-byte header
                }
                
                // SIMD Header Validation & Parsing
                this.currentHeader = VectorizedFrameDecoder.decodeHeader(buffer, buffer.position());
                buffer.position(buffer.position() + 8); // Advance pointer past header
                this.state = State.READING_PAYLOAD;
            }

            if (state == State.READING_PAYLOAD) {
                if (buffer.remaining() < currentHeader.payloadLength()) {
                    break; // TCP Fragmentation: Wait for the rest of the payload
                }

                // Zero-copy extraction of the payload slice
                ByteBuffer payloadSlice = buffer.slice(buffer.position(), currentHeader.payloadLength());
                buffer.position(buffer.position() + currentHeader.payloadLength());

                // Hand-off to RingBuffer (Phase 4 integration)
                dispatchToRingBuffer(currentHeader, payloadSlice);

                // Reset state for the next continuous frame in the stream
                this.state = State.AWAITING_HEADER;
                this.currentHeader = null;
            }
        }

        // Buffer compaction: shifts unread bytes to the start, positioning for next OS write
        buffer.compact();
    }

    private void dispatchToRingBuffer(VectorizedFrameDecoder.Header header, ByteBuffer payload) {
        dev.dogmilian.nexus.engine.NexusRingBuffer ring = dev.dogmilian.nexus.NexusGlobal.ENGINE.getRingBuffer();
        
        // Wait-free claim next sequence
        long seq = ring.next();
        dev.dogmilian.nexus.engine.NexusEvent event = ring.get(seq);
        
        // Populate event
        event.header = header;
        
        // Zero-GC Payload copy into pre-allocated buffer
        event.payload.clear();
        if (payload.remaining() > dev.dogmilian.nexus.engine.NexusEvent.MAX_PAYLOAD_SIZE) {
            payload.limit(payload.position() + dev.dogmilian.nexus.engine.NexusEvent.MAX_PAYLOAD_SIZE);
        }
        event.payload.put(payload);
        event.payload.flip();
        
        event.sourceChannel = this.channel;
        
        // Wait-free publish
        ring.publish(seq);
    }

    private void close() {
        try {
            channel.close();
        } catch (IOException ignored) {}
        key.cancel();
        // Here we would release the buffer back to DirectBufferPool
    }
}
