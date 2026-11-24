package com.miniredis.server;

import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespValue;
import com.miniredis.protocol.RespWriter;
import com.miniredis.util.Log;

import java.io.IOException;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Serves one client connection: read command -> execute -> write reply, until the client leaves. */
public final class ClientHandler implements Runnable {
    private final Socket socket;
    private final CommandExecutor executor;
    private final int idleTimeoutMs;
    private final AtomicInteger activeClients;

    public ClientHandler(Socket socket, CommandExecutor executor, int idleTimeoutMs, ServerStats stats) {
        this.socket = socket;
        this.executor = executor;
        this.idleTimeoutMs = idleTimeoutMs;
        this.activeClients = stats.activeClients;
    }

    @Override
    public void run() {
        String client = String.valueOf(socket.getRemoteSocketAddress());
        Log.info("[conn] %s connected (active=%d, thread=%s)", client, activeClients.get(), threadName());
        String reason = "closed by client";
        try (Socket s = socket) {
            s.setTcpNoDelay(true);              // don't let Nagle's algorithm delay small replies
            s.setSoTimeout(idleTimeoutMs);      // 0 = wait forever
            RespParser parser = new RespParser(s.getInputStream());
            RespWriter writer = new RespWriter(s.getOutputStream());
            try {
                while (true) {
                    List<String> args = parser.readCommand();
                    if (args == null) break;
                    if (args.isEmpty()) continue;
                    writer.write(executor.execute(args));
                    if (!parser.hasBufferedInput()) writer.flush();   // pipelined requests -> one flush per batch
                }
            } catch (ProtocolException e) {
                reason = "protocol error: " + e.getMessage();
                writer.write(new RespValue.Err("ERR Protocol error: " + e.getMessage()));
                writer.flush();
            }
        } catch (SocketTimeoutException e) {
            reason = "idle timeout";
        } catch (IOException e) {
            reason = "connection error: " + e.getMessage();
        } finally {
            Log.info("[conn] %s disconnected: %s (active=%d)", client, reason, activeClients.decrementAndGet());
        }
    }

    private static String threadName() {
        Thread t = Thread.currentThread();
        return t.isVirtual() ? "virtual#" + t.threadId() : t.getName();
    }
}
