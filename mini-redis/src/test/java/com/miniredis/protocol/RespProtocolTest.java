package com.miniredis.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespProtocolTest {

    private static RespParser parser(String raw) {
        return new RespParser(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesRespArray() throws IOException {
        RespParser p = parser("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nalice\r\n");
        assertEquals(List.of("SET", "name", "alice"), p.readCommand());
        assertNull(p.readCommand());          // clean EOF
    }

    @Test
    void parsesInlineCommand() throws IOException {
        assertEquals(List.of("PING"), parser("PING\r\n").readCommand());
        assertEquals(List.of("SET", "a", "1"), parser("SET a 1\n").readCommand());
    }

    @Test
    void bulkStringsAreBinarySafe() throws IOException {
        // the value itself contains \r\n - only possible because of the length prefix
        RespParser p = parser("*2\r\n$4\r\nECHO\r\n$4\r\na\r\nb\r\n");
        assertEquals(List.of("ECHO", "a\r\nb"), p.readCommand());
    }

    @Test
    void handlesPartialTcpReads() throws IOException {
        // simulates TCP delivering the command one byte at a time
        byte[] raw = "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream oneByteAtATime = new InputStream() {
            private int pos;

            @Override
            public int read() {
                return pos < raw.length ? raw[pos++] & 0xff : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= raw.length) return -1;
                b[off] = raw[pos++];
                return 1;
            }
        };
        assertEquals(List.of("GET", "key"), new RespParser(oneByteAtATime).readCommand());
    }

    @Test
    void rejectsHugeBulkLength() {
        assertThrows(ProtocolException.class, () -> parser("*1\r\n$999999999999\r\n").readCommand());
    }

    @Test
    void connectionClosedMidCommandIsEof() {
        assertThrows(EOFException.class, () -> parser("*2\r\n$3\r\nGET\r\n$3\r\nke").readCommand());
    }

    @Test
    void bulkLengthCountsBytesNotCharacters() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespWriter w = new RespWriter(out);
        w.write(new RespValue.Bulk("héllo"));      // 5 characters, 6 bytes in UTF-8
        w.flush();
        assertArrayEquals("$6\r\nhéllo\r\n".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test
    void encodesAllReplyTypes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespWriter w = new RespWriter(out);
        w.write(RespValue.OK);
        w.write(new RespValue.Err("ERR boom"));
        w.write(new RespValue.Int(42));
        w.write(RespValue.NIL);
        w.write(new RespValue.Arr(List.of(new RespValue.Bulk("a"))));
        w.flush();
        assertEquals("+OK\r\n-ERR boom\r\n:42\r\n$-1\r\n*1\r\n$1\r\na\r\n", out.toString(StandardCharsets.UTF_8));
    }
}
