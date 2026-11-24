package com.miniredis.persistence;

import com.miniredis.command.Dispatcher;
import com.miniredis.protocol.RespParser;
import com.miniredis.util.Log;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class AofLoader {
    private AofLoader() {
    }

    /**
     * Replays the AOF into the store without re-logging.
     * If the server crashed mid-write, the last record is incomplete: it is cut off so later appends stay valid.
     */
    public static int replay(Path file, Dispatcher dispatcher) throws IOException {
        if (!Files.exists(file)) return 0;
        int replayed = 0;
        long lastGoodOffset = 0;
        boolean incomplete = false;

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            RespParser parser = new RespParser(in);
            while (true) {
                List<String> args;
                try {
                    args = parser.readCommand();
                } catch (EOFException e) {
                    incomplete = true;
                    break;
                }
                if (args == null) break;
                if (!args.isEmpty()) {
                    dispatcher.dispatch(args);
                    replayed++;
                }
                lastGoodOffset = parser.bytesConsumed();
            }
        }

        if (incomplete) {
            long size = Files.size(file);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(lastGoodOffset);
            }
            Log.info("[aof] last record was incomplete (crash mid-write): truncated %d byte(s)", size - lastGoodOffset);
        }
        return replayed;
    }
}
