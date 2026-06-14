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
        this.ringBuffer = new NexusRingBuffer(capacity);
        this.barrier = new SequenceBarrier(ringBuffer);
        this.router = router;
        // Project Loom: Unbounded lightweight virtual threads
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void start() {
        // Dispatch a single Virtual Thread to poll the RingBuffer.
        // It acts as the orchestrator to fan out actual routing into more Virtual Threads.
        Thread.ofVirtual().name("nexus-disruptor-poller").start(() -> {
            long nextSequence = 0;
            while (!Thread.currentThread().isInterrupted()) {
                long available = barrier.waitFor(nextSequence);
                while (nextSequence <= available) {
                    NexusEvent event = ringBuffer.get(nextSequence);
                    long currentSeq = nextSequence;
                    
                    // Route in a separate Virtual Thread for massive concurrent fan-out
                    virtualExecutor.submit(() -> {
                        router.route(event);
                        // Update consumer sequence if we want to release space, etc.
                        consumerSequence.setRelease(currentSeq);
                    });
                    
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
