package com.miniredis.store.eviction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact LRU in O(1): HashMap for lookup + doubly linked list for recency order (most recent at the head). */
public final class LruPolicy implements EvictionPolicy {
    private static final class Node {
        final String key;
        Node prev, next;

        Node(String key) {
            this.key = key;
        }
    }

    private final Map<String, Node> index = new HashMap<>();
    private final Node head = new Node(null);   // sentinel: most recently used side
    private final Node tail = new Node(null);   // sentinel: least recently used side

    public LruPolicy() {
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public void onAccess(String key) {
        Node n = index.get(key);
        if (n != null) {
            unlink(n);
            addFront(n);
        }
    }

    @Override
    public void onInsert(String key) {
        Node n = new Node(key);
        index.put(key, n);
        addFront(n);
    }

    @Override
    public void onDelete(String key) {
        Node n = index.remove(key);
        if (n != null) unlink(n);
    }

    @Override
    public String victim() {
        return tail.prev == head ? null : tail.prev.key;
    }

    @Override
    public List<String> keysByRecency() {
        List<String> keys = new ArrayList<>(index.size());
        for (Node n = head.next; n != tail; n = n.next) keys.add(n.key);
        return keys;
    }

    private void unlink(Node n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void addFront(Node n) {
        n.next = head.next;
        n.prev = head;
        head.next.prev = n;
        head.next = n;
    }
}
