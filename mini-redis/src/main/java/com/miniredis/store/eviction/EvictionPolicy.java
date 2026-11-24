package com.miniredis.store.eviction;

import java.util.List;

/** Strategy for choosing which key to evict when the store is full. Not thread-safe: guarded by the store lock. */
public interface EvictionPolicy {
    void onAccess(String key);

    void onInsert(String key);

    void onDelete(String key);

    /** @return the key to evict, or null if empty. */
    String victim();

    /** Keys from most to least recently used, for inspection. Policies without an order return an empty list. */
    default List<String> keysByRecency() {
        return List.of();
    }
}
