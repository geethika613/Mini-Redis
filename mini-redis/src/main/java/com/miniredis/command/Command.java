package com.miniredis.command;

import com.miniredis.protocol.RespValue;
import com.miniredis.store.DataStore;

import java.util.List;

public interface Command {
    String name();

    /** Redis convention: n > 0 means exactly n args (including the name); n < 0 means at least -n. */
    int arity();

    /** Write commands are appended to the AOF. */
    boolean isWrite();

    RespValue execute(List<String> args, DataStore store);
}
