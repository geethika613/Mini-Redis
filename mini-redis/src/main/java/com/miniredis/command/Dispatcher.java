package com.miniredis.command;

import com.miniredis.protocol.RespValue;
import com.miniredis.store.DataStore;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Looks up a command in the registry, validates arity, and executes it. */
public final class Dispatcher {
    private final Map<String, Command> registry = new HashMap<>();
    private final DataStore store;

    public Dispatcher(DataStore store, List<Command> commands) {
        this.store = store;
        for (Command c : commands) registry.put(c.name(), c);
    }

    public Command lookup(String name) {
        return registry.get(name.toUpperCase(Locale.ROOT));
    }

    public RespValue dispatch(List<String> args) {
        Command cmd = lookup(args.get(0));
        if (cmd == null) return new RespValue.Err("ERR unknown command '" + args.get(0) + "'");
        int a = cmd.arity();
        if ((a > 0 && args.size() != a) || (a < 0 && args.size() < -a)) {
            return new RespValue.Err("ERR wrong number of arguments for '"
                    + cmd.name().toLowerCase(Locale.ROOT) + "' command");
        }
        return cmd.execute(args, store);
    }
}
