package com.miniredis.persistence;

import java.util.Locale;

/** When the AOF is forced from the OS page cache to disk. */
public enum FsyncPolicy {
    /** fsync before replying: no acknowledged write is lost, slowest. */
    ALWAYS,
    /** background fsync once per second: can lose ~1s of writes on power loss. */
    EVERYSEC,
    /** let the OS decide: fastest, can lose many seconds of writes on power loss. */
    NO;

    public static FsyncPolicy parse(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }
}
