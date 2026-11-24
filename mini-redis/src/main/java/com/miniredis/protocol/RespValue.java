package com.miniredis.protocol;

import java.util.List;

/** A reply sent back to the client. Sealed so the writer is forced to handle every type. */
public sealed interface RespValue {
    record Simple(String value) implements RespValue {}
    record Err(String message) implements RespValue {}
    record Int(long value) implements RespValue {}
    record Bulk(String value) implements RespValue {}          // value == null -> nil
    record Arr(List<RespValue> items) implements RespValue {}

    RespValue OK = new Simple("OK");
    RespValue NIL = new Bulk(null);
}
