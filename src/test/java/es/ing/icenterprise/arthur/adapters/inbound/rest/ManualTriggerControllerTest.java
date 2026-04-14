package es.ing.icenterprise.arthur.adapters.inbound.rest;

import es.ing.icenterprise.arthur.adapters.outbound.report.ExcelReportStore;
import es.ing.icenterprise.arthur.core.domain.enums.Status;
import es.ing.icenterprise.arthur.core.domain.model.AggregatedMetrics;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteProcessUseCase;
import es.ing.icenterprise.arthur.core.services.DepartmentUpdateService;
import es.ing.icenterprise.arthur.core.services.RoleOwnershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ManualTriggerController.class)
class ManualTriggerControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean ExecuteProcessUseCase executeProcessUseCase;
    @MockBean RoleOwnershipService roleOwnershipService;
    @MockBean DepartmentUpdateService departmentUpdateService;
    @MockBean ExcelReportStore excelReportStore;

    // ── POST /execute ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /execute with no jobs param returns 200 with report id and status")
    void executeWithNoJobsReturns200() throws Exception {
        when(executeProcessUseCase.execute(any())).thenReturn(buildReport(Status.SUCCESS));

        mockMvc.perform(post("/api/ingesta/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.totals.totalJobs").value(1));
    }

    @Test
    @DisplayName("POST /execute with jobs param filters execution")
    void executeWithJobsParamReturns200() throws Exception {
        when(executeProcessUseCase.execute(any())).thenReturn(buildReport(Status.SUCCESS));

        mockMvc.perform(post("/api/ingesta/execute").param("jobs", "job-a", "job-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ── GET /health ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /health returns 200 with status UP")
    void healthReturns200() throws Exception {
        mockMvc.perform(get("/api/ingesta/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ── GET /test ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /test returns 200 with application name")
    void testEndpointReturns200() throws Exception {
        mockMvc.perform(get("/api/ingesta/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("ingesta-processor"));
    }

    // ── POST /roles/ownership ─────────────────────────────────────────────────

    @Test
    @DisplayName("POST /roles/ownership with explicit date returns 200")
    void roleOwnershipWithExplicitDate() throws Exception {
        when(roleOwnershipService.execute(eq(LocalDate.of(2026, 4, 1))))
                .thenReturn(new RoleOwnershipService.RoleOwnershipResult(3, 15));

        mockMvc.perform(post("/api/ingesta/roles/ownership").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.rolesProcessed").value(3))
                .andExpect(jsonPath("$.totalRecords").value(15));
    }

    @Test
    @DisplayName("POST /roles/ownership without date uses today")
    void roleOwnershipWithoutDateUsesToday() throws Exception {
        when(roleOwnershipService.execute(any()))
                .thenReturn(new RoleOwnershipService.RoleOwnershipResult(0, 0));

        mockMvc.perform(post("/api/ingesta/roles/ownership"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ── POST /departments/update ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /departments/update with explicit date returns 200")
    void departmentUpdateWithExplicitDate() throws Exception {
        when(departmentUpdateService.execute(eq(LocalDate.of(2026, 4, 1))))
                .thenReturn(new DepartmentUpdateService.DepartmentUpdateResult(5, 3));

        mockMvc.perform(post("/api/ingesta/departments/update").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.departmentsProcessed").value(5))
                .andExpect(jsonPath("$.departmentsInserted").value(3));
    }

    @Test
    @DisplayName("POST /departments/update without date uses today")
    void departmentUpdateWithoutDateUsesToday() throws Exception {
        when(departmentUpdateService.execute(any()))
                .thenReturn(new DepartmentUpdateService.DepartmentUpdateResult(0, 0));

        mockMvc.perform(post("/api/ingesta/departments/update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ── GET /report/{id}/excel ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /report/{id}/excel returns 200 with xlsx bytes when report found")
    void downloadExcelReportReturns200WhenFound() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] fakeBytes = new byte[]{1, 2, 3};
        when(excelReportStore.find(id)).thenReturn(Optional.of(fakeBytes));

        mockMvc.perform(get("/api/ingesta/report/" + id + "/excel"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"ingesta-report-" + id + ".xlsx\""));
    }

    @Test
    @DisplayName("GET /report/{id}/excel returns 404 when report not found")
    void downloadExcelReportReturns404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(excelReportStore.find(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/ingesta/report/" + id + "/excel"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /report/{id}/excel returns 400 for invalid UUID")
    void downloadExcelReportReturns400ForInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/ingesta/report/not-a-uuid/excel"))
                .andExpect(status().isBadRequest());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private ProcessReport buildReport(Status status) {
        Instant start = Instant.now().minusSeconds(1);
        AggregatedMetrics metrics = new AggregatedMetrics(1, 1, 0, 0, 0, 0, 0, 0, 0, 100.0);
        return ProcessReport.builder()
                .executionStart(start).executionEnd(Instant.now())
                .status(status).totals(metrics)
                .build();
    }
}
