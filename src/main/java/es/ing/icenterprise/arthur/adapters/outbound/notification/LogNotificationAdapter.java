package es.ing.icenterprise.arthur.adapters.outbound.notification;

import es.ing.icenterprise.arthur.core.domain.enums.NotificationType;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationAdapter.class);

    @Override
    public void notify(ProcessReport report, NotificationType type) {
        log.info("=== Ingesta Process Report ===");
        log.info("ID: {}", report.getId());
        log.info("Status: {}", report.getStatus());
        log.info("Duration: {} ms", report.getTotalDurationMs());
        log.info("Triggered: {}", report.isManuallyTriggered() ? "Manual" : "Scheduled");
        log.info("Total Jobs: {} | Success: {} | Failed: {}",
                report.getTotals().totalJobs(),
                report.getTotals().successfulJobs(),
                report.getTotals().failedJobs());
        log.info("Records Processed: {} | Failed: {} | Success Rate: {}%",
                report.getTotals().totalRecordsProcessed(),
                report.getTotals().totalRecordsFailed(),
                String.format("%.1f", report.getTotals().overallSuccessRate()));
    }
}
