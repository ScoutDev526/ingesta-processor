package es.ing.icenterprise.arthur.core.domain.model;

import es.ing.icenterprise.arthur.core.domain.enums.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Task {

    private final UUID id;
    private final String name;
    private final TaskType taskType;
    private final int order;
    private final List<Step> steps;
    private final Metrics metrics;
    private final List<LogEntry> logs;
    private final boolean stopOnFailure;
    private Status status;

    public Task(String name, TaskType taskType, int order, boolean stopOnFailure) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.taskType = taskType;
        this.order = order;
        this.steps = new ArrayList<>();
        this.metrics = new Metrics();
        this.logs = new ArrayList<>();
        this.stopOnFailure = stopOnFailure;
        this.status = Status.PENDING;
    }

    public void addStep(Step step) {
        this.steps.add(step);
    }

    public void start() {
        this.status = Status.RUNNING;
        this.metrics.start();
        addLog(LogEntry.info(name, "Task started"));
    }

    public void complete(Status status) {
        this.status = status;
        this.metrics.finish(status);
        addLog(LogEntry.info(name, "Task completed with status: " + status));
    }

    public void addLog(LogEntry entry) {
        this.logs.add(entry);
        if (entry.getLevel() == LogLevel.ERROR) metrics.incrementErrors();
        if (entry.getLevel() == LogLevel.WARN) metrics.incrementWarnings();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public TaskType getTaskType() { return taskType; }
    public int getOrder() { return order; }
    public List<Step> getSteps() { return List.copyOf(steps); }
    public Metrics getMetrics() { return metrics; }
    public List<LogEntry> getLogs() { return List.copyOf(logs); }
    public boolean isStopOnFailure() { return stopOnFailure; }
    public Status getStatus() { return status; }
}
