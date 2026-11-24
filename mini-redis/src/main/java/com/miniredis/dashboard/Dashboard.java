package com.miniredis.dashboard;

import com.miniredis.tools.Cli;
import com.miniredis.tools.LoadTest;
import com.miniredis.tools.RespClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Web dashboard for the live demo. Talks to Mini-Redis over RESP like any other client.
 *   java -cp out com.miniredis.dashboard.Dashboard [--http-port 8080] [--redis-port 6380]
 * Binds to 127.0.0.1 only: it can run commands, so it must not be reachable from the network.
 */
public final class Dashboard {
    private static final int MAX_BODY_BYTES = 10_000;

    private final String redisHost;
    private final int redisPort;
    private final ReentrantLock testLock = new ReentrantLock();
    private RespClient client;                      // guarded by "this"

    private Dashboard(String redisHost, int redisPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) opts.put(args[i].replace("--", ""), args[i + 1]);
        int httpPort = Integer.parseInt(opts.getOrDefault("http-port", "8080"));
        int redisPort = Integer.parseInt(opts.getOrDefault("redis-port", "6380"));
        new Dashboard("localhost", redisPort).start(httpPort);
    }

    private void start(int httpPort) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), httpPort), 0);
        http.createContext("/", this::index);
        http.createContext("/api/state", ex -> route(ex, "GET", e -> state()));
        http.createContext("/api/command", ex -> route(ex, "POST", this::command));
        http.createContext("/api/race", ex -> route(ex, "POST", this::race));
        http.createContext("/api/bench", ex -> route(ex, "POST", this::bench));
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        System.out.printf("Mini-Redis dashboard: http://localhost:%d  (talking to Mini-Redis on port %d)%n", httpPort, redisPort);
    }

    // ---------- handlers ----------

    private void index(HttpExchange ex) throws IOException {
        try (ex; InputStream html = Dashboard.class.getResourceAsStream("/dashboard/index.html")) {
            String path = ex.getRequestURI().getPath();
            if (html == null || !(path.equals("/") || path.equals("/index.html"))) {
                send(ex, 404, "not found", "text/plain");
                return;
            }
            send(ex, 200, new String(html.readAllBytes(), StandardCharsets.UTF_8), "text/html; charset=utf-8");
        }
    }

    /** Polled by the page: INFO + INSPECT, as JSON. */
    private String state() {
        try {
            String info = (String) call("INFO");
            List<?> rows = (List<?>) call("INSPECT", "100");
            StringBuilder json = new StringBuilder("{\"connected\":true,\"info\":{");
            boolean first = true;
            for (String line : info.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (line.startsWith("#") || colon < 0) continue;
                if (!first) json.append(',');
                json.append(q(line.substring(0, colon))).append(':').append(q(line.substring(colon + 1)));
                first = false;
            }
            json.append("},\"keys\":[");
            for (int i = 0; i < rows.size(); i++) {
                List<?> row = (List<?>) rows.get(i);
                if (i > 0) json.append(',');
                json.append("{\"key\":").append(q((String) row.get(0)))
                        .append(",\"value\":").append(q((String) row.get(1)))
                        .append(",\"ttl\":").append(row.get(2)).append('}');
            }
            return json.append("]}").toString();
        } catch (IOException | RuntimeException e) {
            return "{\"connected\":false,\"error\":" + q(String.valueOf(e.getMessage())) + "}";
        }
    }

    /** Runs one command typed in the console. */
    private String command(HttpExchange ex) throws IOException {
        String line = new String(ex.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        List<String> args = tokenize(line);
        if (args.isEmpty()) return "{\"type\":\"err\",\"output\":\"(error) empty command\"}";
        Object reply = call(args.toArray(String[]::new));
        String type = reply == null ? "nil"
                : reply instanceof RespClient.ErrorReply ? "err"
                : reply instanceof RespClient.Status ? "status"
                : reply instanceof Long ? "int"
                : reply instanceof List<?> ? "arr" : "str";
        return "{\"type\":" + q(type) + ",\"output\":" + q(Cli.format(reply)) + "}";
    }

    private String race(HttpExchange ex) throws Exception {
        Map<String, String> p = query(ex);
        int clients = intParam(p, "clients", 50, 1, 200);
        int ops = intParam(p, "ops", 1000, 1, 100_000);
        return exclusive(() -> {
            LoadTest.RaceResult r = LoadTest.incrRace(redisHost, redisPort, clients, ops);
            return String.format("{\"clients\":%d,\"ops\":%d,\"expected\":%d,\"actual\":%d,\"millis\":%d}",
                    r.clients(), r.ops(), r.expected(), r.actual(), r.millis());
        });
    }

    private String bench(HttpExchange ex) throws Exception {
        Map<String, String> p = query(ex);
        int clients = intParam(p, "clients", 10, 1, 200);
        int ops = intParam(p, "ops", 5000, 1, 200_000);
        int pipeline = intParam(p, "pipeline", 1, 1, 128);
        return exclusive(() -> {
            LoadTest.BenchResult r = LoadTest.bench(redisHost, redisPort, clients, ops, pipeline);
            return String.format(java.util.Locale.ROOT,
                    "{\"clients\":%d,\"ops\":%d,\"pipeline\":%d,\"opsPerSec\":%.1f,\"p50Ms\":%.3f,\"p99Ms\":%.3f,\"maxMs\":%.3f}",
                    r.clients(), r.ops(), r.pipeline(), r.opsPerSec(), r.p50Ms(), r.p99Ms(), r.maxMs());
        });
    }

    // ---------- helpers ----------

    private interface Handler {
        String handle(HttpExchange ex) throws Exception;
    }

    private interface Job {
        String run() throws Exception;
    }

    private void route(HttpExchange ex, String method, Handler handler) throws IOException {
        try (ex) {
            if (!ex.getRequestMethod().equalsIgnoreCase(method)) {
                send(ex, 405, "{\"error\":\"method not allowed\"}", "application/json");
                return;
            }
            String body;
            int status = 200;
            try {
                body = handler.handle(ex);
            } catch (Exception e) {
                status = 500;
                body = "{\"error\":" + q(String.valueOf(e.getMessage())) + "}";
            }
            send(ex, status, body, "application/json");
        }
    }

    /** Only one race test or benchmark at a time, so results aren't distorted. */
    private String exclusive(Job job) throws Exception {
        if (!testLock.tryLock()) throw new IllegalStateException("another test is already running");
        try {
            return job.run();
        } finally {
            testLock.unlock();
        }
    }

    /** One persistent connection for console + polling. Reconnects on the next call after a failure. */
    private synchronized Object call(String... args) throws IOException {
        try {
            if (client == null) client = new RespClient(redisHost, redisPort);
            return client.call(args);
        } catch (IOException e) {
            if (client != null) {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // already broken
                }
                client = null;
            }
            throw e;
        }
    }

    private static void send(HttpExchange ex, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    /** Splits a console line into arguments; supports "double quoted strings". */
    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                hasToken = true;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (hasToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
            } else {
                cur.append(c);
                hasToken = true;
            }
        }
        if (hasToken) out.add(cur.toString());
        return out;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> params = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return params;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }

    private static int intParam(Map<String, String> params, String name, int def, int min, int max) {
        try {
            int v = Integer.parseInt(params.getOrDefault(name, String.valueOf(def)));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** JSON string literal with escaping. */
    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
