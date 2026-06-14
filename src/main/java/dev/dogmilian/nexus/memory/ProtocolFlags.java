package dev.dogmilian.nexus.memory;

import jdk.internal.vm.annotation.Contended;

/**
 * Bitmask utility for Protocol Flags.
 * Using @Contended to prevent False Sharing in L1/L2 caches when
 * tracking flag statistics or state across threads.
 */
public final class ProtocolFlags {
    public static final byte FLAG_PUB = 0x01;
    public static final byte FLAG_SUB = 0x02;
    public static final byte FLAG_ACK = 0x04;

    @Contended("flags_group")
    private volatile long processedPubs;

    @Contended("flags_group")
    private volatile long processedSubs;

    @Contended("flags_group")
    private volatile long processedAcks;

    private ProtocolFlags() {
        // Prevent instantiation
    }

    public static boolean isPub(byte flags) {
        return (flags & FLAG_PUB) != 0;
    }

    public static boolean isSub(byte flags) {
        return (flags & FLAG_SUB) != 0;
    }

    public static boolean isAck(byte flags) {
        return (flags & FLAG_ACK) != 0;
    }
}
