package es.ing.icenterprise.arthur.adapters.inbound.scheduler;

import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteCommand;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteProcessUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Component
public class SchedulerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SchedulerAdapter.class);

    private final ExecuteProcessUseCase executeProcessUseCase;

    @Value("${ingesta.scheduler.enabled:false}")
    private boolean enabled;

    @Value("${ingesta.scheduler.infopacks.enabled:false}")
    private boolean infopacksEnabled;

    public SchedulerAdapter(ExecuteProcessUseCase executeProcessUseCase) {
        this.executeProcessUseCase = executeProcessUseCase;
    }

    @Scheduled(cron = "${ingesta.scheduler.cron:0 0 11 * * ?}")
    public void scheduledExecution() {
        if (!enabled) {
            log.debug("Scheduler is disabled, skipping execution");
            return;
        }

        log.info("Starting scheduled ingestion");
        ProcessReport report = executeProcessUseCase.execute(ExecuteCommand.fromScheduler());
        log.info("Scheduled ingestion completed. Status: {}", report.getStatus());
    }

    /**
     * Fires on days 1-3 of each month. The isFirstBusinessDayOfMonth() guard ensures
     * execution only happens on the actual first business day (Mon-Fri), covering months
     * where the 1st falls on a weekend (max shift is +2 days = Monday the 3rd).
     */
    @Scheduled(cron = "${ingesta.scheduler.infopacks.cron:0 0 9 1-3 * ?}")
    public void scheduledInfopacks() {
        if (!infopacksEnabled) {
            log.debug("Infopacks scheduler is disabled, skipping");
            return;
        }
        if (!isFirstBusinessDayOfMonth()) {
            log.debug("Today is not the first business day of the month, skipping infopacks");
            return;
        }
        log.info("First business day of month — infopacks execution triggered (not yet implemented)");
        // TODO: implement infopacks job execution
        // ProcessReport report = executeProcessUseCase.execute(
        //         ExecuteCommand.fromManual(List.of("infopacks-job"), false));
        // log.info("Infopacks completed. Status: {}", report.getStatus());
    }

    /**
     * Returns true if today is the first Monday-Friday of the current month.
     * Walks forward from the 1st of the month skipping Saturday and Sunday.
     */
    private boolean isFirstBusinessDayOfMonth() {
        LocalDate today = LocalDate.now();
        LocalDate candidate = today.withDayOfMonth(1);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        return today.equals(candidate);
    }
}
