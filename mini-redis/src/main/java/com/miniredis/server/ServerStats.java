package com.miniredis.server;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Live counters shown by INFO (and the dashboard). Atomics: updated from many client threads. */
public final class ServerStats {
    public final AtomicInteger activeClients = new AtomicInteger();
    public final AtomicLong totalConnections = new AtomicLong();
    public final AtomicLong rejectedConnections = new AtomicLong();
    public final AtomicLong totalCommands = new AtomicLong();
}
