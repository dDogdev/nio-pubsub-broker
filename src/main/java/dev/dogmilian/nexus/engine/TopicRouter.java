package dev.dogmilian.nexus.engine;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.io.IOException;
import java.nio.ByteBuffer;

import dev.dogmilian.nexus.protocol.ChannelSessionContext;

/**
 * Fast O(1) Topic Router based on StampedLock.
 * 256 predefined topic hash buckets for immediate lookup.
 */
public final class TopicRouter {
    // Array of 256 Topics (Hash 0 to 255)
    private final TopicBucket[] buckets = new TopicBucket[256];

    public TopicRouter() {
        for (int i = 0; i < 256; i++) {
            buckets[i] = new TopicBucket();
        }
    }

    public void subscribe(int topicHash, ChannelSessionContext ctx) {
        TopicBucket bucket = buckets[topicHash & 0xFF];
        long stamp = bucket.lock.writeLock();
        try {
            if (!bucket.subscribers.contains(ctx)) {
                bucket.subscribers.add(ctx);
            }
        } finally {
            bucket.lock.unlockWrite(stamp);
        }
    }

    public void unsubscribe(int topicHash, ChannelSessionContext ctx) {
        TopicBucket bucket = buckets[topicHash & 0xFF];
        long stamp = bucket.lock.writeLock();
        try {
            bucket.subscribers.remove(ctx);
        } finally {
            bucket.lock.unlockWrite(stamp);
        }
    }

    public void route(NexusEvent event) {
        if (event.header == null) return;
        int hash = event.header.topicHash() & 0xFF;
        TopicBucket bucket = buckets[hash];

        // Optimistic read for maximum performance
        long stamp = bucket.lock.tryOptimisticRead();
        List<ChannelSessionContext> targets = copySubscribers(bucket.subscribers);

        if (!bucket.lock.validate(stamp)) {
            // Fallback to read lock if optimistic read failed (concurrent write)
            stamp = bucket.lock.readLock();
            try {
                targets = copySubscribers(bucket.subscribers);
            } finally {
                bucket.lock.unlockRead(stamp);
            }
        }

        // Fan-out routing
        for (ChannelSessionContext ctx : targets) {
            boolean success = ctx.outbound.enqueue(event.payload.duplicate());
            if (!success) {
                dev.dogmilian.nexus.fault.SlowConsumerWatchdog.executeDecapitation(ctx.key, ctx.channel);
                unsubscribe(hash, ctx);
            } else {
                try {
                    ctx.key.interestOps(ctx.key.interestOps() | java.nio.channels.SelectionKey.OP_WRITE);
                    ctx.key.selector().wakeup();
                } catch (Exception e) {
                    unsubscribe(hash, ctx);
                }
            }
        }
    }

    private List<ChannelSessionContext> copySubscribers(List<ChannelSessionContext> original) {
        return new ArrayList<>(original); // Fast shallow copy
    }

    private static final class TopicBucket {
        final StampedLock lock = new StampedLock();
        final List<ChannelSessionContext> subscribers = new ArrayList<>();
    }
}
