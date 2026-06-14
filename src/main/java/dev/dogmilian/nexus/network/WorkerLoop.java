package dev.dogmilian.nexus.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import dev.dogmilian.nexus.protocol.ChannelSessionContext;

/**
 * Worker Loop handles OP_READ / OP_WRITE for multiple channels.
 * Contains active defense against Linux Epoll 100% CPU Bug.
 */
public final class WorkerLoop implements Runnable {
    private static final int EPOLL_BUG_THRESHOLD = 512;
    private static final long EPOLL_BUG_TIME_WINDOW_MS = 1000;

    private Selector selector;
    private final Queue<SocketChannel> pendingRegistrations = new ConcurrentLinkedQueue<>();
    private final int workerId;

    public WorkerLoop(int workerId) throws IOException {
        this.workerId = workerId;
        this.selector = Selector.open();
    }

    public void register(SocketChannel channel) {
        pendingRegistrations.add(channel);
        selector.wakeup(); // Interrupt blocking select to register new channel
    }

    @Override
    public void run() {
        Thread.currentThread().setName("nexus-worker-" + workerId);
        
        int emptySelects = 0;
        long windowStart = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                processPendingRegistrations();

                int selected = selector.select(10); // 10ms bounded wait

                if (selected == 0) {
                    // Linux Epoll CPU Bug Defense
                    emptySelects++;
                    long now = System.currentTimeMillis();
                    if (now - windowStart > EPOLL_BUG_TIME_WINDOW_MS) {
                        emptySelects = 0;
                        windowStart = now;
                    } else if (emptySelects > EPOLL_BUG_THRESHOLD) {
                        System.err.println("[DEFENSE] Epoll CPU Bug detected in worker " + workerId + ". Rebuilding Selector.");
                        rebuildSelector();
                        emptySelects = 0;
                        windowStart = now;
                    }
                    continue;
                }

                emptySelects = 0; // Reset as we have legitimate events

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) continue;

                    if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (IOException e) {
                System.err.println("[ERROR] Worker " + workerId + " exception: " + e.getMessage());
            }
        }
    }

    private void processPendingRegistrations() {
        SocketChannel channel;
        while ((channel = pendingRegistrations.poll()) != null) {
            try {
                // Register for OP_READ by default, OP_WRITE triggers asynchronously when needed
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                ChannelSessionContext ctx = new ChannelSessionContext(key, channel, ByteBuffer.allocateDirect(1024 * 64));
                key.attach(ctx);
            } catch (IOException e) {
                // Decapitate client if registration fails
                try {
                    channel.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private void handleRead(SelectionKey key) {
        ChannelSessionContext ctx = (ChannelSessionContext) key.attachment();
        if (ctx != null) {
            try {
                ctx.read();
            } catch (IOException e) {
                key.cancel();
            }
        }
    }

    private void handleWrite(SelectionKey key) {
        dev.dogmilian.nexus.protocol.ChannelSessionContext ctx = (dev.dogmilian.nexus.protocol.ChannelSessionContext) key.attachment();
        if (ctx != null) {
            try {
                boolean allFlushed = ctx.outbound.flush(ctx.channel);
                if (!allFlushed) {
                    dev.dogmilian.nexus.fault.SlowConsumerWatchdog.checkAndExecute(key, ctx.outbound, ctx.channel);
                } else {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE); // Remove OP_WRITE when done
                }
            } catch (IOException e) {
                dev.dogmilian.nexus.fault.SlowConsumerWatchdog.executeDecapitation(key, ctx.channel);
            }
        }
    }

    private void rebuildSelector() throws IOException {
        Selector newSelector = Selector.open();
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid() || key.channel().keyFor(newSelector) != null) {
                continue;
            }
            int interestOps = key.interestOps();
            Object attachment = key.attachment();
            key.cancel();
            key.channel().register(newSelector, interestOps, attachment);
        }
        selector.close();
        this.selector = newSelector;
    }
}
