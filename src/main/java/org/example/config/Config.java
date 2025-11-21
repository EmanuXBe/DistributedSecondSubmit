package org.example.config;

import java.nio.file.Paths;

public final class Config {
    private Config() {}

    public static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    // Hosts / ports
    public static String gcBindHost() { return env("GC_BIND_HOST", "*"); }
    public static String gcPsPort() { return env("GC_PS_PORT", "6055"); }
    public static String gcPubPort() { return env("GC_PUB_PORT", "6060"); }
    public static String actorHost() { return env("ACTOR_HOST", "localhost"); }
    public static String actorPort() { return env("ACTOR_PORT", "6056"); }
    public static String gaHost() { return env("GA_HOST", "localhost"); }
    public static String gaPort() { return env("GA_PORT", "6057"); }
    public static String ga2Host() { return env("GA2_HOST", "localhost"); }
    public static String ga2RouterPort() { return env("GA2_ROUTER_PORT", "6070"); }
    public static String ga2RepPort() { return env("GA2_REP_PORT", "6080"); }

    // Paths (relative by default)
    public static String replicaBookDbPath() { return toAbs(env("R_BOOK_DB", "data/replica/books.csv")); }
    public static String replicaLoansPath() { return toAbs(env("R_LOANS_PATH", "data/replica/loans.csv")); }
    public static String replicaPendingLogPath() { return toAbs(env("R_PENDING_LOG", "data/replica/pending.log")); }

    public static String primaryBookDbPath() { return toAbs(env("P_BOOK_DB", "data/primary/books.csv")); }
    public static String primaryLoansPath() { return toAbs(env("P_LOANS_PATH", "data/primary/loans.csv")); }
    public static String primaryPendingLogPath() { return toAbs(env("P_PENDING_LOG", "data/primary/pending.log")); }

    public static String requestsFilePath() { return toAbs(env("REQUESTS_FILE", "data/requests/requests.txt")); }

    private static String toAbs(String path) {
        return Paths.get(path).toAbsolutePath().toString();
    }
}
