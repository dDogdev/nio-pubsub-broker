package dev.dogmilian.nexus.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Boss Acceptor handles incoming connections at maximum speed.
 * It assigns accepted channels to Worker Loops in a round-robin fashion.
 */
public final class BossAcceptor implements Runnable {
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final WorkerLoop[] workers;
    private final AtomicInteger roundRobin = new AtomicInteger(0);

    public BossAcceptor(int port, WorkerLoop[] workers) throws IOException {
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 64);
        this.serverChannel.bind(new InetSocketAddress(port), 4096); // High backlog
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        this.workers = workers;
    }

    @Override
    public void run() {
        // High priority thread for minimal accept latency
        Thread.currentThread().setName("nexus-boss-acceptor");
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (selector.select() == 0) continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (key.isValid() && key.isAcceptable()) {
                        SocketChannel client = serverChannel.accept();
                        if (client != null) {
                            configureAndDispatch(client);
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("BossAcceptor failed critically", e);
            }
        }
    }

    private void configureAndDispatch(SocketChannel client) throws IOException {
        client.configureBlocking(false);
        
        // Critical for low-latency HFT platforms (bypass Nagle's algorithm)
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        
        // Set OS buffers to decent size to avoid packet fragmentation dropping
        client.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 64);
        client.setOption(StandardSocketOptions.SO_SNDBUF, 1024 * 64);
        client.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

        // Round-robin worker selection, avoiding locks
        int index = Math.abs(roundRobin.getAndIncrement() % workers.length);
        workers[index].register(client);
    }
}
