package dev.dogmilian.nexus.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free Object Pool for Direct ByteBuffers.
 * Abstraction for Zero-Copy from kernel space to user space.
 */
public final class DirectBufferPool {
    private final ByteBuffer[] pool;
    private final int capacity;
    
    private static final VarHandle POOL_ARRAY = MethodHandles.arrayElementVarHandle(ByteBuffer[].class);

    private final AtomicInteger top;

    public DirectBufferPool(int capacity, int bufferSize) {
        this.capacity = capacity;
        this.pool = new ByteBuffer[capacity];
        for (int i = 0; i < capacity; i++) {
            this.pool[i] = ByteBuffer.allocateDirect(bufferSize);
        }
        this.top = new AtomicInteger(capacity);
    }

    public ByteBuffer acquire() {
        while (true) {
            int currentTop = top.get();
            if (currentTop == 0) {
                throw new IllegalStateException("DirectBufferPool depleted. Increase capacity or check for leaks.");
            }
            int newTop = currentTop - 1;
            if (top.compareAndSet(currentTop, newTop)) {
                ByteBuffer buffer = (ByteBuffer) POOL_ARRAY.getVolatile(pool, newTop);
                if (buffer != null) {
                    POOL_ARRAY.setVolatile(pool, newTop, null);
                    buffer.clear();
                    return buffer;
                }
            }
        }
    }

    public void release(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Only direct buffers allowed in this pool");
        }
        
        // Zero out buffer for security and clear states
        buffer.clear();
        int capacity = buffer.capacity();
        for (int i = 0; i < capacity; i++) {
            buffer.put(i, (byte) 0);
        }
        buffer.clear();
        
        while (true) {
            int currentTop = top.get();
            if (currentTop == this.capacity) {
                throw new IllegalStateException("DirectBufferPool is full. Invalid release.");
            }
            if (top.compareAndSet(currentTop, currentTop + 1)) {
                POOL_ARRAY.setVolatile(pool, currentTop, buffer);
                return;
            }
        }
    }
}
