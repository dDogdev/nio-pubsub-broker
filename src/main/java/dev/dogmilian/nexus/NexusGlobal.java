package dev.dogmilian.nexus;

import dev.dogmilian.nexus.engine.DisruptorEngine;
import dev.dogmilian.nexus.engine.TopicRouter;

public final class NexusGlobal {
    public static final TopicRouter ROUTER = new TopicRouter();
    // 64K slot RingBuffer
    public static final DisruptorEngine ENGINE = new DisruptorEngine(1024 * 64, ROUTER);
    public static final java.util.concurrent.atomic.AtomicInteger ACTIVE_CONNECTIONS = new java.util.concurrent.atomic.AtomicInteger(0);
    
    static {
        ENGINE.start();
    }
}
