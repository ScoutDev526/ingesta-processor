package es.ing.icenterprise.arthur.adapters.outbound.report;

import es.ing.icenterprise.arthur.core.domain.enums.FileType;
import es.ing.icenterprise.arthur.core.domain.enums.Status;
import es.ing.icenterprise.arthur.core.domain.model.Job;
import es.ing.icenterprise.arthur.core.domain.model.LogEntry;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelExecutionLogAdapterTest {

    private final ExcelExecutionLogAdapter adapter = new ExcelExecutionLogAdapter();

    @Test
    @DisplayName("Global sheet uses reportTitle as sheet name")
    void globalSheetUsesReportTitleAsName() throws IOException {
        byte[] bytes = adapter.export(List.of(), "MyReport");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("MyReport");
        }
    }

    @Test
    @DisplayName("One sheet per normalized job name is created after global sheet")
    void perJobSheetCreatedForEachGroup() throws IOException {
        Job jobA = jobWithLog("alpha", Status.SUCCESS, LogEntry.info("alpha", "msg"));
        Job jobB = jobWithLog("beta", Status.SUCCESS, LogEntry.info("beta", "msg"));

        byte[] bytes = adapter.export(List.of(jobA, jobB), "Global");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3); // global + alpha + beta
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("alpha");
            assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("beta");
        }
    }

    @Test
    @DisplayName("Language suffix variants are grouped into the same sheet")
    void languageSuffixNormalizationGroupsJobs() throws IOException {
        Job jobEs = jobWithLog("contracts-import-es", Status.SUCCESS, LogEntry.info("contracts-import-es", "es log"));
        Job jobEn = jobWithLog("contracts-import-en", Status.SUCCESS, LogEntry.info("contracts-import-en", "en log"));

        byte[] bytes = adapter.export(List.of(jobEs, jobEn), "Global");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // Only 2 sheets: global + one merged sheet for "contracts-import"
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("contracts-import");
            // Each job contributes 3 logs (start + extra + complete), so 1 header + 6 data = 7
            assertThat(wb.getSheetAt(1).getPhysicalNumberOfRows()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("Header row contains TIMESTAMP, SEVERITY, STEP, MESSAGE columns")
    void headerRowContainsFourExpectedColumns() throws IOException {
        byte[] bytes = adapter.export(List.of(), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var header = wb.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("TIMESTAMP");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("SEVERITY");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("STEP");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("MESSAGE");
        }
    }

    @Test
    @DisplayName("TRACE log rows have light grey fill (D9D9D9)")
    void rowColorForTraceIsGrey() throws IOException {
        // Minimal job: only one log entry so row 1 is exactly the TRACE row
        Job job = jobWithSingleLog(LogEntry.trace("step", "trace detail"));
        byte[] bytes = adapter.export(List.of(job), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFCellStyle cs = (XSSFCellStyle) wb.getSheetAt(1).getRow(1).getCell(0).getCellStyle();
            assertThat(cs.getFillForegroundColorColor().getARGBHex())
                    .isEqualToIgnoringCase("FFD9D9D9");
        }
    }

    @Test
    @DisplayName("SUMMARY log rows have light green fill (C6EFCE)")
    void rowColorForSummaryIsGreen() throws IOException {
        Job job = jobWithSingleLog(LogEntry.summary("step", "total: 5"));
        byte[] bytes = adapter.export(List.of(job), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFCellStyle cs = (XSSFCellStyle) wb.getSheetAt(1).getRow(1).getCell(0).getCellStyle();
            assertThat(cs.getFillForegroundColorColor().getARGBHex())
                    .isEqualToIgnoringCase("FFC6EFCE");
        }
    }

    @Test
    @DisplayName("WARN log rows have light yellow fill (FFEB9C)")
    void rowColorForWarnIsYellow() throws IOException {
        Job job = jobWithSingleLog(LogEntry.warn("step", "warning"));
        byte[] bytes = adapter.export(List.of(job), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFCellStyle cs = (XSSFCellStyle) wb.getSheetAt(1).getRow(1).getCell(0).getCellStyle();
            assertThat(cs.getFillForegroundColorColor().getARGBHex())
                    .isEqualToIgnoringCase("FFFFEB9C");
        }
    }

    @Test
    @DisplayName("ERROR log rows have light red fill (FFC7CE)")
    void rowColorForErrorIsRed() throws IOException {
        Job job = jobWithSingleLog(LogEntry.error("step", "error"));
        byte[] bytes = adapter.export(List.of(job), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFCellStyle cs = (XSSFCellStyle) wb.getSheetAt(1).getRow(1).getCell(0).getCellStyle();
            assertThat(cs.getFillForegroundColorColor().getARGBHex())
                    .isEqualToIgnoringCase("FFFFC7CE");
        }
    }

    @Test
    @DisplayName("Job sheet tab color is green for SUCCESS status")
    void tabColorForSuccessJobIsGreen() throws IOException {
        Job job = jobWithLog("myjob", Status.SUCCESS, LogEntry.info("s", "ok"));
        byte[] bytes = adapter.export(List.of(job), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = wb.getSheetAt(1); // job sheet
            assertThat(sheet.getTabColor().getARGBHex()).isEqualToIgnoringCase("FF70AD47");
        }
    }

    @Test
    @DisplayName("Job sheet tab color is red for FAILED status")
    void tabColorForFailedJobIsRed() throws IOException {
        Job job = jobWithLog("myjob", Status.FAILED, LogEntry.error("s", "fail"));
        byte[] bytes = adapter.export(List.of(job), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = wb.getSheetAt(1);
            assertThat(sheet.getTabColor().getARGBHex()).isEqualToIgnoringCase("FFFF0000");
        }
    }

    @Test
    @DisplayName("Job sheet tab color is blue for SKIPPED status")
    void tabColorForSkippedJobIsBlue() throws IOException {
        Job job = new Job("myjob", "/f.xlsx", FileType.EXCEL);
        job.skip("no file");
        byte[] bytes = adapter.export(List.of(job), "Report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = wb.getSheetAt(1);
            assertThat(sheet.getTabColor().getARGBHex()).isEqualToIgnoringCase("FF4472C4");
        }
    }

    @Test
    @DisplayName("Empty job list produces only the global sheet")
    void emptyJobListProducesOnlyGlobalSheet() throws IOException {
        byte[] bytes = adapter.export(List.of(), "ESClassificationSystem");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("ESClassificationSystem");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates a Job with exactly one log entry (no start/complete overhead).
     * Use for row-color tests where row 1 must be the specific log level.
     */
    private Job jobWithSingleLog(LogEntry log) {
        Job job = new Job("j", "/data/test.xlsx", FileType.EXCEL);
        job.addLog(log);
        return job;
    }

    /** Creates a Job that has already completed with the given status and one extra log entry. */
    private Job jobWithLog(String name, Status finalStatus, LogEntry extraLog) {
        Job job = new Job(name, "/data/test.xlsx", FileType.EXCEL);
        job.start();
        job.addLog(extraLog);
        job.complete(finalStatus);
        return job;
    }
}
