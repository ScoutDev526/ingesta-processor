package es.ing.icenterprise.arthur.adapters.outbound.notification;

import es.ing.icenterprise.arthur.core.domain.enums.NotificationType;
import es.ing.icenterprise.arthur.core.domain.enums.Status;
import es.ing.icenterprise.arthur.core.domain.model.AggregatedMetrics;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;

class LogNotificationAdapterTest {

    private final LogNotificationAdapter adapter = new LogNotificationAdapter();

    @Test
    @DisplayName("notify does not throw for a SUCCESS report")
    void notifyDoesNotThrowForSuccessReport() {
        ProcessReport report = buildReport(Status.SUCCESS, true);
        assertThatNoException().isThrownBy(() -> adapter.notify(report, NotificationType.EMAIL));
    }

    @Test
    @DisplayName("notify does not throw for a FAILED report triggered by scheduler")
    void notifyDoesNotThrowForFailedScheduledReport() {
        ProcessReport report = buildReport(Status.FAILED, false);
        assertThatNoException().isThrownBy(() -> adapter.notify(report, NotificationType.EMAIL));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private ProcessReport buildReport(Status status, boolean manual) {
        Instant start = Instant.now().minusSeconds(5);
        AggregatedMetrics metrics = new AggregatedMetrics(2, 1, 0, 0, 100, 5, 10, 0, 2, 50.0);
        return ProcessReport.builder()
                .executionStart(start)
                .executionEnd(Instant.now())
                .status(status)
                .manuallyTriggered(manual)
                .totals(metrics)
                .build();
    }
}
