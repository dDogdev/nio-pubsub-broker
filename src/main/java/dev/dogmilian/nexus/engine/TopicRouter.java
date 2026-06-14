package dev.dogmilian.nexus.engine;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.io.IOException;
import java.nio.ByteBuffer;

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

    public void subscribe(int topicHash, SocketChannel channel) {
        TopicBucket bucket = buckets[topicHash & 0xFF];
        long stamp = bucket.lock.writeLock();
        try {
            if (!bucket.subscribers.contains(channel)) {
                bucket.subscribers.add(channel);
            }
        } finally {
            bucket.lock.unlockWrite(stamp);
        }
    }

    public void unsubscribe(int topicHash, SocketChannel channel) {
        TopicBucket bucket = buckets[topicHash & 0xFF];
        long stamp = bucket.lock.writeLock();
        try {
            bucket.subscribers.remove(channel);
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
        List<SocketChannel> targets = copySubscribers(bucket.subscribers);

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
        for (SocketChannel channel : targets) {
            try {
                // In Phase 5 we will push this to their OutboundRingBuffer.
                // For now, we write directly to demonstrate flow.
                channel.write(event.payload.duplicate());
            } catch (IOException e) {
                // Channel closed or broken
                unsubscribe(hash, channel);
            }
        }
    }

    private List<SocketChannel> copySubscribers(List<SocketChannel> original) {
        return new ArrayList<>(original); // Fast shallow copy
    }

    private static final class TopicBucket {
        final StampedLock lock = new StampedLock();
        final List<SocketChannel> subscribers = new ArrayList<>();
    }
}
