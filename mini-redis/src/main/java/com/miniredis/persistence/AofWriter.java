package com.miniredis.persistence;

import com.miniredis.protocol.RespWriter;
import com.miniredis.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Append-only file: every successful write command is appended in RESP format. */
public final class AofWriter implements Closeable {
    private final FileChannel channel;
    private final FsyncPolicy policy;
    private final ScheduledExecutorService syncer;

    public AofWriter(Path file, FsyncPolicy policy) throws IOException {
        this.channel = FileChannel.open(file, CREATE, WRITE, APPEND);
        this.policy = policy;
        if (policy == FsyncPolicy.EVERYSEC) {
            this.syncer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aof-fsync");
                t.setDaemon(true);
                return t;
            });
            syncer.scheduleAtFixedRate(this::syncQuietly, 1, 1, TimeUnit.SECONDS);
        } else {
            this.syncer = null;
        }
    }

    /** Called under the store lock, so the file order matches the execution order. */
    public void append(List<List<String>> commands) throws IOException {
        if (commands.isEmpty()) return;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (List<String> command : commands) buf.writeBytes(RespWriter.encodeCommand(command));
        ByteBuffer bytes = ByteBuffer.wrap(buf.toByteArray());
        while (bytes.hasRemaining()) channel.write(bytes);      // -> OS page cache (survives a process crash)
        if (policy == FsyncPolicy.ALWAYS) channel.force(false);  // -> disk (survives a power loss)
    }

    private void syncQuietly() {
        try {
            channel.force(false);
        } catch (IOException e) {
            Log.info("[aof] background fsync failed: %s", e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        if (syncer != null) syncer.shutdownNow();
        channel.force(true);
        channel.close();
    }
}
