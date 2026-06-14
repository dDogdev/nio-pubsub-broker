package dev.dogmilian.nexus.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class DisruptorEngine {
    private final NexusRingBuffer ringBuffer;
    private final SequenceBarrier barrier;
    private final ExecutorService virtualExecutor;
    private final TopicRouter router;
    
    @jdk.internal.vm.annotation.Contended("consumer")
    private final AtomicLong consumerSequence = new AtomicLong(-1);

    public DisruptorEngine(int capacity, TopicRouter router) {
        this.ringBuffer = new NexusRingBuffer(capacity, consumerSequence);
        this.barrier = new SequenceBarrier(ringBuffer);
        this.router = router;
        // Project Loom: Unbounded lightweight virtual threads
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void start() {
        // Dispatch a single Virtual Thread to poll the RingBuffer.
        // It routes synchronously to guarantee ordered consumer sequence updates.
        Thread.ofVirtual().name("nexus-disruptor-poller").start(() -> {
            long nextSequence = 0;
            while (!Thread.currentThread().isInterrupted()) {
                long available = barrier.waitFor(nextSequence);
                while (nextSequence <= available) {
                    NexusEvent event = ringBuffer.get(nextSequence);
                    
                    // Route synchronously. TopicRouter makes a detached copy of the payload.
                    router.route(event);
                    
                    // Release the slot safely back to the producer
                    consumerSequence.setRelease(nextSequence);
                    
                    nextSequence++;
                }
            }
        });
        System.out.println("[NEXUS] Disruptor Engine started with Project Loom Virtual Threads.");
    }

    public NexusRingBuffer getRingBuffer() {
        return ringBuffer;
    }
}
