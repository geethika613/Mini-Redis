package com.miniredis.protocol;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Encodes replies in RESP. Buffered: call flush() to actually send. */
public final class RespWriter {
    private static final byte[] CRLF = {'\r', '\n'};
    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out instanceof BufferedOutputStream ? out : new BufferedOutputStream(out);
    }

    public void write(RespValue value) throws IOException {
        switch (value) {
            case RespValue.Simple s -> line('+', s.value());
            case RespValue.Err e -> line('-', e.message());
            case RespValue.Int i -> line(':', Long.toString(i.value()));
            case RespValue.Bulk b -> {
                if (b.value() == null) {
                    line('$', "-1");
                    return;
                }
                byte[] data = b.value().getBytes(StandardCharsets.UTF_8);
                line('$', Integer.toString(data.length));   // BYTE length, not String.length()
                out.write(data);
                out.write(CRLF);
            }
            case RespValue.Arr a -> {
                line('*', Integer.toString(a.items().size()));
                for (RespValue item : a.items()) write(item);
            }
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    /** Encodes a command as a RESP array of bulk strings (used by the AOF and the client tools). */
    public static byte[] encodeCommand(List<String> args) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            RespWriter writer = new RespWriter(buf);
            writer.write(new RespValue.Arr(args.stream().<RespValue>map(RespValue.Bulk::new).toList()));
            writer.flush();
            return buf.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void line(char prefix, String text) throws IOException {
        out.write(prefix);
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }
}
