package dev.dogmilian.nexus.network;

import java.io.IOException;

/**
 * Orchestrates the Boss and Worker loops.
 * Architecture targets maximum throughput by decoupling OP_ACCEPT from OP_READ/OP_WRITE.
 */
public final class MultiReactor {
    private final BossAcceptor bossAcceptor;
    private final WorkerLoop[] workers;
    private final Thread bossThread;
    private final Thread[] workerThreads;

    public MultiReactor(int port, int numWorkers) throws IOException {
        this.workers = new WorkerLoop[numWorkers];
        this.workerThreads = new Thread[numWorkers];

        // JVM-level NUMA alignment via prioritization.
        // FFM libc/sched_setaffinity extensions can be plugged here later.
        for (int i = 0; i < numWorkers; i++) {
            this.workers[i] = new WorkerLoop(i);
            this.workerThreads[i] = new Thread(this.workers[i]);
            // Workers should execute as fast as possible to drain kernel queues
            this.workerThreads[i].setPriority(Thread.MAX_PRIORITY - 1);
        }

        this.bossAcceptor = new BossAcceptor(port, workers);
        this.bossThread = new Thread(this.bossAcceptor);
    }

    public void start() {
        for (Thread wt : workerThreads) {
            wt.start();
        }
        bossThread.start();
        System.out.println("[NEXUS] Multi-Reactor kernel initialized. Boss: 1, Workers: " + workers.length);
    }

    public void shutdown() {
        bossThread.interrupt();
        for (Thread wt : workerThreads) {
            wt.interrupt();
        }
    }
}
