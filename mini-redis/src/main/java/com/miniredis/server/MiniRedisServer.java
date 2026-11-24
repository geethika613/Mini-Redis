package com.miniredis.server;

import com.miniredis.command.Commands;
import com.miniredis.command.Dispatcher;
import com.miniredis.persistence.AofLoader;
import com.miniredis.persistence.AofWriter;
import com.miniredis.persistence.FsyncPolicy;
import com.miniredis.store.DataStore;
import com.miniredis.store.eviction.LruPolicy;
import com.miniredis.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MiniRedisServer {

    record Config(int port, int maxKeys, int maxClients, int idleTimeoutMs, Path aofPath,
                  FsyncPolicy fsync, boolean virtualThreads, int poolSize, boolean unsafeNoLock) {}

    public static void main(String[] args) throws Exception {
        Config cfg = parse(args);

        DataStore store = new DataStore(cfg.maxKeys(), new LruPolicy(), System::currentTimeMillis);
        ServerStats stats = new ServerStats();
        Dispatcher dispatcher = new Dispatcher(store, Commands.all(() -> serverInfo(cfg, stats)));

        AofWriter aof = null;
        if (cfg.aofPath() != null) {
            long start = System.nanoTime();
            int replayed = AofLoader.replay(cfg.aofPath(), dispatcher);
            Log.info("[aof] replayed %d command(s) from %s in %d ms -> %d key(s) restored",
                    replayed, cfg.aofPath(), (System.nanoTime() - start) / 1_000_000, store.size());
            aof = new AofWriter(cfg.aofPath(), cfg.fsync());
        }
        CommandExecutor executor = new CommandExecutor(dispatcher, store, aof, !cfg.unsafeNoLock(), stats);

        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "expiry-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(() -> executor.runLocked(() -> {
            int removed = store.removeExpired();
            if (removed > 0) Log.info("[expire] sweeper removed %d expired key(s)", removed);
        }), 1, 1, TimeUnit.SECONDS);

        ExecutorService workers = cfg.virtualThreads()
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(cfg.poolSize());
        AtomicInteger activeClients = stats.activeClients;

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(cfg.port()), 128);

        AofWriter aofToClose = aof;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("[server] shutting down");
            try {
                server.close();
                if (aofToClose != null) aofToClose.close();     // final fsync
            } catch (IOException e) {
                Log.info("[server] error during shutdown: %s", e.getMessage());
            }
            workers.shutdownNow();
        }));

        Log.info("[server] Mini-Redis listening on port %d | threads=%s | max-clients=%d | max-keys=%d | "
                        + "idle-timeout=%dms | aof=%s | fsync=%s%s",
                cfg.port(), cfg.virtualThreads() ? "virtual" : "platform(pool=" + cfg.poolSize() + ")",
                cfg.maxClients(), cfg.maxKeys(), cfg.idleTimeoutMs(),
                cfg.aofPath() == null ? "off" : cfg.aofPath(), cfg.fsync(),
                cfg.unsafeNoLock() ? " | !! UNSAFE: global lock DISABLED (demo only)" : "");

        while (true) {
            final Socket client;
            try {
                client = server.accept();               // blocks until the kernel completes a handshake
            } catch (SocketException e) {
                break;                                  // server socket closed by the shutdown hook
            }
            stats.totalConnections.incrementAndGet();
            if (activeClients.incrementAndGet() > cfg.maxClients()) {
                activeClients.decrementAndGet();
                stats.rejectedConnections.incrementAndGet();
                Log.info("[conn] rejected %s: max clients (%d) reached", client.getRemoteSocketAddress(), cfg.maxClients());
                try (client) {
                    client.getOutputStream().write("-ERR max number of clients reached\r\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // client already gone
                }
                continue;
            }
            workers.submit(new ClientHandler(client, executor, cfg.idleTimeoutMs(), stats));
        }
    }

    /** The "# Server" section of INFO: configuration plus live counters. */
    private static Map<String, String> serverInfo(Config cfg, ServerStats stats) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("port", String.valueOf(cfg.port()));
        info.put("threads", cfg.virtualThreads() ? "virtual" : "platform(pool=" + cfg.poolSize() + ")");
        info.put("max_clients", String.valueOf(cfg.maxClients()));
        info.put("idle_timeout_ms", String.valueOf(cfg.idleTimeoutMs()));
        info.put("aof", cfg.aofPath() == null ? "off" : cfg.aofPath().toString());
        info.put("fsync", cfg.fsync().name().toLowerCase(Locale.ROOT));
        info.put("global_lock", cfg.unsafeNoLock() ? "off" : "on");
        info.put("connected_clients", String.valueOf(stats.activeClients.get()));
        info.put("total_connections", String.valueOf(stats.totalConnections.get()));
        info.put("rejected_connections", String.valueOf(stats.rejectedConnections.get()));
        info.put("total_commands", String.valueOf(stats.totalCommands.get()));
        return info;
    }

    static Config parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + arg);
            String key = arg.substring(2);
            if (key.equals("unsafe-no-lock")) {
                opts.put(key, "true");
                continue;
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + arg);
            opts.put(key, args[++i]);
        }
        boolean virtual = opts.getOrDefault("threads", "platform").equalsIgnoreCase("virtual");
        int pool = Integer.parseInt(opts.getOrDefault("pool", "64"));
        int maxClients = Integer.parseInt(opts.getOrDefault("max-clients", virtual ? "10000" : String.valueOf(pool)));
        if (!virtual) maxClients = Math.min(maxClients, pool);   // a fixed pool can't serve more clients than threads
        String aof = opts.getOrDefault("aof", "appendonly.aof");
        return new Config(
                Integer.parseInt(opts.getOrDefault("port", "6380")),
                Integer.parseInt(opts.getOrDefault("max-keys", "10000")),
                maxClients,
                Integer.parseInt(opts.getOrDefault("idle-timeout", "300000")),
                aof.equalsIgnoreCase("off") ? null : Path.of(aof),
                FsyncPolicy.parse(opts.getOrDefault("fsync", "everysec")),
                virtual,
                pool,
                opts.containsKey("unsafe-no-lock"));
    }
}
