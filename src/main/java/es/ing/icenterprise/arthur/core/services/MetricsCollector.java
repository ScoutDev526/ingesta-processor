package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.domain.model.Job;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;

import java.util.List;

public interface MetricsCollector {

    ProcessReport collect(List<Job> jobs, boolean manuallyTriggered);
}
