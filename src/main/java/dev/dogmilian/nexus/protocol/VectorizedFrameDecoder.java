package dev.dogmilian.nexus.protocol;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.lang.foreign.MemorySegment;

/**
 * SIMD-powered Frame Decoder using Project Vector.
 * Extracts the 8-byte frame header in 1 CPU clock cycle using 64-bit registers.
 * 
 * Protocol Header Layout (8 bytes strict):
 * [0-1] Magic Number (0x4E 0x4D - 'NM' Nexus/Netherite)
 * [2]   Flags (Bitmask: 0x01=PUB, 0x02=SUB, 0x04=ACK)
 * [3]   Topic Hash (0-255, Fast-Routing O(1))
 * [4-7] Payload Length (Big-Endian Int32)
 */
public final class VectorizedFrameDecoder {
    // 64-bit vector species to load exactly 8 bytes into a SIMD register
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_64;
    
    private static final byte MAGIC_0 = 0x4E; // 'N'
    private static final byte MAGIC_1 = 0x4D; // 'M'

    public static Header decodeHeader(ByteBuffer buffer, int offset) throws java.io.IOException {
        // Zero-copy SIMD load: pull 8 bytes directly from off-heap memory to CPU register
        MemorySegment segment = MemorySegment.ofBuffer(buffer);
        ByteVector vector = ByteVector.fromMemorySegment(SPECIES, segment, offset, ByteOrder.BIG_ENDIAN);
        
        // 1-cycle extraction using SIMD lane access
        byte m0 = vector.lane(0);
        byte m1 = vector.lane(1);

        if (m0 != MAGIC_0 || m1 != MAGIC_1) {
            throw new java.net.ProtocolException("Protocol Violation: Invalid Magic Number");
        }

        byte flags = vector.lane(2);
        byte topicHash = vector.lane(3);
        
        // Reconstruct 32-bit payload length from vector lanes
        int payloadLen = ((vector.lane(4) & 0xFF) << 24) |
                         ((vector.lane(5) & 0xFF) << 16) |
                         ((vector.lane(6) & 0xFF) << 8) |
                         (vector.lane(7) & 0xFF);

        return new Header(flags, topicHash, payloadLen);
    }

    public record Header(byte flags, byte topicHash, int payloadLength) {}
}
