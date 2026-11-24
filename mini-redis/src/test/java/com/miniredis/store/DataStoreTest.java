package com.miniredis.store;

import com.miniredis.store.eviction.LruPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataStoreTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);   // fake clock: tests control time

    private DataStore store(int maxKeys) {
        return new DataStore(maxKeys, new LruPolicy(), clock::get);
    }

    @Test
    void evictsLeastRecentlyUsedKey() {
        DataStore s = store(3);
        s.set("a", "1");
        s.set("b", "2");
        s.set("c", "3");
        s.get("a");                       // a becomes most recently used -> b is now LRU
        s.set("d", "4");

        assertFalse(s.exists("b"));
        assertTrue(s.exists("a"));
        assertTrue(s.exists("c"));
        assertTrue(s.exists("d"));
        assertEquals(1, s.evictedKeys());
    }

    @Test
    void overwritingExistingKeyDoesNotEvict() {
        DataStore s = store(2);
        s.set("a", "1");
        s.set("b", "2");
        s.set("a", "updated");
        assertEquals(2, s.size());
        assertEquals(0, s.evictedKeys());
    }

    @Test
    void lazyExpiryHidesExpiredKeyOnRead() {
        DataStore s = store(10);
        s.set("session", "abc");
        s.expireAt("session", clock.get() + 2_000);
        assertEquals("abc", s.get("session"));

        clock.addAndGet(2_000);
        assertNull(s.get("session"));
        assertEquals(-2, s.ttlMillis("session"));
    }

    @Test
    void sweeperRemovesExpiredKeysNobodyReads() {
        DataStore s = store(10);
        s.set("k1", "v");
        s.set("k2", "v");
        s.set("forever", "v");
        s.expireAt("k1", clock.get() + 1_000);
        s.expireAt("k2", clock.get() + 1_000);

        clock.addAndGet(1_500);
        assertEquals(2, s.removeExpired());
        assertEquals(1, s.size());
    }

    @Test
    void setClearsTtlButUpdateKeepsIt() {
        DataStore s = store(10);
        s.set("k", "1");
        s.expireAt("k", clock.get() + 5_000);
        s.update("k", "2");
        assertTrue(s.ttlMillis("k") > 0);

        s.set("k", "3");
        assertEquals(-1, s.ttlMillis("k"));
    }

    @Test
    void inspectListsKeysInLruOrderWithoutTouchingThem() {
        DataStore s = store(10);
        s.set("a", "1");
        s.set("b", "2");
        s.set("c", "3");
        s.get("a");

        assertEquals(java.util.List.of("a", "c", "b"),
                s.inspect(10).stream().map(DataStore.KeyView::key).toList());
        assertEquals("b", s.lruVictim());
        s.inspect(10);                              // read-only: must not change the order
        assertEquals("b", s.lruVictim());
    }

    @Test
    void expireInThePastDeletesImmediately() {
        DataStore s = store(10);
        s.set("k", "v");
        assertTrue(s.expireAt("k", clock.get() - 1));
        assertFalse(s.exists("k"));
    }
}
