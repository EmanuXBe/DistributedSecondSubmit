package org.example.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class Console {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Console() {}

    public static void info(String tag, String msg) {
        System.out.println(prefix(tag) + msg);
    }

    public static void warn(String tag, String msg) {
        System.out.println(prefix(tag) + "WARN: " + msg);
    }

    public static void error(String tag, String msg) {
        System.err.println(prefix(tag) + "ERROR: " + msg);
    }

    private static String prefix(String tag) {
        return "[" + LocalTime.now().format(FMT) + "][" + tag + "] ";
    }
}
