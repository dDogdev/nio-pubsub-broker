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
        AWAITING_HANDSHAKE,
        AWAITING_HEADER,
        READING_PAYLOAD
    }

    private State state = State.AWAITING_HANDSHAKE;
    // Removed the permanent 64KB off-heap buffer per client to fix OOM DDoS vector
    // Only a small heap array for fragmented/partial frames
    private byte[] pendingBytes = null;
    public long lastActivity = System.currentTimeMillis();

    public final SelectionKey key;
    public final SocketChannel channel;
    public final dev.dogmilian.nexus.fault.OutboundRingBuffer outbound = new dev.dogmilian.nexus.fault.OutboundRingBuffer(256);
    
    private VectorizedFrameDecoder.Header currentHeader;

    public ChannelSessionContext(SelectionKey key, SocketChannel channel) {
        this.key = key;
        this.channel = channel;
    }

    public void read(ByteBuffer sharedReadBuffer) throws IOException {
        this.lastActivity = System.currentTimeMillis();
        sharedReadBuffer.clear();

        // Restore pending fragment if exists
        if (pendingBytes != null) {
            sharedReadBuffer.put(pendingBytes);
            pendingBytes = null;
        }

        int bytesRead = channel.read(sharedReadBuffer);
        if (bytesRead == -1) {
            close();
            return;
        }

        // Prepare buffer for reading data
        sharedReadBuffer.flip();

        while (sharedReadBuffer.hasRemaining()) {
            if (state == State.AWAITING_HANDSHAKE) {
                if (sharedReadBuffer.remaining() < 8) {
                    break; // Wait for full 8-byte handshake secret
                }
                long secret = sharedReadBuffer.getLong();
                if (secret != dev.dogmilian.nexus.NexusGlobal.SERVER_SECRET) {
                    throw new java.net.ProtocolException("Protocol Violation: Invalid Handshake Secret");
                }
                this.state = State.AWAITING_HEADER;
            }

            if (state == State.AWAITING_HEADER) {
                if (sharedReadBuffer.remaining() < 8) {
                    break; // Wait for more bytes to form the 8-byte header
                }
                
                this.currentHeader = VectorizedFrameDecoder.decodeHeader(sharedReadBuffer, sharedReadBuffer.position());
                
                if (currentHeader.payloadLength() > dev.dogmilian.nexus.engine.NexusEvent.MAX_PAYLOAD_SIZE) {
                    throw new java.net.ProtocolException("Protocol Violation: Payload exceeded MAX_PAYLOAD_SIZE of " + dev.dogmilian.nexus.engine.NexusEvent.MAX_PAYLOAD_SIZE);
                }
                
                sharedReadBuffer.position(sharedReadBuffer.position() + 8); 
                this.state = State.READING_PAYLOAD;
            }

            if (state == State.READING_PAYLOAD) {
                if (sharedReadBuffer.remaining() < currentHeader.payloadLength()) {
                    break; // Wait for the rest of the payload
                }
                
                if (currentHeader.payloadLength() < 0) {
                    throw new java.net.ProtocolException("Protocol Violation: Negative payload length");
                }

                ByteBuffer payloadSlice = sharedReadBuffer.slice(sharedReadBuffer.position(), currentHeader.payloadLength());
                sharedReadBuffer.position(sharedReadBuffer.position() + currentHeader.payloadLength());

                dispatchToRingBuffer(currentHeader, payloadSlice);

                this.state = State.AWAITING_HEADER;
                this.currentHeader = null;
            }
        }

        // Save any unread fragmented bytes into the small heap array
        if (sharedReadBuffer.hasRemaining()) {
            pendingBytes = new byte[sharedReadBuffer.remaining()];
            sharedReadBuffer.get(pendingBytes);
        }
    }

    private void dispatchToRingBuffer(VectorizedFrameDecoder.Header header, ByteBuffer payload) throws java.io.IOException {
        dev.dogmilian.nexus.engine.NexusRingBuffer ring = dev.dogmilian.nexus.NexusGlobal.ENGINE.getRingBuffer();
        
        long seq = ring.next();
        dev.dogmilian.nexus.engine.NexusEvent event = ring.get(seq);
        
        event.header = header;
        
        event.payload.clear();
        event.payload.put(payload);
        event.payload.flip();
        
        event.sourceChannel = this.channel;
        
        ring.publish(seq);
    }

    public void close() {
        if (channel.isOpen()) {
            dev.dogmilian.nexus.NexusGlobal.ACTIVE_CONNECTIONS.decrementAndGet();
            try {
                channel.close();
            } catch (IOException ignored) {}
        }
        key.cancel();
    }
}
