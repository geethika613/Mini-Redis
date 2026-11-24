package com.miniredis.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load tester (also used by the dashboard).
 *   Race test:  java -cp out com.miniredis.tools.LoadTest incr  --clients 50 --ops 1000
 *   Benchmark:  java -cp out com.miniredis.tools.LoadTest bench --clients 10 --ops 20000 --pipeline 1
 */
public final class LoadTest {
    public record RaceResult(int clients, int ops, long expected, long actual, long millis) {}

    public record BenchResult(int clients, int ops, int pipeline, double opsPerSec,
                              double p50Ms, double p99Ms, double maxMs) {}

    private static final String RACE_KEY = "race:counter";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: LoadTest incr|bench [--port 6380] [--clients 50] [--ops 1000] [--pipeline 1]");
            return;
        }
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) opts.put(args[i].replace("--", ""), args[i + 1]);
        int port = Integer.parseInt(opts.getOrDefault("port", "6380"));
        int clients = Integer.parseInt(opts.getOrDefault("clients", "50"));
        int ops = Integer.parseInt(opts.getOrDefault("ops", "1000"));
        int pipeline = Integer.parseInt(opts.getOrDefault("pipeline", "1"));

        switch (args[0]) {
            case "incr" -> {
                RaceResult r = incrRace("localhost", port, clients, ops);
                System.out.printf("INCR race test: %d clients x %d INCR on '%s' in %d ms%n", r.clients(), r.ops(), RACE_KEY, r.millis());
                System.out.printf("  expected = %d%n  actual   = %d%n  lost updates = %d%n", r.expected(), r.actual(), r.expected() - r.actual());
                System.out.println(r.expected() == r.actual() ? "  RESULT: PASS (no race)" : "  RESULT: FAIL (race condition!)");
            }
            case "bench" -> {
                BenchResult r = bench("localhost", port, clients, ops, pipeline);
                System.out.printf("SET benchmark: %d clients x %d ops, pipeline=%d%n", r.clients(), r.ops(), r.pipeline());
                System.out.printf("  throughput: %,.0f ops/sec%n", r.opsPerSec());
                System.out.printf("  latency per %s: p50=%.3f ms  p99=%.3f ms  max=%.3f ms%n",
                        r.pipeline() == 1 ? "request" : "batch of " + r.pipeline(), r.p50Ms(), r.p99Ms(), r.maxMs());
            }
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    /** N clients each run INCR on the same key. Any lost update means a race condition. */
    public static RaceResult incrRace(String host, int port, int clients, int ops) throws Exception {
        try (RespClient c = new RespClient(host, port)) {
            c.call("DEL", RACE_KEY);
        }
        long start = System.nanoTime();
        runConcurrently(clients, () -> {
            try (RespClient c = new RespClient(host, port)) {
                for (int i = 0; i < ops; i++) c.call("INCR", RACE_KEY);
            }
            return null;
        });
        long millis = (System.nanoTime() - start) / 1_000_000;

        long actual;
        try (RespClient c = new RespClient(host, port)) {
            Object value = c.call("GET", RACE_KEY);
            actual = value == null ? 0 : Long.parseLong((String) value);
        }
        return new RaceResult(clients, ops, (long) clients * ops, actual, millis);
    }

    /** N clients send SETs. Warmup first (JIT), then all clients start the measured phase together. */
    public static BenchResult bench(String host, int port, int clients, int ops, int pipeline) throws Exception {
        int batches = Math.max(1, ops / pipeline);
        int warmupBatches = Math.max(1, batches / 10);
        AtomicLong measureStart = new AtomicLong();
        CyclicBarrier afterWarmup = new CyclicBarrier(clients, () -> measureStart.set(System.nanoTime()));

        List<Object> results = runConcurrently(clients, () -> {
            try (RespClient c = new RespClient(host, port)) {
                runBatches(c, warmupBatches, pipeline, null);
                afterWarmup.await(60, TimeUnit.SECONDS);
                long[] latencies = new long[batches];
                runBatches(c, batches, pipeline, latencies);
                return latencies;
            }
        });
        long elapsedNs = System.nanoTime() - measureStart.get();

        long[] all = results.stream().map(r -> (long[]) r).flatMapToLong(Arrays::stream).sorted().toArray();
        long totalOps = (long) clients * batches * pipeline;
        return new BenchResult(clients, batches * pipeline, pipeline,
                totalOps / (elapsedNs / 1e9),
                all[all.length / 2] / 1e6,
                all[Math.min(all.length - 1, (int) (all.length * 0.99))] / 1e6,
                all[all.length - 1] / 1e6);
    }

    private static void runBatches(RespClient c, int batches, int pipeline, long[] latencies) throws Exception {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < pipeline; i++) c.send(List.of("SET", "key:" + rnd.nextInt(5000), "value-" + rnd.nextInt()));
            c.flush();
            for (int i = 0; i < pipeline; i++) c.readReply();
            if (latencies != null) latencies[b] = System.nanoTime() - t0;
        }
    }

    private interface Task {
        Object run() throws Exception;
    }

    /** Starts all clients at the same instant (start gate) to maximise contention. */
    private static List<Object> runConcurrently(int clients, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.run();
                }));
            }
            gate.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
