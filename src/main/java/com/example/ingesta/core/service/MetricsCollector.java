package com.example.ingesta.core.service;

import com.example.ingesta.core.domain.model.Job;
import com.example.ingesta.core.domain.model.ProcessReport;

import java.util.List;

public interface MetricsCollector {

    ProcessReport collect(List<Job> jobs, boolean manuallyTriggered);
}
