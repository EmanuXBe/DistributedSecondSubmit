package org.example.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Simple chain-of-responsibility/command router for string requests.
 * Handlers are evaluated en orden de registro.
 */
public final class CommandRouter {
    private final Map<Predicate<String>, Function<String, String>> handlers = new LinkedHashMap<>();
    private final String defaultResponse;

    public CommandRouter(String defaultResponse) {
        this.defaultResponse = defaultResponse;
    }

    public CommandRouter onPrefix(String prefix, Function<String, String> handler) {
        handlers.put(req -> req != null && req.startsWith(prefix), handler);
        return this;
    }

    public CommandRouter onExact(String exact, Function<String, String> handler) {
        handlers.put(req -> req != null && req.equalsIgnoreCase(exact), handler);
        return this;
    }

    public String dispatch(String request) {
        for (var entry : handlers.entrySet()) {
            if (entry.getKey().test(request)) {
                return entry.getValue().apply(request);
            }
        }
        return defaultResponse;
    }
}
