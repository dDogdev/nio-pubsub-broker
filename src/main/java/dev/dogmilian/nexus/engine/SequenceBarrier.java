package dev.dogmilian.nexus.engine;

public final class SequenceBarrier {
    private final NexusRingBuffer ringBuffer;

    public SequenceBarrier(NexusRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    public long waitFor(long sequence) {
        long availableSequence;
        // Busy-spin (Mechanical Sympathy)
        while ((availableSequence = ringBuffer.getPublishedSequence()) < sequence) {
            Thread.onSpinWait(); // Hints CPU that we are in a spin loop
        }
        return availableSequence;
    }
}
