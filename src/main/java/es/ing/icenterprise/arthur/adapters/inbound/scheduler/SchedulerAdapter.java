package es.ing.icenterprise.arthur.adapters.inbound.scheduler;

import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.usecases.ExecuteCommand;
import es.ing.icenterprise.arthur.core.usecases.ExecuteProcessUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SchedulerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SchedulerAdapter.class);

    private final ExecuteProcessUseCase executeProcessUseCase;

    @Value("${ingesta.scheduler.enabled:false}")
    private boolean enabled;

    public SchedulerAdapter(ExecuteProcessUseCase executeProcessUseCase) {
        this.executeProcessUseCase = executeProcessUseCase;
    }

    @Scheduled(cron = "${ingesta.scheduler.cron:0 0 2 * * ?}")
    public void scheduledExecution() {
        if (!enabled) {
            log.debug("Scheduler is disabled, skipping execution");
            return;
        }

        log.info("Starting scheduled ingestion");
        ProcessReport report = executeProcessUseCase.execute(ExecuteCommand.fromScheduler());
        log.info("Scheduled ingestion completed. Status: {}", report.getStatus());
    }
}
