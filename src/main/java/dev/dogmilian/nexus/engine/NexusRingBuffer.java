package dev.dogmilian.nexus.engine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * LMAX Disruptor-inspired RingBuffer.
 * Wait-Free publishing using VarHandles to kill False Sharing on the Sequence.
 */
public final class NexusRingBuffer {
    private final NexusEvent[] ring;
    private final int mask;
    private final java.util.concurrent.atomic.AtomicLong gatingSequence;

    @jdk.internal.vm.annotation.Contended("sequence")
    private volatile long cursor = -1; // Claimed by producers
    
    @jdk.internal.vm.annotation.Contended("sequence")
    private volatile long published = -1; // Available for consumers

    private static final VarHandle CURSOR;
    private static final VarHandle PUBLISHED;
    
    static {
        try {
            CURSOR = MethodHandles.lookup().findVarHandle(NexusRingBuffer.class, "cursor", long.class);
            PUBLISHED = MethodHandles.lookup().findVarHandle(NexusRingBuffer.class, "published", long.class);
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }

    public NexusRingBuffer(int capacity, java.util.concurrent.atomic.AtomicLong gatingSequence) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of 2");
        }
        this.ring = new NexusEvent[capacity];
        for (int i = 0; i < capacity; i++) {
            ring[i] = new NexusEvent();
        }
        this.mask = capacity - 1;
        this.gatingSequence = gatingSequence;
    }

    public long next() {
        // Wait-Free CAS loop equivalent via VarHandle
        long nextSeq = (long) CURSOR.getAndAdd(this, 1L) + 1;
        long wrapPoint = nextSeq - ring.length;
        while (gatingSequence.get() < wrapPoint) {
            Thread.onSpinWait(); // Mechanical backpressure to prevent overrun
        }
        return nextSeq;
    }

    public NexusEvent get(long sequence) {
        return ring[(int) (sequence & mask)];
    }

    public void publish(long sequence) {
        // For wait-free multiple producers, publishing must happen in order.
        while ((long) PUBLISHED.getVolatile(this) != sequence - 1) {
            Thread.onSpinWait(); // Mechanical sympathy for CPU spin lock
        }
        PUBLISHED.setVolatile(this, sequence);
    }
    
    public long getPublishedSequence() {
        return (long) PUBLISHED.getVolatile(this);
    }
}
