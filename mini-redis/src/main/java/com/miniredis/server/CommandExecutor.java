package com.miniredis.server;

import com.miniredis.command.Command;
import com.miniredis.command.Dispatcher;
import com.miniredis.persistence.AofWriter;
import com.miniredis.protocol.RespValue;
import com.miniredis.store.DataStore;
import com.miniredis.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs every command under ONE global lock: store + TTL set + LRU list + AOF append change together.
 * Socket reads, parsing and reply writing happen outside the lock, in parallel.
 */
public final class CommandExecutor {
    private final ReentrantLock lock = new ReentrantLock();
    private final Dispatcher dispatcher;
    private final DataStore store;
    private final AofWriter aof;            // null when persistence is off
    private final boolean locking;          // false only for the race-condition demo
    private final ServerStats stats;

    public CommandExecutor(Dispatcher dispatcher, DataStore store, AofWriter aof, boolean locking) {
        this(dispatcher, store, aof, locking, new ServerStats());
    }

    public CommandExecutor(Dispatcher dispatcher, DataStore store, AofWriter aof, boolean locking, ServerStats stats) {
        this.dispatcher = dispatcher;
        this.store = store;
        this.aof = aof;
        this.locking = locking;
        this.stats = stats;
    }

    public RespValue execute(List<String> args) {
        stats.totalCommands.incrementAndGet();
        if (locking) lock.lock();
        try {
            RespValue result = dispatcher.dispatch(args);
            Command cmd = dispatcher.lookup(args.get(0));
            if (aof != null && cmd != null && cmd.isWrite() && !(result instanceof RespValue.Err)) {
                aof.append(toAofRecords(args));
            }
            return result;
        } catch (IOException e) {
            Log.info("[aof] write failed: %s", e.getMessage());
            return new RespValue.Err("ERR AOF write failed");
        } catch (RuntimeException e) {
            Log.info("[error] %s while executing %s", e, args.get(0));
            return new RespValue.Err("ERR internal error");
        } finally {
            if (locking) lock.unlock();
        }
    }

    /** Background tasks (the expiry sweeper) always take the lock. */
    public void runLocked(Runnable task) {
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    /** Relative expiry is logged as absolute time, otherwise every replay would extend the key's life. */
    private List<List<String>> toAofRecords(List<String> args) {
        String name = args.get(0).toUpperCase(Locale.ROOT);
        return switch (name) {
            case "EXPIRE" -> List.of(absoluteExpiry(args.get(1)));
            case "SET" -> args.size() == 5
                    ? List.of(List.of("SET", args.get(1), args.get(2)), absoluteExpiry(args.get(1)))
                    : List.of(args);
            default -> List.of(args);
        };
    }

    private List<String> absoluteExpiry(String key) {
        long at = store.expireAtMillis(key);
        return at >= 0 ? List.of("PEXPIREAT", key, Long.toString(at)) : List.of("DEL", key);
    }
}
