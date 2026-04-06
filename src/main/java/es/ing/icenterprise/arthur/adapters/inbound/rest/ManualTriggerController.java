package es.ing.icenterprise.arthur.adapters.inbound.rest;

import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteCommand;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteProcessUseCase;
import es.ing.icenterprise.arthur.adapters.outbound.report.ExcelReportStore;
import es.ing.icenterprise.arthur.core.services.DepartmentUpdateService;
import es.ing.icenterprise.arthur.core.services.RoleOwnershipService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ingesta")
public class ManualTriggerController {

    private final ExecuteProcessUseCase executeProcessUseCase;
    private final RoleOwnershipService roleOwnershipService;
    private final DepartmentUpdateService departmentUpdateService;
    private final ExcelReportStore excelReportStore;

    public ManualTriggerController(ExecuteProcessUseCase executeProcessUseCase,
                                   RoleOwnershipService roleOwnershipService,
                                   DepartmentUpdateService departmentUpdateService,
                                   ExcelReportStore excelReportStore) {
        this.executeProcessUseCase = executeProcessUseCase;
        this.roleOwnershipService = roleOwnershipService;
        this.departmentUpdateService = departmentUpdateService;
        this.excelReportStore = excelReportStore;
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

    @PostMapping("/departments/update")
    public ResponseEntity<Map<String, Object>> updateDepartments(
            @RequestParam(required = false) String date) {

        LocalDate timestamp = (date != null) ? LocalDate.parse(date) : LocalDate.now();
        DepartmentUpdateService.DepartmentUpdateResult result = departmentUpdateService.execute(timestamp);

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "timestamp", timestamp.toString(),
                "departmentsProcessed", result.departmentsProcessed(),
                "departmentsInserted", result.departmentsInserted()
        ));
    }

    /**
     * Download the Excel execution log report generated during a previous /execute call.
     *
     * Usage example:
     *   1. POST /api/ingesta/execute  → returns JSON with "id"
     *   2. GET  /api/ingesta/report/{id}/excel  → downloads ingesta-report-{id}.xlsx
     *
     * The report stays in memory until the application restarts.
     */
    @GetMapping("/report/{reportId}/excel")
    public ResponseEntity<byte[]> downloadExcelReport(@PathVariable String reportId) {
        UUID id;
        try {
            id = UUID.fromString(reportId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        return excelReportStore.find(id)
                .map(bytes -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"ingesta-report-" + reportId + ".xlsx\"")
                        .contentType(MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .body(bytes))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
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
