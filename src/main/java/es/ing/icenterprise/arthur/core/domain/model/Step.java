package es.ing.icenterprise.arthur.core.domain.model;

import es.ing.icenterprise.arthur.core.domain.enums.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Step {

    private final UUID id;
    private final String name;
    private final StepType stepType;
    private final int order;
    private final Map<String, Object> parameters;
    private final Metrics metrics;
    private final List<LogEntry> logs;
    private Status status;

    public Step(String name, StepType stepType, int order) {
        this(name, stepType, order, Map.of());
    }

    public Step(String name, StepType stepType, int order, Map<String, Object> parameters) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.stepType = stepType;
        this.order = order;
        this.parameters = new HashMap<>(parameters);
        this.metrics = new Metrics();
        this.logs = new ArrayList<>();
        this.status = Status.PENDING;
    }

    public void start() {
        this.status = Status.RUNNING;
        this.metrics.start();
        addLog(LogEntry.info(name, "Step started"));
    }

    public void complete(Status status) {
        this.status = status;
        this.metrics.finish(status);
        addLog(LogEntry.info(name, "Step completed with status: " + status));
    }

    public void addLog(LogEntry entry) {
        this.logs.add(entry);
        if (entry.getLevel() == LogLevel.ERROR) metrics.incrementErrors();
        if (entry.getLevel() == LogLevel.WARN) metrics.incrementWarnings();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public StepType getStepType() { return stepType; }
    public int getOrder() { return order; }
    public Metrics getMetrics() { return metrics; }
    public Map<String, Object> getParameters() { return Map.copyOf(parameters); }
    public List<LogEntry> getLogs() { return List.copyOf(logs); }
    public Status getStatus() { return status; }
}
