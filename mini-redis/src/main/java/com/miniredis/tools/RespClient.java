package com.miniredis.tools;

import com.miniredis.protocol.RespWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal RESP client used by the CLI and the load tester. */
public final class RespClient implements AutoCloseable {
    public record Status(String text) {}

    public record ErrorReply(String message) {}

    private final Socket socket;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;

    public RespClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
    }

    public Object call(String... args) throws IOException {
        send(List.of(args));
        flush();
        return readReply();
    }

    public void send(List<String> args) throws IOException {
        out.write(RespWriter.encodeCommand(args));
    }

    public void flush() throws IOException {
        out.flush();
    }

    /** @return Status, ErrorReply, Long, String, null (nil) or List<Object>. */
    public Object readReply() throws IOException {
        int type = in.read();
        if (type == -1) throw new EOFException("server closed the connection");
        String line = readLine();
        return switch (type) {
            case '+' -> new Status(line);
            case '-' -> new ErrorReply(line);
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int len = Integer.parseInt(line);
                if (len < 0) yield null;
                byte[] data = in.readNBytes(len);
                readLine();
                yield new String(data, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int n = Integer.parseInt(line);
                if (n < 0) yield null;
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) items.add(readReply());
                yield items;
            }
            default -> throw new IOException("unexpected reply type: " + (char) type);
        };
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') return buf.toString(StandardCharsets.UTF_8);
            if (b != '\r') buf.write(b);
        }
        throw new EOFException("server closed the connection");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
