package com.miniredis.command;

import java.util.Map;

/** Server-level facts (config + live stats) shown by INFO. Keeps the command layer independent of the server. */
@FunctionalInterface
public interface ServerInfo {
    ServerInfo NONE = Map::of;

    Map<String, String> snapshot();
}
