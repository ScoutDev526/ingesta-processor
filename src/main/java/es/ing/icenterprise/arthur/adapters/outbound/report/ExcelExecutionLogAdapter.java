package es.ing.icenterprise.arthur.adapters.outbound.report;

import es.ing.icenterprise.arthur.core.domain.enums.LogLevel;
import es.ing.icenterprise.arthur.core.domain.enums.Status;
import es.ing.icenterprise.arthur.core.domain.model.Job;
import es.ing.icenterprise.arthur.core.domain.model.LogEntry;
import es.ing.icenterprise.arthur.core.domain.model.Step;
import es.ing.icenterprise.arthur.core.domain.model.Task;
import es.ing.icenterprise.arthur.core.ports.outbound.ExecutionLogExporterPort;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates an Excel (.xlsx) execution log report from completed jobs.
 *
 * Sheet layout:
 * - Sheet 1: global log (all jobs combined), title = reportTitle
 * - Sheets 2..N: one per normalized job name (language suffixes stripped)
 *
 * Columns: TIMESTAMP | SEVERITY | STEP | MESSAGE
 * Row colors: TRACE=grey, INFO=white, SUMMARY=green, WARN=yellow, ERROR=red
 * Tab color: SUCCESS=green, PARTIAL=yellow, SKIPPED=blue, FAILED=red, other=black
 */
@Component
public class ExcelExecutionLogAdapter implements ExecutionLogExporterPort {

    private static final Logger log = LoggerFactory.getLogger(ExcelExecutionLogAdapter.class);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Row background colors (ARGB hex: FF + RGB)
    private static final String COLOR_TRACE   = "FFD9D9D9"; // light grey
    private static final String COLOR_SUMMARY = "FFC6EFCE"; // light green
    private static final String COLOR_WARN    = "FFFFEB9C"; // light yellow
    private static final String COLOR_ERROR   = "FFFFC7CE"; // light red

    // Tab colors (RGB bytes)
    private static final byte[] TAB_SUCCESS  = hex("70AD47");
    private static final byte[] TAB_PARTIAL  = hex("FFBF00");
    private static final byte[] TAB_SKIPPED  = hex("4472C4");
    private static final byte[] TAB_FAILED   = hex("FF0000");
    private static final byte[] TAB_BLACK    = hex("000000");

    @Override
    public byte[] export(List<Job> jobs, String reportTitle) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            StylePalette styles = new StylePalette(wb);

            // --- Sheet 1: global (all jobs) ---
            List<LogEntry> allLogs = new ArrayList<>();
            Status worstGlobal = Status.PENDING;
            for (Job job : jobs) {
                allLogs.addAll(collectLogs(job));
                worstGlobal = worst(worstGlobal, job.getStatus());
            }
            allLogs.sort(Comparator.comparing(LogEntry::getTimestamp));
            XSSFSheet globalSheet = wb.createSheet(safeSheetName(reportTitle));
            writeSheet(globalSheet, allLogs, styles);
            setTabColor(globalSheet, worstGlobal);

            // --- Sheets 2..N: one per normalized job name ---
            // Group jobs by normalized name (preserving insertion order)
            Map<String, List<Job>> grouped = new LinkedHashMap<>();
            for (Job job : jobs) {
                String key = normalizeJobName(job.getName());
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(job);
            }

            for (Map.Entry<String, List<Job>> entry : grouped.entrySet()) {
                String sheetName = safeSheetName(entry.getKey());
                // Ensure unique sheet names within the workbook
                sheetName = uniqueSheetName(wb, sheetName);

                List<LogEntry> jobLogs = new ArrayList<>();
                Status worstStatus = Status.PENDING;
                for (Job job : entry.getValue()) {
                    jobLogs.addAll(collectLogs(job));
                    worstStatus = worst(worstStatus, job.getStatus());
                }
                jobLogs.sort(Comparator.comparing(LogEntry::getTimestamp));

                XSSFSheet sheet = wb.createSheet(sheetName);
                writeSheet(sheet, jobLogs, styles);
                setTabColor(sheet, worstStatus);
            }

            wb.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate Excel execution log report: {}", e.getMessage(), e);
            throw new RuntimeException("Excel report generation failed", e);
        }
    }

    // ── Log collection ───────────────────────────────────────────────────────

    private List<LogEntry> collectLogs(Job job) {
        List<LogEntry> logs = new ArrayList<>(job.getLogs());
        for (Task task : job.getTasks()) {
            logs.addAll(task.getLogs());
            for (Step step : task.getSteps()) {
                logs.addAll(step.getLogs());
            }
        }
        return logs;
    }

    // ── Sheet writing ─────────────────────────────────────────────────────────

    private void writeSheet(XSSFSheet sheet, List<LogEntry> logs, StylePalette styles) {
        // Header row
        Row header = sheet.createRow(0);
        writeHeaderCell(header, 0, "TIMESTAMP",  styles.headerStyle);
        writeHeaderCell(header, 1, "SEVERITY",   styles.headerStyle);
        writeHeaderCell(header, 2, "STEP",       styles.headerStyle);
        writeHeaderCell(header, 3, "MESSAGE",    styles.headerStyle);

        // Data rows
        int rowIdx = 1;
        for (LogEntry entry : logs) {
            Row row = sheet.createRow(rowIdx++);
            CellStyle rowStyle = styles.styleFor(entry.getLevel());

            createCell(row, 0, TS_FMT.format(entry.getTimestamp()), rowStyle);
            createCell(row, 1, entry.getLevel().name(),              rowStyle);
            createCell(row, 2, entry.getSource(),                    rowStyle);
            createCell(row, 3, entry.getMessage(),                   rowStyle);
        }

        // Column widths (in units of 1/256 of a character width)
        sheet.setColumnWidth(0, 22 * 256);  // TIMESTAMP
        sheet.setColumnWidth(1, 10 * 256);  // SEVERITY
        sheet.setColumnWidth(2, 30 * 256);  // STEP
        sheet.setColumnWidth(3, 100 * 256); // MESSAGE

        // Freeze header row + auto-filter
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 3));
    }

    private void writeHeaderCell(Row row, int col, String text, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    // ── Tab color ─────────────────────────────────────────────────────────────

    private void setTabColor(XSSFSheet sheet, Status status) {
        byte[] rgb = switch (status) {
            case SUCCESS -> TAB_SUCCESS;
            case PARTIAL -> TAB_PARTIAL;
            case SKIPPED -> TAB_SKIPPED;
            case FAILED  -> TAB_FAILED;
            default      -> TAB_BLACK;
        };
        sheet.setTabColor(new XSSFColor(rgb, null));
    }

    // ── Name helpers ──────────────────────────────────────────────────────────

    /** Strips common language suffixes and truncates to 31 chars (Excel limit). */
    private String normalizeJobName(String name) {
        String normalized = name.replaceAll("(?i)[-_](es|en|fr|de|it|pt)$", "").trim();
        return normalized.length() > 31 ? normalized.substring(0, 31) : normalized;
    }

    private String safeSheetName(String name) {
        // Excel forbids: / \ ? * [ ] : and names over 31 chars
        String safe = name.replaceAll("[/\\\\?*\\[\\]:]", "_").trim();
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private String uniqueSheetName(XSSFWorkbook wb, String desired) {
        String candidate = desired;
        int suffix = 2;
        while (wb.getSheet(candidate) != null) {
            String s = String.valueOf(suffix++);
            int maxBase = 31 - s.length() - 1;
            candidate = (desired.length() > maxBase ? desired.substring(0, maxBase) : desired) + "-" + s;
        }
        return candidate;
    }

    // ── Status priority ───────────────────────────────────────────────────────

    private static final List<Status> STATUS_PRIORITY =
            List.of(Status.FAILED, Status.PARTIAL, Status.SUCCESS, Status.SKIPPED,
                    Status.RUNNING, Status.PENDING);

    private Status worst(Status a, Status b) {
        int ia = STATUS_PRIORITY.indexOf(a);
        int ib = STATUS_PRIORITY.indexOf(b);
        if (ia < 0) return b;
        if (ib < 0) return a;
        return ia <= ib ? a : b;
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    private static byte[] hex(String rgb) {
        int r = Integer.parseInt(rgb.substring(0, 2), 16);
        int g = Integer.parseInt(rgb.substring(2, 4), 16);
        int b = Integer.parseInt(rgb.substring(4, 6), 16);
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }

    // ── Style palette ─────────────────────────────────────────────────────────

    private static class StylePalette {
        final CellStyle headerStyle;
        final CellStyle traceStyle;
        final CellStyle infoStyle;
        final CellStyle summaryStyle;
        final CellStyle warnStyle;
        final CellStyle errorStyle;

        StylePalette(XSSFWorkbook wb) {
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 10);

            headerStyle = createStyle(wb, null, headerFont);
            traceStyle   = createStyle(wb, COLOR_TRACE,   null);
            infoStyle    = createStyle(wb, null,           null);
            summaryStyle = createStyle(wb, COLOR_SUMMARY, null);
            warnStyle    = createStyle(wb, COLOR_WARN,    null);
            errorStyle   = createStyle(wb, COLOR_ERROR,   null);
        }

        CellStyle styleFor(LogLevel level) {
            return switch (level) {
                case TRACE   -> traceStyle;
                case SUMMARY -> summaryStyle;
                case WARN    -> warnStyle;
                case ERROR   -> errorStyle;
                default      -> infoStyle;
            };
        }

        private CellStyle createStyle(XSSFWorkbook wb, String argbColor, Font font) {
            XSSFCellStyle style = wb.createCellStyle();
            if (argbColor != null) {
                XSSFColor color = new XSSFColor(
                        new byte[]{
                                (byte) Integer.parseInt(argbColor.substring(2, 4), 16),
                                (byte) Integer.parseInt(argbColor.substring(4, 6), 16),
                                (byte) Integer.parseInt(argbColor.substring(6, 8), 16)
                        }, null);
                style.setFillForegroundColor(color);
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (font != null) style.setFont(font);
            style.setWrapText(false);
            return style;
        }
    }
}
