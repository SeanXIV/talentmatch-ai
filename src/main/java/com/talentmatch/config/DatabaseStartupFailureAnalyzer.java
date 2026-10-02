package com.talentmatch.config;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;

/**
 * Turns "database not reachable" startup failures into one actionable message.
 *
 * <p>Matches a {@link ConnectException} anywhere in the cause chain, or an {@link SQLException}
 * with SQLState 08001 (cannot connect), 28P01 (bad password) or 3D000 (unknown database).
 * The password is never printed. Registered in {@code META-INF/spring.factories}.
 */
public class DatabaseStartupFailureAnalyzer implements FailureAnalyzer {

    private static final Set<String> SQL_STATES = Set.of("08001", "28P01", "3D000");
    private static final Pattern JDBC_URL =
            Pattern.compile("^jdbc:postgresql://([^/:?]+)(?::(\\d+))?/([^?;]*)");

    private final Environment environment;

    public DatabaseStartupFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        Throwable cause = findDatabaseCause(failure);
        if (cause == null) {
            return null;
        }
        String url = property("spring.datasource.url", "");
        String host = "localhost";
        String port = "5432";
        String db = "talentmatch";
        Matcher m = JDBC_URL.matcher(url);
        if (m.find()) {
            host = m.group(1);
            if (m.group(2) != null) {
                port = m.group(2);
            }
            db = m.group(3);
        }
        String user = property("spring.datasource.username", "(unset)");
        String reason = reason(cause);
        String description = "Could not connect to PostgreSQL at " + host + ":" + port + "/" + db
                + " as " + user + "." + (reason.isEmpty() ? "" : " Reason: " + reason);
        String action = "Start it with `bash scripts/start_db.sh`, or set "
                + "DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD.";
        return new FailureAnalysis(description, action, failure);
    }

    private String property(String key, String fallback) {
        if (environment == null) {
            return fallback;
        }
        try {
            String value = environment.getProperty(key);
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException e) {
            // Unresolvable placeholder (e.g. prod profile without DB_HOST): show the raw name.
            return fallback;
        }
    }

    private static String reason(Throwable cause) {
        if (cause instanceof SQLException sql) {
            return switch (String.valueOf(sql.getSQLState())) {
                case "28P01" -> "authentication failed (wrong user or password).";
                case "3D000" -> "the database does not exist.";
                default -> "the server is not reachable.";
            };
        }
        return "the server is not reachable.";
    }

    private static Throwable findDatabaseCause(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && SQL_STATES.contains(sql.getSQLState())) {
                return t;
            }
            if (t instanceof ConnectException) {
                return t;
            }
        }
        return null;
    }
}
