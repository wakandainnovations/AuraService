package com.aura.service.proxy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TtlCache<V> {

    private final int maxEntries;
    private final ConcurrentHashMap<String, Entry<V>> store = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger(0);

    public TtlCache(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public V get(String key) {
        Entry<V> entry = store.get(key);
        if (entry == null) return null;
        if (entry.expiresAtNanos < System.nanoTime()) {
            if (store.remove(key, entry)) {
                size.decrementAndGet();
            }
            return null;
        }
        return entry.value;
    }

    public void put(String key, V value, long ttlNanos) {
        long expiresAt = System.nanoTime() + ttlNanos;
        Entry<V> prev = store.put(key, new Entry<>(value, expiresAt));
        if (prev == null) {
            size.incrementAndGet();
        }
        if (size.get() > maxEntries) {
            evictExpiredOrOldest();
        }
    }

    public void clear() {
        store.clear();
        size.set(0);
    }

    private void evictExpiredOrOldest() {
        long now = System.nanoTime();
        String oldestKey = null;
        long oldestExpiry = Long.MAX_VALUE;
        for (Map.Entry<String, Entry<V>> e : store.entrySet()) {
            if (e.getValue().expiresAtNanos < now) {
                if (store.remove(e.getKey(), e.getValue())) {
                    size.decrementAndGet();
                }
            } else if (e.getValue().expiresAtNanos < oldestExpiry) {
                oldestExpiry = e.getValue().expiresAtNanos;
                oldestKey = e.getKey();
            }
        }
        if (size.get() > maxEntries && oldestKey != null) {
            Entry<V> removed = store.remove(oldestKey);
            if (removed != null) size.decrementAndGet();
        }
    }

    private record Entry<V>(V value, long expiresAtNanos) {}
}
