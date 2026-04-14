package es.ing.icenterprise.arthur.adapters.inbound.scheduler;

import es.ing.icenterprise.arthur.core.domain.enums.Status;
import es.ing.icenterprise.arthur.core.domain.model.AggregatedMetrics;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteProcessUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerAdapterTest {

    @Mock
    private ExecuteProcessUseCase executeProcessUseCase;

    @Test
    @DisplayName("scheduledExecution does nothing when enabled=false")
    void scheduledExecutionDoesNothingWhenDisabled() {
        SchedulerAdapter adapter = new SchedulerAdapter(executeProcessUseCase);
        ReflectionTestUtils.setField(adapter, "enabled", false);

        adapter.scheduledExecution();

        verify(executeProcessUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("scheduledExecution calls use case when enabled=true")
    void scheduledExecutionCallsUseCaseWhenEnabled() {
        SchedulerAdapter adapter = new SchedulerAdapter(executeProcessUseCase);
        ReflectionTestUtils.setField(adapter, "enabled", true);

        ProcessReport report = buildReport();
        when(executeProcessUseCase.execute(any())).thenReturn(report);

        assertThatNoException().isThrownBy(adapter::scheduledExecution);
        verify(executeProcessUseCase, times(1)).execute(any());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private ProcessReport buildReport() {
        Instant start = Instant.now().minusSeconds(1);
        AggregatedMetrics metrics = new AggregatedMetrics(1, 1, 0, 0, 0, 0, 0, 0, 0, 100.0);
        return ProcessReport.builder()
                .executionStart(start).executionEnd(Instant.now())
                .status(Status.SUCCESS).totals(metrics)
                .build();
    }
}
