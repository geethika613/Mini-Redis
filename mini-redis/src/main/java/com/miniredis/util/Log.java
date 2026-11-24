package com.miniredis.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String format, Object... args) {
        System.out.println(LocalTime.now().format(TIME) + " " + String.format(format, args));
    }
}
