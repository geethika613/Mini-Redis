package com.miniredis.command;

import com.miniredis.persistence.AofLoader;
import com.miniredis.persistence.AofWriter;
import com.miniredis.persistence.FsyncPolicy;
import com.miniredis.protocol.RespValue;
import com.miniredis.server.CommandExecutor;
import com.miniredis.store.DataStore;
import com.miniredis.store.eviction.LruPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAndPersistenceTest {

    private static DataStore newStore() {
        return new DataStore(10_000, new LruPolicy(), System::currentTimeMillis);
    }

    private static RespValue run(Dispatcher d, String... args) {
        return d.dispatch(List.of(args));
    }

    @Test
    void dispatcherValidatesCommandsAndArity() {
        Dispatcher d = new Dispatcher(newStore(), Commands.all());
        assertEquals(new RespValue.Simple("PONG"), run(d, "ping"));       // case-insensitive
        assertInstanceOf(RespValue.Err.class, run(d, "FOO"));
        assertEquals(new RespValue.Err("ERR wrong number of arguments for 'get' command"), run(d, "GET"));
    }

    @Test
    void incrRejectsNonIntegers() {
        Dispatcher d = new Dispatcher(newStore(), Commands.all());
        run(d, "SET", "name", "alice");
        assertEquals(new RespValue.Err("ERR value is not an integer or out of range"), run(d, "INCR", "name"));
        assertEquals(new RespValue.Int(1), run(d, "INCR", "counter"));
    }

    @Test
    void aofReplayRestoresStateAndStoresAbsoluteExpiry(@TempDir Path dir) throws Exception {
        Path aofFile = dir.resolve("test.aof");

        DataStore before = newStore();
        Dispatcher d1 = new Dispatcher(before, Commands.all());
        try (AofWriter aof = new AofWriter(aofFile, FsyncPolicy.ALWAYS)) {
            CommandExecutor ex = new CommandExecutor(d1, before, aof, true);
            ex.execute(List.of("SET", "user:1", "bob"));
            ex.execute(List.of("EXPIRE", "user:1", "3600"));
            ex.execute(List.of("INCR", "views"));
            ex.execute(List.of("INCR", "views"));
            ex.execute(List.of("GET", "user:1"));          // read: must NOT be logged
        }

        String log = Files.readString(aofFile);
        assertTrue(log.contains("PEXPIREAT"), "EXPIRE must be logged as absolute PEXPIREAT");
        assertTrue(!log.contains("GET"), "reads must not be logged");

        DataStore after = newStore();
        int replayed = AofLoader.replay(aofFile, new Dispatcher(after, Commands.all()));
        assertEquals(4, replayed);
        assertEquals("bob", after.get("user:1"));
        assertEquals("2", after.get("views"));
        assertTrue(after.ttlMillis("user:1") > 3_500_000);
    }

    @Test
    void aofReplayTruncatesHalfWrittenRecord(@TempDir Path dir) throws Exception {
        Path aofFile = dir.resolve("test.aof");
        Files.writeString(aofFile, "*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n");
        long goodSize = Files.size(aofFile);
        Files.writeString(aofFile, "*3\r\n$3\r\nSET\r\n$4\r\nhal", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        DataStore store = newStore();
        assertEquals(1, AofLoader.replay(aofFile, new Dispatcher(store, Commands.all())));
        assertEquals("1", store.get("a"));
        assertEquals(goodSize, Files.size(aofFile));
    }

    @Test
    void concurrentIncrLosesNoUpdatesWithGlobalLock() throws Exception {
        DataStore store = newStore();
        CommandExecutor ex = new CommandExecutor(new Dispatcher(store, Commands.all()), store, null, true);
        int threads = 50, ops = 1_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                gate.await();
                for (int i = 0; i < ops; i++) ex.execute(List.of("INCR", "counter"));
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertEquals(String.valueOf(threads * ops), store.get("counter"));
    }
}
