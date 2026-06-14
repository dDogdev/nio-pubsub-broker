package dev.dogmilian.nexus.memory;

import java.lang.foreign.Arena;

/**
 * Global Memory Arena based on Project Panama (JEP 454).
 * Destroys the concept of Heap allocations on the hot path.
 */
public final class PanamaMemoryArena {
    private static final Arena SHARED_ARENA = Arena.ofShared();

    private PanamaMemoryArena() {
        // Prevent instantiation
    }

    public static Arena getSharedArena() {
        return SHARED_ARENA;
    }
}
