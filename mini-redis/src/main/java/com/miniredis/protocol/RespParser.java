package com.miniredis.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads client commands from a byte stream.
 * Supports RESP arrays of bulk strings (what redis-cli sends) and inline commands ("PING\r\n").
 */
public final class RespParser {
    private static final int MAX_BULK_BYTES = 512 * 1024 * 1024;
    private static final int MAX_ARGS = 1024 * 1024;
    private static final int MAX_LINE_BYTES = 64 * 1024;

    private final InputStream in;
    private long bytesConsumed;

    public RespParser(InputStream in) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
    }

    /** @return the command's arguments, or null on a clean EOF between commands. */
    public List<String> readCommand() throws IOException {
        while (true) {
            int first = read();
            if (first == -1) return null;
            if (first == '*') return readArray();
            if (first == '\r' || first == '\n') continue;
            String line = readLine(first).trim();
            if (!line.isEmpty()) return List.of(line.split("\\s+"));
        }
    }

    /** True if more bytes are already buffered, i.e. the client pipelined more commands. */
    public boolean hasBufferedInput() throws IOException {
        return in.available() > 0;
    }

    /** Bytes consumed so far. AOF replay uses this to find the end of the last complete record. */
    public long bytesConsumed() {
        return bytesConsumed;
    }

    private List<String> readArray() throws IOException {
        int count = parseLength(readLine(-1));
        if (count > MAX_ARGS) throw new ProtocolException("invalid multibulk length");
        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int marker = read();
            if (marker == -1) throw new EOFException("connection closed mid-command");
            if (marker != '$') throw new ProtocolException("expected '$', got '" + (char) marker + "'");
            int len = parseLength(readLine(-1));
            if (len > MAX_BULK_BYTES) throw new ProtocolException("invalid bulk length");
            byte[] data = in.readNBytes(len);      // blocks until all len bytes arrive -> handles partial TCP reads
            bytesConsumed += data.length;
            if (data.length < len) throw new EOFException("connection closed mid-command");
            readLine(-1);                          // trailing \r\n
            args.add(new String(data, StandardCharsets.UTF_8));
        }
        return args;
    }

    private String readLine(int firstByte) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (firstByte >= 0) buf.write(firstByte);
        for (int b; (b = read()) != -1; ) {
            if (b == '\n') return buf.toString(StandardCharsets.UTF_8);
            if (b != '\r') buf.write(b);
            if (buf.size() > MAX_LINE_BYTES) throw new ProtocolException("line too long");
        }
        throw new EOFException("connection closed mid-line");
    }

    private int read() throws IOException {
        int b = in.read();
        if (b != -1) bytesConsumed++;
        return b;
    }

    private static int parseLength(String s) throws ProtocolException {
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ProtocolException("invalid length: '" + s + "'");
        }
        if (n < 0) throw new ProtocolException("negative length: " + s);
        return n;
    }
}
