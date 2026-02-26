package es.ing.icenterprise.arthur.core.domain.model;

import es.ing.icenterprise.arthur.core.domain.enums.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class Metrics {

    private Instant startTime;
    private Instant endTime;
    private long durationMs;
    private Status status;
    private long recordsProcessed;
    private long recordsFailed;
    private long recordsSkipped;
    private int errorCount;
    private int warningCount;
    private final Map<String, Object> customMetrics;

    public Metrics() {
        this.status = Status.PENDING;
        this.customMetrics = new HashMap<>();
    }

    public void start() {
        this.startTime = Instant.now();
        this.status = Status.RUNNING;
    }

    public void finish(Status status) {
        this.endTime = Instant.now();
        this.status = status;
        if (startTime != null && endTime != null) {
            this.durationMs = Duration.between(startTime, endTime).toMillis();
        }
    }

    public void incrementProcessed(long count) { this.recordsProcessed += count; }
    public void incrementFailed(long count) { this.recordsFailed += count; }
    public void incrementSkipped(long count) { this.recordsSkipped += count; }
    public void incrementErrors() { this.errorCount++; }
    public void incrementWarnings() { this.warningCount++; }
    public void addCustomMetric(String key, Object value) { this.customMetrics.put(key, value); }

    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public long getDurationMs() { return durationMs; }
    public Status getStatus() { return status; }
    public long getRecordsProcessed() { return recordsProcessed; }
    public long getRecordsFailed() { return recordsFailed; }
    public long getRecordsSkipped() { return recordsSkipped; }
    public int getErrorCount() { return errorCount; }
    public int getWarningCount() { return warningCount; }
    public Map<String, Object> getCustomMetrics() { return Map.copyOf(customMetrics); }
}
