package dev.dogmilian.nexus;

import dev.dogmilian.nexus.engine.DisruptorEngine;
import dev.dogmilian.nexus.engine.TopicRouter;

public final class NexusGlobal {
    public static final TopicRouter ROUTER = new TopicRouter();
    // 64K slot RingBuffer
    public static final DisruptorEngine ENGINE = new DisruptorEngine(1024 * 64, ROUTER);
    
    static {
        ENGINE.start();
    }
}
