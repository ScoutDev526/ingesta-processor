package es.ing.icenterprise.arthur.core.domain.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class LogEntry {

    private final Instant timestamp;
    private final LogLevel level;
    private final String message;
    private final String source;
    private final Throwable throwable;
    private final Map<String, String> context;

    private LogEntry(Instant timestamp, LogLevel level, String message, String source,
                     Throwable throwable, Map<String, String> context) {
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
        this.source = source;
        this.throwable = throwable;
        this.context = context != null ? new HashMap<>(context) : new HashMap<>();
    }

    public static LogEntry info(String source, String message) {
        return new LogEntry(Instant.now(), LogLevel.INFO, message, source, null, null);
    }

    public static LogEntry warn(String source, String message) {
        return new LogEntry(Instant.now(), LogLevel.WARN, message, source, null, null);
    }

    public static LogEntry error(String source, String message, Throwable throwable) {
        return new LogEntry(Instant.now(), LogLevel.ERROR, message, source, throwable, null);
    }

    public static LogEntry error(String source, String message) {
        return new LogEntry(Instant.now(), LogLevel.ERROR, message, source, null, null);
    }

    public LogEntry withContext(String key, String value) {
        this.context.put(key, value);
        return this;
    }

    public Instant getTimestamp() { return timestamp; }
    public LogLevel getLevel() { return level; }
    public String getMessage() { return message; }
    public String getSource() { return source; }
    public Throwable getThrowable() { return throwable; }
    public Map<String, String> getContext() { return Map.copyOf(context); }

    @Override
    public String toString() {
        return String.format("[%s] %s - %s: %s", timestamp, level, source, message);
    }
}
