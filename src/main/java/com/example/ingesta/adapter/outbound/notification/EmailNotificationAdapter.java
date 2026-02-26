package com.example.ingesta.adapter.outbound.notification;

import com.example.ingesta.core.domain.model.NotificationType;
import com.example.ingesta.core.domain.model.ProcessReport;
import com.example.ingesta.core.port.outbound.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationAdapter.class);

    private final JavaMailSender mailSender;

    @Value("${ingesta.notification.email.to:admin@example.com}")
    private String toEmail;

    @Value("${ingesta.notification.email.from:ingesta@example.com}")
    private String fromEmail;

    @Value("${ingesta.notification.enabled:false}")
    private boolean enabled;

    public EmailNotificationAdapter(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void notify(ProcessReport report, NotificationType type) {
        if (!enabled) {
            log.info("Email notifications disabled. Report status: {}", report.getStatus());
            return;
        }

        log.info("Sending email notification for report: {}", report.getId());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setFrom(fromEmail);
        message.setSubject(String.format("[Ingesta] Process %s - %s",
                report.getStatus(), report.getId()));
        message.setText(buildEmailBody(report));

        mailSender.send(message);
        log.info("Email notification sent");
    }

    private String buildEmailBody(ProcessReport report) {
        return String.format("""
                Ingesta Process Report
                ======================
                ID: %s
                Status: %s
                Duration: %d ms
                Triggered: %s
                
                Summary:
                - Total Jobs: %d
                - Successful: %d
                - Failed: %d
                - Records Processed: %d
                - Records Failed: %d
                - Success Rate: %.1f%%
                """,
                report.getId(),
                report.getStatus(),
                report.getTotalDurationMs(),
                report.isManuallyTriggered() ? "Manual" : "Scheduled",
                report.getTotals().totalJobs(),
                report.getTotals().successfulJobs(),
                report.getTotals().failedJobs(),
                report.getTotals().totalRecordsProcessed(),
                report.getTotals().totalRecordsFailed(),
                report.getTotals().overallSuccessRate()
        );
    }
}
