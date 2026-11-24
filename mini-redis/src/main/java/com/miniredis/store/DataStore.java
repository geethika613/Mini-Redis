package com.miniredis.store;

import com.miniredis.store.eviction.EvictionPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Key-value store with TTL (lazy + active expiry) and pluggable eviction.
 * NOT thread-safe on purpose: CommandExecutor guards every call with one lock,
 * so the map, the TTL set and the LRU list always change together.
 */
public final class DataStore {
    private record Entry(String value, long expireAtMillis) {
        boolean expired(long now) {
            return expireAtMillis >= 0 && expireAtMillis <= now;
        }
    }

    private final Map<String, Entry> map = new HashMap<>();
    private final Set<String> keysWithTtl = new HashSet<>();
    private final EvictionPolicy eviction;
    private final int maxKeys;
    private final LongSupplier clock;
    private long evictedKeys;
    private long expiredKeys;

    public DataStore(int maxKeys, EvictionPolicy eviction, LongSupplier clock) {
        this.maxKeys = maxKeys;
        this.eviction = eviction;
        this.clock = clock;
    }

    public long now() {
        return clock.getAsLong();
    }

    public String get(String key) {
        Entry e = live(key);
        if (e == null) return null;
        eviction.onAccess(key);
        return e.value();
    }

    public boolean exists(String key) {
        return live(key) != null;
    }

    /** SET semantics: replaces the value and clears any TTL. */
    public void set(String key, String value) {
        put(key, value, -1);
    }

    /** Replaces the value but keeps the TTL (used by INCR). */
    public void update(String key, String value) {
        Entry e = live(key);
        put(key, value, e == null ? -1 : e.expireAtMillis());
    }

    public boolean delete(String key) {
        if (map.remove(key) == null) return false;
        keysWithTtl.remove(key);
        eviction.onDelete(key);
        return true;
    }

    /** @return false if the key does not exist. An expiry time in the past deletes the key. */
    public boolean expireAt(String key, long atMillis) {
        Entry e = live(key);
        if (e == null) return false;
        if (atMillis <= now()) {
            delete(key);
            expiredKeys++;
            return true;
        }
        map.put(key, new Entry(e.value(), atMillis));
        keysWithTtl.add(key);
        return true;
    }

    /** @return -2 if missing, -1 if no TTL, otherwise milliseconds remaining. */
    public long ttlMillis(String key) {
        Entry e = live(key);
        if (e == null) return -2;
        if (e.expireAtMillis() < 0) return -1;
        return e.expireAtMillis() - now();
    }

    /** @return -2 if missing, -1 if no TTL, otherwise the absolute expiry time in epoch millis. */
    public long expireAtMillis(String key) {
        Entry e = live(key);
        return e == null ? -2 : e.expireAtMillis();
    }

    /** Active expiry: removes every expired key that has a TTL. Called by the background sweeper. */
    public int removeExpired() {
        long now = now();
        int removed = 0;
        for (Iterator<String> it = keysWithTtl.iterator(); it.hasNext(); ) {
            String key = it.next();
            Entry e = map.get(key);
            if (e == null || e.expired(now)) {
                it.remove();
                if (e != null) {
                    map.remove(key);
                    eviction.onDelete(key);
                    expiredKeys++;
                    removed++;
                }
            }
        }
        return removed;
    }

    /** A read-only view of one key, used by INSPECT. */
    public record KeyView(String key, String value, long ttlMillis) {}

    /** Live keys, most recently used first. Read-only: does not change LRU order or delete anything. */
    public List<KeyView> inspect(int limit) {
        long now = now();
        List<String> order = eviction.keysByRecency();
        Iterable<String> keys = order.isEmpty() ? map.keySet() : order;
        List<KeyView> views = new ArrayList<>();
        for (String key : keys) {
            if (views.size() >= limit) break;
            Entry e = map.get(key);
            if (e == null || e.expired(now)) continue;
            views.add(new KeyView(key, e.value(), e.expireAtMillis() < 0 ? -1 : e.expireAtMillis() - now));
        }
        return views;
    }

    /** The key that would be evicted next, or null. */
    public String lruVictim() {
        return eviction.victim();
    }

    public int size() {
        return map.size();
    }

    public int maxKeys() {
        return maxKeys;
    }

    public long evictedKeys() {
        return evictedKeys;
    }

    public long expiredKeys() {
        return expiredKeys;
    }

    /** Lazy expiry: an expired key is deleted the moment someone touches it. */
    private Entry live(String key) {
        Entry e = map.get(key);
        if (e == null) return null;
        if (e.expired(now())) {
            delete(key);
            expiredKeys++;
            return null;
        }
        return e;
    }

    private void put(String key, String value, long expireAt) {
        boolean existed = live(key) != null;
        if (!existed && map.size() >= maxKeys) evictOne();
        map.put(key, new Entry(value, expireAt));
        if (expireAt >= 0) keysWithTtl.add(key);
        else keysWithTtl.remove(key);
        if (existed) eviction.onAccess(key);
        else eviction.onInsert(key);
    }

    private void evictOne() {
        String victim = eviction.victim();
        if (victim != null && delete(victim)) evictedKeys++;
    }
}
