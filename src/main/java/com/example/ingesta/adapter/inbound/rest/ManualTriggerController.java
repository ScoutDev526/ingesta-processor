package com.example.ingesta.adapter.inbound.rest;

import com.example.ingesta.core.domain.model.ProcessReport;
import com.example.ingesta.core.port.inbound.ExecuteCommand;
import com.example.ingesta.core.port.inbound.ExecuteProcessUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingesta")
public class ManualTriggerController {

    private final ExecuteProcessUseCase executeProcessUseCase;

    public ManualTriggerController(ExecuteProcessUseCase executeProcessUseCase) {
        this.executeProcessUseCase = executeProcessUseCase;
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestParam(required = false) List<String> jobs) {

        ExecuteCommand command = (jobs != null && !jobs.isEmpty())
                ? ExecuteCommand.fromManual(jobs)
                : ExecuteCommand.fromManual();

        ProcessReport report = executeProcessUseCase.execute(command);

        return ResponseEntity.ok(Map.of(
                "id", report.getId().toString(),
                "status", report.getStatus().name(),
                "durationMs", report.getTotalDurationMs(),
                "totals", Map.of(
                        "totalJobs", report.getTotals().totalJobs(),
                        "successfulJobs", report.getTotals().successfulJobs(),
                        "failedJobs", report.getTotals().failedJobs(),
                        "recordsProcessed", report.getTotals().totalRecordsProcessed(),
                        "successRate", report.getTotals().overallSuccessRate()
                )
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
