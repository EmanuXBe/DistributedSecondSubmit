package org.example.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RequestParser {
    private static final Pattern ID_PATTERN = Pattern.compile("(\\d+)");

    private RequestParser() {}

    public static String extractBookId(String request) {
        if (request == null || request.isEmpty()) {
            return null;
        }
        // Prefer value after ':' if present
        String[] colonSplit = request.split(":", 2);
        if (colonSplit.length == 2 && colonSplit[1].trim().matches("\\d+")) {
            return colonSplit[1].trim();
        }
        // Otherwise search first numeric token
        Matcher m = ID_PATTERN.matcher(request);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
