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
    private static final long IDLE_TIMEOUT_MS = 30000; // 30 seconds

    private Selector selector;
    private final Queue<SocketChannel> pendingRegistrations = new ConcurrentLinkedQueue<>();
    private final int workerId;
    
    // Single shared read buffer per Thread. Prevents 6.4GB OOM DDoS vector.
    private final ByteBuffer sharedReadBuffer = ByteBuffer.allocateDirect(1024 * 64);

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
        long lastScanTime = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                processPendingRegistrations();

                int selected = selector.select(10); // 10ms bounded wait
                
                long now = System.currentTimeMillis();
                
                // Zombie connection scanner
                if (now - lastScanTime > 5000) {
                    scanAndReapZombies(now);
                    lastScanTime = now;
                }

                if (selected == 0) {
                    // Linux Epoll CPU Bug Defense
                    emptySelects++;
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

    private void scanAndReapZombies(long now) {
        for (SelectionKey key : selector.keys()) {
            if (key.isValid()) {
                Object attachment = key.attachment();
                if (attachment instanceof ChannelSessionContext) {
                    ChannelSessionContext ctx = (ChannelSessionContext) attachment;
                    if (now - ctx.lastActivity > IDLE_TIMEOUT_MS) {
                        System.err.println("[WATCHDOG] Idle timeout. Decapitating zombie connection: " + ctx.channel);
                        ctx.close();
                    }
                }
            }
        }
    }

    private void processPendingRegistrations() {
        SocketChannel channel;
        while ((channel = pendingRegistrations.poll()) != null) {
            try {
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                ChannelSessionContext ctx = new ChannelSessionContext(key, channel);
                key.attach(ctx);
            } catch (IOException e) {
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
                ctx.read(sharedReadBuffer);
            } catch (IOException e) {
                ctx.close();
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
                ctx.close();
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
