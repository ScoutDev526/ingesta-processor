package es.ing.icenterprise.arthur.adapters.inbound.rest;

import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteCommand;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteProcessUseCase;
import es.ing.icenterprise.arthur.core.services.RoleOwnershipService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingesta")
public class ManualTriggerController {

    private final ExecuteProcessUseCase executeProcessUseCase;
    private final RoleOwnershipService roleOwnershipService;

    public ManualTriggerController(ExecuteProcessUseCase executeProcessUseCase,
                                   RoleOwnershipService roleOwnershipService) {
        this.executeProcessUseCase = executeProcessUseCase;
        this.roleOwnershipService = roleOwnershipService;
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

    @PostMapping("/roles/ownership")
    public ResponseEntity<Map<String, Object>> calculateRoleOwnership(
            @RequestParam(required = false) String date) {

        LocalDate timestamp = (date != null) ? LocalDate.parse(date) : LocalDate.now();
        RoleOwnershipService.RoleOwnershipResult result = roleOwnershipService.execute(timestamp);

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "timestamp", timestamp.toString(),
                "rolesProcessed", result.rolesProcessed(),
                "totalRecords", result.totalRecords()
        ));
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        return ResponseEntity.ok(Map.of(
                "application", "ingesta-processor",
                "status", "UP",
                "timestamp", java.time.Instant.now().toString()
        ));
    }
}
