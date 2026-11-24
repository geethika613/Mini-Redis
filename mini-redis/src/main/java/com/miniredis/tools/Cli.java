package com.miniredis.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * redis-cli look-alike.
 *   One-shot:    java -cp out com.miniredis.tools.Cli [-p 6380] SET name alice
 *   Interactive: java -cp out com.miniredis.tools.Cli [-p 6380]
 */
public final class Cli {
    public static void main(String[] args) throws IOException {
        int port = 6380;
        int first = 0;
        if (args.length >= 2 && args[0].equals("-p")) {
            port = Integer.parseInt(args[1]);
            first = 2;
        }
        try (RespClient client = new RespClient("localhost", port)) {
            if (first < args.length) {
                System.out.println(format(client.call(Arrays.copyOfRange(args, first, args.length))));
                return;
            }
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                System.out.print("mini-redis:" + port + "> ");
                System.out.flush();
                String line = stdin.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) break;
                System.out.println(format(client.call(line.split("\\s+"))));
            }
        }
    }

    public static String format(Object reply) {
        if (reply == null) return "(nil)";
        if (reply instanceof RespClient.Status s) return s.text();
        if (reply instanceof RespClient.ErrorReply e) return "(error) " + e.message();
        if (reply instanceof Long n) return "(integer) " + n;
        if (reply instanceof String s) return s.contains("\n") ? s.replace("\r", "").stripTrailing() : "\"" + s + "\"";
        if (reply instanceof List<?> items) {
            if (items.isEmpty()) return "(empty array)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(i + 1).append(") ").append(format(items.get(i)));
            }
            return sb.toString();
        }
        return String.valueOf(reply);
    }
}
