package com.miniredis.command;

import com.miniredis.protocol.RespValue;
import com.miniredis.protocol.RespValue.Arr;
import com.miniredis.protocol.RespValue.Bulk;
import com.miniredis.protocol.RespValue.Err;
import com.miniredis.protocol.RespValue.Int;
import com.miniredis.protocol.RespValue.Simple;
import com.miniredis.store.DataStore;

import java.util.List;

/** One small class per command. Adding a command = write a class + list it in all(). */
public final class Commands {
    private static final long MAX_EXPIRE_SECONDS = 100L * 365 * 24 * 3600;   // guards against overflow

    private Commands() {
    }

    public static List<Command> all() {
        return all(ServerInfo.NONE);
    }

    public static List<Command> all(ServerInfo serverInfo) {
        return List.of(new PingCommand(), new EchoCommand(), new SetCommand(), new GetCommand(),
                new DelCommand(), new ExistsCommand(), new IncrCommand(), new ExpireCommand(),
                new PExpireAtCommand(), new TtlCommand(), new DbSizeCommand(), new InfoCommand(serverInfo),
                new InspectCommand(), new CommandCommand());
    }

    private abstract static class Base implements Command {
        private final String name;
        private final int arity;
        private final boolean write;

        Base(String name, int arity, boolean write) {
            this.name = name;
            this.arity = arity;
            this.write = write;
        }

        @Override public String name() { return name; }
        @Override public int arity() { return arity; }
        @Override public boolean isWrite() { return write; }
    }

    static final class PingCommand extends Base {
        PingCommand() { super("PING", -1, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            return switch (a.size()) {
                case 1 -> new Simple("PONG");
                case 2 -> new Bulk(a.get(1));
                default -> new Err("ERR wrong number of arguments for 'ping' command");
            };
        }
    }

    static final class EchoCommand extends Base {
        EchoCommand() { super("ECHO", 2, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            return new Bulk(a.get(1));
        }
    }

    /** SET key value [EX seconds] */
    static final class SetCommand extends Base {
        SetCommand() { super("SET", -3, true); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            if (a.size() == 3) {
                s.set(a.get(1), a.get(2));
                return RespValue.OK;
            }
            if (a.size() == 5 && a.get(3).equalsIgnoreCase("EX")) {
                Long sec = parseLong(a.get(4));
                if (sec == null || sec <= 0 || sec > MAX_EXPIRE_SECONDS) {
                    return new Err("ERR invalid expire time in 'set' command");
                }
                s.set(a.get(1), a.get(2));
                s.expireAt(a.get(1), s.now() + sec * 1000);
                return RespValue.OK;
            }
            return new Err("ERR syntax error");
        }
    }

    static final class GetCommand extends Base {
        GetCommand() { super("GET", 2, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            return new Bulk(s.get(a.get(1)));
        }
    }

    static final class DelCommand extends Base {
        DelCommand() { super("DEL", -2, true); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            long n = a.subList(1, a.size()).stream().filter(s::delete).count();
            return new Int(n);
        }
    }

    static final class ExistsCommand extends Base {
        ExistsCommand() { super("EXISTS", -2, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            long n = a.subList(1, a.size()).stream().filter(s::exists).count();
            return new Int(n);
        }
    }

    static final class IncrCommand extends Base {
        IncrCommand() { super("INCR", 2, true); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            String key = a.get(1);
            String current = s.get(key);
            long value = 0;
            if (current != null) {
                Long parsed = parseLong(current);
                if (parsed == null) return new Err("ERR value is not an integer or out of range");
                value = parsed;
            }
            if (value == Long.MAX_VALUE) return new Err("ERR increment or decrement would overflow");
            value++;
            if (current == null) s.set(key, Long.toString(value));
            else s.update(key, Long.toString(value));     // INCR keeps the existing TTL
            return new Int(value);
        }
    }

    static final class ExpireCommand extends Base {
        ExpireCommand() { super("EXPIRE", 3, true); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            Long sec = parseLong(a.get(2));
            if (sec == null || Math.abs(sec) > MAX_EXPIRE_SECONDS) {
                return new Err("ERR value is not an integer or out of range");
            }
            return new Int(s.expireAt(a.get(1), s.now() + sec * 1000) ? 1 : 0);
        }
    }

    /** Absolute-time expiry. The AOF stores EXPIRE as PEXPIREAT so replay is deterministic. */
    static final class PExpireAtCommand extends Base {
        PExpireAtCommand() { super("PEXPIREAT", 3, true); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            Long at = parseLong(a.get(2));
            if (at == null) return new Err("ERR value is not an integer or out of range");
            return new Int(s.expireAt(a.get(1), at) ? 1 : 0);
        }
    }

    static final class TtlCommand extends Base {
        TtlCommand() { super("TTL", 2, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            long ms = s.ttlMillis(a.get(1));
            return new Int(ms < 0 ? ms : (ms + 500) / 1000);
        }
    }

    static final class DbSizeCommand extends Base {
        DbSizeCommand() { super("DBSIZE", 1, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            return new Int(s.size());
        }
    }

    static final class InfoCommand extends Base {
        private final ServerInfo serverInfo;

        InfoCommand(ServerInfo serverInfo) {
            super("INFO", -1, false);
            this.serverInfo = serverInfo;
        }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            StringBuilder sb = new StringBuilder("# Server\r\n");
            serverInfo.snapshot().forEach((k, v) -> sb.append(k).append(':').append(v).append("\r\n"));
            String victim = s.lruVictim();
            sb.append("# Keyspace\r\n")
                    .append("keys:").append(s.size()).append("\r\n")
                    .append("max_keys:").append(s.maxKeys()).append("\r\n")
                    .append("evicted_keys:").append(s.evictedKeys()).append("\r\n")
                    .append("expired_keys:").append(s.expiredKeys()).append("\r\n")
                    .append("lru_victim:").append(victim == null ? "" : victim).append("\r\n");
            return new Bulk(sb.toString());
        }
    }

    /**
     * INSPECT [limit]: debug command (not in real Redis) used by the dashboard.
     * Returns [key, value, ttlMillis] rows, most recently used first. Does not count as an access.
     */
    static final class InspectCommand extends Base {
        InspectCommand() { super("INSPECT", -1, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            int limit = 100;
            if (a.size() > 1) {
                Long parsed = parseLong(a.get(1));
                if (parsed == null || parsed < 1) return new Err("ERR limit must be a positive integer");
                limit = (int) Math.min(parsed, 10_000);
            }
            List<RespValue> rows = s.inspect(limit).stream()
                    .<RespValue>map(k -> new Arr(List.of(new Bulk(k.key()), new Bulk(k.value()), new Int(k.ttlMillis()))))
                    .toList();
            return new Arr(rows);
        }
    }

    /** redis-cli sends COMMAND DOCS on startup; an empty reply lets it start cleanly. */
    static final class CommandCommand extends Base {
        CommandCommand() { super("COMMAND", -1, false); }

        @Override
        public RespValue execute(List<String> a, DataStore s) {
            return new Arr(List.of());
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
